package com.example.test_app.dialogue

import java.lang.Character.UnicodeScript

/**
 * Language-agnostic script helpers (pure Kotlin, no per-language code, no language table).
 *
 * The speech model reports the spoken language itself (`Transcript.language`, a free-form name such as "tamil" or `en`); this object is
 * only the fallback used when no language came with the transcript (the Whisper path, a typed transcript, the mock): [detect] names the
 * dominant non-Latin Unicode script of the letters (`devanagari`, `tamil`, `bengali`, `oriya`, ...), else `en`. The script of a word
 * is NOT its language (Assamese is written in the Bengali script, Hindi and Marathi in Devanagari): it is a fallback label only.
 * [hasNonLatin] (any letter outside Latin/Common/Inherited) is the single "this text cannot be read by the English voice / is not
 * English" test used by the mapper, the read-outs and the phrase validator. See `doc/DIALOGUE.md`.
 */
object LanguageDetect {
    private fun isLatinLike(script: UnicodeScript) =
        script == UnicodeScript.LATIN || script == UnicodeScript.COMMON || script == UnicodeScript.INHERITED

    /** The language to store: the STT's own [sttLanguage] (trimmed, lowercased; `english` becomes `en`) when it gave one, else [detect]. */
    fun resolve(sttLanguage: String?, text: String): String = normalizeName(sttLanguage) ?: detect(text)

    /** A language name as reported by a model -> stored form: lowercase, `en` for English, null for blank / unknown / "n/a". */
    fun normalizeName(name: String?): String? {
        val whole = name.orEmpty().trim().trim('"', '\'', '.', '*', '`', '[', ']', '<', '>').trim().lowercase()
        if (whole in UNKNOWN) return null
        val cleaned = whole.substringBefore('(').substringBefore(',').substringBefore('/').substringBefore(';').trim()
        return when {
            cleaned.isEmpty() || cleaned in UNKNOWN -> null
            cleaned == "english" || cleaned == "en" -> "en"
            else -> cleaned
        }
    }

    private val UNKNOWN = setOf("unknown", "n/a", "na", "none", "null", "unclear", "unspecified", "not sure", "undetermined", "<language>")

    /**
     * `en`, or the lowercase name of the dominant non-Latin Unicode script of [text]'s letters (`devanagari`, `tamil`, `bengali`, `oriya`, ...).
     * Latin-script text of any language (Hinglish, romanized Tamil, ...) is `en`: the report is English either way and the model reads it.
     * Mostly-Latin text with a few foreign letters stays `en`.
     */
    fun detect(text: String): String {
        val counts = HashMap<UnicodeScript, Int>()
        var latin = 0
        text.codePoints().forEach { cp ->
            if (!Character.isLetter(cp)) return@forEach
            val script = UnicodeScript.of(cp)
            if (script == UnicodeScript.LATIN) latin++ else if (!isLatinLike(script)) counts.merge(script, 1, Int::plus)
        }
        val top = counts.maxByOrNull { it.value } ?: return "en"
        return if (top.value >= latin / 4) top.key.name.lowercase() else "en"
    }

    /** True when [text] has any letter outside Latin/Common/Inherited scripts (Devanagari, Tamil, Odia, ...): the English TTS voice cannot read it. */
    fun hasNonLatin(text: String): Boolean = text.codePoints().anyMatch { Character.isLetter(it) && !isLatinLike(UnicodeScript.of(it)) }

    /** [text] unless it contains non-Latin script, then [generic] (a spoken fallback such as "the place you mentioned"). */
    fun speakable(text: String, generic: String): String = if (hasNonLatin(text)) generic else text
}
