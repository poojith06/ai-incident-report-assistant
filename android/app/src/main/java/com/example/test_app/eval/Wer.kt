package com.example.test_app.eval

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Word error counts of one reference/hypothesis pair. [wer] = (S + D + I) / N with N = [referenceWords].
 * With an empty reference the rate is 0.0 for an empty hypothesis and 1.0 otherwise (the ratio is undefined).
 */
@Serializable
data class WerResult(
    val substitutions: Int,
    val deletions: Int,
    val insertions: Int,
    @SerialName("reference_words") val referenceWords: Int,
) {
    val errors: Int get() = substitutions + deletions + insertions
    val wer: Double
        get() = if (referenceWords == 0) (if (errors == 0) 0.0 else 1.0) else errors.toDouble() / referenceWords

    operator fun plus(o: WerResult) = WerResult(
        substitutions + o.substitutions, deletions + o.deletions, insertions + o.insertions, referenceWords + o.referenceWords,
    )

    companion object {
        val ZERO = WerResult(0, 0, 0, 0)
    }
}

/**
 * Word error rate: word-level Levenshtein distance (substitution, deletion and insertion cost 1) between the
 * normalised reference and hypothesis ([TextNorm.tokens]: case, punctuation, apostrophes and number words are
 * normalised away). Pure Kotlin. The backtrace prefers a match/substitution over a deletion over an insertion, so
 * the split of S/D/I on ties is fixed; the total distance never depends on it.
 */
object Wer {
    fun compute(reference: String, hypothesis: String): WerResult {
        val ref = TextNorm.tokens(reference)
        val hyp = TextNorm.tokens(hypothesis)
        val d = Array(ref.size + 1) { IntArray(hyp.size + 1) }
        for (i in 0..ref.size) d[i][0] = i
        for (j in 0..hyp.size) d[0][j] = j
        for (i in 1..ref.size) {
            for (j in 1..hyp.size) {
                val sub = d[i - 1][j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1
                d[i][j] = minOf(sub, d[i - 1][j] + 1, d[i][j - 1] + 1)
            }
        }
        var s = 0
        var del = 0
        var ins = 0
        var i = ref.size
        var j = hyp.size
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + (if (ref[i - 1] == hyp[j - 1]) 0 else 1) -> {
                    if (ref[i - 1] != hyp[j - 1]) s++
                    i--
                    j--
                }
                i > 0 && d[i][j] == d[i - 1][j] + 1 -> {
                    del++
                    i--
                }
                else -> {
                    ins++
                    j--
                }
            }
        }
        return WerResult(s, del, ins, ref.size)
    }

    /** Corpus WER: total errors over total reference words (longer utterances weigh more). Null without any reference word. */
    fun corpus(results: List<WerResult>): Double? {
        val total = results.fold(WerResult.ZERO) { a, b -> a + b }
        return if (total.referenceWords == 0) null else total.errors.toDouble() / total.referenceWords
    }

    /** Mean of the per-item rates (every item weighs the same). Null for an empty list. */
    fun mean(results: List<WerResult>): Double? = if (results.isEmpty()) null else results.map { it.wer }.average()
}
