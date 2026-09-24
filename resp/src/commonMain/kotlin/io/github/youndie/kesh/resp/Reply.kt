package io.github.youndie.kesh.resp

/**
 * A RESP2 reply. The five kinds the protocol has, and nothing RESP3 adds (research D-1).
 *
 * Text replies ([Simple], [Error]) must not contain CR or LF: the protocol has no escaping for them.
 * The constructors enforce it, because a newline in an error message is a reply that desynchronises
 * the client, and it would come from user input — Redis maps both to spaces for the same reason.
 */
sealed interface Reply {
    class Simple(
        val text: String,
    ) : Reply {
        init {
            requireNoLineBreak(text)
        }
    }

    /** [message] is sent as written, prefix included: `ERR …`, `WRONGTYPE …`, `NOAUTH …`. */
    class Error(
        val message: String,
    ) : Reply {
        init {
            requireNoLineBreak(message)
        }
    }

    class Integer(
        val value: Long,
    ) : Reply

    /** `null` is the null bulk string, `$-1`. */
    class Bulk(
        val bytes: ByteArray?,
    ) : Reply

    /** `null` is the null array, `*-1`. */
    class Multi(
        val items: List<Reply>?,
    ) : Reply

    companion object {
        val OK: Reply = Simple("OK")
        val PONG: Reply = Simple("PONG")
        val NULL_BULK: Reply = Bulk(null)
    }
}

private fun requireNoLineBreak(text: String) {
    require('\r' !in text && '\n' !in text) { "a RESP simple string or error cannot contain CR or LF" }
}
