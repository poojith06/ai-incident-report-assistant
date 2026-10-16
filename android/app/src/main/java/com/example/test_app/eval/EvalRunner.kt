package com.example.test_app.eval

import com.example.test_app.dialogue.AsrResult
import com.example.test_app.dialogue.DialogueConfig
import com.example.test_app.dialogue.DialogueAssets
import com.example.test_app.dialogue.DialogueLogger
import com.example.test_app.dialogue.DialogueManager
import com.example.test_app.dialogue.DialogueOutcome
import com.example.test_app.dialogue.FieldAwareSpeechIn
import com.example.test_app.dialogue.LlmCallMetrics
import com.example.test_app.dialogue.LlmMetricsProvider
import com.example.test_app.dialogue.Rephraser
import com.example.test_app.dialogue.SpeechOut
import com.example.test_app.domain.config.IncidentConfig
import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.FieldKind
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.InputMode
import com.example.test_app.domain.model.MediaItem
import com.example.test_app.domain.model.Severity
import com.example.test_app.domain.model.asBooleanOrNull
import com.example.test_app.domain.model.displayText
import com.example.test_app.domain.service.ExtractionRequest
import com.example.test_app.domain.service.ImageAnalysisService
import com.example.test_app.domain.service.IncidentExtractionService
import com.example.test_app.domain.service.SpeechToTextService
import com.example.test_app.domain.service.ToolExecutor
import com.example.test_app.platform.FileSpeechIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Where the utterances of a run come from. */
enum class EvalInput(val wire: String) {
    /** The case's transcripts are typed in: no speech recognition (WER is "n/a (no ASR)"). */
    TRANSCRIPT("transcript"),

    /** The case's WAV files go through the real `SpeechToTextService` via `FileSpeechIn`. */
    AUDIO("audio"),
}

/** Progress of a run: [done] of [total] cases finished; [currentId] is the case being run (null at the end). */
data class EvalProgress(val done: Int, val total: Int, val currentId: String?)

/** Report as flat text per report field key (null = empty), the form the metrics compare. */
object ReportText {
    fun fieldTexts(report: IncidentReport): Map<String, String?> =
        IncidentField.entries.associate { it.key to text(it, report.fields[it.key]) }

    private fun text(field: IncidentField, entry: FieldEntry?): String? {
        if (entry == null) return null
        val s = when (field.kind) {
            FieldKind.YES_NO -> entry.value.asBooleanOrNull()?.toString()
            else -> entry.displayText(field)
        }
        return s?.takeIf { it.isNotBlank() }
    }
}

/**
 * Runs evaluation cases through the REAL [DialogueManager] pipeline with the injected services (mock or real, as the
 * app's DI container decides): speech in (typed transcripts, or WAV files through [stt]), extraction, mode, planner,
 * optional rephraser, read-back. Speech out is silent (the spoken lines are recorded). Each case gets a fresh manager;
 * a case that throws becomes `status = "error"`, a case without its audio in an audio run becomes `skipped`; neither
 * stops the run. Scoring is done by the pure functions of this package ([FieldMatch], [Mapping], [DialogueInvariants],
 * [EvalMetrics]). See `eval/README.md` and `doc/TESTING.md` (Level 4).
 *
 * Timing: `asr_ms` = wall time of `SpeechIn.listen` (WAV read + recognition; audio input only); `llm_calls` = every
 * extraction/update call with the engine's prefill/decode times when the service is an `LlmMetricsProvider`;
 * `first_form_ms` = narration ASR + first extraction (end of speech to filled form); `total_ms` = the whole dialogue
 * (no real waiting: speech in/out are instantaneous here).
 *
 * @param stt the speech-to-text service for audio runs; null = no ASR (audio runs are refused: every case is skipped).
 */
class EvalRunner(
    private val assets: DialogueAssets,
    private val extraction: IncidentExtractionService,
    private val rephraser: Rephraser?,
    private val stt: SpeechToTextService?,
    private val dialogueConfig: DialogueConfig = DialogueConfig(),
    private val incidentConfig: IncidentConfig = IncidentConfig(),
    private val toolExecutor: ToolExecutor? = null,
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now() },
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val caseTimeoutMs: Long = 180_000,
    /** Photo analysis for photo-only cases (`images` in the case): Gemma vision or the mock; null = those cases are skipped. */
    private val imageAnalysis: ImageAnalysisService? = null,
    /** Directory the case's `images` paths are relative to (the `eval` directory); null = photo-only cases are skipped. */
    private val imageRoot: File? = null,
) {
    suspend fun run(
        cases: List<EvalCase>,
        input: EvalInput,
        audioRoot: File?,
        meta: EvalMeta,
        onProgress: (EvalProgress) -> Unit = {},
    ): EvalRun {
        val started = clock()
        val results = mutableListOf<CaseResult>()
        cases.forEachIndexed { i, c ->
            onProgress(EvalProgress(i, cases.size, c.id))
            results += runCase(c, input, audioRoot)
        }
        onProgress(EvalProgress(cases.size, cases.size, null))
        val asrAvailable = input == EvalInput.AUDIO && stt != null
        return EvalRun(
            runId = started.format(RUN_ID_FORMAT), startedAt = started.toString(), input = input.wire, meta = meta,
            summary = EvalMetrics.summarize(results, asrAvailable, meta.asrModel), cases = results,
        )
    }

    private fun skipReason(case: EvalCase, input: EvalInput, audioRoot: File?): String? {
        if (case.photoOnly) {
            if (imageAnalysis == null) return "no photo analysis on this build (Gemma vision or Mock AI)"
            if (imageRoot == null) return "image directory unavailable"
            val missing = case.images.filter { !File(imageRoot, it).isFile }
            return if (missing.isEmpty()) null else "images missing: ${missing.joinToString()}"
        }
        if (input != EvalInput.AUDIO) return null
        if (stt == null) return "no speech-to-text service on this build (ASR n/a)"
        if (audioRoot == null) return "audio directory unavailable"
        if (!case.silent && case.audio == null) return "case declares no audio"
        val undeclared = case.followUpAnswers.filter { it.transcript != null && it.audio == null }.map { it.field }
        if (undeclared.isNotEmpty()) return "answer audio not declared for $undeclared"
        val missing = case.requiredAudio().filter { !File(audioRoot, it).isFile }
        return if (missing.isEmpty()) null else "audio missing: ${missing.joinToString()}"
    }

    private fun audioPath(case: EvalCase, key: String?): String? =
        if (key == null) case.audio else case.answerFor(key)?.takeIf { it.transcript != null }?.audio

    private fun referenceFor(case: EvalCase, key: String?): String? =
        if (key == null) case.transcript else case.answerFor(key)?.transcript

    private suspend fun runCase(case: EvalCase, input: EvalInput, audioRoot: File?): CaseResult {
        skipReason(case, input, audioRoot)?.let { return base(case, input, CaseResult.STATUS_SKIPPED, it) }
        val logger = DialogueLogger()
        val timing = TimingExtraction(extraction, monotonicMs)
        val inner: FieldAwareSpeechIn = when {
            case.photoOnly -> TranscriptSpeechIn(case) // never asked: a photo-only session opens no microphone
            input == EvalInput.TRANSCRIPT -> TranscriptSpeechIn(case)
            else -> FileSpeechIn(stt!!) { key -> audioPath(case, key)?.let { File(audioRoot, it) } }
        }
        val speech = RecordingSpeechIn(inner, monotonicMs)
        val out = RecordingSpeechOut()
        val manager = DialogueManager(
            assets, speech, out, timing, dialogueConfig, rephraser, toolExecutor, logger, { null }, incidentConfig, clock, imageAnalysis,
        )
        val media = case.images.map { MediaItem(File(it).nameWithoutExtension, "image", File(imageRoot!!, it).toURI().toString(), clock().toString()) }
        if (case.silent) manager.onMicTap() // spec scenario 11: the hint is shown and the (simulated) user taps once, then stays silent
        val t0 = monotonicMs()
        val outcome: DialogueOutcome? = try {
            withTimeoutOrNull(caseTimeoutMs) {
                if (case.photoOnly) manager.run(AsrResult("", 1f, ""), InputMode.PHOTO_ONLY, media) else manager.run()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return base(case, input, CaseResult.STATUS_ERROR, "${e::class.simpleName}: ${e.message}")
                .copy(utterances = utterances(case, input, speech), totalMs = monotonicMs() - t0)
        }
        val totalMs = monotonicMs() - t0
        if (outcome == null) {
            return base(case, input, CaseResult.STATUS_ERROR, "timed out after ${caseTimeoutMs / 1000} s")
                .copy(utterances = utterances(case, input, speech), totalMs = totalMs)
        }

        val report = (outcome as? DialogueOutcome.Review)?.report
        val texts = report?.let(ReportText::fieldTexts).orEmpty()
        val placements = Mapping.classify(case.expected.fields, texts)
        val turns = turns(logger.lines)
        val asked = manager.askedFields
        val utter = utterances(case, input, speech)
        val firstExtract = timing.calls.firstOrNull { it.kind == "extract" }
        return base(case, input, CaseResult.STATUS_OK, null).copy(
            utterances = utter,
            result = if (report != null) EvalCases.RESULT_REVIEW else EvalCases.RESULT_NO_NARRATION,
            mode = manager.currentMode.name,
            emergency = report?.dialogue?.emergency ?: false,
            asked = asked,
            fields = case.expected.fields.map { (f, exp) ->
                FieldResult(f, exp, texts[f], FieldMatch.match(f, exp, texts[f]).name, placements.getValue(f).name)
            } + listOfNotNull(severityCheck(case.expected.severityMin, texts["severity_level"])),
            emptyChecks = case.expected.emptyFields.map { EmptyCheck(it, texts[it]) },
            outcomeChecks = case.expected.outcomes.map { (f, exp) -> OutcomeCheck(f, exp, turns.firstOrNull { it.field == f }?.outcome) },
            turns = turns,
            askOnceViolations = DialogueInvariants.askOnceViolations(asked),
            invariantViolations = DialogueInvariants.check(
                manager.spoken.map { SpokenObs(it.kind.name, it.text, it.body, it.field, it.mode.name) }, asked,
            ),
            llmCalls = timing.calls.toList(),
            firstFormMs = firstExtract?.let { (utter.firstOrNull { u -> u.key == null }?.asrMs ?: 0L) + it.ms },
            totalMs = totalMs,
            spoken = manager.spoken.map { it.text },
        )
    }

    /** The "at least" severity of a photo-only case as a result row: matched when the report's severity is the minimum or higher. */
    private fun severityCheck(min: String?, actual: String?): FieldResult? {
        if (min == null) return null
        val ok = actual != null && Severity.ALLOWED.indexOf(actual) >= Severity.ALLOWED.indexOf(min)
        return FieldResult("severity_level", ">= $min", actual, if (ok) MatchLevel.EXACT.name else MatchLevel.NONE.name,
            (if (ok) Placement.CORRECT else if (actual == null) Placement.MISSING else Placement.WRONG).name)
    }

    private fun base(case: EvalCase, input: EvalInput, status: String, detail: String?) = CaseResult(
        id = case.id, status = status, detail = detail, input = input.wire,
        expectedResult = case.expected.result, expectedMode = case.expected.mode,
        expectedEmergency = case.expected.emergency, expectedAsked = case.expected.askedFields,
        askedIncludes = case.expected.askedIncludes, askedExcludes = case.expected.askedExcludes,
    )

    private fun utterances(case: EvalCase, input: EvalInput, speech: RecordingSpeechIn): List<UtteranceResult> =
        speech.records.map { r ->
            val reference = referenceFor(case, r.key)
            val recognised = input == EvalInput.AUDIO && audioPath(case, r.key) != null // a recording was sent to the recogniser
            UtteranceResult(
                key = r.key, reference = reference, hypothesis = r.hypothesis?.text,
                asrMs = if (recognised) r.ms else null, confidence = r.hypothesis?.confidence,
                wer = if (recognised && !reference.isNullOrBlank()) Wer.compute(reference, r.hypothesis?.text.orEmpty()) else null,
            )
        }

    /** The ask turns of the dialogue log (spec section 10 lines with `state == ASK_LOOP` and a field). */
    private fun turns(lines: List<String>): List<TurnResult> = lines.mapNotNull { line ->
        val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@mapNotNull null
        if (o.str("state") != "ASK_LOOP") return@mapNotNull null
        val field = o.str("field") ?: return@mapNotNull null
        TurnResult(
            field = field, outcome = o.str("outcome"), template = o.str("template"), rephrased = o.str("rephrased"),
            validator = o.str("validator"), spoken = o.str("spoken"),
            rephraseMs = (o["latency_ms"] as? JsonObject)?.get("rephrase")?.jsonPrimitive?.longOrNull,
        )
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private companion object {
        val RUN_ID_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

// ---- helpers of the runner ---------------------------------------------------------------------------

/** Typed-transcript speech input: answers the narration and every question from the case (no recognition). */
internal class TranscriptSpeechIn(private val case: EvalCase) : FieldAwareSpeechIn {
    @Volatile private var key: String? = null

    override fun expectAnswerFor(field: String?) {
        key = field
    }

    override suspend fun listen(silenceTimeoutSec: Int, maxSeconds: Int): AsrResult? {
        val k = key
        if (k == null) return case.transcript?.takeIf { it.isNotBlank() }?.let { AsrResult(it, 1f, "en") }
        val a = case.answerFor(k) ?: return null
        return a.transcript?.takeIf { it.isNotBlank() }?.let { AsrResult(it, a.asrConf ?: 1f, "en") }
    }
}

/** Records every `listen` of the wrapped speech input: the key it answered, how long it took and what it returned. */
internal class RecordingSpeechIn(private val inner: FieldAwareSpeechIn, private val nowMs: () -> Long) : FieldAwareSpeechIn {
    class Record(val key: String?, val ms: Long, val hypothesis: AsrResult?)

    val records = mutableListOf<Record>()
    private var key: String? = null

    override fun expectAnswerFor(field: String?) {
        key = field
        inner.expectAnswerFor(field)
    }

    override suspend fun listen(silenceTimeoutSec: Int, maxSeconds: Int): AsrResult? {
        val k = key
        val t0 = nowMs()
        val r = inner.listen(silenceTimeoutSec, maxSeconds) // exceptions propagate: the dialogue decides what they mean
        records += Record(k, nowMs() - t0, r)
        return r
    }
}

/** Speech out that says nothing (the dialogue records the lines it "spoke"). */
internal class RecordingSpeechOut : SpeechOut {
    override suspend fun speak(text: String, language: String) = Unit
}

/**
 * Times every extraction call. When the wrapped service is an [LlmMetricsProvider], a call that produced NEW metrics
 * (a different instance than after the previous call) is an LLM call with prefill and decode times; update calls
 * answered by Kotlin rules alone leave the metrics untouched and get none.
 */
internal class TimingExtraction(private val inner: IncidentExtractionService, private val nowMs: () -> Long) : IncidentExtractionService {
    val calls = mutableListOf<LlmCallResult>()
    private var seen: LlmCallMetrics? = (inner as? LlmMetricsProvider)?.lastCallMetrics()

    override suspend fun extract(request: ExtractionRequest): String {
        val t0 = nowMs()
        try {
            return inner.extract(request)
        } finally {
            val ms = nowMs() - t0
            val m = (inner as? LlmMetricsProvider)?.lastCallMetrics()
            val fresh = m != null && m !== seen
            if (fresh) seen = m
            calls += LlmCallResult(
                kind = if (request.previousReport == null) "extract" else "update", ms = ms,
                prefillMs = if (fresh) m?.prefillMs else null, decodeMs = if (fresh) m?.decodeMs else null,
            )
        }
    }
}
