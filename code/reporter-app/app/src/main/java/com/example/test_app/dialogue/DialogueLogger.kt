package com.example.test_app.dialogue

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** One log line (Spec v2 section 10 plus the optional LLM metrics of build prompt section 6). Null values are written as JSON null. */
data class TurnLog(
    val epochSec: Long,
    val state: String,
    val mode: DialogueMode,
    val field: String? = null,
    val template: String? = null,
    val rephrased: String? = null,
    /** `pass`, `fail:<reason>`, `timeout`, or null when no rephrase was attempted. */
    val validator: String? = null,
    val spoken: String? = null,
    val asrText: String? = null,
    val asrConf: Float? = null,
    /** Language detected from the first narration (the STT-reported language name, or `en` / the dominant script name from `LanguageDetect`); null before it. */
    val lang: String? = null,
    /** `filled`, `unknown_reporter_unsure`, `no_response`, `unverified` or null for non-question turns. */
    val outcome: String? = null,
    val rephraseMs: Long? = null,
    val asrMs: Long? = null,
    val promptTokens: Int? = null,
    val prefillMs: Long? = null,
    val decodeMs: Long? = null,
    /** Audit of the planner (ADR-048): `field -> "ask: <reason>"` / `"skip: <reason>"`. Null for turns that do not plan. */
    val relevance: Map<String, String>? = null,
)

/**
 * Appends one JSON line per turn to `<dir>/<reportId>.jsonl` (Spec v2 section 10). The report id is only known after
 * the first extraction, so lines are held in memory and flushed by [bind]. With `dir == null` (tests) lines are only
 * kept in [lines]. Logging never throws into the dialogue.
 */
class DialogueLogger(private val dir: File? = null) {
    private val _lines = mutableListOf<String>()
    private var file: File? = null

    /** Every line written so far. */
    val lines: List<String> get() = synchronized(_lines) { _lines.toList() }

    /** Names the report; flushes buffered lines to `<dir>/<reportId>.jsonl`. */
    fun bind(reportId: String) {
        val d = dir ?: return
        synchronized(_lines) {
            runCatching {
                d.mkdirs()
                val f = File(d, "$reportId.jsonl")
                f.writeText(_lines.joinToString("") { it + "\n" })
                file = f
            }
        }
    }

    fun log(entry: TurnLog) {
        val line = toJson(entry).toString()
        synchronized(_lines) {
            _lines += line
            file?.let { f -> runCatching { f.appendText(line + "\n") } }
        }
    }

    companion object {
        fun toJson(e: TurnLog): JsonObject = buildJsonObject {
            put("t", e.epochSec)
            put("state", e.state)
            put("mode", e.mode.name)
            put("field", e.field)
            put("template", e.template)
            put("rephrased", e.rephrased)
            put("validator", e.validator)
            put("spoken", e.spoken)
            put("asr_text", e.asrText)
            put("asr_conf", e.asrConf)
            put("lang", e.lang)
            put("outcome", e.outcome)
            put("latency_ms", buildJsonObject {
                put("rephrase", e.rephraseMs)
                put("asr", e.asrMs)
            })
            put("prompt_tokens", e.promptTokens)
            put("prefill_ms", e.prefillMs)
            put("decode_ms", e.decodeMs)
            put("relevance", e.relevance?.let { m -> buildJsonObject { m.forEach { (k, v) -> put(k, v) } } } ?: kotlinx.serialization.json.JsonNull)
        }
    }
}
