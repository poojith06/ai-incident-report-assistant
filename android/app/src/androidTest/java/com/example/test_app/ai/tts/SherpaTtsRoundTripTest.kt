package com.example.test_app.ai.tts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.test_app.ai.llama.GemmaSpeechToText
import com.example.test_app.ai.llama.LlamaGemmaEngine
import com.example.test_app.ai.whisper.WavPcm
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Round trip for intelligibility: transcribes the WAVs that `SherpaTtsTest` wrote to `files/tts_out/` with the Gemma speech model,
 * the language forced from the file name (`te_1.wav` -> Telugu, `grid_te_sid0_e1.wav` -> Telugu, `hi_sid6.wav` -> Hindi, `en_*` -> English),
 * and logs `TTSRT file | language | asr_ms | text`. The comparison with the source text is done on the PC (`doc/TTS.md`). Needs `files/gemma`.
 * Gemma wants 16 kHz mono; `WavPcm` resamples the 22.05 / 24 kHz TTS output.
 */
@RunWith(AndroidJUnit4::class)
class SherpaTtsRoundTripTest {
    private val names = mapOf(
        "en" to "English", "te" to "Telugu", "ta" to "Tamil", "hi" to "Hindi", "as" to "Assamese", "or" to "Odia",
        "kn" to "Kannada", "ml" to "Malayalam", "gu" to "Gujarati",
    )

    @Test
    fun transcribeSynthesisedClips() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // optional instrumentation argument `-e prefix hi_loc`: only the clips whose file name starts with it
        val prefix = InstrumentationRegistry.getArguments().getString("prefix").orEmpty()
        val wavs = File(ctx.filesDir, "tts_out").listFiles { f -> f.extension == "wav" && f.name.startsWith(prefix) }!!.sortedBy { it.name }
        val engine = LlamaGemmaEngine(ctx)
        for (f in wavs) {
            val code = f.nameWithoutExtension.removePrefix("grid_").substringBefore('_')
            val lang = names[code] ?: continue
            val stt = GemmaSpeechToText(engine) { lang }
            val t = try {
                stt.transcribePcm(WavPcm.read16kMono(f))
            } catch (e: Throwable) {
                Log.e("TTSRT", "${f.name} | $lang | FAILED ${e.javaClass.simpleName}: ${e.message}")
                continue
            }
            Log.i("TTSRT", "${f.name} | $lang | asr_ms=${stt.lastAsrMs} | ${t.text.replace('\n', ' ')}")
        }
        engine.close()
    }
}
