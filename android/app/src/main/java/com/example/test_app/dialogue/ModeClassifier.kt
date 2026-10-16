package com.example.test_app.dialogue

import com.example.test_app.domain.service.TextMatch

/** Optional CLIP label of a photo. Out of scope in this phase: callers pass null. */
data class ClipLabel(val label: String, val score: Float)

/**
 * Decides the tone mode (Spec v2 section 3.1): `finalMode = max(ruleMode, modelMode)`.
 * Keyword rules come from `mode_keywords.json`; a negation word within [ModeKeywords.negationWindowTokens] tokens
 * before an injury-related CARE keyword cancels that keyword ("no one was hurt"); the same guard applies to the URGENT
 * distress phrases listed under `negatable` ("I don't need help", "not very urgent").
 * Escalate-only is enforced by the caller (`DialogueManager`), which keeps the highest mode seen.
 * See `doc/DIALOGUE.md`.
 */
class ModeClassifier(private val keywords: ModeKeywords) {

    /** Mode from keyword rules applied to every text in [texts] (original and English transcripts, answers). */
    fun ruleMode(texts: List<String>): DialogueMode {
        var mode = DialogueMode.PLAIN
        for (text in texts) {
            val lower = text.lowercase()
            if (keywords.urgent.any { hasUrgentKeyword(lower, it) }) return DialogueMode.URGENT
            if (mode < DialogueMode.CARE && hasCareKeyword(lower)) mode = DialogueMode.CARE
        }
        return mode
    }

    /** Mode implied by extracted fields (Spec v2 section 3.1 step 2). */
    fun modelMode(injury: Boolean?, severity: String?, clip: ClipLabel? = null): DialogueMode {
        var mode = DialogueMode.PLAIN
        if (injury == true) mode = DialogueMode.CARE
        if (severity.equals("Critical", ignoreCase = true)) mode = DialogueMode.URGENT
        if (clip != null && clip.score >= 0.5f && clip.label.lowercase() in setOf("fire", "smoke")) mode = DialogueMode.URGENT
        return mode
    }

    /** `max(ruleMode, modelMode)`. */
    fun classify(texts: List<String>, injury: Boolean?, severity: String?, clip: ClipLabel? = null): DialogueMode =
        DialogueMode.max(ruleMode(texts), modelMode(injury, severity, clip))

    /** True for harassment / threat / assault style incidents (drives `people_involved` and the typing offer). */
    fun isPersonalIncident(vararg texts: String?): Boolean =
        texts.filterNotNull().any { t -> keywords.personalIncident.any { TextMatch.contains(t.lowercase(), it, prefix = true) } }

    /** An URGENT keyword; the distress phrases in [ModeKeywords.urgentNegatable] ("need help", "very urgent") obey the negation guard. */
    private fun hasUrgentKeyword(lower: String, keyword: String): Boolean =
        if (keyword in keywords.urgentNegatable) {
            TextMatch.containsUnnegated(lower, keyword, keywords.negationWords, keywords.negationWindowTokens, keywords.negationAfterWords)
        } else {
            TextMatch.contains(lower, keyword)
        }

    private fun hasCareKeyword(lower: String): Boolean {
        for (kw in keywords.care) {
            for (m in TextMatch.regex(kw).findAll(lower)) {
                if (kw in keywords.careInjuryRelated && isNegated(lower, m.range.first, m.range.last + 1)) continue
                return true
            }
        }
        return false
    }

    /** A negation word (or any `...n't` word) among the last N tokens before [keywordStart], within the same clause. */
    internal fun isNegated(lower: String, keywordStart: Int, keywordEnd: Int = keywordStart): Boolean =
        TextMatch.isNegated(lower, keywordStart, keywords.negationWords, keywords.negationWindowTokens, keywordEnd, keywords.negationAfterWords)
}
