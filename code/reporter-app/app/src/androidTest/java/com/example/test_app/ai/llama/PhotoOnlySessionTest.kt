package com.example.test_app.ai.llama

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.test_app.AppContainer
import com.example.test_app.ai.LlmIncidentExtractionService
import com.example.test_app.dialogue.AsrResult
import com.example.test_app.dialogue.DialogueConfig
import com.example.test_app.dialogue.DialogueManager
import com.example.test_app.dialogue.DialogueOutcome
import com.example.test_app.dialogue.SpeechIn
import com.example.test_app.dialogue.SpeechOut
import com.example.test_app.domain.config.IncidentConfig
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.InputMode
import com.example.test_app.domain.model.MediaItem
import com.example.test_app.domain.model.displayText
import com.example.test_app.domain.service.DefaultValidationService
import com.example.test_app.domain.service.IncidentPolicies
import com.example.test_app.knowledgegraph.AnalysisResult
import com.example.test_app.platform.DialogueAssetsLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.OffsetDateTime

/**
 * Photo-only sessions through the REAL dialogue and the real Gemma pipeline (ADR-062): for every image of `files/images_test/` one
 * session with no narration. Asserts the session is silent and mic-free (no `listen`, nothing spoken, no question asked) and logs
 * under tag `PHOTOLAB` the report fields with confidence and status, the validation result, wall time and the post-incident analysis
 * (does the knowledge graph abstain on an image-only report?). `-e only name` filters the images.
 */
@RunWith(AndroidJUnit4::class)
class PhotoOnlySessionTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private class NoMicIn : SpeechIn {
        var listens = 0
        override suspend fun listen(silenceTimeoutSec: Int, maxSeconds: Int): AsrResult? {
            listens++
            return null
        }
    }

    private class CountingOut : SpeechOut {
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String, language: String) {
            spoken += text
        }
    }

    @Test
    fun photoOnlySessions() = runBlocking {
        val only = InstrumentationRegistry.getArguments().getString("only").orEmpty()
        val files = File(ctx.filesDir, "images_test").listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") && f.name.contains(only) }!!.sortedBy { it.name }
        val engine = LlamaGemmaEngine(ctx)
        val assets = DialogueAssetsLoader.load(ctx)
        val container = AppContainer(ctx)
        engine.load()
        for (f in files) {
            val media = MediaItem(f.nameWithoutExtension, "image", f.toURI().toString(), OffsetDateTime.now().toString())
            val mic = NoMicIn()
            val out = CountingOut()
            val manager = DialogueManager(
                assets, mic, out, LlmIncidentExtractionService(engine), DialogueConfig(useLlmRephrase = false),
                incidentConfig = IncidentConfig(), imageAnalysis = GemmaImageAnalysis(engine),
            )
            val t0 = System.currentTimeMillis()
            val outcome = manager.run(AsrResult("", 1f, ""), InputMode.PHOTO_ONLY, listOf(media)) as DialogueOutcome.Review
            val ms = System.currentTimeMillis() - t0
            val report = DefaultValidationService().validate(
                IncidentPolicies.apply(outcome.report, cues = assets.severityCues), IncidentConfig(),
            )
            val fields = IncidentField.entries.joinToString(" | ") { fld ->
                val e = report.fields[fld.key]
                "${fld.key}=${e?.displayText(fld)?.ifBlank { null } ?: "-"}(${"%.2f".format(e?.confidence ?: 0.0)}/${e?.effectiveStatus})"
            }
            Log.i(TAG, "${f.name} SESSION ms=$ms listens=${mic.listens} spoken=${out.spoken.size} asked=${manager.askedFields} mode=${manager.currentMode} status=${report.status} missing=${report.validation.missingFields} lowConf=${report.validation.lowConfidenceFields} | $fields")
            val analysis = container.knowledgeGraph.analyze(report.copy(status = IncidentReport.STATUS_SUBMITTED), emptyList()) // only a submitted report counts as confirmed
            Log.i(
                TAG,
                "${f.name} KG " + when (analysis) {
                    is AnalysisResult.Done -> "done abstained=${analysis.set.abstained} immediate=${analysis.set.immediateActions.size} ops=${analysis.set.operationalSteps.size} " +
                        "teams=${analysis.set.responsibleTeams.size} unresolved=${analysis.set.unresolvedQuestions.map { it.toString().take(80) }} warnings=${analysis.set.warnings} " +
                        "first=${analysis.set.immediateActions.firstOrNull()?.toString()?.take(120)}"
                    is AnalysisResult.Failed -> "failed ${analysis.message}"
                },
            )
            assertEquals("no microphone in a photo-only session", 0, mic.listens)
            assertTrue("nothing is asked", manager.askedFields.isEmpty())
            assertTrue("nothing is spoken before the read-back: ${out.spoken}", out.spoken.size <= 1)
        }
        engine.close()
    }

    private companion object {
        const val TAG = "PHOTOLAB"
    }
}
