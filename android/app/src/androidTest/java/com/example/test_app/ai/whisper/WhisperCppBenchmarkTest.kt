package com.example.test_app.ai.whisper

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.test_app.eval.EvalCases
import com.example.test_app.eval.Wer
import com.example.test_app.eval.WerResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Whisper CPU stopgap benchmark: transcribes the 4 human recordings (eval cases E27..E30) on the board and logs transcript,
 * WER (against the approximate faster-whisper references in cases.json), asr_ms and real-time factor under tag BENCH.
 *
 * Needs `files/whisper/<model>` and `files/eval/audio/test-N.wav` in the app's internal storage (doc/DEVICE_SETUP.md).
 * Arguments (`am instrument -e key value`): `model` (default ggml-small.en.bin), `threads` (default 6), `prompt` (true = place-name
 * prompt), `rounds` (default 2 timed rounds after one warm-up). Run with `am instrument`, NOT connectedAndroidTest (it uninstalls the app).
 */
@RunWith(AndroidJUnit4::class)
class WhisperCppBenchmarkTest {
    @Test
    fun benchmark() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val modelName = args.getString("model") ?: WhisperCppSpeechToText.DEFAULT_MODEL
        val threads = args.getString("threads")?.toInt() ?: WhisperCppSpeechToText.DEFAULT_THREADS
        val prompt = args.getString("prompt") == "true"
        val rounds = args.getString("rounds")?.toInt() ?: 2

        val model = WhisperCppSpeechToText.findModel(ctx, modelName)
        assertTrue("model $modelName not found in ${WhisperCppSpeechToText.candidateFiles(ctx, modelName)}", model != null)
        val cases = EvalCases.parse(ctx.assets.open("eval/cases.json").bufferedReader().use { it.readText() })
            .filter { "human-recording" in it.tags }
        assertTrue("no human-recording cases", cases.isNotEmpty())
        val audioDirs = listOfNotNull(ctx.getExternalFilesDir(null), ctx.filesDir).map { File(it, "eval") }

        val stt = WhisperCppSpeechToText(model!!, threads) { if (prompt) WhisperCppSpeechToText.PLACE_NAMES_PROMPT else null }
        val t0 = System.currentTimeMillis()
        stt.load()
        val loadMs = System.currentTimeMillis() - t0

        val pcms = cases.associate { c ->
            val f = audioDirs.map { File(it, c.audio!!) }.firstOrNull { it.canRead() }
            assertTrue("${c.id}: recording ${c.audio} not readable in $audioDirs", f != null)
            c.id to WavPcm.read16kMono(f!!)
        }
        // Warm-up (first call allocates the compute buffers); not counted.
        stt.transcribePcm(pcms.getValue(cases.last().id))

        val asr = mutableListOf<Long>()
        val rtf = mutableListOf<Double>()
        var total = WerResult.ZERO
        val perCaseWer = mutableListOf<Double>()
        repeat(rounds) { round ->
            for (c in cases) {
                val pcm = pcms.getValue(c.id)
                val t = stt.transcribePcm(pcm)
                val ms = stt.lastAsrMs
                val audioMs = pcm.size / 16L
                val w = Wer.compute(c.transcript!!, t.text)
                asr += ms
                rtf += ms.toDouble() / audioMs
                if (round == 0) {
                    total += w
                    perCaseWer += w.wer
                }
                Log.i(
                    "BENCH",
                    "WHISPER model=$modelName threads=$threads prompt=$prompt round=$round ${c.id} audio_ms=$audioMs asr_ms=$ms rtf=${"%.2f".format(ms.toDouble() / audioMs)} " +
                        "wer=${"%.3f".format(w.wer)} (S${w.substitutions} D${w.deletions} I${w.insertions} N${w.referenceWords}) text=\"${t.text}\" ref=\"${c.transcript}\"",
                )
                assertTrue("${c.id}: empty transcript", t.text.isNotBlank())
            }
        }
        fun med(l: List<Long>) = l.sorted()[l.size / 2]
        Log.i(
            "BENCH",
            "WHISPER SUMMARY model=$modelName threads=$threads prompt=$prompt load_ms=$loadMs n=${asr.size} asr_ms_median=${med(asr)} " +
                "asr_ms_max=${asr.max()} rtf_mean=${"%.2f".format(rtf.average())} corpus_wer=${"%.3f".format(total.wer)} " +
                "mean_case_wer=${"%.3f".format(perCaseWer.average())}",
        )
        stt.close()
    }
}
