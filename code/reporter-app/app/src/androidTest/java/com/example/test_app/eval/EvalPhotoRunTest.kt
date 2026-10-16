package com.example.test_app.eval

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.test_app.AppContainer
import com.example.test_app.ai.AiStatus
import com.example.test_app.dialogue.DialogueConfig
import com.example.test_app.mock.AiBackend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The photo-only cases (`P01`..`P06` of `assets/eval/cases.json`, tag `photo-only`) through the real pipeline: Real AI with the Gemma
 * backend (describe -> extract), no speech, no questions. Needs the Gemma model and the images in `files/eval/images/` (copy:
 * `doc/TESTING.md` "Photo-only reports"). Results: `files/eval_runs/<run id>/results.json`; per case logged under tag `EVALPHOTO`.
 * The developer settings are restored afterwards.
 */
@RunWith(AndroidJUnit4::class)
class EvalPhotoRunTest {
    @Test
    fun photoOnlyCasesOnGemma() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val container = AppContainer(ctx)
        val before = container.devSettings.current
        try {
            val cpu = InstrumentationRegistry.getArguments().getString("cpu") == "true" // -e cpu true: image encoder on the CPU (ADR-062)
            container.devSettings.update { it.copy(useRealAi = true, aiBackend = AiBackend.GEMMA, gemmaOneCall = false, gemmaEncoderOnCpu = cpu) }
            container.ensureModelsLoading()
            val status = withTimeout(300_000) { container.aiStatus.first { it is AiStatus.Ready || it is AiStatus.Failed } }
            assertTrue("models not loaded: $status", status is AiStatus.Ready)
            val cases = EvalCases.parse(ctx.assets.open("eval/cases.json").bufferedReader(Charsets.UTF_8).use { it.readText() }).filter { it.photoOnly }
            val runner = EvalRunner(
                assets = container.dialogueAssets, extraction = container.extraction, rephraser = null, stt = null,
                dialogueConfig = DialogueConfig(useLlmRephrase = false), incidentConfig = container.incidentConfig(),
                imageAnalysis = container.photoAnalysis(), imageRoot = container.evalDir,
            )
            val run = runner.run(cases, EvalInput.TRANSCRIPT, container.evalDir, EvalMeta(aiMode = "real", llmModel = "Gemma 4 E2B"))
            for (r in run.cases) {
                Log.i(
                    "EVALPHOTO",
                    "${r.id} status=${r.status} detail=${r.detail} asked=${r.asked} total_ms=${r.totalMs} llm=${r.llmCalls.map { "${it.kind}:${it.ms}" }} " +
                        "fields=${r.fields.map { "${it.field}: expected ${it.expected} got ${it.actual} (${it.match}/${it.placement})" }} " +
                        "empty=${r.emptyChecks.map { "${it.field}=${it.actual}" }} violations=${r.invariantViolations}",
                )
            }
            val dir = EvalReport.write(run, container.evalRunsDir)
            Log.i("EVALPHOTO", "headline=${EvalReport.headline(run)} results=${File(dir, "results.json").absolutePath}")
        } finally {
            container.devSettings.update { before }
        }
    }
}
