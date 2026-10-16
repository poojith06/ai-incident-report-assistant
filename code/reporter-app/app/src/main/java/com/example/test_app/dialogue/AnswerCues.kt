package com.example.test_app.dialogue

import com.example.test_app.domain.service.TextMatch

/**
 * Answer and narration cues driven by `field_keywords.json` (Spec v2 sections 4.3, 4.4, 4.5, 6), English plus Hindi
 * (Devanagari and romanized, all `needs_native_review`). See `doc/DIALOGUE.md`.
 */
class AnswerCues(private val keywords: FieldKeywords) {
    /** "I don't know", "skip", "pata nahi", "पता नहीं", "छोड़ो", "रहने दो": the user declines this question. */
    fun isUnsure(answer: String): Boolean = TextMatch.containsAny(answer, keywords.unsurePhrases)

    /** "here", "yahin", "यहीं", "ikkada": the user means the current GPS position. */
    fun isHere(answer: String): Boolean {
        val t = answer.trim().trimEnd('.', '!', ',', '\u0964').lowercase()
        return keywords.herePhrases.any { t == it || t.endsWith(" $it") && t.split(" ").size <= 3 }
    }

    /** True if [text] implies the incident was in the past (earlier, yesterday, kal, कल, ...). */
    fun hasPastCue(text: String): Boolean = TextMatch.containsAny(text, keywords.pastCues)

    /** A read-back reply that needs no merge ("no", "that's fine", "looks good", "ठीक है"). */
    fun isNoChange(answer: String): Boolean {
        val t = answer.trim().trimEnd('.', '!', ',', '\u0964').lowercase()
        return keywords.noChangePhrases.any { it == t }
    }

    /** Words in [text] excluding articles and fillers. */
    fun contentWordCount(text: String): Int = TextMatch.tokens(text).count { it !in keywords.fillerWords }
}
