package io.github.youndie.kesh.bench

import kotlin.math.abs

/**
 * Counts and user bytes per part, against appendix A scaled by the same factor. What the generator
 * prints, and what `--check` holds to [tolerance].
 */
class DatasetSummary(
    private val scale: Double,
) {
    private val keys = LongArray(Part.entries.size)
    private val bytes = LongArray(Part.entries.size)

    fun add(entry: Entry) {
        keys[entry.part.ordinal]++
        bytes[entry.part.ordinal] += entry.userBytes
    }

    fun keys(part: Part): Long = keys[part.ordinal]

    fun userBytes(part: Part): Long = bytes[part.ordinal]

    /** Deviation of this part's user bytes from appendix A, as a fraction: 0.013 is 1.3 % over. */
    fun deviation(part: Part): Double = userBytes(part).toDouble() / (part.userBytes * scale) - 1

    fun withinTolerance(tolerance: Double = 0.02): Boolean = Part.entries.all { abs(deviation(it)) <= tolerance }

    fun table(): String =
        buildString {
            appendLine("part          keys        target keys   user bytes      target bytes    deviation")
            for (part in Part.entries) {
                val target = (part.userBytes * scale).toLong()
                val targetKeys = maxOf(1L, (part.keys * scale).toLong())
                appendLine(
                    part.name.lowercase().padEnd(14) +
                        keys(part).toString().padEnd(12) + targetKeys.toString().padEnd(14) +
                        userBytes(part).toString().padEnd(16) + target.toString().padEnd(16) +
                        percent(deviation(part)),
                )
            }
            val total = Part.entries.sumOf { userBytes(it) }
            val totalTarget = Part.entries.sumOf { (it.userBytes * scale).toLong() }
            append("total         ${Part.entries.sumOf { keys(it) }.toString().padEnd(26)}")
            append("${total.toString().padEnd(16)}${totalTarget.toString().padEnd(16)}")
            appendLine(percent(total.toDouble() / totalTarget - 1))
        }

    private fun percent(fraction: Double): String {
        val tenths = kotlin.math.round(fraction * 1000).toLong()
        val sign = if (tenths < 0) "-" else "+"
        return "$sign${abs(tenths) / 10}.${abs(tenths) % 10} %"
    }
}
