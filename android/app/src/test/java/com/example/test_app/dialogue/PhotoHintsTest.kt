package com.example.test_app.dialogue

import com.example.test_app.ai.FakeEngine
import com.example.test_app.ai.LlmIncidentExtractionService
import com.example.test_app.ai.Prompts
import com.example.test_app.domain.model.FieldStatus
import com.example.test_app.domain.model.MediaItem
import com.example.test_app.domain.service.ImageAnalysisService
import com.example.test_app.domain.service.ImageHint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dialogue describes the attached photos (Gemma 4 vision, 512 px) before the first extraction and passes the hints on. */
class PhotoHintsTest {
    private val reply = """ "Water Leak", "severity": "Medium", "location": "lab", "datetime": null, "persons_involved": null, "actions_taken": null, "description": "A pipe leaks in the lab."}"""

    private fun photo(id: String, type: String = "image") = MediaItem(id, type, "file:///photos/$id.jpg", "2026-10-11T14:00:00+05:30")

    private class FakeAnalysis(val fail: Set<String> = emptySet()) : ImageAnalysisService {
        val analysed = mutableListOf<String>()
        override suspend fun analyze(media: MediaItem): ImageHint {
            analysed += media.id
            if (media.id in fail) throw IllegalStateException("no")
            return ImageHint(media.id, listOf("pipe"), "A pipe is leaking onto the floor (${media.id}).")
        }
    }

    private fun manager(engine: FakeEngine, analysis: ImageAnalysisService?, narration: String = "a pipe is leaking in the lab") = DialogueManager(
        TestAssets.assets, ScriptIn(listOf(said(narration))), RecordingOut(), LlmIncidentExtractionService(engine) { TEST_CLOCK },
        DialogueConfig(useLlmRephrase = false), clock = { TEST_CLOCK }, imageAnalysis = analysis,
    )

    private fun engine() = FakeEngine(replyFor = { p -> if (p.endsWith(Prompts.EXTRACTION_PREFIX)) reply else "}" })

    @Test fun photoDescriptionsReachTheExtractionPrompt() = runTest {
        val engine = engine()
        val analysis = FakeAnalysis()
        manager(engine, analysis).run(media = listOf(photo("p1")))
        assertEquals(listOf("p1"), analysis.analysed)
        val first = engine.prompts.first()
        assertTrue(first, first.contains("\nPHOTO: A pipe is leaking onto the floor (p1)."))
    }

    @Test fun noAnalysisServiceMeansNoPhotoLine() = runTest {
        val engine = engine()
        manager(engine, null).run(media = listOf(photo("p1")))
        assertTrue(engine.prompts.none { it.contains("PHOTO") })
    }

    @Test fun aFailingPhotoIsSkippedAndNeverBlocksTheReport() = runTest {
        val engine = engine()
        val analysis = FakeAnalysis(fail = setOf("p1"))
        val outcome = manager(engine, analysis).run(media = listOf(photo("p1"), photo("p2")))
        assertTrue(outcome is DialogueOutcome.Review)
        assertEquals(listOf("p1", "p2"), analysis.analysed)
        val first = engine.prompts.first()
        assertTrue(first.contains("PHOTO: A pipe is leaking onto the floor (p2)."))
        assertTrue(!first.contains("(p1)"))
    }

    // ---- photo only (ADR-053): no narration, the report comes from the photo description alone ----

    private fun photoOnlyManager(engine: FakeEngine, analysis: ImageAnalysisService?) = DialogueManager(
        TestAssets.assets, ScriptIn(emptyList()), RecordingOut(), LlmIncidentExtractionService(engine) { TEST_CLOCK },
        DialogueConfig(useLlmRephrase = false), clock = { TEST_CLOCK }, imageAnalysis = analysis,
    )

    @Test fun aPhotoOnlyReportIsBuiltFromThePhotoDescriptionAlone() = runTest {
        val engine = engine()
        val analysis = FakeAnalysis()
        val m = photoOnlyManager(engine, analysis)
        val outcome = m.run(narration = AsrResult("", 1f, ""), inputMode = com.example.test_app.domain.model.InputMode.PHOTO_ONLY, media = listOf(photo("p1")))
        val report = (outcome as DialogueOutcome.Review).report
        val prompt = engine.prompts.first()
        assertTrue(prompt, prompt.endsWith("<|im_start|>user\nPHOTO: A pipe is leaking onto the floor (p1).<|im_end|>\n<|im_start|>assistant\n" + Prompts.EXTRACTION_PREFIX))
        assertTrue("the photo-only rules say nobody spoke (ADR-062)", prompt.contains("Nobody described the incident: you only get descriptions of PHOTOS."))
        assertTrue("and are not the narration rules", !prompt.contains("If the user said nothing, base the report on PHOTO alone."))
        assertEquals("photo_only", report.input.mode)
        assertEquals("", report.input.transcript)
        assertEquals(listOf("p1"), report.input.media.map { it.id })
        // fields that came from the vision description are AI guesses with a low confidence (Review shows them as low confidence)
        for (key in listOf("incident_type", "incident_location", "incident_description", "severity_level")) {
            val e = report.fields.getValue(key)
            assertEquals(key, com.example.test_app.domain.model.FieldSource.AI, e.source)
            assertTrue("$key ${e.confidence}", e.confidence <= 0.5)
        }
        assertEquals("Water Leak", report.fields.getValue("incident_type").value.toString().trim('"'))
    }

    @Test fun aPhotoOnlyReportAsksNothingAndOpensNoMicrophone() = runTest {
        // location is missing and the (empty) narration is thin: with a narration the planner would ask both (ADR-048); with photos only it asks nothing
        val noLocation = """ "Water Leak", "severity": "Medium", "location": null, "datetime": null, "persons_involved": null, "actions_taken": null, "description": "A pipe leaks."}"""
        val engine = FakeEngine(replyFor = { p -> if (p.endsWith(Prompts.EXTRACTION_PREFIX)) noLocation else "}" })
        val mic = CountingIn()
        val out = RecordingOut()
        val m = DialogueManager(
            TestAssets.assets, mic, out, LlmIncidentExtractionService(engine) { TEST_CLOCK },
            DialogueConfig(useLlmRephrase = false), clock = { TEST_CLOCK }, imageAnalysis = FakeAnalysis(),
        )
        val report = (m.run(narration = AsrResult("", 1f, ""), inputMode = com.example.test_app.domain.model.InputMode.PHOTO_ONLY, media = listOf(photo("p1"))) as DialogueOutcome.Review).report
        assertEquals(emptyList<String>(), m.askedFields)
        assertEquals("the microphone is never opened", 0, mic.listens)
        assertEquals("only the read-back statement is spoken", listOf(SpokenKind.READBACK), m.spoken.map { it.kind })
        assertEquals("what the photo cannot show stays flagged for the Review screen", FieldStatus.MISSING_FLAGGED, report.fields.getValue("incident_location").effectiveStatus)
    }

    @Test fun aPhotoOnlyFireAsksNoSafetyQuestionAndSaysNoDeferLineButStillRaisesTheEmergencyFlag() = runTest {
        val fire = """ "Fire", "severity": "Critical", "location": "building exterior", "datetime": null, "persons_involved": null, "actions_taken": null, "description": "A building is on fire."}"""
        val engine = FakeEngine(replyFor = { p -> if (p.endsWith(Prompts.EXTRACTION_PREFIX)) fire else "}" })
        val mic = CountingIn()
        val m = DialogueManager(
            TestAssets.assets, mic, RecordingOut(), LlmIncidentExtractionService(engine) { TEST_CLOCK },
            DialogueConfig(useLlmRephrase = false), clock = { TEST_CLOCK }, imageAnalysis = FakeAnalysis(),
        )
        val report = (m.run(narration = AsrResult("", 1f, ""), inputMode = com.example.test_app.domain.model.InputMode.PHOTO_ONLY, media = listOf(photo("p1"))) as DialogueOutcome.Review).report
        assertEquals(DialogueMode.URGENT, m.currentMode)
        assertTrue(report.dialogue.emergency)
        assertEquals(0, mic.listens)
        assertEquals(listOf(SpokenKind.READBACK), m.spoken.map { it.kind })
    }

    @Test fun aPhotoWithANarrationKeepsTheNormalFlowAndItsQuestions() = runTest {
        val noLocation = """ "Water Leak", "severity": "Medium", "location": null, "datetime": null, "persons_involved": null, "actions_taken": null, "description": "A pipe leaks in the lab."}"""
        val engine = FakeEngine(replyFor = { p -> if (p.endsWith(Prompts.EXTRACTION_PREFIX)) noLocation else "}" })
        val mic = CountingIn()
        val m = DialogueManager(
            TestAssets.assets, mic, RecordingOut(), LlmIncidentExtractionService(engine) { TEST_CLOCK },
            DialogueConfig(useLlmRephrase = false), clock = { TEST_CLOCK }, imageAnalysis = FakeAnalysis(),
        )
        m.run(narration = AsrResult("a pipe is leaking", 1f, "en"), inputMode = com.example.test_app.domain.model.InputMode.VOICE_AND_PHOTO, media = listOf(photo("p1")))
        assertTrue("with a spoken description the usual questions apply: ${m.askedFields}", m.askedFields.isNotEmpty())
        assertTrue(mic.listens > 0)
    }

    private class CountingIn : SpeechIn {
        var listens = 0
        override suspend fun listen(silenceTimeoutSec: Int, maxSeconds: Int): AsrResult? {
            listens++
            return null
        }
    }

    @Test fun aNarrationWithPhotosKeepsTheUserWordsFirst() = runTest {
        val engine = engine()
        manager(engine, FakeAnalysis()).run(media = listOf(photo("p1")))
        val prompt = engine.prompts.first()
        assertTrue(prompt, prompt.contains("<|im_start|>user\na pipe is leaking in the lab\nPHOTO: A pipe is leaking onto the floor (p1)."))
    }

    @Test fun atMostThreeImagesAreAnalysedAndOtherMediaIsIgnored() = runTest {
        val analysis = FakeAnalysis()
        manager(engine(), analysis).run(media = listOf(photo("a"), photo("v", type = "video"), photo("b"), photo("c"), photo("d")))
        assertEquals(listOf("a", "b", "c"), analysis.analysed)
    }
}
