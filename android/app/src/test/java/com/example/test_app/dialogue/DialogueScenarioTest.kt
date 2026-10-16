package com.example.test_app.dialogue

import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.FieldStatus
import com.example.test_app.domain.model.Gps
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.LocationSource
import com.example.test_app.domain.model.ReportInput
import com.example.test_app.domain.model.TimeSource
import com.example.test_app.domain.model.asBooleanOrNull
import com.example.test_app.domain.model.asTextOrNull
import com.example.test_app.domain.model.boolValue
import com.example.test_app.domain.model.listValue
import com.example.test_app.domain.model.textValue
import com.example.test_app.domain.service.ExtractionException
import com.example.test_app.domain.service.ExtractionRequest
import com.example.test_app.domain.service.IncidentExtractionService
import com.example.test_app.domain.service.IncidentJsonCodec
import com.example.test_app.domain.service.PlannerHint
import com.example.test_app.domain.service.ToolExecutor
import com.example.test_app.mock.DevSettingsStore
import com.example.test_app.mock.MockIncidentExtraction
import com.example.test_app.mock.MockScenario
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.test_app.domain.model.asStringList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

// ---- fakes ------------------------------------------------------------------------------------

private class RecordingSpeechOut : SpeechOut {
    val texts = mutableListOf<String>()
    override suspend fun speak(text: String, language: String) {
        texts += text
    }
}

/** Plays a script; `null` entries and an exhausted script both mean silence. */
private class ScriptedSpeechIn(script: List<AsrResult?>) : SpeechIn {
    private val queue = ArrayDeque(script)
    val listens = mutableListOf<Int>()
    override suspend fun listen(silenceTimeoutSec: Int, maxSeconds: Int): AsrResult? {
        listens += silenceTimeoutSec
        return queue.removeFirstOrNull()
    }
}

/** Fake extraction service: returns scripted JSON, one per call, and records the requests. */
private class ScriptedExtraction(script: List<String>) : IncidentExtractionService {
    private val queue = ArrayDeque(script)
    val requests = mutableListOf<ExtractionRequest>()
    override suspend fun extract(request: ExtractionRequest): String {
        requests += request
        return queue.removeFirstOrNull() ?: throw ExtractionException("script exhausted")
    }
}

private fun say(text: String, conf: Float = 0.9f) = AsrResult(text, conf, "en")

private fun fe(v: Any?): FieldEntry = when (v) {
    null -> FieldEntry()
    is String -> FieldEntry(textValue(v), 0.9)
    is Boolean -> FieldEntry(boolValue(v), 0.9)
    is List<*> -> FieldEntry(listValue(v.map { it as String }), 0.9)
    else -> error("bad value $v")
}

/** One scripted extraction response (raw JSON text, like the real service returns). */
private fun json(transcript: String, vararg fields: Pair<String, Any?>): String = IncidentJsonCodec.encode(
    IncidentReport(
        reportId = "INC-2026-000001", status = IncidentReport.STATUS_NEEDS_CLARIFICATION,
        createdAt = "2026-10-11T09:30:00+05:30", input = ReportInput("voice", transcript),
        fields = fields.associate { it.first to fe(it.second) },
    ),
)

/** A rephraser configuration; [rephrasedOk] = the spoken question is expected to be the rephrased text. */
private class Case(val name: String, val rephraser: Rephraser?, val rephrasedOk: Boolean)

private val GOOD_TEXT = mapOf(
    "injured_person" to "Was it you who got hurt, or someone else?",
    "injury_type" to "What kind of injury is it? You can skip this.",
    "location" to "You mentioned it just now. Where in the building did this happen?",
    "description" to "What did you see around there?",
    "time" to "Roughly when was this?",
    "immediate_actions" to "Has anyone been able to help yet, like first aid?",
    "people_involved" to "Who was involved? You can skip this.",
)

private class FakeRephraser(private val reply: suspend (RephraseRequest) -> String?) : Rephraser {
    var calls = 0
    override suspend fun rephrase(req: RephraseRequest): String? {
        calls++
        return reply(req)
    }
}

private val CASES = listOf(
    Case("template-only", null, false),
    Case("good", FakeRephraser { GOOD_TEXT.getValue(it.field) }, true),
    Case("why", FakeRephraser { "Why did this happen? Tell me where it was." }, false),
    Case("two questions", FakeRephraser { "Where did it happen? Was anyone hurt?" }, false),
    Case("ask again", FakeRephraser { "Let me ask again, where did this happen?" }, false),
    Case("null", FakeRephraser { null }, false),
    Case("timeout", FakeRephraser { delay(10_000); GOOD_TEXT.getValue(it.field) }, false),
)

private val OFF = CASES[0]

/** The usual "needs two questions" narration (location missing, thin description) and what the model extracts from it. */
private const val NARR = "There's water all over the floor."

private val CLOCK = OffsetDateTime.parse("2026-10-11T09:30:00+05:30")

private class Harness(
    val case: Case,
    speech: List<AsrResult?>,
    extractions: List<String>,
    gps: Gps? = null,
    tool: ToolExecutor? = null,
    val logger: DialogueLogger = DialogueLogger(),
    speakGreeting: Boolean = false,
) {
    val assets = TestAssets.assets
    val out = RecordingSpeechOut()
    val inp = ScriptedSpeechIn(speech)
    val ext = ScriptedExtraction(extractions)
    val manager = DialogueManager(
        assets, inp, out, ext,
        DialogueConfig(useLlmRephrase = case.rephraser != null, speakGreeting = speakGreeting), case.rephraser, tool, logger, { gps }, clock = { CLOCK },
    )

    suspend fun run(): DialogueOutcome.Review = manager.run() as DialogueOutcome.Review

    val lines get() = manager.spoken
    fun kind(k: SpokenKind) = lines.filter { it.kind == k }
    val questions get() = kind(SpokenKind.QUESTION)
    val fieldsAsked get() = questions.map { it.field }

    /** What the question for [field] must be: the rephrase when the fake is good, else the template of the mode it was asked in. */
    fun expectedBody(line: SpokenLine): String =
        if (case.rephrasedOk) GOOD_TEXT.getValue(line.field!!) else assets.questionBank.question(line.field!!, line.mode)

    fun status(report: IncidentReport, key: String) = report.fields.getValue(key).effectiveStatus
    fun text(report: IncidentReport, f: IncidentField) = report.fields[f.key]?.value?.asTextOrNull()
}

/** Spec v2 section 12 scenario 14, applied to every run. */
private fun assertInvariants(h: Harness, report: IncidentReport? = null) {
    val lines = h.lines
    val asked = h.manager.askedFields
    assertEquals("ask-once", asked.size, asked.toSet().size)
    assertEquals("asked == question lines", asked, h.fieldsAsked)
    assertEquals("no spoken line twice", lines.size, lines.map { it.text }.toSet().size)
    for (k in listOf(SpokenKind.GREETING, SpokenKind.SAFETY_CHECK, SpokenKind.READBACK, SpokenKind.OPENING, SpokenKind.DEFER)) {
        assertTrue("$k spoken at most once", h.kind(k).size <= 1)
    }
    assertTrue(
        "STRICT cap: at most 2 questions in total including the safety check, was ${h.questions.size} + ${h.kind(SpokenKind.SAFETY_CHECK).size}",
        h.questions.size + h.kind(SpokenKind.SAFETY_CHECK).size <= DialogueConfig().maxQuestionsTotal,
    )
    for (q in h.questions) {
        assertEquals("one '?' in \"${q.text}\"", 1, q.text.count { it == '?' })
        assertEquals("one '?' in body \"${q.body}\"", 1, q.body.count { it == '?' })
        assertTrue("<= 20 words: \"${q.body}\"", QuestionBank.wordCount(q.body) <= 20)
        assertTrue("never why", !q.body.lowercase().startsWith("why"))
    }
    for (l in lines) {
        if (l.kind != SpokenKind.QUESTION && l.kind != SpokenKind.SAFETY_CHECK) {
            assertEquals("no '?' in ${l.kind}", 0, l.text.count { it == '?' })
        }
        val t = l.text.lowercase()
        for (banned in listOf("notified", "help is on the way", "didn't catch", "could you repeat", "say that again", "ask again")) {
            assertFalse("\"$banned\" in \"${l.text}\"", t.contains(banned))
        }
    }
    h.kind(SpokenKind.READBACK).forEach { assertEquals("the read-back is a statement", 0, it.text.count { c -> c == '?' }) }
    // the spoken text is the fake speech out's text, nothing else
    assertEquals(lines.map { it.text }, h.out.texts)
    // escalate-only
    assertEquals(lines.map { it.mode }, lines.map { it.mode }.sortedBy { it.ordinal })
    // each question's wording is the template or (good fake) the rephrase
    for (q in h.questions) assertEquals(h.expectedBody(q), q.body)
    if (report != null) assertEquals(asked, report.dialogue.askedFields)
}

class DialogueScenarioTest {

    // ===================== Spec v2 section 12, template-only =====================

    @Test fun s01_completeDescriptionNeedsNoFollowUps() = s01(OFF)
    @Test fun s02_waterOnFloorAsksLocationFromGpsThenThinDescription() = s02(OFF)
    @Test fun s03_slippedAndHurtAsksOnlyInjuryType() = s03(OFF)
    @Test fun s04_negatedInjuryDoesNotTriggerCare() = s04(OFF)
    @Test fun s05_smokeIsUrgentWithSingleSafetyCheck() = s05(OFF)
    @Test fun s06_harassmentIsCareWithTypingOfferAndSkippedPeople() = s06(OFF)
    @Test fun s07_thinDescriptionAndPastCue() = s07(OFF)
    @Test fun s08_silenceOnAQuestionMovesOn() = s08(OFF)
    @Test fun s09_unclearThenSilenceFlagsTheRest() = s09(OFF)
    @Test fun s10_anAnswerThatRevealsAnInjuryEscalatesToCareAndNeverComesBack() = s10(OFF)
    @Test fun s11_silenceBeforeTheNarrationShowsHintAndWaitsWithNoGreeting() = s11(OFF)
    @Test fun s12_readbackIsAStatementAndNothingIsListenedForAfterIt() = s12(OFF)
    @Test fun s12b_noQuestionsMeansNoListeningAfterTheNarration() = s12b(OFF)
    @Test fun s13_noNotificationClaimsInCareScenarios() = s13(OFF)
    @Test fun s14_invariantsHoldInEveryScenario() = allScenarios(OFF)

    // ===================== Spec v2 section 13: fake rephrasers =====================

    @Test fun rephraser_a_goodText_isSpokenAndEverythingStillHolds() = allScenarios(CASES[1])
    @Test fun rephraser_b_why_fallsBackToTemplate() = allScenarios(CASES[2])
    @Test fun rephraser_c_twoQuestions_fallsBackToTemplate() = allScenarios(CASES[3])
    @Test fun rephraser_d_askAgain_fallsBackToTemplate() = allScenarios(CASES[4])
    @Test fun rephraser_e_null_fallsBackToTemplate() = allScenarios(CASES[5])
    @Test fun rephraser_f_timeout_fallsBackToTemplate() = allScenarios(CASES[6])

    @Test fun goodRephrasedTextsPassTheValidator() {
        val v = PhraseValidator(TestAssets.assets.blocked, TestAssets.assets.fieldKeywords)
        for ((field, text) in GOOD_TEXT) assertEquals(field, ValidationResult.Pass, v.validate(field, text))
    }

    @Test fun rephraserFlagOffNeverCallsTheRephraser() = runTest {
        val r = FakeRephraser { GOOD_TEXT.getValue(it.field) }
        val m = DialogueManager(
            TestAssets.assets, ScriptedSpeechIn(listOf(say(NARR))), RecordingSpeechOut(),
            ScriptedExtraction(listOf(json("x", *S2_FIELDS))), DialogueConfig(useLlmRephrase = false), r,
        )
        m.run()
        assertEquals(0, r.calls)
        assertEquals(TestAssets.assets.questionBank.question("location", DialogueMode.PLAIN), m.spoken.first { it.kind == SpokenKind.QUESTION }.text)
    }

    @Test fun rephraserIsCalledOncePerQuestionAndOnlyForAskedFields() = runTest {
        val r = FakeRephraser { GOOD_TEXT.getValue(it.field) }
        val m = DialogueManager(
            TestAssets.assets, ScriptedSpeechIn(listOf(say("There's water all over the floor."), say("by the lift"), say("a pipe burst"))),
            RecordingSpeechOut(),
            ScriptedExtraction(listOf(json("x", *S2_FIELDS), json("x", "incident_location" to "By the lift"), json("x", "incident_description" to "A pipe burst"))),
            DialogueConfig(useLlmRephrase = true), r,
        )
        m.run()
        assertEquals(m.askedFields.size, r.calls)
    }

    @Test fun rephraseRequestCarriesFieldModeTemplateAndUserWords() = runTest {
        var seen: RephraseRequest? = null
        val r = FakeRephraser { if (seen == null) seen = it; null }
        DialogueManager(
            TestAssets.assets, ScriptedSpeechIn(listOf(say(NARR))), RecordingSpeechOut(),
            ScriptedExtraction(listOf(json("x", *S2_FIELDS))), DialogueConfig(), r,
        ).run()
        val req = seen!!
        assertEquals("location", req.field)
        assertEquals(DialogueMode.PLAIN, req.mode)
        assertEquals(TestAssets.assets.questionBank.question("location", DialogueMode.PLAIN), req.template)
        assertEquals(NARR, req.transcriptSoFar)
        assertEquals("en", req.language)
    }

    // ===================== scenario bodies =====================

    private fun allScenarios(c: Case) {
        s01(c); s02(c); s03(c); s04(c); s05(c); s06(c); s07(c); s08(c); s09(c); s10(c); s11(c); s12(c); s12b(c); s13(c)
    }

    private val S2_FIELDS = arrayOf<Pair<String, Any?>>(
        "incident_description" to "Water on the floor", "incident_type" to "Water Leak",
    )

    private val S8_FIELDS = arrayOf<Pair<String, Any?>>(
        "incident_description" to "Ceiling panel dropped in block C", "incident_type" to "Accident", "incident_location" to "Block C",
    )

    private fun s01(c: Case) = runTest {
        val h = Harness(
            c,
            listOf(say("There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area.")),
            listOf(
                json(
                    "x",
                    "incident_description" to "Water leak near Conference Room 2", "incident_type" to "Water Leak",
                    "incident_location" to "Conference Room 2", "date_time" to "2026-10-11T10:00:00+05:30",
                    "injury" to false, "injury_outcome" to "No injury",
                    "immediate_actions_taken" to "Maintenance isolated the area.", "hazard_still_active" to false,
                ),
            ),
        )
        val out = h.run()
        assertEquals(DialogueMode.PLAIN, h.manager.currentMode)
        assertEquals(emptyList<String>(), h.manager.askedFields)
        assertTrue("no opening line", h.kind(SpokenKind.OPENING).isEmpty())
        assertTrue("zero questions", h.questions.isEmpty())
        val rb = h.kind(SpokenKind.READBACK).single().text
        assertTrue(rb, rb.contains("Water Leak") && rb.contains("Conference Room 2") && rb.contains("10 AM"))
        assertEquals("PLAIN", out.report.dialogue.mode)
        assertEquals(TimeSource.SPOKEN, out.report.timeSource)
        assertEquals(1, h.ext.requests.size)
        assertInvariants(h, out.report)
    }

    private fun s02(c: Case) = runTest {
        // ADR-048: location (missing) first; the injury question is skipped for a pure facility issue; the thin description is asked second.
        val gps = Gps(12.9716, 77.5946)
        val h = Harness(
            c, listOf(say("There's water all over the floor."), say("here"), say("a pipe burst near the lift")),
            listOf(json("x", *S2_FIELDS), json("x", "incident_description" to "A pipe burst near the lift")), gps = gps,
        )
        val out = h.run()
        assertEquals(DialogueMode.PLAIN, h.manager.currentMode)
        assertEquals(listOf("location", "description"), h.fieldsAsked)
        assertEquals(2, h.questions.size)
        assertEquals("GPS 12.97160, 77.59460", h.text(out.report, IncidentField.INCIDENT_LOCATION))
        assertEquals(LocationSource.GPS, out.report.locationSource)
        assertEquals(FieldStatus.FILLED, h.status(out.report, "incident_location"))
        assertFalse("injury is not asked for a water leak", "injury" in h.manager.askedFields)
        assertTrue(out.report.fields.getValue("injury").value.asBooleanOrNull() == null)
        assertEquals("a couple of quick details", "Got it. Just a couple of quick details.", h.kind(SpokenKind.OPENING).single().text)
        assertEquals("'here' needs no extraction call", 2, h.ext.requests.size)
        assertInvariants(h, out.report)
    }

    @Test fun hereWithoutGpsFallsBackToTheExtractedSpokenText() = runTest {
        val h = Harness(
            OFF, listOf(say("There's water all over the floor."), say("here")),
            listOf(json("x", *S2_FIELDS), json("x", "incident_location" to "Here, near the lift")),
        )
        val out = h.run()
        assertEquals(2, h.ext.requests.size)
        assertEquals("Here, near the lift", h.text(out.report, IncidentField.INCIDENT_LOCATION))
        assertEquals(LocationSource.SPOKEN, out.report.locationSource)
    }

    private fun s03(c: Case) = runTest {
        val h = Harness(
            c, listOf(say("I slipped in the pantry and I'm hurt."), say("I twisted my ankle")),
            listOf(
                json(
                    "x",
                    "incident_description" to "Slipped in the pantry", "incident_type" to "Slip / Fall", "incident_location" to "Pantry",
                    "injury" to true, "persons_involved" to listOf("Reporter (injured)"),
                ),
                json("x", "injury_type" to "Twisted ankle"),
            ),
        )
        val out = h.run()
        assertEquals(DialogueMode.CARE, h.manager.currentMode)
        assertEquals("opening acknowledgement", "I'm sorry this happened. Thank you for reporting it. I just need a couple of details.", h.kind(SpokenKind.OPENING).single().text)
        assertEquals(listOf("injury_type"), h.fieldsAsked)
        for (never in listOf("injury", "injured_person", "location")) assertFalse(never in h.manager.askedFields)
        assertEquals("Twisted ankle", h.text(out.report, IncidentField.INJURY_TYPE))
        assertEquals(FieldStatus.FILLED, h.status(out.report, "injury_type"))
        assertEquals("CARE", out.report.dialogue.mode)
        assertTrue(h.kind(SpokenKind.READBACK).single().text.startsWith("Thank you. Reporting this helps keep everyone safe."))
        assertInvariants(h, out.report)
    }

    private fun s04(c: Case) = runTest {
        val h = Harness(
            c, listOf(say("Nobody was hurt but the printer gave off a spark in room 4.")),
            listOf(
                json(
                    "x",
                    "incident_description" to "Printer sparked in room 4", "incident_type" to "Equipment Fault",
                    "incident_location" to "Room 4", "injury" to false,
                ),
            ),
        )
        val out = h.run()
        assertEquals(DialogueMode.PLAIN, h.manager.currentMode)
        assertTrue(h.questions.isEmpty())
        assertEquals(false, out.report.fields.getValue("injury").value.asBooleanOrNull())
        assertEquals(FieldStatus.FILLED, h.status(out.report, "injury"))
        assertFalse(out.report.dialogue.emergency)
        assertInvariants(h, out.report)
    }

    private fun s05(c: Case) = runTest {
        val h = Harness(
            c, listOf(say("There's smoke coming from the server room!"), null),
            listOf(
                json(
                    "x",
                    "incident_description" to "Smoke from the server room", "incident_type" to "Fire / Smoke",
                    "incident_location" to "Server room", "hazard_still_active" to true,
                ),
            ),
        )
        val out = h.run()
        assertEquals(DialogueMode.URGENT, h.manager.currentMode)
        val safety = h.kind(SpokenKind.SAFETY_CHECK)
        assertEquals("safety check once", 1, safety.size)
        assertEquals("Are you somewhere safe right now?", safety.single().text)
        assertTrue("safety check comes before any question", h.lines.indexOf(safety.single()) < h.lines.indexOfFirst { it.kind == SpokenKind.QUESTION })
        assertTrue("no opening in URGENT", h.kind(SpokenKind.OPENING).isEmpty())
        assertEquals("URGENT = safety check + one field (immediate actions, severity is High)", listOf("immediate_actions"), h.fieldsAsked)
        assertTrue(out.report.dialogue.emergency)
        assertTrue(h.manager.uiState.value.emergency)
        assertEquals("URGENT", out.report.dialogue.mode)
        assertEquals("defer spoken once", "Your safety comes first. We can finish the details later.", h.kind(SpokenKind.DEFER).single().text)
        val actionsQ = h.questions.single()
        assertEquals(DialogueMode.URGENT, actionsQ.mode)
        assertFalse("injury is never asked (ADR-050)", "injury" in h.manager.askedFields)
        assertEquals(FieldStatus.NO_RESPONSE, h.status(out.report, "immediate_actions_taken"))
        assertInvariants(h, out.report)
    }

    private fun s06(c: Case) = runTest {
        val h = Harness(
            c, listOf(say("Someone has been harassing me near the parking lot."), say("skip")),
            listOf(
                json(
                    "x",
                    "incident_description" to "Harassment near the parking lot", "incident_type" to "Harassment",
                    "incident_location" to "Near the parking lot",
                ),
            ),
        )
        val out = h.run()
        assertEquals(DialogueMode.CARE, h.manager.currentMode)
        assertEquals("injury is never asked (ADR-050)", listOf("people_involved"), h.fieldsAsked)
        val offer = h.assets.questionBank.line("offer_typing")
        val first = h.questions.first()
        assertEquals("typing offer with the first CARE question", first.body + " " + offer, first.text)
        assertEquals(1, h.questions.count { it.text.contains(offer) })
        assertEquals(FieldStatus.UNKNOWN_REPORTER_UNSURE, h.status(out.report, "persons_involved"))
        assertEquals(1, out.report.dialogue.askedFields.count { it == "people_involved" })
        assertEquals("'skip' needs no extraction call", 1, h.ext.requests.size)
        assertInvariants(h, out.report)
    }

    private fun s07(c: Case) = runTest {
        val h = Harness(
            c, listOf(say("Something happened earlier in lab 3, nobody was hurt."), say("a chemical bottle fell and broke"), say("pata nahi")),
            listOf(
                json("x", "incident_description" to "Something happened earlier in lab 3", "incident_location" to "Lab 3", "injury" to false),
                json("x", "incident_description" to "A chemical bottle fell and broke in lab 3", "incident_type" to "Spill"),
            ),
        )
        val out = h.run()
        assertEquals(listOf("description", "time"), h.fieldsAsked)
        assertEquals(FieldStatus.FILLED, h.status(out.report, "incident_description"))
        assertEquals("A chemical bottle fell and broke in lab 3", h.text(out.report, IncidentField.INCIDENT_DESCRIPTION))
        assertEquals(FieldStatus.UNKNOWN_REPORTER_UNSURE, h.status(out.report, "date_time"))
        assertEquals(TimeSource.DEFAULT_NOW, out.report.timeSource)
        assertTrue("a time value still exists (now)", h.text(out.report, IncidentField.DATE_TIME) != null)
        assertEquals(1, h.kind(SpokenKind.READBACK).size)
        assertInvariants(h, out.report)
    }

    private fun s08(c: Case) = runTest {
        // silence on a question moves on: no_response, never asked again; both questions are listened for with the answer timeout
        val h = Harness(c, listOf(say(NARR), null, null), listOf(json("x", *S2_FIELDS)))
        val out = h.run()
        assertEquals(listOf("location", "description"), h.fieldsAsked)
        assertEquals(FieldStatus.NO_RESPONSE, h.status(out.report, "incident_location"))
        assertEquals("location never asked again", 1, h.questions.count { it.field == "location" })
        assertEquals(1, h.ext.requests.size)
        assertEquals("narration 10 s, answers 8 s; nothing is listened for after the read-back", listOf(10, 8, 8), h.inp.listens)
        assertInvariants(h, out.report)
    }

    private fun s09(c: Case) = runTest {
        val h = Harness(
            c, listOf(say("Something is broken. It happened earlier."), say("uh", conf = 0.3f), null),
            listOf(json("x", "incident_description" to "Something is broken")),
        )
        val out = h.run()
        assertEquals(DialogueMode.PLAIN, h.manager.currentMode)
        assertEquals("location first, then the thin description", listOf("location", "description"), h.fieldsAsked)
        assertEquals(FieldStatus.UNVERIFIED, h.status(out.report, "incident_location"))
        assertEquals("uh", out.report.fields.getValue("incident_location").rawAnswer)
        assertEquals("remaining wanted field (past cue, time) is flagged", FieldStatus.MISSING_FLAGGED, h.status(out.report, "date_time"))
        assertEquals("low-confidence answer is not sent to extraction", 1, h.ext.requests.size)
        assertEquals("still reaches the read-back", 1, h.kind(SpokenKind.READBACK).size)
        assertTrue(out.report.fields.values.none { it.rawAnswer != null && it.effectiveStatus != FieldStatus.UNVERIFIED })
        assertInvariants(h, out.report)
    }

    private fun s10(c: Case) = runTest {
        val h = Harness(
            c, listOf(say("Someone pulled the door handle and it broke."), say("Block A, and I cut my hand on it"), say("The handle came off in my hand")),
            listOf(
                json("x", "incident_description" to null, "incident_type" to "Equipment Damage"),
                json("x", "incident_location" to "Block A", "injury" to true, "persons_involved" to listOf("Reporter (injured)"), "injury_type" to "Cut hand"),
                json("x", "incident_description" to "The handle came off in the reporter's hand"),
            ),
        )
        val out = h.run()
        assertEquals(DialogueMode.CARE, h.manager.currentMode)
        val q = h.questions
        assertEquals("location", q[0].field)
        assertEquals(DialogueMode.PLAIN, q[0].mode)
        assertEquals("description", q[1].field)
        assertEquals("the second question is asked in CARE wording", DialogueMode.CARE, q[1].mode)
        assertEquals(h.expectedBody(q[1]), q[1].body)
        if (!c.rephrasedOk) assertEquals("In your own words, what happened? Only share what you're comfortable with.", q[1].body)
        for (never in listOf("injury", "injured_person", "injury_type")) assertFalse(never in h.manager.askedFields)
        assertEquals("CARE", out.report.dialogue.mode)
        assertEquals("Cut hand", h.text(out.report, IncidentField.INJURY_TYPE))
        assertEquals(true, out.report.fields.getValue("injury").value.asBooleanOrNull())
        assertInvariants(h, out.report)
    }

    private fun s11(c: Case) = runTest {
        // ADR-052: no greeting; the first listen starts at once (10 s of silence shows the hint) and nothing is ever spoken first
        val h = Harness(c, listOf(null), emptyList())
        var outcome: DialogueOutcome? = null
        val job = launch { outcome = h.manager.run() }
        runCurrent()
        assertTrue("no greeting is spoken", h.kind(SpokenKind.GREETING).isEmpty())
        assertTrue("nothing at all is spoken before the narration", h.lines.isEmpty())
        assertTrue("hint shown", h.manager.uiState.value.showMicHint)
        assertEquals(DialogueState.WAITING_FOR_TAP, h.manager.uiState.value.state)
        assertEquals("the very first listen used the 10 s timeout", listOf(10), h.inp.listens)
        advanceTimeBy(120_000); runCurrent()
        assertTrue(h.lines.isEmpty())
        assertNull(outcome)
        h.manager.onMicTap()
        runCurrent()
        job.join()
        assertEquals(DialogueOutcome.NoNarration, outcome)
        assertFalse(h.manager.uiState.value.showMicHint)
        assertTrue(h.lines.isEmpty())
        assertInvariants(h)
    }

    @Test fun theGreetingIsSpokenOnlyWhenTheFlagIsOn() = runTest {
        val h = Harness(OFF, listOf(null), emptyList(), speakGreeting = true)
        val job = launch { h.manager.run() }
        runCurrent()
        assertEquals("Hi, describe your incident.", h.kind(SpokenKind.GREETING).single().text)
        h.manager.onMicTap()
        runCurrent()
        job.join()
        assertEquals("the greeting is not repeated", 1, h.kind(SpokenKind.GREETING).size)
    }

    private fun s12(c: Case) = runTest {
        // ADR-048: the read-back is a statement; the dialogue does not listen after it (nothing to repeat, nothing to answer).
        val h = Harness(c, listOf(say(NARR), null, null, say("actually it was block D")), listOf(json("x", *S2_FIELDS)))
        val out = h.run()
        assertEquals(1, h.kind(SpokenKind.READBACK).size)
        assertEquals("nothing spoken after the read-back", SpokenKind.READBACK, h.lines.last().kind)
        assertFalse("no question in the read-back", h.kind(SpokenKind.READBACK).single().text.contains('?'))
        assertEquals("only the narration and the two answers were listened for", listOf(10, 8, 8), h.inp.listens)
        assertEquals("a late reply is never consumed", null, h.text(out.report, IncidentField.INCIDENT_LOCATION))
        assertEquals(DialogueState.REVIEW, h.manager.uiState.value.state)
        assertInvariants(h, out.report)
    }

    private fun s12b(c: Case) = runTest {
        val h = Harness(
            c,
            listOf(say("There was a water leak near Conference Room 2 around 10 AM."), say("actually it was Conference Room 3")),
            listOf(
                json(
                    "x", "incident_description" to "Water leak", "incident_type" to "Water Leak",
                    "incident_location" to "Conference Room 2", "date_time" to "2026-10-11T10:00:00+05:30", "injury" to false,
                ),
            ),
        )
        val out = h.run()
        assertEquals("zero questions, so only the narration is listened for", listOf(10), h.inp.listens)
        assertEquals("Conference Room 2", h.text(out.report, IncidentField.INCIDENT_LOCATION))
        assertEquals(1, h.kind(SpokenKind.READBACK).size)
        assertEquals(SpokenKind.READBACK, h.lines.last().kind)
        assertEquals(1, h.ext.requests.size)
        assertInvariants(h, out.report)
    }

    private fun s13(c: Case) = runTest {
        val care = Harness(
            c, listOf(say("I slipped in the pantry and I'm hurt."), say("I twisted my ankle")),
            listOf(
                json(
                    "x", "incident_description" to "Slipped in the pantry", "incident_type" to "Slip / Fall",
                    "incident_location" to "Pantry", "injury" to true, "persons_involved" to listOf("Reporter (injured)"),
                ),
                json("x", "injury_type" to "Twisted ankle"),
            ),
        )
        care.run()
        val rb = care.kind(SpokenKind.READBACK).single().text.lowercase()
        assertFalse(rb.contains("notified"))
        assertFalse(rb.contains("help is on the way"))
        assertEquals("{next_step} is empty", "thank you. reporting this helps keep everyone safe. i've logged slip / fall at pantry. please review and tap submit.", rb)
        assertInvariants(care)

        val urgent = Harness(
            c, listOf(say("There's smoke coming from the server room!"), say("yes I'm safe")),
            listOf(json("x", "incident_description" to "Smoke", "incident_type" to "Fire / Smoke", "incident_location" to "Server room", "hazard_still_active" to true)),
        )
        urgent.run()
        assertFalse(urgent.kind(SpokenKind.READBACK).single().text.lowercase().contains("notified"))
        assertInvariants(urgent)
    }

    // ===================== extra behaviour =====================

    @Test fun firstExtractionFailurePropagatesLaterFailuresOnlyMarkTheField() = runTest {
        val bad = Harness(OFF, listOf(say("anything")), emptyList())
        try {
            bad.run()
            throw AssertionError("expected ExtractionException")
        } catch (e: ExtractionException) {
            assertTrue(bad.questions.isEmpty())
        }
        val h = Harness(OFF, listOf(say(NARR), say("no")), listOf(json("x", *S2_FIELDS)))
        val out = h.run()
        assertEquals(FieldStatus.UNVERIFIED, h.status(out.report, "incident_location"))
        assertEquals("no", out.report.fields.getValue("incident_location").rawAnswer)
        assertEquals(listOf("location", "description"), h.fieldsAsked)
    }

    @Test fun answerExtractionRequestCarriesDialogueFieldAndPreviousReport() = runTest {
        val h = Harness(OFF, listOf(say(NARR), say("by the lift")), listOf(json("x", *S2_FIELDS), json("x", "incident_location" to "By the lift")))
        h.run()
        val r = h.ext.requests[1]
        assertEquals(listOf("location"), r.answeredQuestion!!.targetFields)
        assertEquals(h.assets.questionBank.question("location", DialogueMode.PLAIN), r.answeredQuestion!!.text)
        assertEquals("by the lift", r.answerTranscript)
        assertEquals(NARR, r.originalTranscript)
        assertEquals("Water Leak", r.previousReport!!.fields.getValue("incident_type").value.asTextOrNull())
    }

    @Test fun answerThatFillsOtherFieldsMarksThisFieldUnverified() = runTest {
        val h = Harness(
            OFF, listOf(say(NARR), say("it is on the third floor")),
            listOf(json("x", *S2_FIELDS), json("x", "incident_description" to "Water on the third floor near the lift")),
        )
        val out = h.run()
        assertEquals(FieldStatus.UNVERIFIED, h.status(out.report, "incident_location"))
        assertEquals("it is on the third floor", out.report.fields.getValue("incident_location").rawAnswer)
        assertEquals("Water on the third floor near the lift", h.text(out.report, IncidentField.INCIDENT_DESCRIPTION))
        assertEquals(FieldStatus.FILLED, h.status(out.report, "incident_description"))
    }

    @Test fun unsurePhrasesNeedNoExtraction() = runTest {
        for (phrase in listOf("I don't know", "not sure", "no idea", "teliyadu", "skip chey", "nahi pata")) {
            val h = Harness(OFF, listOf(say(NARR), say(phrase)), listOf(json("x", *S2_FIELDS)))
            val out = h.run()
            assertEquals(phrase, FieldStatus.UNKNOWN_REPORTER_UNSURE, h.status(out.report, "incident_location"))
            assertEquals(1, h.ext.requests.size)
        }
    }

    @Test fun laterAnswerThatFillsAFlaggedFieldUpgradesItToFilled() = runTest {
        // the description answer "hmm" is unclear (the extraction fills the TIME instead), so the description is first unverified;
        // it holds a value, so Review shows it as filled
        val h = Harness(
            OFF, listOf(say("Something happened earlier in lab 3."), say("hmm")),
            listOf(
                json("x", "incident_description" to "Something happened", "incident_location" to "Lab 3"),
                json("x", "date_time" to "2026-10-11T10:00:00+05:30"),
            ),
        )
        val out = h.run()
        assertEquals(FieldStatus.FILLED, h.status(out.report, "incident_description"))
        assertEquals(FieldStatus.FILLED, h.status(out.report, "date_time"))
    }

    @Test fun noReplyIsListenedForAfterTheReadback() = runTest {
        val h = Harness(OFF, listOf(say(NARR), null, null, say("that's fine")), listOf(json("x", *S2_FIELDS)))
        h.run()
        assertEquals("the reply after the read-back is never listened for", listOf(10, 8, 8), h.inp.listens)
        assertEquals(1, h.ext.requests.size)
    }

    @Test fun escalationAfterAnAnswerSpendsTheSecondQuestionOnTheSafetyCheck() = runTest {
        val h = Harness(
            OFF, listOf(say(NARR), say("by the lift, but there is smoke now"), null),
            listOf(json("x", *S2_FIELDS), json("x", "incident_location" to "By the lift")),
        )
        val out = h.run()
        assertEquals(DialogueMode.URGENT, h.manager.currentMode)
        assertEquals(1, h.kind(SpokenKind.SAFETY_CHECK).size)
        assertEquals(listOf("location"), h.fieldsAsked)
        assertEquals(2, h.questions.size + h.kind(SpokenKind.SAFETY_CHECK).size)
        assertTrue(out.report.dialogue.emergency)
        assertInvariants(h, out.report)
    }

    @Test fun runCanOnlyBeCalledOnce() = runTest {
        val h = Harness(OFF, listOf(say("A ceiling panel dropped in block C.")), listOf(json("x", *S8_FIELDS)))
        h.run()
        try {
            h.manager.run()
            throw AssertionError("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test fun narrationPassedInSkipsTheGreeting() = runTest {
        val h = Harness(OFF, emptyList(), listOf(json("x", *S2_FIELDS)), speakGreeting = true)
        h.manager.run(narration = say(NARR))
        assertTrue(h.kind(SpokenKind.GREETING).isEmpty())
        assertEquals(listOf("location", "description"), h.fieldsAsked)
    }

    @Test fun closingIsSpokenOnceAndMatchesTheMode() = runTest {
        val h = Harness(OFF, listOf(say("A ceiling panel dropped in block C.")), listOf(json("x", *S8_FIELDS)))
        h.run()
        h.manager.close()
        h.manager.close()
        val closing = h.kind(SpokenKind.CLOSING).single()
        assertEquals("Report saved.", closing.text)
    }

    @Test fun toolExecutorReceivesThePlannerHint() = runTest {
        val hints = mutableListOf<PlannerHint>()
        val tool = object : ToolExecutor {
            override suspend fun fillIncidentForm(report: IncidentReport) = report.copy(toolCall = com.example.test_app.domain.model.ToolCall("fill_incident_form", "fields"))
            override suspend fun fillIncidentFormWithHint(report: IncidentReport, hint: PlannerHint) =
                com.example.test_app.domain.service.ToolResult(true, fillIncidentForm(report), hint.also { hints += it })
            override suspend fun submitIncidentReport(report: IncidentReport) = report
        }
        val h = Harness(
            OFF, listOf(say("There's water all over the floor."), say("by the lift"), say("a pipe burst")),
            listOf(json("x", *S2_FIELDS), json("x", "incident_location" to "By the lift"), json("x", "incident_description" to "A pipe burst")), tool = tool,
        )
        val out = h.run()
        assertEquals(listOf("location", "description"), hints[0].missing)
        assertEquals("location", hints[0].nextField)
        assertEquals("PLAIN", hints[0].mode)
        assertEquals("Where did this happen? You can say 'here' if it's where you are.", hints[0].template)
        assertEquals("description", hints[1].nextField)
        assertNull("nothing left (cap reached)", hints.last().nextField)
        val json = Json.parseToJsonElement(h.manager.lastToolResult!!.toJson().toString()).jsonObject
        assertEquals(setOf("ok", "missing", "next_field", "mode", "template"), json.keys)
        assertEquals("fill_incident_form", out.report.toolCall?.name)
    }

    @Test fun logsOneLinePerTurnWithTheSpecFields() = runTest {
        val h = Harness(
            CASES[1], listOf(say(NARR), say("by the lift"), say("a pipe burst")),
            listOf(json("x", *S2_FIELDS), json("x", "incident_location" to "By the lift"), json("x", "incident_description" to "A pipe burst")),
        )
        h.run()
        val lines = h.logger.lines.map { Json.parseToJsonElement(it).jsonObject }
        val ask = lines.first { it["state"]!!.jsonPrimitive.content == "ASK_LOOP" }
        for (k in listOf("t", "state", "mode", "field", "template", "rephrased", "validator", "spoken", "asr_text", "asr_conf", "outcome", "latency_ms", "prompt_tokens", "prefill_ms", "decode_ms")) {
            assertTrue("missing $k", k in ask.keys)
        }
        assertEquals("location", ask["field"]!!.jsonPrimitive.content)
        assertEquals("pass", ask["validator"]!!.jsonPrimitive.content)
        assertEquals("filled", ask["outcome"]!!.jsonPrimitive.content)
        assertEquals("by the lift", ask["asr_text"]!!.jsonPrimitive.content)
        assertTrue(lines.any { it["state"]!!.jsonPrimitive.content == "READBACK" })
        assertTrue("no greeting line (ADR-052)", lines.none { it["state"]!!.jsonPrimitive.content == "GREETING" })
    }

    @Test fun logRecordsValidatorRejectionAndTimeout() = runTest {
        for ((case, expected) in listOf(CASES[2] to "fail:", CASES[6] to "timeout", CASES[5] to "fail:null")) {
            val h = Harness(case, listOf(say(NARR), null, null), listOf(json("x", *S2_FIELDS)))
            h.run()
            val ask = h.logger.lines.map { Json.parseToJsonElement(it).jsonObject }.first { it["state"]!!.jsonPrimitive.content == "ASK_LOOP" }
            assertTrue(ask["validator"]!!.jsonPrimitive.content, ask["validator"]!!.jsonPrimitive.content.startsWith(expected))
        }
    }

    @Test fun reportIsStampedWithStatusesAndDialogueInfo() = runTest {
        val h = Harness(OFF, listOf(say("A ceiling panel dropped in block C.")), listOf(json("x", *S8_FIELDS)))
        val out = h.run()
        assertEquals(IncidentField.entries.map { it.key }.toSet(), out.report.fields.keys)
        assertTrue(out.report.fields.values.all { it.status != null })
        assertEquals(emptyList<String>(), out.report.dialogue.askedFields)
        assertEquals(LocationSource.SPOKEN, out.report.locationSource)
        assertEquals(TimeSource.DEFAULT_NOW, out.report.timeSource)
        assertEquals("2026-10-11", h.text(out.report, IncidentField.DATE_TIME)!!.take(10))
        // JSON round trip keeps everything
        val back = IncidentJsonCodec.decode(IncidentJsonCodec.encode(out.report))
        assertEquals(out.report, back)
    }

    // ===================== Phase 1 mocks =====================

    @Test fun worksWithThePhase1MockExtraction() = runTest {
        val settings = DevSettingsStore().apply { update { it.copy(scenario = MockScenario.INJURY_FOLLOW_UPS, llmDelayMs = 0) } }
        val inp = ScriptedSpeechIn(
            listOf(say("Someone slipped near the conference room and hurt his ankle, water is dripping from the ceiling."), say("It was my colleague Ravi"), say("A twisted ankle")),
        )
        val m = DialogueManager(TestAssets.assets, inp, RecordingSpeechOut(), MockIncidentExtraction(settings), clock = { CLOCK })
        val out = m.run() as DialogueOutcome.Review
        assertEquals("injury is never asked (ADR-050); the narration says someone is hurt", listOf("immediate_actions", "injured_person"), m.askedFields)
        assertEquals(DialogueMode.CARE, m.currentMode)
        assertEquals(true, out.report.fields.getValue("injury").value.asBooleanOrNull())
    }
}
