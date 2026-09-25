package io.github.youndie.kesh.server.command

import io.github.youndie.kesh.resp.Reply
import io.github.youndie.kesh.resp.parseRedisLong
import io.github.youndie.kesh.server.client.ClientState
import io.github.youndie.kesh.server.client.Clients
import io.github.youndie.kesh.server.config.MemoryConfig
import io.github.youndie.kesh.server.info.CommandStats
import io.github.youndie.kesh.server.info.Info
import io.github.youndie.kesh.server.persistence.Persistence
import io.github.youndie.kesh.server.pubsub.PubSub
import io.github.youndie.kesh.store.Db
import io.github.youndie.kesh.store.commands.StoreCommands
import io.github.youndie.kesh.store.eviction.Eviction
import io.github.youndie.kesh.store.expiry.ActiveExpiry
import kotlin.time.TimeSource

/**
 * Routes a parsed command to its implementation. Runs on the store thread only (research D-14).
 *
 * The checks run in Redis's order (`redis/redis@7.2!/src/server.c` — `processCommand`): the command
 * exists, then its arity, then authentication. So an unauthenticated client sending a command kesh
 * does not have gets "unknown command", not `NOAUTH` — as it would from Redis.
 *
 * @return the reply, or `null` when the connection must be dropped without one (see [SECURITY]).
 */
class CommandDispatcher(
    private val clients: Clients,
    private val password: String?,
    private val db: Db = Db(),
    /** Milliseconds since the epoch; read once per data command (Redis's `commandTimeSnapshot`). */
    private val clock: () -> Long = ::epochMillis,
    /** The active expiry cycle, for `INFO stats`; the server runs it (B-13). */
    private val expiry: ActiveExpiry? = null,
    /** `SAVE` and `LASTSAVE`; none in tests that do not save (B-14). */
    private val persistence: Persistence? = null,
    /** `maxmemory-policy` and its work (B-12); the server also runs it between commands. */
    private val eviction: Eviction = Eviction(),
    /** The RESP port for `INFO server`, once bound. */
    port: () -> Int = { 0 },
) {
    /** Commands run and their durations, for `INFO` and `/metrics` (B-15). */
    val stats = CommandStats()

    /** Channels and patterns, and who listens (B-27). */
    val pubsub = PubSub()

    /** `INFO`'s report (B-15). */
    val info = Info(db, clients, stats, eviction, expiry, persistence, pubsub, port, clock)

    private val memoryConfig = MemoryConfig(db, eviction)

    /** Redis's `pre_command_oom_state`: over `maxmemory` with nothing left to evict, at this command's start. */
    private var outOfMemory = false

    private val table: Map<String, CommandSpec> =
        listOf(
            CommandSpec("ping", -1) { c, a -> ping(c, a) },
            CommandSpec("subscribe", -2) { c, a -> pubsub.subscribe(c, a.drop(1)) },
            CommandSpec("unsubscribe", -1) { c, a -> pubsub.unsubscribe(c, a.drop(1)) },
            CommandSpec("psubscribe", -2) { c, a -> pubsub.psubscribe(c, a.drop(1)) },
            CommandSpec("punsubscribe", -1) { c, a -> pubsub.punsubscribe(c, a.drop(1)) },
            CommandSpec("publish", 3) { _, a -> Reply.Integer(pubsub.publish(a[1], a[2]).toLong()) },
            CommandSpec("echo", 2) { _, a -> Reply.Bulk(a[1]) },
            CommandSpec("quit", -1, noAuth = true) { c, _ -> quit(c) },
            CommandSpec("auth", -2, noAuth = true) { c, a -> auth(c, a) },
            CommandSpec("hello", -1, noAuth = true) { c, a -> hello(c, a) },
            CommandSpec("select", 2) { _, a -> select(a) },
            CommandSpec(
                "config",
                -2,
                subcommands =
                    listOf(
                        CommandSpec("get", -3) { _, a -> memoryConfig.get(a.drop(2).map { it.decodeToString() }) },
                        CommandSpec("set", -4) { _, a -> memoryConfig.set(a.drop(2).map { it.decodeToString() }) },
                    ),
            ),
            CommandSpec("info", -1) { _, a ->
                Reply.Bulk(info.report(a.drop(1).map { it.decodeToString().lowercase() }).encodeToByteArray())
            },
            CommandSpec("save", 1) { _, _ -> persistence?.save(db) ?: Reply.Error("ERR") },
            CommandSpec("lastsave", 1) { _, _ -> Reply.Integer(persistence?.lastSave ?: 0) },
            CommandSpec(
                "client",
                -2,
                subcommands =
                    listOf(
                        CommandSpec("setname", 3) { c, a -> clientSetName(c, a) },
                        CommandSpec("getname", 2) { c, _ ->
                            c.name?.let { Reply.Bulk(it.encodeToByteArray()) }
                                ?: Reply.NULL_BULK
                        },
                        CommandSpec("id", 2) { c, _ -> Reply.Integer(c.id) },
                        CommandSpec("list", -2) { _, a -> clientList(a) },
                        CommandSpec("kill", -3) { c, a -> clientKill(c, a) },
                        CommandSpec("setinfo", 4) { c, a -> clientSetInfo(c, a) },
                        CommandSpec("help", 2) { _, _ -> help("CLIENT", CLIENT_HELP) },
                    ),
            ),
            CommandSpec(
                "command",
                -1,
                subcommands =
                    listOf(
                        CommandSpec("count", 2) { _, _ -> Reply.Integer(commandCount().toLong()) },
                        CommandSpec("info", -2) { _, a -> commandInfo(a.drop(2).map { it.decodeToString() }) },
                        CommandSpec("docs", -2) { _, _ -> Reply.Multi(emptyList()) },
                        CommandSpec("help", 2) { _, _ -> help("COMMAND", COMMAND_HELP) },
                    ),
                handler = { _, _ -> commandInfo(null) },
            ),
        ).plus(
            StoreCommands.all.map { command ->
                CommandSpec(command.name, command.arity, denyOom = command.denyOom) { _, args ->
                    db.now = clock()
                    command.run(db, args)
                }
            },
        ).associateBy { it.name }

    fun execute(
        client: ClientState,
        args: List<ByteArray>,
    ): Reply? {
        require(args.isNotEmpty()) { "an empty command never reaches the dispatcher" }
        val name = args[0].decodeToString().lowercase()
        if (name in SECURITY) return null

        val base = table[name] ?: return unknownCommand(args)
        val spec =
            if (base.subcommands.isNotEmpty() && args.size >= 2) {
                val sub = args[1].decodeToString().lowercase()
                base.subcommands.firstOrNull { it.name == sub }
                    ?: return error(
                        "ERR unknown subcommand '${args[1].decodeToString().take(128)}'. Try ${name.uppercase()} HELP.",
                    )
            } else {
                base
            }
        val fullName = if (spec === base) name else "$name|${spec.name}"
        if (!spec.arityAllows(args.size)) return error("ERR wrong number of arguments for '$fullName' command")
        if (password != null && !client.authenticated && !spec.noAuth) return NOAUTH

        client.startCommand(fullName)
        val handler = spec.handler ?: return unknownCommand(args)
        // `processCommand` evicts before every command that got this far, whatever it is (B-12).
        outOfMemory = false
        if (db.maxMemory != 0L) {
            db.now = clock()
            outOfMemory = eviction.perform(db) == Eviction.Result.FAIL
        }
        // `processCommand`: a command that may add data is refused while the dataset is over
        // `maxmemory` and the policy could evict nothing more (B-11, B-12); reads and deletes still run.
        // A refused command is not counted, as Redis counts only what reaches `call()`.
        if (spec.denyOom && outOfMemory) return OOM
        // `processCommand`, after the memory check: a RESP2 client with a subscription may only
        // (un)subscribe, `PING` and `QUIT` (research D-26).
        if (client.subscriptions > 0 && name !in SUBSCRIBE_MODE_ALLOWED) {
            return error(
                "ERR Can't execute '$fullName': only (P|S)SUBSCRIBE / (P|S)UNSUBSCRIBE / PING / QUIT / RESET are " +
                    "allowed in this context",
            )
        }
        val started = TimeSource.Monotonic.markNow()
        val reply = handler(client, args)
        stats.record(fullName, started.elapsedNow().inWholeMicroseconds)
        info.afterCommand()
        return reply
    }

    /** `pingCommand`: in subscribe mode a RESP2 client gets `pong` and the message as an array. */
    private fun ping(
        client: ClientState,
        args: List<ByteArray>,
    ): Reply =
        when {
            args.size > 2 -> {
                error("ERR wrong number of arguments for 'ping' command")
            }

            client.subscriptions > 0 -> {
                Reply.Multi(listOf(Reply.Bulk(PONG_BULK), Reply.Bulk(if (args.size == 2) args[1] else ByteArray(0))))
            }

            args.size == 2 -> {
                Reply.Bulk(args[1])
            }

            else -> {
                Reply.PONG
            }
        }

    private fun quit(client: ClientState): Reply {
        client.closeAfterReply = true
        return Reply.OK
    }

    /** `redis/redis@7.2!/src/acl.c` — `authCommand`. One user, `default`, as Redis without ACLs. */
    private fun auth(
        client: ClientState,
        args: List<ByteArray>,
    ): Reply {
        if (args.size > 3) return SYNTAX
        if (args.size == 2 && password == null) {
            return error(
                "ERR AUTH <password> called without any password configured for the default user. " +
                    "Are you sure your configuration is correct?",
            )
        }
        val user = if (args.size == 2) DEFAULT_USER else args[1]
        return if (authenticate(client, user, args.last())) Reply.OK else WRONGPASS
    }

    /**
     * `redis/redis@7.2!/src/networking.c` — `helloCommand`, with one difference the brief prescribes:
     * protocol 3 is refused like any unsupported version (research D-1). Lettuce reads that refusal
     * as "fall back to RESP2" (research §1.1).
     */
    private fun hello(
        client: ClientState,
        args: List<ByteArray>,
    ): Reply {
        var next = 1
        if (args.size >= 2) {
            val version =
                parseRedisLong(args[next++])
                    ?: return error("ERR Protocol version is not an integer or out of range")
            if (version != 2L) return error("NOPROTO unsupported protocol version")
        }
        var user: ByteArray? = null
        var pass: ByteArray? = null
        var newName: String? = null
        while (next < args.size) {
            val option = args[next].decodeToString()
            val more = args.size - 1 - next
            when {
                option.equals("AUTH", ignoreCase = true) && more >= 2 -> {
                    user = args[next + 1]
                    pass = args[next + 2]
                    next += 3
                }

                option.equals("SETNAME", ignoreCase = true) && more >= 1 -> {
                    newName = args[next + 1].decodeToString()
                    if (!isPrintableToken(newName)) return BAD_NAME
                    next += 2
                }

                else -> {
                    return error("ERR Syntax error in HELLO option '$option'")
                }
            }
        }
        if (user != null && pass != null && !authenticate(client, user, pass)) return WRONGPASS
        if (!client.authenticated) {
            return error(
                "NOAUTH HELLO must be called with the client already authenticated, otherwise the " +
                    "HELLO <proto> AUTH <user> <pass> option can be used to authenticate the client and " +
                    "select the RESP protocol version at the same time",
            )
        }
        if (newName != null) client.name = newName.ifEmpty { null }
        return Reply.Multi(
            listOf(
                bulk("server"),
                bulk(SERVER_NAME),
                bulk("version"),
                bulk(Info.REDIS_COMPATIBLE_VERSION),
                bulk("proto"),
                Reply.Integer(2),
                bulk("id"),
                Reply.Integer(client.id),
                bulk("mode"),
                bulk("standalone"),
                bulk("role"),
                bulk("master"),
                bulk("modules"),
                Reply.Multi(emptyList()),
            ),
        )
    }

    /** One database (research, brief §3): `SELECT 0` succeeds, any other index does not. */
    private fun select(args: List<ByteArray>): Reply {
        val index =
            parseRedisLong(args[1])
                ?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
                ?: return NOT_AN_INTEGER
        return if (index == 0L) Reply.OK else error("ERR DB index is out of range")
    }

    private fun clientSetName(
        client: ClientState,
        args: List<ByteArray>,
    ): Reply {
        val name = args[2].decodeToString()
        if (!isPrintableToken(name)) return BAD_NAME
        client.name = name.ifEmpty { null }
        return Reply.OK
    }

    /** `redis/redis@7.2!/src/networking.c` — `clientSetinfoCommand`. */
    private fun clientSetInfo(
        client: ClientState,
        args: List<ByteArray>,
    ): Reply {
        val attribute = args[2].decodeToString()
        val value = args[3].decodeToString()
        val isName = attribute.equals("lib-name", ignoreCase = true)
        if (!isName && !attribute.equals("lib-ver", ignoreCase = true)) {
            return error("ERR Unrecognized option '$attribute'")
        }
        if (!isPrintableToken(
                value,
            )
        ) {
            return error("ERR $attribute cannot contain spaces, newlines or special characters.")
        }
        if (isName) client.libName = value.ifEmpty { null } else client.libVersion = value.ifEmpty { null }
        return Reply.OK
    }

    private fun clientList(args: List<ByteArray>): Reply {
        val selected: Collection<ClientState> =
            when {
                args.size == 2 -> {
                    clients.all()
                }

                args.size == 4 && args[2].decodeToString().equals("type", ignoreCase = true) -> {
                    when (args[3].decodeToString().lowercase()) {
                        "normal" -> clients.all()
                        "master", "slave", "replica", "pubsub" -> emptyList()
                        else -> return error("ERR Unknown client type '${args[3].decodeToString()}'")
                    }
                }

                args.size > 3 && args[2].decodeToString().equals("id", ignoreCase = true) -> {
                    val ids =
                        args.drop(3).map {
                            parseRedisLong(it)?.takeIf { id -> id > 0 }
                                ?: return error("ERR Invalid client ID")
                        }
                    clients.all().filter { it.id in ids }
                }

                else -> {
                    return SYNTAX
                }
            }
        return Reply.Bulk(selected.joinToString("") { clientLine(it) + "\n" }.encodeToByteArray())
    }

    /**
     * Redis's `CLIENT LIST` line with its keys in its order (`catClientInfoString`), so that tools
     * splitting on spaces find what they look for. Values kesh does not track are written as zero,
     * and `fd` as `-1`, because the transport does not expose the descriptor.
     */
    private fun clientLine(c: ClientState): String =
        "id=${c.id} addr=${c.address} laddr=${c.localAddress} fd=-1 name=${c.name.orEmpty()} " +
            "age=${c.ageSeconds} idle=${c.idleSeconds} flags=${if (c.subscriptions > 0) "P" else "N"} db=0 " +
            "sub=${c.channels.size} psub=${c.patterns.size} ssub=0 multi=-1 qbuf=0 " +
            "qbuf-free=0 argv-mem=0 multi-mem=0 rbs=0 rbp=0 obl=0 oll=0 omem=0 tot-mem=0 events=r " +
            "cmd=${c.lastCommand} user=default redir=-1 resp=2 lib-name=${c.libName.orEmpty()} " +
            "lib-ver=${c.libVersion.orEmpty()}"

    /** `redis/redis@7.2!/src/networking.c` — the `kill` branch of `clientCommand`. */
    private fun clientKill(
        self: ClientState,
        args: List<ByteArray>,
    ): Reply {
        var address: String? = null
        var localAddress: String? = null
        var id = 0L
        var typeMatchesNone = false
        var skipMe = true
        if (args.size == 3) {
            address = args[2].decodeToString()
            skipMe = false
        } else {
            var i = 2
            while (i < args.size) {
                val option = args[i].decodeToString().lowercase()
                val value = args.getOrNull(i + 1)?.decodeToString() ?: return SYNTAX
                when (option) {
                    "id" -> {
                        id = parseRedisLong(value)?.takeIf { it >= 1 }
                            ?: return error("ERR client-id should be greater than 0")
                    }

                    "type" -> {
                        when (value.lowercase()) {
                            "normal" -> Unit
                            "master", "slave", "replica", "pubsub" -> typeMatchesNone = true
                            else -> return error("ERR Unknown client type '$value'")
                        }
                    }

                    "addr" -> {
                        address = value
                    }

                    "laddr" -> {
                        localAddress = value
                    }

                    "user" -> {
                        if (value != "default") return error("ERR No such user '$value'")
                    }

                    "skipme" -> {
                        skipMe =
                            when (value.lowercase()) {
                                "yes" -> true
                                "no" -> false
                                else -> return SYNTAX
                            }
                    }

                    else -> {
                        return SYNTAX
                    }
                }
                i += 2
            }
        }
        val victims =
            clients.all().filter {
                !typeMatchesNone &&
                    (address == null || it.address == address) &&
                    (localAddress == null || it.localAddress == localAddress) &&
                    (id == 0L || it.id == id) &&
                    !(it === self && skipMe)
            }
        victims.forEach { if (it === self) self.closeAfterReply = true else it.kill() }
        return when {
            args.size != 3 -> Reply.Integer(victims.size.toLong())
            victims.isEmpty() -> error("ERR No such client")
            else -> Reply.OK
        }
    }

    private fun commandCount(): Int = table.size

    /**
     * `COMMAND` and `COMMAND INFO`: Redis's ten-field entry per command. Only name, arity, the
     * `noauth` flag and the subcommands are real; key positions are 0 because no command here takes
     * a key yet, and the ACL categories, tips and key specs are empty (kesh has no ACLs). Enough for a
     * client that enumerates commands during its handshake; the oracle compares this by shape.
     */
    private fun commandInfo(names: List<String>?): Reply {
        val specs = names?.map { table[it.lowercase()] } ?: table.values.toList()
        return Reply.Multi(specs.map { spec -> spec?.let { info(it, it.name) } ?: Reply.Multi(null) })
    }

    private fun info(
        spec: CommandSpec,
        fullName: String,
    ): Reply =
        Reply.Multi(
            listOf(
                bulk(fullName),
                Reply.Integer(spec.arity.toLong()),
                Reply.Multi(if (spec.noAuth) listOf(Reply.Simple("no_auth")) else emptyList()),
                Reply.Integer(0),
                Reply.Integer(0),
                Reply.Integer(0),
                Reply.Multi(emptyList()),
                Reply.Multi(emptyList()),
                Reply.Multi(emptyList()),
                Reply.Multi(spec.subcommands.map { info(it, "${spec.name}|${it.name}") }),
            ),
        )

    private fun help(
        command: String,
        lines: List<String>,
    ): Reply =
        Reply.Multi(
            (listOf("$command <subcommand> [<arg> [value] [opt] ...]. Subcommands are:") + lines + HELP_TAIL)
                .map { Reply.Simple(it) },
        )

    /**
     * Constant-time over the bytes compared, as Redis's `time_independent_strcmp`, so the reply time
     * does not say how much of a guessed password was right.
     */
    private fun authenticate(
        client: ClientState,
        user: ByteArray,
        given: ByteArray,
    ): Boolean {
        if (!user.contentEquals(DEFAULT_USER)) return false
        // `default` without a password accepts any password, as Redis's `nopass` user does.
        val expected = password?.encodeToByteArray() ?: return true.also { client.authenticated = true }
        var difference = expected.size xor given.size
        for (i in 0 until maxOf(expected.size, given.size)) {
            difference = difference or ((expected.getOrElse(i) { 0 }.toInt()) xor (given.getOrElse(i) { 0 }.toInt()))
        }
        return (difference == 0).also { if (it) client.authenticated = true }
    }

    /**
     * `redis/redis@7.2!/src/server.c` — `commandCheckExistence`: the name as sent, cut at 128 bytes;
     * each argument quoted and followed by a space while the list is under 128 bytes; CR and LF mapped
     * to spaces, because they come from the user and would break the reply's framing.
     */
    private fun unknownCommand(args: List<ByteArray>): Reply {
        val name = args[0].decodeToString().take(128)
        val quoted = StringBuilder()
        for (i in 1 until args.size) {
            if (quoted.length >= 128) break
            quoted.append('\'').append(args[i].decodeToString().take(128 - quoted.length)).append("' ")
        }
        return error("ERR unknown command '$name', with args beginning with: $quoted")
    }

    private companion object {
        /** What a subscribed RESP2 client may send (`processCommand`; kesh has no `RESET` or `SSUBSCRIBE`). */
        val SUBSCRIBE_MODE_ALLOWED = setOf("ping", "subscribe", "unsubscribe", "psubscribe", "punsubscribe", "quit")
        val PONG_BULK = "pong".encodeToByteArray()

        const val SERVER_NAME = "kesh"

        /**
         * `POST` and `Host:` as a command are an HTTP request aimed at the port from a browser —
         * Redis drops such a connection without a reply (`securityWarningCommand`), and so does kesh.
         */
        val SECURITY = setOf("post", "host:")

        /** `shared.oomerr`, `redis/redis@7.2!/src/server.c`. */
        val OOM: Reply = Reply.Error("OOM command not allowed when used memory > 'maxmemory'.")

        val DEFAULT_USER = "default".encodeToByteArray()

        val NOAUTH: Reply = Reply.Error("NOAUTH Authentication required.")
        val WRONGPASS: Reply = Reply.Error("WRONGPASS invalid username-password pair or user is disabled.")
        val SYNTAX: Reply = Reply.Error("ERR syntax error")
        val NOT_AN_INTEGER: Reply = Reply.Error("ERR value is not an integer or out of range")
        val BAD_NAME: Reply = Reply.Error("ERR Client names cannot contain spaces, newlines or special characters.")

        val CLIENT_HELP =
            listOf(
                "GETNAME",
                "    Return the name of the current connection.",
                "ID",
                "    Return the ID of the current connection.",
                "KILL <ip:port>",
                "    Kill connection made from <ip:port>.",
                "KILL <option> <value> [<option> <value> [...]]",
                "    Kill connections. Options are: ID, TYPE, USER, ADDR, LADDR, SKIPME.",
                "LIST [TYPE <type>] [ID <id> [<id> ...]]",
                "    Return information about client connections.",
                "SETINFO <option> <value>",
                "    Set client meta attr. Options are: LIB-NAME, LIB-VER.",
                "SETNAME <name>",
                "    Assign the name <name> to the current connection.",
            )
        val COMMAND_HELP =
            listOf(
                "(no subcommand)",
                "    Return details about all commands.",
                "COUNT",
                "    Return the total number of commands in this server.",
                "DOCS [<command-name> ...]",
                "    Return documentation details about commands (none, in kesh).",
                "INFO [<command-name> ...]",
                "    Return details about multiple commands.",
            )
        val HELP_TAIL = listOf("HELP", "    Print this help.")

        fun error(message: String): Reply = Reply.Error(message.replace('\r', ' ').replace('\n', ' '))

        fun bulk(text: String): Reply = Reply.Bulk(text.encodeToByteArray())

        /** Redis's `validateClientAttr`: every byte from `!` to `~`. */
        fun isPrintableToken(text: String): Boolean = text.all { it in '!'..'~' }
    }
}

/**
 * One entry of the command table. [arity] is Redis's: positive means exactly that many arguments,
 * name included; negative means at least its absolute value.
 */
class CommandSpec(
    val name: String,
    val arity: Int,
    val noAuth: Boolean = false,
    /** Redis's `denyoom`: refused while over `maxmemory` with nothing left to evict. */
    val denyOom: Boolean = false,
    val subcommands: List<CommandSpec> = emptyList(),
    val handler: ((ClientState, List<ByteArray>) -> Reply)? = null,
) {
    fun arityAllows(argc: Int): Boolean = if (arity > 0) argc == arity else argc >= -arity
}
