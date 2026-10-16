package com.example.test_app.dialogue

import com.example.test_app.domain.config.IncidentConfig
import com.example.test_app.domain.model.ClarificationQuestion
import com.example.test_app.domain.model.ClarificationTurn
import com.example.test_app.domain.model.DialogueInfo
import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.FieldSource
import com.example.test_app.domain.model.FieldStatus
import com.example.test_app.domain.model.Gps
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.InputMode
import com.example.test_app.domain.model.LocationSource
import com.example.test_app.domain.model.MediaItem
import com.example.test_app.domain.model.TimeSource
import com.example.test_app.domain.model.asTextOrNull
import com.example.test_app.domain.model.isEmptyValue
import com.example.test_app.domain.model.textValue
import com.example.test_app.domain.service.ExtractionException
import com.example.test_app.domain.service.ExtractionRequest
import com.example.test_app.domain.service.IncidentExtractionService
import com.example.test_app.domain.service.IncidentJsonCodec
import com.example.test_app.domain.service.IncidentPolicies
import com.example.test_app.domain.service.PlannerHint
import com.example.test_app.domain.service.ImageAnalysisService
import com.example.test_app.domain.service.ImageHint
import com.example.test_app.domain.service.ToolExecutor
import com.example.test_app.domain.service.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/** States of the dialogue (Spec v2 section 5). `WAITING_FOR_TAP` = greeting got no answer: hint shown, mic tap needed. */
enum class DialogueState {
    IDLE, GREETING, LISTEN_NARRATION, WAITING_FOR_TAP, WAITING_FOR_ANSWER_TAP, EXTRACT, CLASSIFY_MODE, SAFETY_CHECK, EMERGENCY_FLOW,
    OPENING, ASK_LOOP, DEFER, READBACK, REVIEW,
}

/** What the UI should show right now. */
data class DialogueUiState(
    val state: DialogueState = DialogueState.IDLE,
    val mode: DialogueMode = DialogueMode.PLAIN,
    /** True once the mode is URGENT: Review shows the red emergency banner. */
    val emergency: Boolean = false,
    /** "Tap the mic when you're ready" (greeting got no answer within 10 s). */
    val showMicHint: Boolean = false,
    val askedFields: List<String> = emptyList(),
    /** The text being spoken or just spoken, for on-screen captions. */
    val lastSpoken: String? = null,
    /** The latest thing the user said (transcript), for the processing screen. */
    val heard: String? = null,
)

/** What kind of line was spoken. */
enum class SpokenKind { GREETING, OPENING, QUESTION, SAFETY_CHECK, DEFER, READBACK, CLOSING }

/** One spoken line. [text] is exactly what went to `SpeechOut`; [body] is [text] without the typing offer; [field] is set for questions. */
data class SpokenLine(val kind: SpokenKind, val text: String, val mode: DialogueMode, val field: String? = null, val body: String = text)

/** Result of [DialogueManager.run]. */
sealed interface DialogueOutcome {
    /** The dialogue is over: show Review (the read-back statement was spoken; nothing is listened for after it). */
    data class Review(val report: IncidentReport) : DialogueOutcome

    /** The user never spoke, even after tapping the mic. */
    data object NoNarration : DialogueOutcome
}

/**
 * The follow-up dialogue state machine (Spec v2 sections 4-9): greeting once, extraction, mode classification,
 * safety check once, ask loop (each field at most once, no retries), read-back once, review. It is the ONLY
 * component that decides what is spoken; the extraction LLM's free text is never spoken and the [Rephraser] may only
 * reword the planner's template. Every collaborator is an interface, so it runs in plain JVM tests.
 *
 * Extraction goes through [IncidentExtractionService]: the first narration with no previous report, each answer with
 * `previousReport = <current>`, `answeredQuestion.targetFields = [<dialogue field key>]`. See `doc/DIALOGUE.md`
 * (how to hook this into `IncidentFlowViewModel`) and `doc/STATE_MACHINE.md`.
 */
class DialogueManager(
    private val assets: DialogueAssets,
    private val speechIn: SpeechIn,
    private val speechOut: SpeechOut,
    private val extraction: IncidentExtractionService,
    private val config: DialogueConfig = DialogueConfig(),
    private val rephraser: Rephraser? = null,
    private val toolExecutor: ToolExecutor? = null,
    private val logger: DialogueLogger? = null,
    /** Current GPS fix, or null. Today `DeviceInfo.gps` is always null, so "here" falls through to the spoken text. */
    private val gps: () -> Gps? = { null },
    private val incidentConfig: IncidentConfig = IncidentConfig(),
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now() },
    /**
     * Describes the attached photos before the first extraction (Gemma 4: photos are downscaled to 512 px and described). The hints ride in
     * every [ExtractionRequest]. Null = no analysis (Mock AI, Qwen). A failed analysis is skipped: the report never depends on it.
     */
    private val imageAnalysis: ImageAnalysisService? = null,
) {
    /** The language pack in use: the forced language of the settings until the first narration is heard, then the language of that narration. */
    private var pack: LanguagePack = assets.pack(config.language)
    private val bank: QuestionBank get() = pack.bank
    private val classifier = ModeClassifier(assets.modeKeywords)
    private val cues = AnswerCues(assets.fieldKeywords)
    private val validator = PhraseValidator(assets.blocked, assets.fieldKeywords)
    private val planner = FollowUpPlanner()
    private val form = DialogueForm(cues, classifier, assets.severityCues, assets.relevance)

    private val asked = LinkedHashSet<String>()
    private val outcomes = LinkedHashMap<String, Outcome>()
    private val transcripts = mutableListOf<String>()
    private val spokenLines = mutableListOf<SpokenLine>()
    private val micTap = CompletableDeferred<Unit>()
    private val answerTaps = Channel<Unit>(Channel.CONFLATED)

    private var started = false

    /**
     * Questions are asked at all: the user setting is on AND somebody spoke. A photo-only report (no narration, [InputMode.PHOTO_ONLY],
     * ADR-062) is built from the photos alone: no opening line, no question, no safety check, no defer line and no answer recording
     * (the microphone is never opened); the fields the photos cannot show stay empty and flagged for the Review screen.
     */
    private val followUps: Boolean get() = config.followUpsEnabled && inputMode != InputMode.PHOTO_ONLY
    private var mode = DialogueMode.PLAIN
    private var emergency = false
    private var safetyDone = false
    private var safetyAsked = false
    private var typingOffered = false
    private var closed = false
    private var thin = false
    private var locationFromGps = false
    private var firstTranscript = ""
    private var language = "en"
    private var inputMode = InputMode.VOICE
    private var media: List<MediaItem> = emptyList()
    private var imageHints: List<ImageHint> = emptyList()
    private lateinit var work: IncidentReport

    private val _ui = MutableStateFlow(DialogueUiState())

    /** Observable state for the UI. */
    val uiState: StateFlow<DialogueUiState> = _ui.asStateFlow()

    /** Every line spoken so far, in order. */
    val spoken: List<SpokenLine> get() = spokenLines.toList()

    /** Fields asked so far, in order; each appears at most once. */
    val askedFields: List<String> get() = asked.toList()

    val currentMode: DialogueMode get() = mode

    /** The latest tool result (hint included), or null before the first extraction or without a [ToolExecutor]. */
    var lastToolResult: ToolResult? = null
        private set

    private class Outcome(val status: String, val raw: String? = null)

    /** The mic button was tapped while [DialogueState.WAITING_FOR_TAP]. Safe to call at any time. */
    fun onMicTap() {
        micTap.complete(Unit)
        answerTaps.trySend(Unit)
    }

    /**
     * Runs the whole dialogue once. With [narration] == null the greeting is spoken once (only when [DialogueConfig.speakGreeting] is on and [greet] is not false, used
     * when the caller retries after a speech-recognition failure) and the first utterance is listened for; with a
     * transcript the greeting is skipped (the user already spoke). Throws [ExtractionException] when the FIRST extraction
     * fails and whatever [SpeechIn.listen] throws while waiting for the first narration (the caller shows its error
     * state); later extraction or listen failures only mark the field.
     */
    suspend fun run(
        narration: AsrResult? = null,
        inputMode: InputMode = InputMode.VOICE,
        media: List<MediaItem> = emptyList(),
        greet: Boolean = true,
    ): DialogueOutcome {
        check(!started) { "DialogueManager.run can only be called once per report" }
        started = true
        this.inputMode = inputMode
        this.media = media

        var first = narration
        if (first == null) {
            if (greet && config.speakGreeting) {
                setState(DialogueState.GREETING)
                speak(SpokenKind.GREETING, bank.greeting)
                log(DialogueState.GREETING, spoken = bank.greeting)
            }
            setState(DialogueState.LISTEN_NARRATION)
            first = listenNarration()
            if (first == null) {
                _ui.value = _ui.value.copy(state = DialogueState.WAITING_FOR_TAP, showMicHint = true)
                log(DialogueState.WAITING_FOR_TAP, outcome = FieldStatus.NO_RESPONSE)
                micTap.await()
                _ui.value = _ui.value.copy(showMicHint = false, state = DialogueState.LISTEN_NARRATION)
                first = listenNarration()
                if (first == null) return DialogueOutcome.NoNarration
            }
        }
        firstTranscript = first.text
        language = LanguageDetect.resolve(first.language, first.text)
        pack = assets.pack(language)
        transcripts += first.text

        setState(DialogueState.EXTRACT)
        imageHints = analyzePhotos()
        val firstReport = extract(
            ExtractionRequest(inputMode, first.text, null, null, null, imageHints, media, incidentConfig),
        )
        work = form.reconcileInjury(firstReport)
        logger?.bind(work.reportId)
        thin = form.isThin(work, firstTranscript)
        hintToTool()

        setState(DialogueState.CLASSIFY_MODE)
        reclassify()
        ensureSafetyCheck()

        log(DialogueState.CLASSIFY_MODE, relevance = planner.explain(plannerState(), mode, asked))
        if (followUps) {
            openingIfNeeded()
            askLoop()
        } else {
            log(DialogueState.CLASSIFY_MODE, outcome = "follow_ups_off")
        }
        flagRemaining()
        deferIfUrgent()

        readback()
        setState(DialogueState.REVIEW)
        return DialogueOutcome.Review(buildReport())
    }

    /** Speaks the closing line once (call after the user submitted). */
    suspend fun close() {
        if (closed) return
        closed = true
        val key = if (mode == DialogueMode.PLAIN) "closing_plain" else "closing_care"
        speak(SpokenKind.CLOSING, bank.line(key))
        log(DialogueState.REVIEW, spoken = bank.line(key))
    }

    /** Snapshot of the report with statuses, `dialogue` info, sources and defaults applied. Valid after [run] extracted. */
    fun currentReport(): IncidentReport = buildReport()

    /** [currentReport], or null while the first extraction has not finished (nothing to show yet). */
    fun currentReportOrNull(): IncidentReport? = if (::work.isInitialized) buildReport() else null

    // ---- ASK_LOOP ------------------------------------------------------------------------------

    private suspend fun openingIfNeeded() {
        if (mode == DialogueMode.URGENT) return
        val n = minOf(planner.remaining(plannerState(), mode, asked).size, mode.maxQuestions - asked.size, config.maxQuestionsTotal - questionsUsed())
        if (n < 1) return
        setState(DialogueState.OPENING)
        val line = when {
            mode == DialogueMode.CARE -> bank.line("opening_care")
            n == 1 -> bank.line("opening_one")
            else -> bank.line("opening_many")
        }
        speak(SpokenKind.OPENING, line)
        log(DialogueState.OPENING, spoken = line)
    }

    private suspend fun askLoop() {
        setState(DialogueState.ASK_LOOP)
        while (!capReached()) {
            val field = planner.next(plannerState(), mode, asked) ?: break
            askField(field)
            reclassify()
            ensureSafetyCheck()
            setState(DialogueState.ASK_LOOP)
        }
    }

    private suspend fun askField(field: String) {
        val template = bank.question(field, mode)
        val wording = chooseWording(field, template)
        var text = wording.text
        if (mode == DialogueMode.CARE && !typingOffered && plannerState().personalIncident) {
            text = text + " " + bank.line("offer_typing")
            typingOffered = true
        }
        val why = planner.explain(plannerState(), mode, asked)[field]?.let { mapOf(field to it) }
        assert(field !in asked) { "ask-once violated: '$field' asked twice" }
        assert(!capReached()) { "question cap violated: ${questionsUsed()} of ${config.maxQuestionsTotal} used before asking '$field'" }
        asked += field
        publish()
        speak(SpokenKind.QUESTION, text, field = field, body = wording.text)

        val t0 = System.currentTimeMillis()
        (speechIn as? FieldAwareSpeechIn)?.expectAnswerFor(field)
        val answer = listenAnswer()
        val asrMs = System.currentTimeMillis() - t0
        val outcome = handleAnswer(field, wording.text, answer)
        outcomes[DialogueFields.reportKey(field)] = outcome
        val m = (rephraser as? LlmMetricsProvider)?.lastCallMetrics()
        log(
            DialogueState.ASK_LOOP, field = field, template = template, rephrased = wording.rephrased,
            validator = wording.validatorLog, spoken = text, asr = answer, outcome = outcome.status,
            rephraseMs = wording.rephraseMs, asrMs = asrMs,
            metrics = if (wording.rephrased != null || wording.validatorLog != null) m else null,
            relevance = why,
        )
    }

    private class Wording(val text: String, val rephrased: String?, val validatorLog: String?, val rephraseMs: Long?)

    /** Template or validated rephrase (Spec v2 sections 8.3, 8.4). Never throws, never takes more than the timeout. */
    private suspend fun chooseWording(field: String, template: String): Wording {
        val r = rephraser
        if (!config.useLlmRephrase || r == null || !pack.isEnglish) return Wording(template, null, null, null) // the rephraser and its validator are English-only
        val req = RephraseRequest(field, mode, template, transcripts.joinToString(" "), config.language)
        val t0 = System.currentTimeMillis()
        var timedOut = false
        val result: String? = try {
            withTimeout(config.rephraseTimeoutMs) { r.rephrase(req) }
        } catch (e: TimeoutCancellationException) {
            timedOut = true
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val ms = System.currentTimeMillis() - t0
        if (timedOut) return Wording(template, result, "timeout", ms)
        val verdict = if (result == null) ValidationResult.Fail("null") else validator.validate(field, result)
        return if (verdict is ValidationResult.Pass) Wording(result!!.trim(), result.trim(), verdict.logValue, ms)
        else Wording(template, result, verdict.logValue, ms)
    }

    // ---- answers -------------------------------------------------------------------------------

    private suspend fun handleAnswer(field: String, question: String, answer: AsrResult?): Outcome {
        if (answer == null) return Outcome(FieldStatus.NO_RESPONSE)
        transcripts += answer.text
        if (answer.confidence < config.minAsrConfidence) return Outcome(FieldStatus.UNVERIFIED, answer.text)
        if (cues.isUnsure(answer.text)) return Outcome(FieldStatus.UNKNOWN_REPORTER_UNSURE)
        if (field == DialogueFields.LOCATION && cues.isHere(answer.text)) {
            val fix = gps()
            if (fix != null) {
                val text = String.format(Locale.US, "GPS %.5f, %.5f", fix.latitude, fix.longitude)
                work = work.copy(
                    fields = work.fields + (IncidentField.INCIDENT_LOCATION.key to
                        FieldEntry(textValue(text), GPS_ANSWER_CONFIDENCE, FieldSource.USER_VOICE)),
                )
                locationFromGps = true
                return Outcome(FieldStatus.FILLED)
            }
        }
        val before = work
        val after = try {
            extract(
                ExtractionRequest(
                    inputMode, firstTranscript, work, answer.text,
                    ClarificationQuestion(listOf(field), question), imageHints, media, incidentConfig,
                ),
            )
        } catch (e: ExtractionException) {
            return Outcome(FieldStatus.UNVERIFIED, answer.text)
        }
        work = form.reconcileInjury(mergeAnswer(before, after, field, question, answer.text))
        hintToTool()
        val key = DialogueFields.reportKey(field)
        val ok = if (field == DialogueFields.DESCRIPTION) {
            val b = before.fields[key]?.value
            val a = work.fields[key]?.value
            a != null && !a.isEmptyValue() && a != b
        } else {
            field in form.filled(work)
        }
        return if (ok) Outcome(FieldStatus.FILLED) else Outcome(FieldStatus.UNVERIFIED, answer.text)
    }

    private suspend fun extract(request: ExtractionRequest): IncidentReport =
        IncidentJsonCodec.decode(extraction.extract(request))

    /** Hints for at most [MAX_ANALYSED_PHOTOS] attached photos, one by one; a photo that cannot be analysed is skipped. */
    private suspend fun analyzePhotos(): List<ImageHint> {
        val service = imageAnalysis ?: return emptyList()
        return media.filter { it.type == "image" }.take(MAX_ANALYSED_PHOTOS).mapNotNull { item ->
            try {
                service.analyze(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Non-empty values of [update] replace those in [prev]; changed values become `user_voice`; the answer is added to the history. */
    private fun mergeAnswer(prev: IncidentReport, update: IncidentReport, field: String?, question: String, answerText: String): IncidentReport {
        val keys = (IncidentField.entries.map { it.key } + prev.fields.keys + update.fields.keys).distinct()
        val changed = mutableListOf<String>()
        val fields = keys.associateWith { k ->
            val p = prev.fields[k]
            val n = update.fields[k]
            when {
                n != null && !n.value.isEmptyValue() && (p == null || p.value != n.value) -> {
                    changed += k
                    n.copy(source = FieldSource.USER_VOICE)
                }
                else -> p ?: n ?: FieldEntry()
            }
        }
        val turn = ClarificationTurn(
            question = question, answer = answerText,
            askedFields = listOfNotNull(field?.let { DialogueFields.reportKey(it) }), updatedFields = changed,
        )
        return prev.copy(
            fields = fields,
            clarification = prev.clarification.copy(history = prev.clarification.history + turn),
        )
    }

    // ---- mode, safety, defer -------------------------------------------------------------------

    /** Escalate-only: the mode never goes down. Reaching URGENT raises the emergency flag at once (banner), asked or not. */
    private fun reclassify() {
        val m = classifier.classify(transcripts, form.injury(work), form.severity(work, urgent = mode == DialogueMode.URGENT, texts = transcripts))
        if (m > mode) {
            mode = m
        }
        if (mode == DialogueMode.URGENT) emergency = true
        publish()
    }

    /** Questions used so far: planner fields plus the safety check. Read-back and closing are not questions. */
    private fun questionsUsed(): Int = asked.size + if (safetyAsked) 1 else 0

    /** True once nothing more may be asked: per-mode cap or the STRICT per-report total (`DialogueConfig.maxQuestionsTotal`). */
    private fun capReached(): Boolean =
        planner.capReached(mode, asked, if (safetyAsked) 1 else 0, config.maxQuestionsTotal)

    /**
     * URGENT: asks the safety check once. It counts as a question: when the total is already used up it is NOT asked
     * (hard stop), but the emergency flag and banner are set regardless.
     */
    private suspend fun ensureSafetyCheck() {
        if (mode != DialogueMode.URGENT || safetyDone) return
        safetyDone = true
        emergency = true
        if (!followUps) { // the user switched questions off (or nobody spoke: photo-only): the emergency flag, banner and severity floors remain
            log(DialogueState.SAFETY_CHECK, outcome = "skipped_follow_ups_off")
            publish()
            return
        }
        if (questionsUsed() >= config.maxQuestionsTotal) {
            log(DialogueState.SAFETY_CHECK, outcome = "skipped_question_cap")
            publish()
            return
        }
        assert(questionsUsed() < config.maxQuestionsTotal) { "question cap violated by the safety check" }
        safetyAsked = true
        setState(DialogueState.SAFETY_CHECK)
        val line = bank.line("safety_check")
        speak(SpokenKind.SAFETY_CHECK, line)
        (speechIn as? FieldAwareSpeechIn)?.expectAnswerFor(ANSWER_HINT_SAFETY)
        val answer = listenAnswer()
        if (answer != null) transcripts += answer.text
        log(DialogueState.SAFETY_CHECK, spoken = line, asr = answer, outcome = if (answer == null) FieldStatus.NO_RESPONSE else "answered")
        // No answer = assume not safe. Nothing is sent in this phase: the emergency flag only drives the on-screen banner.
        setState(DialogueState.EMERGENCY_FLOW)
    }

    /** The audit reasons at the end of the dialogue: a field that was still wanted but cut by the question cap says so. */
    private fun finalRelevance(): Map<String, String> {
        val cut = planner.remaining(plannerState(), mode, asked).toSet()
        return planner.explain(plannerState(), mode, asked).mapValues { (f, r) ->
            if (f in cut) "skip: question cap reached (${questionsUsed()} of ${config.maxQuestionsTotal} used), still wanted: ${r.removePrefix("ask: ")}" else r
        }
    }

    /** Fields the planner would still ask but could not (cap reached) are flagged for the review screen. */
    private fun flagRemaining() {
        for (f in planner.remaining(plannerState(), mode, asked)) {
            outcomes[DialogueFields.reportKey(f)] = Outcome(FieldStatus.MISSING_FLAGGED)
        }
    }

    private suspend fun deferIfUrgent() {
        if (mode != DialogueMode.URGENT || inputMode == InputMode.PHOTO_ONLY) return // photo-only: nobody to talk to, no "finish later" line
        val unresolved = planner.remaining(plannerState(), mode, asked).isNotEmpty() ||
            asked.any { outcomes[DialogueFields.reportKey(it)]?.status != FieldStatus.FILLED }
        if (!unresolved) return
        setState(DialogueState.DEFER)
        val line = bank.line("defer")
        speak(SpokenKind.DEFER, line)
        log(DialogueState.DEFER, spoken = line)
    }

    // ---- read-back -----------------------------------------------------------------------------

    /**
     * Speaks the read-back once. It is a STATEMENT (ADR-048): it asks nothing and the dialogue does not listen after it; the user
     * corrects on the Review screen. It is not a question, so it does not count against `maxQuestionsTotal`.
     */
    private suspend fun readback() {
        setState(DialogueState.READBACK)
        val line = readbackLine(buildReport())
        speak(SpokenKind.READBACK, line)
        log(DialogueState.READBACK, spoken = line, relevance = finalRelevance())
    }

    /**
     * The fixed read-back (Spec v2 section 5.1) in the language pack of the session. `{next_step}` is always empty in this phase: nothing is
     * sent. The incident type goes through the glossary; the location is the English report value (spoken as an English run).
     */
    private fun readbackLine(report: IncidentReport): String {
        val t = pack.readout
        fun text(f: IncidentField) = report.fields[f.key]?.value?.asTextOrNull()?.trim().orEmpty()
        // The English TTS voice cannot read Devanagari / Telugu: never put such text into an English spoken line (other packs have a voice for it).
        val rawType = if (pack.isEnglish) LanguageDetect.speakable(text(IncidentField.INCIDENT_TYPE), "") else text(IncidentField.INCIDENT_TYPE)
        val type = pack.glossary.incidentType(rawType) ?: rawType
        val location = if (pack.isEnglish) LanguageDetect.speakable(text(IncidentField.INCIDENT_LOCATION), t["fallback_place"]) else text(IncidentField.INCIDENT_LOCATION)
        val template = bank.line(if (mode == DialogueMode.PLAIN) "readback_plain" else "readback_care")
        val whenText = if (report.timeSource == TimeSource.DEFAULT_NOW) t["time_set_now"] else FormReadout.clockPhrase(text(IncidentField.DATE_TIME), t, pack.glossary)
        val summary = listOf(type, location).filter { it.isNotEmpty() }.joinToString(t["summary_join"]).ifEmpty { t["fallback_report"] }
        val line = template
            .replace("{incident_type}", type.ifEmpty { t["fallback_incident"] })
            .replace("{location}", location)
            .replace("{time_phrase}", whenText)
            .replace("{summary}", summary)
            .replace("{next_step}", "")
            .replace(Regex("\\s*,(\\s*,)+"), ",")
        return line.replace(Regex("\\s+"), " ").trim()
    }

    // ---- report assembly -----------------------------------------------------------------------

    private fun plannerState(): PlannerState = form.plannerState(work, transcripts, thin, urgent = mode == DialogueMode.URGENT)

    private fun hint(): PlannerHint {
        val s = plannerState()
        val next = if (capReached()) null else planner.next(s, mode, asked)
        return PlannerHint(
            missing = planner.remaining(s, mode, asked), nextField = next, mode = mode.name,
            template = next?.let { bank.question(it, mode) },
        )
    }

    private suspend fun hintToTool() {
        val tool = toolExecutor ?: return
        val result = tool.fillIncidentFormWithHint(work, hint())
        lastToolResult = result
        work = work.copy(toolCall = result.report.toolCall)
    }

    /** The report as the review screen should see it. */
    private fun buildReport(): IncidentReport {
        var r = form.reconcileInjury(work)
        val timeKey = IncidentField.DATE_TIME.key
        val spokenTime = r.fields[timeKey]?.value?.isEmptyValue() == false
        if (!spokenTime) {
            r = r.copy(
                fields = r.fields + (timeKey to FieldEntry(
                    textValue(clock().truncatedTo(ChronoUnit.SECONDS).toString()), IncidentPolicies.DEFAULTED_CONFIDENCE, FieldSource.AI,
                )),
            )
        }
        r = r.copy(dialogue = DialogueInfo(mode.name, asked.toList(), emergency || mode == DialogueMode.URGENT))
        r = IncidentPolicies.apply(r, clock(), assets.severityCues, transcripts)
        val hasLocation = r.fields[IncidentField.INCIDENT_LOCATION.key]?.value?.isEmptyValue() == false
        val fields = IncidentField.entries.associate { f ->
            val entry = r.fields[f.key] ?: FieldEntry()
            val o = outcomes[f.key]
            val status = when {
                // the model's value was in non-Latin script and was not stored (ModelOutputMapper): stays flagged until the user fixes it
                entry.status == FieldStatus.UNVERIFIED && entry.rawAnswer != null && entry.value.isEmptyValue() -> FieldStatus.UNVERIFIED
                o == null -> entry.effectiveStatus
                (o.status == FieldStatus.NO_RESPONSE || o.status == FieldStatus.UNVERIFIED) &&
                    !entry.value.isEmptyValue() && !(f == IncidentField.DATE_TIME && !spokenTime) -> FieldStatus.FILLED
                else -> o.status
            }
            f.key to entry.copy(status = status, rawAnswer = if (status == FieldStatus.UNVERIFIED) (o?.raw ?: entry.rawAnswer) else null)
        }
        return r.copy(
            fields = fields,
            locationSource = if (!hasLocation) null else if (locationFromGps) LocationSource.GPS else LocationSource.SPOKEN,
            timeSource = if (spokenTime) TimeSource.SPOKEN else TimeSource.DEFAULT_NOW,
            dialogue = r.dialogue,
            input = r.input.copy(language = if (firstTranscript.isEmpty()) LanguageDetect.detect(r.input.transcript) else language),
        )
    }

    // ---- plumbing ------------------------------------------------------------------------------

    private suspend fun speak(kind: SpokenKind, text: String, field: String? = null, body: String = text) {
        spokenLines += SpokenLine(kind, text, mode, field, body)
        _ui.value = _ui.value.copy(lastSpoken = text)
        try {
            speechOut.speak(text, pack.code) // the language of the TEXT (the pack's), which picks the voice
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // TTS failure is non-blocking (ADR-012): the text stays on screen via uiState.lastSpoken.
        }
    }

    /** The first narration: blank text counts as silence; listen errors propagate (the caller shows a retry screen). */
    private suspend fun listenNarration(): AsrResult? {
        (speechIn as? FieldAwareSpeechIn)?.expectAnswerFor(null)
        return speechIn.listen(config.greetingSilenceSec, config.maxListenSec)?.takeIf { it.text.isNotBlank() }?.also(::heard)
    }

    private fun heard(r: AsrResult) {
        _ui.value = _ui.value.copy(heard = r.text)
    }

    /**
     * The reply to a question. Hands-free (default): listen at once. Otherwise wait for a mic tap (state `WAITING_FOR_ANSWER_TAP`, up to
     * the greeting-silence time) and then listen; no tap counts as "no response". Never retried.
     */
    private suspend fun listenAnswer(): AsrResult? {
        if (!config.handsFreeAnswers) {
            answerTaps.tryReceive() // forget a tap made while the question was spoken (it skipped the speech)
            setState(DialogueState.WAITING_FOR_ANSWER_TAP)
            val tapped = withTimeoutOrNull(config.greetingSilenceSec * 1000L) { answerTaps.receive() } != null
            setState(DialogueState.ASK_LOOP)
            if (!tapped) return null
        }
        return listenSafe(config.answerSilenceSec)
    }

    /** Listens for an answer; a recording or recognition error counts as "no response" (ask-once: never retried). */
    private suspend fun listenSafe(silenceSec: Int): AsrResult? = try {
        speechIn.listen(silenceSec, config.maxListenSec)?.takeIf { it.text.isNotBlank() }?.also(::heard)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun setState(s: DialogueState) {
        _ui.value = _ui.value.copy(state = s, mode = mode, emergency = emergency, askedFields = asked.toList())
    }

    private fun publish() {
        _ui.value = _ui.value.copy(mode = mode, emergency = emergency, askedFields = asked.toList())
    }

    private fun log(
        state: DialogueState,
        field: String? = null,
        template: String? = null,
        rephrased: String? = null,
        validator: String? = null,
        spoken: String? = null,
        asr: AsrResult? = null,
        outcome: String? = null,
        rephraseMs: Long? = null,
        asrMs: Long? = null,
        metrics: LlmCallMetrics? = null,
        relevance: Map<String, String>? = null,
    ) {
        logger?.log(
            TurnLog(
                epochSec = clock().toEpochSecond(), state = state.name, mode = mode, field = field, template = template,
                rephrased = rephrased, validator = validator, spoken = spoken, asrText = asr?.text, asrConf = asr?.confidence, lang = if (firstTranscript.isEmpty()) null else language,
                outcome = outcome, rephraseMs = rephraseMs, asrMs = asrMs, promptTokens = metrics?.promptTokens,
                prefillMs = metrics?.prefillMs, decodeMs = metrics?.decodeMs, relevance = relevance,
            ),
        )
    }

    private companion object {
        const val GPS_ANSWER_CONFIDENCE = 0.9

        /** Photos described per report (each analysis is one LLM call; Gemma: a few seconds). */
        const val MAX_ANALYSED_PHOTOS = 3
    }
}
