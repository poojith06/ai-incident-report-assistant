package com.example.test_app.domain.service

/**
 * Whole-word, case-insensitive phrase matching shared by the mode classifier, the severity policy, the phrase validator
 * and the answer cues. Pure Kotlin. Letters, combining marks (Devanagari / Telugu vowel signs are `\p{M}`) and digits are
 * word characters, so Hindi and Telugu phrases match as whole words too. See `doc/DIALOGUE.md`.
 */
internal object TextMatch {
    private const val WORD = "\\p{L}\\p{M}\\p{N}"
    private val cache = HashMap<String, Regex>()
    private val clauseBreak = Regex("[.!?,;:\\n\u0964]")

    /** Regex matching [phrase] as whole words; [prefix] = true also accepts longer words (stems such as `diagnos`). */
    fun regex(phrase: String, prefix: Boolean = false): Regex = synchronized(cache) {
        cache.getOrPut("$prefix|$phrase") {
            val p = phrase.lowercase().trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
            Regex("(?<![$WORD])$p" + if (prefix) "" else "(?![$WORD])", RegexOption.IGNORE_CASE)
        }
    }

    fun contains(text: String, phrase: String, prefix: Boolean = false): Boolean = regex(phrase, prefix).containsMatchIn(text)

    fun containsAny(text: String, phrases: Collection<String>): Boolean = phrases.any { contains(text, it) }

    private val tokenRegex = Regex("[$WORD']+")

    fun tokens(text: String): List<String> = tokenRegex.findAll(text.lowercase()).map { it.value }.toList()

    /**
     * Negation guard: true when a negation word (or any `...n't` word) is among the [window] tokens before [keywordStart]
     * within the same clause (commas and sentence ends reset the window), or when one of [afterWords] (Hindi / Telugu put the
     * negation after the word: "chot nahi", "चोट नहीं") is among the [window] tokens after [keywordEnd] in the same clause.
     * [lower] is lowercase text; [keywordEnd] is exclusive.
     */
    fun isNegated(
        lower: String,
        keywordStart: Int,
        negationWords: Set<String>,
        window: Int,
        keywordEnd: Int = keywordStart,
        afterWords: Set<String> = emptySet(),
    ): Boolean {
        val before = lower.substring(0, keywordStart)
        val clause = before.substring(clauseBreak.findAll(before).lastOrNull()?.range?.last?.plus(1) ?: 0)
        if (tokens(clause).takeLast(window).any { it in negationWords || it.endsWith("n't") }) return true
        if (afterWords.isEmpty() || keywordEnd <= keywordStart) return false
        val rest = lower.substring(keywordEnd)
        val tail = rest.substring(0, clauseBreak.find(rest)?.range?.first ?: rest.length)
        return tokens(tail).take(window).any { it in afterWords }
    }

    /** True when [phrase] occurs in [lower] at least once without a negation in front of it (or after it, see [isNegated]). */
    fun containsUnnegated(
        lower: String,
        phrase: String,
        negationWords: Set<String>,
        window: Int,
        afterWords: Set<String> = emptySet(),
    ): Boolean =
        regex(phrase).findAll(lower).any { !isNegated(lower, it.range.first, negationWords, window, it.range.last + 1, afterWords) }
}
