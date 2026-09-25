package io.github.youndie.kesh.server.config

import io.github.youndie.kore.runtime.MemoryBudget
import io.github.youndie.kore.runtime.render

/**
 * Whether a `maxmemory` fits the container (B-26). The Kotlin/Native runtime does not see its
 * container's limit (research §1.2), so a `maxmemory` too large for it ends in an OOM kill, never in
 * `-OOM` replies. kore reads the limit (`containerMemoryBudget()`); this holds `maxmemory` against it
 * at the resident peak ratio the chart sizes its limit with — resident memory under the reference load
 * is up to [peakRatioTenths]/10 × `used_memory` (B-28).
 *
 * A limit that cannot be read, or none at all, refuses nothing: kesh cannot say more than kore can.
 * `maxmemory 0` — no limit, Redis's default — refuses nothing either: there is no number to hold
 * against the budget, and the log says so at startup.
 */
class MemoryBudgetCheck(
    val budget: MemoryBudget,
    /** Peak resident memory per `used_memory`, in tenths — the chart's `measured.residentPeakRatioTenths`. */
    val peakRatioTenths: Int,
) {
    init {
        require(peakRatioTenths >= 10) { "a resident peak below used_memory is not a ratio: $peakRatioTenths/10" }
    }

    private val ratio = "${peakRatioTenths / 10}.${peakRatioTenths % 10}"

    /** The largest `maxmemory` the budget holds, or `null` when there is no bounded budget. */
    val largestFitting: Long?
        get() = (budget as? MemoryBudget.Bounded)?.let { it.bytes / peakRatioTenths * 10 }

    /** `null` when [maxMemory] fits; otherwise why not, naming both numbers, on one line. */
    fun refusal(maxMemory: Long): String? {
        val bounded = budget as? MemoryBudget.Bounded ?: return null
        if (maxMemory == 0L) return null
        val largest = largestFitting!!
        if (maxMemory <= largest) return null
        val human = MemoryConfig::bytesToHuman
        return "maxmemory $maxMemory (${human(maxMemory)}) needs up to $ratio x as much resident memory, " +
            "more than the container's limit of ${bounded.bytes} (${human(bounded.bytes)}, ${bounded.source}); " +
            "at most $largest (${human(largest)}) fits"
    }

    /** The startup line: the budget and what it allows, or why nothing is checked. */
    fun describe(maxMemory: Long): String =
        when {
            budget !is MemoryBudget.Bounded -> "memory budget ${budget.render()}: maxmemory is not checked against it"
            maxMemory == 0L -> "memory budget ${budget.render()}: maxmemory 0 sets no limit, so nothing keeps the dataset inside it"
            else -> "memory budget ${budget.render()}: maxmemory $maxMemory fits, up to $largestFitting"
        }

    companion object {
        /** B-28's measured peak, 3.3 × `used_memory`; the chart passes its own value. */
        const val DEFAULT_PEAK_RATIO_TENTHS = 33
    }
}
