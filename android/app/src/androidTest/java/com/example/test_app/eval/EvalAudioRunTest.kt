package com.example.test_app.eval

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.test_app.AppContainer
import com.example.test_app.ai.AiStatus
import com.example.test_app.mock.SttChoice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Audio eval run on the board without UI automation: the same `AppContainer.runEval(AUDIO)` the developer menu calls, with Real AI
 * (Qwen via Genie) + Whisper CPU. Needs the Qwen bundle, the whisper model and the WAVs in the app's internal files (doc/DEVICE_SETUP.md,
 * eval/README.md). Cases without a recording are skipped by the runner. The developer settings are restored afterwards.
 * Results: `files/eval_runs/<run id>/results.json`; also logged under tag EVALRUN.
 */
@RunWith(AndroidJUnit4::class)
class EvalAudioRunTest {
    @Test
    fun audioRunWithRealAiAndWhisperCpu() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val container = AppContainer(ctx)
        val before = container.devSettings.current
        try {
            container.devSettings.update { it.copy(useRealAi = true, sttChoice = SttChoice.WHISPER_CPU, whisperPlacePrompt = false) }
            container.ensureModelsLoading()
            val status = withTimeout(180_000) { container.aiStatus.first { it is AiStatus.Ready || it is AiStatus.Failed } }
            assertTrue("models not loaded: $status", status is AiStatus.Ready)
            assertTrue("hasRealStt must be true once Whisper CPU is loaded", container.hasRealStt)
            val finished = container.runEval(EvalInput.AUDIO) { p -> Log.i("EVALRUN", "progress ${p.done}/${p.total} ${p.currentId}") }
            finished.lines.forEach { Log.i("EVALRUN", it) }
            Log.i("EVALRUN", "results=${finished.resultsPath}")
            assertTrue(File(finished.resultsPath).isFile)
        } finally {
            container.devSettings.update { before }
        }
    }
}
