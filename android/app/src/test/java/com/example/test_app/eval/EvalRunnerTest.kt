package com.example.test_app.eval

import com.example.test_app.dialogue.DialogueConfig
import com.example.test_app.dialogue.LlmCallMetrics
import com.example.test_app.dialogue.LlmMetricsProvider
import com.example.test_app.dialogue.RephraseRequest
import com.example.test_app.dialogue.Rephraser
import com.example.test_app.dialogue.TestAssets
import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.ReportInput
import com.example.test_app.domain.model.boolValue
import com.example.test_app.domain.model.listValue
import com.example.test_app.domain.model.textValue
import com.example.test_app.domain.service.AudioInput
import com.example.test_app.domain.service.ExtractionException
import com.example.test_app.domain.service.ExtractionRequest
import com.example.test_app.domain.service.IncidentExtractionService
import com.example.test_app.domain.service.IncidentJsonCodec
import com.example.test_app.domain.service.SpeechToTextService
import com.example.test_app.domain.service.SttHint
import com.example.test_app.domain.service.Transcript
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.OffsetDateTime

private val CLOCK = OffsetDateTime.parse("2026-10-11T09:30:00+05:30")

private fun fe(v: Any?): FieldEntry = when (v) {
    null -> FieldEntry()
    is String -> FieldEntry(textValue(v), 0.9)
    is Boolean -> FieldEntry(boolValue(v), 0.9)
    is List<*> -> FieldEntry(listValue(v.map { it as String }), 0.9)
    else -> error("bad value $v")
}

/** One scripted extraction response (raw JSON text, like the real service returns). */
private fun json(vararg fields: Pair<String, Any?>): String = IncidentJsonCodec.encode(
    IncidentReport(
        reportId = "INC-2026-000001", status = IncidentReport.STATUS_NEEDS_CLARIFICATION,
        createdAt = "2026-10-11T09:30:00+05:30", input = ReportInput("voice", "x"),
        fields = fields.associate { it.first to fe(it.second) },
    ),
)

/**
 * Fake extraction service: the responses of a case are queued under its narration text (`originalTranscript`), one per call.
 * A queue that runs dry throws [ExtractionException]. Optionally reports LLM metrics like the real service (a call
 * that should count as an LLM call gets a fresh [LlmCallMetrics] instance).
 */
private class ByNarrationExtraction(
    scripts: Map<String, List<String>>,
    private val metrics: List<LlmCallMetrics?> = emptyList(),
) : IncidentExtractionService, LlmMetricsProvider {
    private val queues = scripts.mapValues { ArrayDeque(it.value) }
    private var calls = 0
    private var last: LlmCallMetrics? = null

    override suspend fun extract(request: ExtractionRequest): String {
        metrics.getOrNull(calls++)?.let { last = it }
        return queues[request.originalTranscript]?.removeFirstOrNull() ?: throw ExtractionException("script exhausted for \"${request.originalTranscript}\"")
    }

    override fun lastCallMetrics(): LlmCallMetrics? = last
}

private class MapStt(private val byFile: Map<String, String>) : SpeechToTextService {
    val hints = mutableListOf<String?>()
    override suspend fun transcribe(audio: AudioInput, hint: SttHint): Transcript {
        hints += hint.expectedField
        return Transcript(byFile.getValue(audio.file.name), "en", 0.9f)
    }
}

private class FakeRephraser(private val reply: (RephraseRequest) -> String?) : Rephraser {
    override suspend fun rephrase(req: RephraseRequest): String? = reply(req)
}

private const val WATER_LEAK = "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area."
private const val WATER_FLOOR = "There's water all over the floor."

private val WATER_LEAK_JSON = json(
    "incident_description" to "Water leak near Conference Room 2", "incident_type" to "Water Leak",
    "incident_location" to "Conference Room 2", "date_time" to "2026-10-11T10:00:00+05:30",
    "injury" to false, "injury_outcome" to "No injury", "immediate_actions_taken" to "Maintenance isolated the area.",
    "hazard_still_active" to false,
)
private val S2 = arrayOf<Pair<String, Any?>>("incident_description" to "Water on the floor", "incident_type" to "Water Leak")

private val CASES_JSON = """
    [
      {"id": "E01", "audio": "audio/T01.wav", "transcript": "$WATER_LEAK",
       "expected": {"mode": "PLAIN", "asked_fields": [], "emergency": false,
         "fields": {"incident_type": "Water Leak", "incident_location": "Conference Room 2", "date_time": "10 AM", "immediate_actions_taken": "Area isolated"}}},
      {"id": "E02", "audio": "audio/T02.wav", "transcript": "$WATER_FLOOR",
       "follow_up_answers": [
         {"field": "location", "transcript": "by the lift", "audio": "audio/T02_a1.wav"},
         {"field": "description", "transcript": "a pipe burst", "audio": "audio/T02_a2.wav"}],
       "expected": {"mode": "PLAIN", "asked_fields": ["location", "description"], "outcomes": {"location": "filled", "description": "filled"},
         "fields": {"incident_type": "Water Leak", "incident_location": "by the lift"}}},
      {"id": "E11", "expected": {"result": "no_narration", "asked_fields": []}}
    ]
""".trimIndent()

class EvalRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val script = mapOf(
        WATER_LEAK to listOf(WATER_LEAK_JSON),
        WATER_FLOOR to listOf(
            json(*S2), json("incident_location" to "By the lift"), json("incident_description" to "A pipe burst"),
        ),
    )

    private fun runner(
        extraction: IncidentExtractionService = ByNarrationExtraction(script),
        stt: SpeechToTextService? = null,
        rephraser: Rephraser? = null,
    ) = EvalRunner(
        assets = TestAssets.assets, extraction = extraction, rephraser = rephraser, stt = stt,
        dialogueConfig = DialogueConfig(useLlmRephrase = rephraser != null), clock = { CLOCK },
    )

    private val cases = EvalCases.parse(CASES_JSON)

    // ---- transcript input ----------------------------------------------------------------------

    @Test fun transcriptRunScoresTheCompleteReport() = runTest {
        val run = runner().run(cases.take(1), EvalInput.TRANSCRIPT, null, EvalMeta())
        val c = run.cases.single()
        assertEquals("ok", c.status)
        assertEquals("transcript", c.input)
        assertEquals("review", c.result)
        assertEquals("PLAIN", c.mode)
        assertEquals(emptyList<String>(), c.asked)
        assertEquals(true, c.askedOk)
        assertEquals(true, c.modeOk)
        assertEquals(emptyList<String>(), c.invariantViolations)
        assertEquals(0, c.askOnceViolations)
        assertEquals(listOf("EXACT", "EXACT", "RELAXED", "RELAXED"), c.fields.map { it.match })
        assertEquals(listOf("CORRECT", "CORRECT", "CORRECT", "CORRECT"), c.fields.map { it.placement })
        assertEquals("2026-10-11T10:00:00+05:30", c.fields.single { it.field == "date_time" }.actual)
        // ADR-052: no greeting is spoken; the read-back statement is the only line
        assertEquals(1, c.spoken.size)
        assertTrue(c.spoken.last().contains("Water Leak"))
        // one extraction call, no recognition numbers for typed input
        assertEquals(listOf("extract"), c.llmCalls.map { it.kind })
        assertNotNull(c.firstFormMs)
        assertEquals(listOf<String?>(null), c.utterances.map { it.key })
        assertEquals(WATER_LEAK, c.utterances.first().reference)
        assertTrue(c.utterances.all { it.asrMs == null && it.wer == null })

        val s = run.summary
        assertEquals(1, s.casesOk)
        assertEquals(1.0, s.extraction.accuracy!!, 1e-9)
        assertEquals(2, s.extraction.exact)
        assertEquals(2, s.extraction.relaxed)
        assertEquals(1.0, s.mapping.placementRate!!, 1e-9)
        assertEquals(0, s.dialogue.questionsTotal)
        assertFalse(s.asr.available)
        assertEquals("n/a (no ASR)", s.asr.note)
        assertNull(s.asr.meanWer)
        assertNull(s.latency.asr)
        assertEquals("transcript", run.input)
    }

    @Test fun answersAreScriptedByFieldAndScored() = runTest {
        val run = runner().run(cases.filter { it.id == "E02" }, EvalInput.TRANSCRIPT, null, EvalMeta())
        val c = run.cases.single()
        assertEquals("ok", c.status)
        assertEquals(listOf("location", "description"), c.asked)
        assertEquals(true, c.askedOk)
        assertEquals(listOf("location" to "filled", "description" to "filled"), c.turns.map { it.field to it.outcome })
        assertEquals(listOf("filled", "filled"), c.outcomeChecks.map { it.actual })
        assertEquals(listOf("EXACT", "NORMALIZED"), c.fields.map { it.match }) // type, location ("By the lift")
        assertEquals(listOf("extract", "update", "update"), c.llmCalls.map { it.kind })
        assertEquals(listOf<String?>(null, "location", "description"), c.utterances.map { it.key })
        assertEquals(emptyList<String>(), c.invariantViolations)
        assertEquals(2, run.summary.dialogue.questionsTotal)
        assertEquals(mapOf("location" to mapOf("filled" to 1), "description" to mapOf("filled" to 1)), run.summary.dialogue.outcomeMix)
        assertEquals(2, run.summary.dialogue.outcomeTotal)
        assertEquals(2, run.summary.dialogue.outcomeCorrect)
    }

    @Test fun aCaseWithoutNarrationTapsOnceAndEndsWithNoNarration() = runTest {
        val run = runner().run(cases.filter { it.id == "E11" }, EvalInput.TRANSCRIPT, null, EvalMeta())
        val c = run.cases.single()
        assertEquals("ok", c.status)
        assertEquals("no_narration", c.result)
        assertTrue(c.resultOk)
        assertEquals(emptyList<String>(), c.spoken) // no greeting is spoken (ADR-052)
        assertEquals(emptyList<String>(), c.asked)
        assertEquals(1, run.summary.dialogue.flowOk)
    }

    @Test fun aFailingCaseIsAnErrorAndTheRunContinues() = runTest {
        val more = cases.take(1) + EvalCases.parse("""[{"id": "BAD", "transcript": "boom", "expected": {}}]""") + cases.take(1).map { it.copy(id = "AGAIN") }
        val progress = mutableListOf<EvalProgress>()
        val ext = ByNarrationExtraction(mapOf(WATER_LEAK to listOf(WATER_LEAK_JSON, WATER_LEAK_JSON)))
        val run = runner(ext).run(more, EvalInput.TRANSCRIPT, null, EvalMeta()) { progress += it }
        assertEquals(listOf("ok", "error", "ok"), run.cases.map { it.status })
        assertTrue(run.cases[1].detail!!.contains("ExtractionException"))
        assertEquals(1, run.summary.casesError)
        assertEquals(2, run.summary.casesOk)
        assertEquals(listOf("E01", "BAD", "AGAIN", null), progress.map { it.currentId })
        assertEquals(EvalProgress(3, 3, null), progress.last())
        assertEquals(EvalProgress(0, 3, "E01"), progress.first())
    }

    @Test fun llmMetricsAreAttributedOnlyToCallsThatMadeNewMetrics() = runTest {
        val m1 = LlmCallMetrics(null, 400, 600)
        val m2 = LlmCallMetrics(null, 100, 200)
        // call 1 (first pass): m1; call 2 (location answer): nothing new; call 3 (description answer): m2
        val ext = ByNarrationExtraction(script, metrics = listOf(m1, null, m2))
        val run = runner(ext).run(cases.filter { it.id == "E02" }, EvalInput.TRANSCRIPT, null, EvalMeta())
        val calls = run.cases.single().llmCalls
        assertEquals(listOf("extract", "update", "update"), calls.map { it.kind })
        assertEquals(listOf<Long?>(400, null, 100), calls.map { it.prefillMs })
        assertEquals(listOf<Long?>(600, null, 200), calls.map { it.decodeMs })
        assertEquals(2, run.summary.latency.prefill!!.count)
        assertEquals(250.0, run.summary.latency.prefill!!.median, 1e-9)
    }

    @Test fun rephraserVerdictsFeedTheRephraserMetrics() = runTest {
        val rephraser = FakeRephraser { req ->
            if (req.field == "location") "You mentioned the floor. Where in the building did this happen?"
            else "Why did this happen? Tell me where it was."
        }
        val run = runner(rephraser = rephraser).run(cases.filter { it.id == "E02" }, EvalInput.TRANSCRIPT, null, EvalMeta(rephraseEnabled = true))
        val c = run.cases.single()
        assertEquals(listOf("pass", "fail"), c.turns.map { it.validator!!.substringBefore(':') })
        assertEquals("You mentioned the floor. Where in the building did this happen?", c.turns[0].rephrased)
        val r = run.summary.rephraser
        assertEquals(2, r.attempts)
        assertEquals(1, r.passed)
        assertEquals(0.5, r.passRate!!, 1e-9)
        assertEquals(1, r.rejectionReasons.values.sum())
        assertNotNull(r.latency)
        assertEquals(emptyList<String>(), c.invariantViolations)
    }

    // ---- audio input ---------------------------------------------------------------------------

    private fun audioDir(vararg names: String): File = tmp.newFolder("eval").also { root ->
        File(root, "audio").mkdirs()
        for (n in names) File(root, "audio/$n").writeBytes(wavBytes(seconds = 0.5))
    }

    @Test fun audioRunGoesThroughTheRecogniserAndComputesWer() = runTest {
        val root = audioDir("T01.wav", "T02.wav", "T02_a1.wav", "T02_a2.wav")
        val stt = MapStt(
            mapOf(
                "T01.wav" to WATER_LEAK,
                "T02.wav" to "Theres water over the floor", // "There's" -> "Theres": 1 deletion ("all"), stays a thin description
                "T02_a1.wav" to "by the lift",
                "T02_a2.wav" to "a pipe burst",
            ),
        )
        val ext = ByNarrationExtraction(script + ("Theres water over the floor" to script.getValue(WATER_FLOOR)))
        val run = runner(ext, stt).run(cases, EvalInput.AUDIO, root, EvalMeta(asrModel = "fake-stt"))
        assertEquals(listOf("ok", "ok", "ok"), run.cases.map { it.status })

        val e01 = run.cases[0]
        assertEquals(0, e01.werTotal!!.errors)
        assertNotNull(e01.utterances.first().asrMs)
        assertEquals("no read-back reply is listened for (ADR-048)", 1, e01.utterances.size)
        assertNotNull(e01.firstFormMs)

        val e02 = run.cases[1]
        assertEquals(listOf<String?>(null, "location", "description"), e02.utterances.map { it.key })
        assertEquals(listOf("Theres water over the floor", "by the lift", "a pipe burst"), e02.utterances.map { it.hypothesis })
        val w = e02.werTotal!!
        assertEquals(1, w.errors)
        assertEquals(12, w.referenceWords) // 6 + 3 + 3
        assertEquals(1.0 / 12, w.wer, 1e-9)
        assertEquals(listOf<String?>(null, "location", "description"), stt.hints.drop(1))

        val asr = run.summary.asr
        assertTrue(asr.available)
        assertEquals("fake-stt", asr.note)
        assertEquals(2, asr.cases)
        assertEquals(4, asr.utterances) // E01 narration + E02 narration, location and description
        assertEquals((0.0 + 1.0 / 12) / 2, asr.meanWer!!, 1e-9)
        assertEquals(1.0 / 30, asr.corpusWer!!, 1e-9) // 1 error in 18 + 12 reference words
        assertEquals(4, run.summary.latency.asr!!.count)
        assertEquals("audio", run.input)
    }

    @Test fun missingAudioSkipsTheCaseAndTheRunContinues() = runTest {
        val root = audioDir("T01.wav", "T02.wav") // the answers' recordings are missing
        val stt = MapStt(mapOf("T01.wav" to WATER_LEAK, "T02.wav" to WATER_FLOOR))
        val run = runner(stt = stt).run(cases, EvalInput.AUDIO, root, EvalMeta())
        assertEquals(listOf("ok", "skipped", "ok"), run.cases.map { it.status }) // E11 needs no audio
        assertTrue(run.cases[1].detail!!.contains("audio missing"))
        assertTrue(run.cases[1].detail!!.contains("audio/T02_a1.wav"))
        assertEquals(1, run.summary.casesSkipped)
    }

    @Test fun withoutARecogniserEveryAudioCaseIsSkippedAndWerIsNotAvailable() = runTest {
        val run = runner(stt = null).run(cases, EvalInput.AUDIO, tmp.newFolder("none"), EvalMeta())
        assertEquals(listOf("skipped", "skipped", "skipped"), run.cases.map { it.status })
        assertTrue(run.cases[0].detail!!.contains("ASR n/a"))
        assertFalse(run.summary.asr.available)
        assertEquals("n/a (no ASR)", run.summary.asr.note)
        assertEquals(3, run.summary.casesSkipped)
    }

    @Test fun anAnswerWithoutDeclaredAudioSkipsTheAudioRun() = runTest {
        val noAudio = EvalCases.parse("""[{"id": "X", "audio": "audio/X.wav", "transcript": "hi", "follow_up_answers": [{"field": "location", "transcript": "no"}], "expected": {}}]""")
        val run = runner(stt = MapStt(emptyMap())).run(noAudio, EvalInput.AUDIO, audioDir("X.wav"), EvalMeta())
        assertEquals("skipped", run.cases.single().status)
        assertTrue(run.cases.single().detail!!.contains("answer audio not declared"))
    }

    // ---- output --------------------------------------------------------------------------------

    @Test fun resultsRoundTripThroughJsonAndAreWrittenWithMarkdown() = runTest {
        val run = runner().run(cases, EvalInput.TRANSCRIPT, null, EvalMeta(device = "QIDK", aiMode = "real", llmModel = "Qwen3-4B"))
        val text = EvalReport.toJson(run)
        assertTrue(text.contains("\"schema\": \"eval_results/1\""))
        assertTrue(text.contains("\"per_field\""))
        assertTrue(text.contains("\"ask_once_violations\""))
        assertEquals(run, EvalReport.fromJson(text))

        val dir = EvalReport.write(run, tmp.newFolder("eval_runs"))
        assertEquals(run.runId, dir.name)
        assertEquals(run, EvalReport.fromJson(File(dir, "results.json").readText()))
        val md = File(dir, "results.md").readText()
        assertTrue(md.contains("### Run ${run.runId}"))
        assertTrue(md.contains("WER: n/a (no ASR)"))
        assertTrue(md.contains("| E02 | ok |"))
        assertTrue(md.contains("**Dialogue**"))
        assertTrue(md.contains("**Latency (ms)**"))
        assertTrue(EvalReport.headline(run).any { it.startsWith("WER: n/a (no ASR)") })
    }

    @Test fun mockRunsAreFlaggedInTheHeadline() = runTest {
        val run = runner().run(cases.take(1), EvalInput.TRANSCRIPT, null, EvalMeta(aiMode = "mock"))
        assertTrue(EvalReport.headline(run).any { it.startsWith("Mock AI") })
    }
}
