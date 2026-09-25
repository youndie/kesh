package io.github.youndie.kesh.server.command

import io.github.youndie.kesh.resp.encode
import io.github.youndie.kesh.server.client.ClientState
import io.github.youndie.kesh.server.client.Clients
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Replies byte for byte as Redis 7.2 words them; each expected string was read in its source. */
class CommandDispatcherTest {
    private class Setup(
        password: String? = null,
    ) {
        val clients = Clients(maxClients = 10, passwordRequired = password != null)
        val dispatcher = CommandDispatcher(clients, password)
        var closed = 0

        fun connect(address: String = "127.0.0.1:5000"): ClientState =
            clients.register(address, "127.0.0.1:6379") {
                closed++
            }!!

        fun reply(
            client: ClientState,
            vararg args: String,
        ) = dispatcher.execute(client, args.map { it.encodeToByteArray() })?.encode()?.decodeToString()
    }

    private val open = Setup()
    private val me = open.connect()

    @Test
    fun `PING answers PONG whatever the case of the name`() {
        assertEquals("+PONG\r\n", open.reply(me, "PING"))
        assertEquals("+PONG\r\n", open.reply(me, "ping"))
    }

    @Test
    fun `PING with a message echoes it back as a bulk string`() {
        assertEquals("$5\r\nhello\r\n", open.reply(me, "PING", "hello"))
    }

    @Test
    fun `PING with two arguments is an arity error in Redis's words`() {
        assertEquals("-ERR wrong number of arguments for 'ping' command\r\n", open.reply(me, "PING", "a", "b"))
    }

    @Test
    fun `an unknown command quotes each argument and ends with a space as Redis does`() {
        assertEquals(
            "-ERR unknown command 'FOO', with args beginning with: 'a' 'b' \r\n",
            open.reply(me, "FOO", "a", "b"),
        )
        assertEquals("-ERR unknown command 'FOO', with args beginning with: \r\n", open.reply(me, "FOO"))
    }

    @Test
    fun `line breaks from the client never reach the reply's framing`() {
        assertEquals("-ERR unknown command 'A B', with args beginning with: 'x y' \r\n", open.reply(me, "A\rB", "x\ny"))
    }

    @Test
    fun `ECHO and SELECT 0 answer as Redis`() {
        assertEquals("$2\r\nhi\r\n", open.reply(me, "ECHO", "hi"))
        assertEquals("+OK\r\n", open.reply(me, "SELECT", "0"))
        assertEquals("-ERR DB index is out of range\r\n", open.reply(me, "SELECT", "1"))
        assertEquals("-ERR value is not an integer or out of range\r\n", open.reply(me, "SELECT", "x"))
    }

    @Test
    fun `without a password every connection is authenticated and AUTH says so`() {
        assertEquals(
            "-ERR AUTH <password> called without any password configured for the default user. " +
                "Are you sure your configuration is correct?\r\n",
            open.reply(me, "AUTH", "secret"),
        )
        assertEquals("+OK\r\n", open.reply(me, "AUTH", "default", "anything"))
    }

    @Test
    fun `with a password only AUTH HELLO and QUIT are answered before AUTH`() {
        val locked = Setup(password = "secret")
        val client = locked.connect()

        assertEquals("-NOAUTH Authentication required.\r\n", locked.reply(client, "PING"))
        assertEquals(
            "-WRONGPASS invalid username-password pair or user is disabled.\r\n",
            locked.reply(client, "AUTH", "nope"),
        )
        assertEquals(
            "-WRONGPASS invalid username-password pair or user is disabled.\r\n",
            locked.reply(client, "AUTH", "admin", "secret"),
        )
        assertFalse(client.authenticated)
        assertEquals("+OK\r\n", locked.reply(client, "AUTH", "secret"))
        assertEquals("+PONG\r\n", locked.reply(client, "PING"))
    }

    @Test
    fun `an unknown command is unknown before it is unauthenticated in Redis's order`() {
        val locked = Setup(password = "secret")
        assertEquals(
            "-ERR unknown command 'NOPE', with args beginning with: 'k' \r\n",
            locked.reply(locked.connect(), "NOPE", "k"),
        )
        assertEquals("-NOAUTH Authentication required.\r\n", locked.reply(locked.connect(), "GET", "k"))
    }

    @Test
    fun `HELLO 3 is refused and HELLO 2 describes the server`() {
        assertEquals("-NOPROTO unsupported protocol version\r\n", open.reply(me, "HELLO", "3"))
        assertEquals("-ERR Protocol version is not an integer or out of range\r\n", open.reply(me, "HELLO", "x"))
        assertEquals(
            "*14\r\n$6\r\nserver\r\n$4\r\nkesh\r\n$7\r\nversion\r\n$5\r\n7.2.0\r\n$5\r\nproto\r\n:2\r\n" +
                "$2\r\nid\r\n:${me.id}\r\n$4\r\nmode\r\n$10\r\nstandalone\r\n$4\r\nrole\r\n$6\r\nmaster\r\n" +
                "$7\r\nmodules\r\n*0\r\n",
            open.reply(me, "HELLO", "2"),
        )
        assertEquals("-ERR Syntax error in HELLO option 'FOO'\r\n", open.reply(me, "HELLO", "2", "FOO"))
    }

    @Test
    fun `HELLO before AUTH authenticates with its AUTH option or says how to`() {
        val locked = Setup(password = "secret")
        val client = locked.connect()
        assertTrue(
            locked
                .reply(
                    client,
                    "HELLO",
                )!!
                .startsWith("-NOAUTH HELLO must be called with the client already authenticated"),
        )
        assertTrue(
            locked.reply(client, "HELLO", "2", "AUTH", "default", "secret", "SETNAME", "app")!!.startsWith("*14\r\n"),
        )
        assertEquals("app", client.name)
    }

    @Test
    fun `CLIENT SETNAME GETNAME ID and SETINFO`() {
        assertEquals("$-1\r\n", open.reply(me, "CLIENT", "GETNAME"))
        assertEquals("+OK\r\n", open.reply(me, "CLIENT", "SETNAME", "worker-1"))
        assertEquals("$8\r\nworker-1\r\n", open.reply(me, "client", "getname"))
        assertEquals(
            "-ERR Client names cannot contain spaces, newlines or special characters.\r\n",
            open.reply(me, "CLIENT", "SETNAME", "a b"),
        )
        assertEquals(":${me.id}\r\n", open.reply(me, "CLIENT", "ID"))
        assertEquals("+OK\r\n", open.reply(me, "CLIENT", "SETINFO", "lib-name", "Lettuce"))
        assertEquals("-ERR Unrecognized option 'color'\r\n", open.reply(me, "CLIENT", "SETINFO", "color", "red"))
        assertEquals(
            "-ERR lib-ver cannot contain spaces, newlines or special characters.\r\n",
            open.reply(me, "CLIENT", "SETINFO", "lib-ver", "1 0"),
        )
    }

    @Test
    fun `CLIENT with a wrong subcommand or arity answers as Redis`() {
        assertEquals("-ERR unknown subcommand 'nope'. Try CLIENT HELP.\r\n", open.reply(me, "CLIENT", "nope"))
        assertEquals("-ERR wrong number of arguments for 'client' command\r\n", open.reply(me, "CLIENT"))
        assertEquals(
            "-ERR wrong number of arguments for 'client|setname' command\r\n",
            open.reply(me, "CLIENT", "SETNAME"),
        )
    }

    @Test
    fun `CLIENT LIST carries Redis's keys in Redis's order`() {
        open.reply(me, "CLIENT", "SETNAME", "worker-1")
        val line = open.reply(me, "CLIENT", "LIST")!!.lines()[1]
        val keys = line.split(' ').map { it.substringBefore('=') }
        assertEquals(
            listOf(
                "id",
                "addr",
                "laddr",
                "fd",
                "name",
                "age",
                "idle",
                "flags",
                "db",
                "sub",
                "psub",
                "ssub",
                "multi",
                "qbuf",
                "qbuf-free",
                "argv-mem",
                "multi-mem",
                "rbs",
                "rbp",
                "obl",
                "oll",
                "omem",
                "tot-mem",
                "events",
                "cmd",
                "user",
                "redir",
                "resp",
                "lib-name",
                "lib-ver",
            ),
            keys,
        )
        assertTrue("name=worker-1 " in line && "cmd=client|list " in line, line)
    }

    @Test
    fun `CLIENT KILL closes another client and counts it`() {
        val other = open.connect("127.0.0.1:5001")
        assertEquals(":1\r\n", open.reply(me, "CLIENT", "KILL", "ID", other.id.toString()))
        assertTrue(other.killed)
        assertEquals(1, open.closed)
        assertEquals("-ERR No such client\r\n", open.reply(me, "CLIENT", "KILL", "10.0.0.1:1"))
        assertEquals(":0\r\n", open.reply(me, "CLIENT", "KILL", "ID", me.id.toString()))
        assertEquals("+OK\r\n", open.reply(me, "CLIENT", "KILL", "127.0.0.1:5000"))
        assertTrue(me.closeAfterReply)
    }

    @Test
    fun `QUIT answers OK and asks for the connection to close`() {
        assertEquals("+OK\r\n", open.reply(me, "QUIT"))
        assertTrue(me.closeAfterReply)
    }

    @Test
    fun `POST and Host are dropped without a reply`() {
        assertNull(open.reply(me, "POST", "/", "HTTP/1.1"))
        assertNull(open.reply(me, "Host:", "localhost"))
    }

    @Test
    fun `COMMAND COUNT and INFO describe the table`() {
        assertEquals(":101\r\n", open.reply(me, "COMMAND", "COUNT"))
        assertTrue(open.reply(me, "COMMAND", "INFO", "ping")!!.startsWith("*1\r\n*10\r\n$4\r\nping\r\n:-1\r\n"))
        assertEquals("*1\r\n*-1\r\n", open.reply(me, "COMMAND", "INFO", "nope"))
    }

    @Test
    fun `a full dataset refuses writes under noeviction and still reads and deletes`() {
        assertEquals("+OK\r\n", open.reply(me, "CONFIG", "SET", "maxmemory", "20kb"))
        var written = 0
        while (open.reply(me, "SET", "k$written", "x".repeat(500)) == "+OK\r\n") written++
        assertTrue(written in 10..100, "$written writes before the limit")
        assertEquals("-OOM command not allowed when used memory > 'maxmemory'.\r\n", open.reply(me, "SET", "more", "x"))
        assertEquals("-OOM command not allowed when used memory > 'maxmemory'.\r\n", open.reply(me, "RPUSH", "l", "x"))
        assertEquals("$500\r\n${"x".repeat(500)}\r\n", open.reply(me, "GET", "k0"))
        assertEquals(":5\r\n", open.reply(me, "DEL", "k0", "k1", "k2", "k3", "k4"))
        assertEquals("+OK\r\n", open.reply(me, "SET", "more", "x"), "room again after the deletes")
        assertTrue(open.reply(me, "INFO", "memory")!!.contains("maxmemory:20480\r\n"))
    }

    @Test
    fun `CONFIG answers maxmemory in Redis's words`() {
        assertEquals(
            "*2\r\n$9\r\nMAXMEMORY\r\n$1\r\n0\r\n",
            open.reply(me, "CONFIG", "GET", "MAXMEMORY"),
            "the name as written",
        )
        assertEquals("*0\r\n", open.reply(me, "CONFIG", "GET", "nothing"))
        assertEquals("+OK\r\n", open.reply(me, "CONFIG", "SET", "maxmemory", "1mb"))
        assertEquals("*2\r\n$9\r\nmaxmemory\r\n$7\r\n1048576\r\n", open.reply(me, "CONFIG", "GET", "maxmemory"))
        assertEquals(
            "-ERR CONFIG SET failed (possibly related to argument 'maxmemory') - argument must be a memory value\r\n",
            open.reply(me, "CONFIG", "SET", "maxmemory", "12xb"),
        )
        assertEquals(
            "-ERR Unknown option or number of arguments for CONFIG SET - 'nothing'\r\n",
            open.reply(me, "CONFIG", "SET", "nothing", "1"),
        )
        assertEquals("-ERR syntax error\r\n", open.reply(me, "CONFIG", "SET", "maxmemory", "1", "x"))
    }
}
