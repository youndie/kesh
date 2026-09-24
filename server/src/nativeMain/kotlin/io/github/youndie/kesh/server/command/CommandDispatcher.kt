package io.github.youndie.kesh.server.command

import io.github.youndie.kesh.resp.Reply

/**
 * Routes a parsed command to its implementation. Runs on the store thread only (research D-14).
 *
 * B-01 knows `PING`; every other name gets Redis's unknown-command error, byte for byte, so that a
 * client probing for a command it may not have gets the answer it already handles.
 */
class CommandDispatcher {
    fun execute(args: List<ByteArray>): Reply {
        require(args.isNotEmpty()) { "an empty command never reaches the dispatcher" }
        return when (val name = args[0].decodeToString().lowercase()) {
            "ping" -> {
                when (args.size) {
                    1 -> Reply.PONG
                    2 -> Reply.Bulk(args[1])
                    else -> wrongArity(name)
                }
            }

            else -> {
                unknownCommand(args)
            }
        }
    }

    /** `redis/redis@7.2!/src/server.c` — `commandCheckArity`. */
    private fun wrongArity(name: String): Reply = Reply.Error("ERR wrong number of arguments for '$name' command")

    /**
     * `redis/redis@7.2!/src/server.c` — `commandCheckArity`'s caller: the name as sent, cut at 128
     * bytes; each argument quoted and followed by a space while the list is under 128 bytes; CR and
     * LF mapped to spaces, because they come from the user and would break the reply's framing.
     */
    private fun unknownCommand(args: List<ByteArray>): Reply {
        val name = args[0].decodeToString().take(NAME_LIMIT)
        val quoted = StringBuilder()
        for (i in 1 until args.size) {
            if (quoted.length >= ARGS_LIMIT) break
            quoted.append('\'').append(args[i].decodeToString().take(ARGS_LIMIT - quoted.length)).append("' ")
        }
        val message = "ERR unknown command '$name', with args beginning with: $quoted"
        return Reply.Error(message.replace('\r', ' ').replace('\n', ' '))
    }

    private companion object {
        const val NAME_LIMIT = 128
        const val ARGS_LIMIT = 128
    }
}
