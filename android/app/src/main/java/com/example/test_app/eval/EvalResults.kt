package com.example.test_app.eval

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One recognised (or typed) utterance of a case. [key] is the dialogue field answered (`null` = narration, `safety`). */
@Serializable
data class UtteranceResult(
    val key: String? = null,
    val reference: String? = null,
    val hypothesis: String? = null,
    @SerialName("asr_ms") val asrMs: Long? = null,
    val confidence: Float? = null,
    /** Present only for audio input (typed transcripts have no recognition errors). */
    val wer: WerResult? = null,
)

/** One expected field value and what the pipeline produced. [match] is a [MatchLevel] name, [placement] a [Placement] name. */
@Serializable
data class FieldResult(val field: String, val expected: String, val actual: String?, val match: String, val placement: String)

/** A field that must stay empty (never invent). */
@Serializable
data class EmptyCheck(val field: String, val actual: String?)

/** Expected versus actual outcome of one asked field. */
@Serializable
data class OutcomeCheck(val field: String, val expected: String, val actual: String?)

/** One question turn, taken from the dialogue's JSONL log. */
@Serializable
data class TurnResult(
    val field: String,
    val outcome: String? = null,
    val template: String? = null,
    val rephrased: String? = null,
    /** `pass`, `fail:<reason>`, `timeout` or null when no rephrase was attempted. */
    val validator: String? = null,
    val spoken: String? = null,
    @SerialName("rephrase_ms") val rephraseMs: Long? = null,
)

/** One LLM call of the extraction service (`extract` = first pass, `update` = an answer or correction). */
@Serializable
data class LlmCallResult(
    val kind: String,
    val ms: Long,
    @SerialName("prefill_ms") val prefillMs: Long? = null,
    @SerialName("decode_ms") val decodeMs: Long? = null,
)

/** Everything measured for one case. `status`: `ok`, `error` (see [detail]) or `skipped` (see [detail]). */
@Serializable
data class CaseResult(
    val id: String,
    val status: String,
    val detail: String? = null,
    /** `transcript` or `audio`. */
    val input: String,
    val utterances: List<UtteranceResult> = emptyList(),
    /** `review` or `no_narration`. */
    val result: String? = null,
    @SerialName("expected_result") val expectedResult: String? = null,
    val mode: String? = null,
    @SerialName("expected_mode") val expectedMode: String? = null,
    val emergency: Boolean = false,
    @SerialName("expected_emergency") val expectedEmergency: Boolean? = null,
    val asked: List<String> = emptyList(),
    @SerialName("expected_asked") val expectedAsked: List<String>? = null,
    @SerialName("asked_includes") val askedIncludes: List<String> = emptyList(),
    @SerialName("asked_excludes") val askedExcludes: List<String> = emptyList(),
    val fields: List<FieldResult> = emptyList(),
    @SerialName("empty_checks") val emptyChecks: List<EmptyCheck> = emptyList(),
    @SerialName("outcome_checks") val outcomeChecks: List<OutcomeCheck> = emptyList(),
    val turns: List<TurnResult> = emptyList(),
    @SerialName("ask_once_violations") val askOnceViolations: Int = 0,
    @SerialName("invariant_violations") val invariantViolations: List<String> = emptyList(),
    @SerialName("llm_calls") val llmCalls: List<LlmCallResult> = emptyList(),
    /** Narration recognition (audio only) plus the first extraction: end of speech to filled form. */
    @SerialName("first_form_ms") val firstFormMs: Long? = null,
    @SerialName("total_ms") val totalMs: Long = 0,
    val spoken: List<String> = emptyList(),
) {
    val ok: Boolean get() = status == STATUS_OK

    /** Is the asked-fields expectation met (exact list, includes, excludes)? Null when the case states none. */
    val askedOk: Boolean?
        get() {
            if (expectedAsked == null && askedIncludes.isEmpty() && askedExcludes.isEmpty()) return null
            return (expectedAsked == null || expectedAsked == asked) &&
                askedIncludes.all { it in asked } && askedExcludes.none { it in asked }
        }

    val modeOk: Boolean? get() = expectedMode?.let { it == mode }
    val resultOk: Boolean get() = expectedResult == null || expectedResult == result

    /** Word error counts over the utterances of this case that have a reference and a hypothesis. */
    val werTotal: WerResult? get() = utterances.mapNotNull { it.wer }.takeIf { it.isNotEmpty() }?.fold(WerResult.ZERO) { a, b -> a + b }

    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_ERROR = "error"
        const val STATUS_SKIPPED = "skipped"
    }
}

/** Where and with what the run was made (filled by the app; the commit hash is added by `tools/eval_report.py`). */
@Serializable
data class EvalMeta(
    val device: String = "unknown",
    @SerialName("android_version") val androidVersion: String = "unknown",
    @SerialName("app_version") val appVersion: String = "unknown",
    /** `real` (Qwen3-4B via Genie) or `mock` (scripted extraction: the numbers are not meaningful). */
    @SerialName("ai_mode") val aiMode: String = "mock",
    @SerialName("llm_model") val llmModel: String = "mock",
    /** Name of the speech-to-text model, or `none` when there is no real ASR. */
    @SerialName("asr_model") val asrModel: String = "none",
    @SerialName("rephrase_enabled") val rephraseEnabled: Boolean = false,
)

/** The whole run: what `results.json` contains. */
@Serializable
data class EvalRun(
    val schema: String = SCHEMA,
    @SerialName("run_id") val runId: String,
    @SerialName("started_at") val startedAt: String,
    /** `transcript` or `audio`. */
    val input: String,
    val meta: EvalMeta,
    val summary: EvalSummary,
    val cases: List<CaseResult>,
) {
    companion object {
        const val SCHEMA = "eval_results/1"
    }
}

// ---- summaries -----------------------------------------------------------------------------------

@Serializable
data class AsrSummary(
    /** False when no real recognition ran (typed transcripts or mock STT): WER is then "n/a (no ASR)". */
    val available: Boolean,
    val note: String,
    val cases: Int = 0,
    val utterances: Int = 0,
    /** Mean of the per-case WER. */
    @SerialName("mean_wer") val meanWer: Double? = null,
    /** Total errors over total reference words. */
    @SerialName("corpus_wer") val corpusWer: Double? = null,
)

@Serializable
data class FieldAccuracy(val total: Int, val matched: Int, val exact: Int, val normalized: Int, val relaxed: Int) {
    val accuracy: Double? get() = if (total == 0) null else matched.toDouble() / total
}

@Serializable
data class ExtractionSummary(
    val total: Int,
    val matched: Int,
    val exact: Int,
    val normalized: Int,
    val relaxed: Int,
    /** `matched / total`; null without expected fields. */
    val accuracy: Double?,
    @SerialName("per_field") val perField: Map<String, FieldAccuracy>,
    /** Fields that had to stay empty, and how many did. */
    @SerialName("empty_checks") val emptyChecks: Int,
    @SerialName("empty_correct") val emptyCorrect: Int,
)

@Serializable
data class MappingSummary(
    val correct: Int,
    val misplaced: Int,
    val wrong: Int,
    val missing: Int,
    /** `correct / (correct + misplaced)`; null when nothing was extracted. */
    @SerialName("placement_rate") val placementRate: Double?,
)

@Serializable
data class DialogueSummary(
    val reports: Int,
    @SerialName("questions_total") val questionsTotal: Int,
    @SerialName("questions_per_report") val questionsPerReport: Double?,
    @SerialName("questions_max") val questionsMax: Int,
    /** field -> outcome -> count (outcomes of the ask turns). */
    @SerialName("outcome_mix") val outcomeMix: Map<String, Map<String, Int>>,
    @SerialName("mode_total") val modeTotal: Int,
    @SerialName("mode_correct") val modeCorrect: Int,
    @SerialName("asked_total") val askedTotal: Int,
    @SerialName("asked_ok") val askedOk: Int,
    @SerialName("outcome_total") val outcomeTotal: Int,
    @SerialName("outcome_correct") val outcomeCorrect: Int,
    @SerialName("flow_total") val flowTotal: Int,
    @SerialName("flow_ok") val flowOk: Int,
    /** Must be 0. */
    @SerialName("ask_once_violations") val askOnceViolations: Int,
    /** Must be 0 (spec scenario 14: one question mark, at most 20 words, no "notified", nothing spoken twice...). */
    @SerialName("invariant_violations") val invariantViolations: Int,
) {
    val modeAccuracy: Double? get() = if (modeTotal == 0) null else modeCorrect.toDouble() / modeTotal
}

@Serializable
data class RephraserSummary(
    val attempts: Int,
    val passed: Int,
    @SerialName("pass_rate") val passRate: Double?,
    /** Rejection reason (the text before `:` of `fail:<reason>`, or `timeout`) -> count. */
    @SerialName("rejection_reasons") val rejectionReasons: Map<String, Int>,
    val latency: Stats?,
)

@Serializable
data class EvalSummary(
    @SerialName("cases_total") val casesTotal: Int,
    @SerialName("cases_ok") val casesOk: Int,
    @SerialName("cases_error") val casesError: Int,
    @SerialName("cases_skipped") val casesSkipped: Int,
    val asr: AsrSummary,
    val extraction: ExtractionSummary,
    val mapping: MappingSummary,
    val dialogue: DialogueSummary,
    val rephraser: RephraserSummary,
    val latency: LatencySummary,
)
