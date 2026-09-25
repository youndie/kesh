package io.github.youndie.kesh.conformance

/**
 * How two replies are compared. [EXACT] unless a script line says otherwise — and it says so on that
 * line, never for a whole script, because a normaliser is also a way to hide a real difference and a
 * reviewer has to be able to see each one (research D-5).
 */
enum class Normaliser {
    /** Byte for byte. */
    EXACT,

    /** Both are arrays holding the same elements, byte for byte, in any order (`SMEMBERS`, `KEYS`). */
    UNORDERED,

    /**
     * Both are arrays of an even length holding the same (field, value) pairs in any order — `HGETALL`
     * of a hash in its table encoding, whose order is the table's in either server. Stricter than
     * [UNORDERED], which would accept a value moved to another field.
     */
    PAIRS,

    /**
     * The same structure — reply types, array lengths, null or not — and any content. For replies that
     * are allowed to differ by design and must still have the right shape: identities, clocks,
     * `HELLO`'s `server` and `version` (research D-18), `CLIENT LIST`.
     */
    SHAPE,

    /**
     * For random replies (`SPOP`, `SRANDMEMBER`; research D-5): membership and count, not value. The
     * line names the population — `[random a b c] SRANDMEMBER s 2` — and **both** replies are held to
     * it, so a population written wrong fails against Redis too. A bulk string must be a member (or
     * null in both); an array must be as long as the oracle's, of members only, and without repeats
     * whenever the oracle's has none.
     */
    RANDOM,

    ;

    fun agree(
        kesh: RespFrame,
        oracle: RespFrame,
        population: Set<List<Byte>> = emptySet(),
    ): Boolean =
        when (this) {
            EXACT -> {
                kesh.bytes.contentEquals(oracle.bytes)
            }

            UNORDERED -> {
                val a = kesh.elements
                val b = oracle.elements
                kesh.type == '*' && oracle.type == '*' && a != null && b != null &&
                    a.map { it.bytes.toList() }.sortedWith(BYTES) == b.map { it.bytes.toList() }.sortedWith(BYTES)
            }

            PAIRS -> {
                val a = kesh.elements
                val b = oracle.elements
                kesh.type == '*' && oracle.type == '*' && a != null && b != null &&
                    a.size % 2 == 0 && pairs(a) == pairs(b)
            }

            SHAPE -> {
                sameShape(kesh, oracle)
            }

            RANDOM -> {
                randomAgree(kesh, oracle, population)
            }
        }

    private fun randomAgree(
        kesh: RespFrame,
        oracle: RespFrame,
        population: Set<List<Byte>>,
    ): Boolean {
        if (kesh.type != oracle.type) return false

        fun member(frame: RespFrame) = frame.type == '$' && frame.bulkPayload()?.toList() in population
        return when (kesh.type) {
            '$' -> {
                if (oracle.bulkPayload() == null) kesh.bulkPayload() == null else member(kesh) && member(oracle)
            }

            '*' -> {
                val a = kesh.elements
                val b = oracle.elements
                if (a == null || b == null) return a == b
                val distinct = b.map { it.bytes.toList() }.toSet().size == b.size
                a.size == b.size && a.all(::member) && b.all(::member) &&
                    (!distinct || a.map { it.bytes.toList() }.toSet().size == a.size)
            }

            else -> {
                kesh.bytes.contentEquals(oracle.bytes)
            }
        }
    }

    private fun pairs(elements: List<RespFrame>): List<List<Byte>> =
        elements
            .chunked(2)
            .map { (field, value) -> field.bytes.toList() + value.bytes.toList() }
            .sortedWith(BYTES)

    private fun sameShape(
        a: RespFrame,
        b: RespFrame,
    ): Boolean {
        if (a.type != b.type) return false
        val x = a.elements
        val y = b.elements
        if (x == null || y == null) return x == y
        if (a.type == '$') return (a.bytes[1] == '-'.code.toByte()) == (b.bytes[1] == '-'.code.toByte())
        return x.size == y.size && x.indices.all { sameShape(x[it], y[it]) }
    }

    private companion object {
        val BYTES =
            Comparator<List<Byte>> { p, q ->
                for (i in 0 until minOf(p.size, q.size)) {
                    val c = p[i].compareTo(q[i])
                    if (c != 0) return@Comparator c
                }
                p.size - q.size
            }
    }
}
