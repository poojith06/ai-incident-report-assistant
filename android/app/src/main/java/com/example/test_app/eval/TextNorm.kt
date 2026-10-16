package com.example.test_app.eval

/**
 * Text normalisation shared by the evaluation metrics (WER and field matching). Pure Kotlin, no Android.
 *
 * Normal form of a text (`tokens` / `normalize`):
 * 1. lower case; typographic apostrophes become `'`;
 * 2. `a.m.` / `p.m.` become `am` / `pm`;
 * 3. apostrophes are deleted (`don't` -> `dont`), every other character that is not a letter or digit becomes a space;
 * 4. the number words zero..twenty, thirty, forty, fifty become digits (`two` -> `2`), so "Conference Room two" and
 *    "Conference Room 2" are the same text. The mapping is applied to BOTH sides of every comparison.
 */
object TextNorm {
    private val NUMBER_WORDS = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6",
        "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10", "eleven" to "11", "twelve" to "12",
        "thirteen" to "13", "fourteen" to "14", "fifteen" to "15", "sixteen" to "16", "seventeen" to "17",
        "eighteen" to "18", "nineteen" to "19", "twenty" to "20", "thirty" to "30", "forty" to "40", "fifty" to "50",
    )
    private val AM_PM = Regex("""\b([ap])\.\s?m\b\.?""")
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /** Lower-cases, rewrites `a.m.`, removes apostrophes. Keeps other punctuation. */
    fun lightClean(text: String): String {
        val s = text.lowercase().replace('\u2019', '\'')
        return AM_PM.replace(s) { it.groupValues[1] + "m" }.replace("'", "")
    }

    /** Tokens of the normal form (see the class comment). */
    fun tokens(text: String): List<String> =
        lightClean(text).split(SPLIT).filter { it.isNotEmpty() }.map { NUMBER_WORDS[it] ?: it }

    /** Replaces number words by digits inside an already lower-cased text, keeping everything else (colons, dashes) intact. */
    fun digitsForNumberWords(text: String): String = Regex("[a-z]+").replace(text) { NUMBER_WORDS[it.value] ?: it.value }

    fun normalize(text: String): String = tokens(text).joinToString(" ")

    /**
     * Light stemming used by the overlap matcher: strips `ing`, `ed`, `es`, `s`, a trailing `e` and a doubled final
     * consonant, so `isolated`, `isolate` and `isolates` all become `isolat`, `slipped` -> `slip`, `leaking` -> `leak`.
     * Words of 3 letters or fewer and numbers are unchanged.
     */
    fun stem(token: String): String {
        if (token.length <= 3 || token.all { it.isDigit() }) return token
        var s = token
        s = when {
            s.endsWith("ing") && s.length > 5 -> s.dropLast(3)
            s.endsWith("ed") && s.length > 4 -> s.dropLast(2)
            s.endsWith("es") && s.length > 4 -> s.dropLast(2)
            s.endsWith("s") && !s.endsWith("ss") && s.length > 3 -> s.dropLast(1)
            else -> s
        }
        if (s.endsWith("e") && s.length > 3) s = s.dropLast(1)
        if (s.length > 3 && s[s.length - 1] == s[s.length - 2] && s.last() !in "aeiouls") s = s.dropLast(1)
        return s
    }

    private val STOP_WORDS = setOf(
        "a", "an", "the", "of", "in", "at", "on", "by", "to", "and", "is", "was", "were", "are", "be", "been", "it", "its",
        "that", "this", "has", "have", "had", "there", "with", "for", "from", "i", "my", "me", "we", "they", "he", "she",
        "his", "her", "their", "them", "also", "just", "uh", "um", "er", "hmm", "so", "then",
    )

    /** Stemmed content tokens: normal-form tokens without stop words. */
    fun contentTokens(text: String): List<String> = tokens(text).filter { it !in STOP_WORDS }.map(::stem)

    private val LOCATION_FILLERS = STOP_WORDS + setOf(
        "near", "next", "beside", "behind", "inside", "outside", "around", "close", "opposite", "front", "area", "side",
    )

    /** Location tokens: normal-form tokens without articles, prepositions of place and stop words (not stemmed). */
    fun locationTokens(text: String): List<String> = tokens(text).filter { it !in LOCATION_FILLERS }
}
