package com.example.test_app.eval

import kotlinx.serialization.Serializable
import kotlin.math.ceil

/**
 * Descriptive statistics of a list of numbers (latencies in ms). Pure Kotlin.
 * - [median]: middle value; the mean of the two middle values for an even count.
 * - [p90]: nearest-rank 90th percentile: the value at rank `ceil(0.9 * n)` of the sorted list (so with 10 values it is the 9th).
 */
@Serializable
data class Stats(
    val count: Int,
    val median: Double,
    val p90: Double,
    val mean: Double,
    val min: Double,
    val max: Double,
) {
    companion object {
        /** Null for an empty list. */
        fun of(values: List<Number>): Stats? {
            if (values.isEmpty()) return null
            val s = values.map { it.toDouble() }.sorted()
            val n = s.size
            val median = if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
            return Stats(n, median, percentile(s, 90), s.average(), s.first(), s.last())
        }

        /** Nearest-rank percentile of an already sorted, non-empty list; [p] in 1..100. */
        fun percentile(sorted: List<Double>, p: Int): Double {
            val rank = ceil(p.toDouble() * sorted.size / 100.0).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}

/** Latency report of an evaluation run (all values in ms; each null when nothing was measured). */
@Serializable
data class LatencySummary(
    /** Speech recognition per utterance (audio input only). */
    val asr: Stats? = null,
    /** Time to first generated chunk of every extraction / update LLM call (approximately prefill). */
    val prefill: Stats? = null,
    /** Generation time after the first chunk of every extraction / update LLM call. */
    val decode: Stats? = null,
    /** Per report: narration recognition (audio input) plus the first extraction = end of speech to filled form. */
    val total: Stats? = null,
)
