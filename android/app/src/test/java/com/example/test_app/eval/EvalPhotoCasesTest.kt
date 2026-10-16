package com.example.test_app.eval

import com.example.test_app.dialogue.DialogueConfig
import com.example.test_app.dialogue.ScriptExtraction
import com.example.test_app.dialogue.TEST_CLOCK
import com.example.test_app.dialogue.TestAssets
import com.example.test_app.dialogue.reportJson
import com.example.test_app.domain.model.MediaItem
import com.example.test_app.domain.service.ImageAnalysisService
import com.example.test_app.domain.service.ImageHint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Photo-only eval cases (`images` in a case, ADR-062) through the real dialogue: no speech, no question, the severity floor row. */
class EvalPhotoCasesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val case = EvalCases.parse(
        """[{"id": "P1", "images": ["images/pipe.jpg"], "tags": ["photo-only"],
            "expected": {"fields": {"incident_type": "Water Leak"}, "severity_min": "Medium",
                         "empty_fields": ["persons_involved"], "asked_fields": []}}]""",
    )

    private class FakeAnalysis : ImageAnalysisService {
        val seen = mutableListOf<String>()
        override suspend fun analyze(media: MediaItem): ImageHint {
            seen += media.id
            return ImageHint(media.id, listOf("pipe"), "Water sprays from a pipe in a trench.")
        }
    }

    private fun root(withImage: Boolean = true): File = tmp.newFolder().also {
        if (withImage) File(it, "images").mkdirs().also { _ -> File(it, "images/pipe.jpg").writeBytes(byteArrayOf(1, 2, 3)) }
    }

    private fun runner(severity: String, analysis: ImageAnalysisService?, imageRoot: File?) = EvalRunner(
        assets = TestAssets.assets,
        extraction = ScriptExtraction(listOf(reportJson("", "incident_type" to "Water Leak", "severity_level" to severity, "incident_description" to "A pipe sprays water."))),
        rephraser = null, stt = null, dialogueConfig = DialogueConfig(useLlmRephrase = false), clock = { TEST_CLOCK },
        imageAnalysis = analysis, imageRoot = imageRoot,
    )

    @Test fun aPhotoOnlyCaseRunsWithoutSpeechAndAsksNothing() = runTest {
        val analysis = FakeAnalysis()
        val r = runner("High", analysis, root()).run(case, EvalInput.TRANSCRIPT, null, EvalMeta()).cases.single()
        assertEquals("detail: ${r.detail}", "ok", r.status)
        assertEquals(listOf("pipe"), analysis.seen)
        assertEquals(emptyList<String>(), r.asked)
        assertEquals(true, r.askedOk)
        assertTrue("no utterance was listened for", r.utterances.isEmpty())
        assertEquals(emptyList<String>(), r.invariantViolations)
        assertEquals(listOf("incident_type", "severity_level"), r.fields.map { it.field })
        assertEquals(listOf("CORRECT", "CORRECT"), r.fields.map { it.placement })
        assertEquals(">= Medium", r.fields.last().expected)
        assertEquals(listOf(null), r.emptyChecks.map { it.actual })
    }

    @Test fun aSeverityBelowTheFloorIsWrongAndAHigherOneIsFine() = runTest {
        val low = runner("Low", FakeAnalysis(), root()).run(case, EvalInput.TRANSCRIPT, null, EvalMeta()).cases.single()
        val sev = low.fields.last()
        assertEquals("NONE", sev.match)
        assertEquals("WRONG", sev.placement)
        val critical = runner("Critical", FakeAnalysis(), root()).run(case, EvalInput.TRANSCRIPT, null, EvalMeta()).cases.single()
        assertEquals("EXACT", critical.fields.last().match)
    }

    @Test fun withoutPhotoAnalysisOrWithoutTheImageTheCaseIsSkippedNotFailed() = runTest {
        val noService = runner("High", null, root()).run(case, EvalInput.TRANSCRIPT, null, EvalMeta()).cases.single()
        assertEquals("skipped", noService.status)
        val noRoot = runner("High", FakeAnalysis(), null).run(case, EvalInput.TRANSCRIPT, null, EvalMeta()).cases.single()
        assertEquals("skipped", noRoot.status)
        val noFile = runner("High", FakeAnalysis(), root(withImage = false)).run(case, EvalInput.TRANSCRIPT, null, EvalMeta()).cases.single()
        assertEquals("skipped", noFile.status)
        assertTrue(noFile.detail!!, noFile.detail!!.contains("images/pipe.jpg"))
    }
}
