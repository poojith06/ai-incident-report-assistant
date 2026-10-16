package com.example.test_app.ai.llama

import com.example.test_app.ai.LlmOutputParser
import com.example.test_app.ai.Prompts
import com.example.test_app.dialogue.LanguageDetect
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure cleanup of raw Gemma 4 output (the JVM tests use hand-written strings until device outputs are captured,
 * `app/src/test/resources/llm_outputs/README.txt`).
 */
object GemmaText {
    private val LABEL = Regex("^(?:the\\s+)?(?:transcript(?:ion)?|audio|text)\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE)
    private val QUOTES = charArrayOf('"', '\'', '\u201C', '\u201D', '\u2018', '\u2019', '`')

    /**
     * Raw reply of the transcription prompt -> the transcript: thought-channel blocks and turn tokens removed, code fences and a leading
     * "Transcript:" label dropped, line breaks and runs of spaces flattened, surrounding quotes removed. Numbers are left as the model
     * wrote them ("a2" vs "a two" differ between runs/devices, `MODELS_USAGE.md` 5b): the eval normaliser and `SpokenTime` treat digits and words alike.
     * Returns "" when nothing is left.
     */
    fun cleanTranscript(raw: String): String {
        var s = LlmOutputParser.cleanText(raw).replace("```", " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        s = LABEL.replace(s, "")
        s = s.trim(*QUOTES).trim()
        return s
    }

    /** A parsed STT reply: the verbatim [text] and the [language] the model named (stored form, see [LanguageDetect.normalizeName]), null when it named none. */
    data class TranscriptReply(val text: String, val language: String?)

    private val LANGUAGE_LINE = Regex("^[\\s*_#>\\-]*(?:detected\\s+)?language[\\s*_]*[:\\-][\\s*_]*(.*)$", RegexOption.IGNORE_CASE)
    private val TRANSCRIPT_LINE = Regex("^[\\s*_#>\\-]*(?:the\\s+)?transcript(?:ion)?[\\s*_]*[:\\-][\\s*_]*(.*)$", RegexOption.IGNORE_CASE)
    private val TRANSCRIPT_INLINE = Regex("\\btranscript(?:ion)?\\s*:", RegexOption.IGNORE_CASE)

    /**
     * Raw reply of [Prompts.TRANSCRIBE_INSTRUCTION] -> transcript + language. Expected shape: `Language: <name>` then `Transcript: <text>`
     * (the transcript may span several lines). Robust to: thought-channel / think / turn tokens, code fences, markdown bold labels,
     * chatter before the `Language:` line, the label order swapped, both labels on one line, a `Language:` line with no `Transcript:`
     * label (the lines after it are the transcript), no `Language:` line at all (the whole reply is the transcript, language null,
     * exactly as [cleanTranscript]) and an unknown language name ("unknown", "n/a" -> null). No language is special-cased.
     */
    fun parseTranscriptReply(raw: String): TranscriptReply {
        val lines = LlmOutputParser.cleanText(raw).replace("```", "\n").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val langIdx = lines.indexOfFirst { LANGUAGE_LINE.matches(it) }
        if (langIdx < 0) {
            val marked = lines.indexOfFirst { TRANSCRIPT_LINE.matches(it) }
            if (marked < 0) return TranscriptReply(cleanTranscript(raw), null)
            return TranscriptReply(joinTranscript(listOf(TRANSCRIPT_LINE.matchEntire(lines[marked])!!.groupValues[1]) + lines.drop(marked + 1)), null)
        }
        var langValue = LANGUAGE_LINE.matchEntire(lines[langIdx])!!.groupValues[1]
        val parts = ArrayList<String>()
        val inline = TRANSCRIPT_INLINE.find(langValue)
        if (inline != null) {
            parts += langValue.substring(inline.range.last + 1)
            langValue = langValue.substring(0, inline.range.first)
        }
        var collecting = parts.isNotEmpty()
        val after = ArrayList<String>()
        for ((i, line) in lines.withIndex()) {
            if (i == langIdx) continue
            val m = TRANSCRIPT_LINE.matchEntire(line)
            when {
                m != null -> { collecting = true; parts += m.groupValues[1] }
                collecting -> parts += line
                i > langIdx -> after += line
            }
        }
        val text = joinTranscript(if (collecting) parts else after)
        return TranscriptReply(text, LanguageDetect.normalizeName(langValue))
    }

    /** The plain-text reply of [Prompts.transcriptionForced]: cleaned like [cleanTranscript]; the language is the chosen [language] (lowercased, `en` for English). */
    fun forcedTranscriptReply(raw: String, language: String): TranscriptReply =
        TranscriptReply(cleanTranscript(raw), LanguageDetect.normalizeName(language))

    private fun joinTranscript(parts: List<String>): String =
        parts.joinToString(" ").replace(Regex("\\s+"), " ").trim().trim(*QUOTES).trim()

    /** What the photo prompt returned: one short sentence and up to [MAX_TAGS] lowercase tags (possibly none). */
    data class PhotoDescription(val description: String, val tags: List<String>)

    const val MAX_TAGS = 5

    /**
     * Raw reply of [Prompts.imageDescription] (continuing after [Prompts.IMAGE_PREFIX]) -> description + tags. When the JSON cannot be
     * read the cleaned first line is the description (no tags). Null when nothing usable came back.
     */
    fun parsePhoto(raw: String): PhotoDescription? {
        val json = LlmOutputParser.parseObject(raw, Prompts.IMAGE_PREFIX)
        val description = (json?.get("description") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.replace(Regex("\\s+"), " ")?.trim()
        if (!description.isNullOrEmpty()) {
            val tags = (json?.get("tags") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_TAGS)
            return PhotoDescription(description, tags)
        }
        if (json != null) return null // a JSON answer without a description is not a description
        val line = LlmOutputParser.cleanLine(raw) ?: return null
        return PhotoDescription(line, emptyList())
    }
}
