package com.example.test_app.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Tolerant parser for raw Qwen3 output (pure Kotlin). The prompts force-prefix the JSON ([Prompts.EXTRACTION_PREFIX],
 * [Prompts.UPDATE_PREFIX]) so the engine returns only the continuation; this class re-attaches the prefix and copes with
 * `<think>` blocks, chat-template tokens, code fences, chatter around the JSON, a repeated prefix, trailing text and
 * truncated generations. See `doc/DIALOGUE.md`.
 */
object LlmOutputParser {
    private val json = Json
    private val TRAILING_COMMA = Regex(",\\s*([}\\]])")

    private val THINK_BLOCK = Regex("(?s)<think>.*?</think>")
    private val THINK_UNCLOSED = Regex("(?s)<think>.*$")
    private val STOP_TOKENS = Regex("<\\|(?:im_end|im_start|endoftext)\\|>|<turn\\|>|<\\|turn>")

    /** Gemma 4 thought channel: `<|channel>thought\n...<channel|>` (an empty one is emitted even with thinking off), also unclosed or stray ends. */
    private val CHANNEL_BLOCK = Regex("(?s)<\\|channel>.*?<channel\\|>")
    private val CHANNEL_UNCLOSED = Regex("(?s)<\\|channel>.*$")
    private val CHANNEL_STRAY_END = Regex("<channel\\|>")
    private val FENCE = Regex("(?s)```[a-zA-Z]*\\s*(.*?)(?:```|$)")
    private val REPEATED_BRACE = Regex("^\\{\\s*(?:\\{\\s*)+")

    /**
     * Removes `<think>...</think>` (and an unclosed `<think>` to the end), then cuts the text at the first
     * `<|im_end|>` / `<|im_start|>` / `<|endoftext|>`. Result is trimmed.
     */
    fun cleanText(raw: String): String {
        var s = raw
        s = CHANNEL_BLOCK.replace(s, "")
        s = CHANNEL_UNCLOSED.replace(s, "")
        s = CHANNEL_STRAY_END.replace(s, "")
        s = THINK_BLOCK.replace(s, "")
        val closeIdx = s.indexOf("</think>")
        if (closeIdx >= 0) s = s.substring(closeIdx + "</think>".length) // opening tag was part of the prompt
        s = THINK_UNCLOSED.replace(s, "")
        val stop = STOP_TOKENS.find(s)
        if (stop != null) s = s.substring(0, stop.range.first)
        return s.trim()
    }

    /** First non-empty line of a rephrase reply with think/stop tokens, code fences and surrounding quotes removed; null if nothing is left. */
    fun cleanLine(raw: String): String? {
        val line = cleanText(raw).replace("```", "").lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val unquoted = line.trim().trim('"', '\'', '\u201C', '\u201D', '\u2018', '\u2019', '`').trim()
        return unquoted.ifEmpty { null }
    }

    /**
     * Parses the model's JSON object. [prefix] is the assistant prefix that ended the prompt (`{"incident_type":` or `{`);
     * it is prepended unless the model repeated it. Returns null when no object can be recovered. A truncated object
     * is repaired by dropping the incomplete last pair; a truncated object with no complete pair is null, except the bare
     * prefix `{` (the model said nothing), which is an empty [JsonObject].
     */
    fun parseObject(raw: String, prefix: String): JsonObject? {
        var text = cleanText(raw)
        val fence = FENCE.find(text)
        if (fence != null) text = fence.groupValues[1].trim()
        // Chatter before the JSON ("Sure! Here is the report: {...}")
        if (text.isNotEmpty() && text[0].isLetter() && !text.startsWith("null") && '{' in text) {
            text = text.substring(text.indexOf('{'))
        }
        val prefixBody = prefix.removePrefix("{").trim()
        val full = when {
            text.startsWith("{") -> text
            prefixBody.isNotEmpty() && text.startsWith(prefixBody) -> "{$text"
            else -> prefix + text
        }
        val candidate = REPEATED_BRACE.replace(full, "{")
        if (!candidate.startsWith("{")) return null
        return extractObject(candidate)
    }

    /** Scans one top-level object out of [text] (ignoring anything after it) and repairs truncation. */
    private fun extractObject(text: String): JsonObject? {
        var depth = 0
        var inString = false
        var escaped = false
        var lastTopComma = -1
        var end = -1
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) { end = i; break }
                }
                ',' -> if (depth == 1) lastTopComma = i
            }
        }
        if (end >= 0) return parse(text.substring(0, end + 1))
        // Truncated: first try closing as-is (last pair may be complete), else drop the incomplete last pair.
        val trimmed = text.trimEnd().trimEnd(',')
        if (!inString && depth == 1 && !trimmed.endsWith(":")) parse("$trimmed}")?.let { return it }
        if (lastTopComma > 0) parse(text.substring(0, lastTopComma) + "}")?.let { return it }
        return if (text.trim() == "{") JsonObject(emptyMap()) else null
    }

    /** Strict parse; a second attempt drops trailing commas before `}` / `]`. */
    private fun parse(candidate: String): JsonObject? {
        fun attempt(text: String): JsonObject? = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
        return attempt(candidate) ?: attempt(TRAILING_COMMA.replace(candidate, "$1"))
    }
}
