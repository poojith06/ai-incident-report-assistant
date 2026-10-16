package com.example.test_app.ai.llama

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.test_app.ai.whisper.WavPcm
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Transcribes the multilingual WAV samples in the app's `files/lang` folder with the Gemma STT and logs the language name,
 * transcript and timing under tag LANG (see doc/TESTING.md). Needs the Gemma model in `files/gemma`.
 */
@RunWith(AndroidJUnit4::class)
class GemmaLanguageTest {
    @Test
    fun transcribeLanguageSamples() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = LlamaGemmaEngine(ctx)
        val stt = GemmaSpeechToText(engine)
        val files = File(ctx.filesDir, "lang").listFiles { f -> f.extension == "wav" }!!.sortedBy { it.name }
        for (f in files) {
            val t = stt.transcribePcm(WavPcm.read16kMono(f))
            Log.i("LANG", "${f.name} | language='${t.language}' | asr_ms=${stt.lastAsrMs} | text=${t.text}")
        }
        // Forced language per clip (file name suffix after test_), the Settings > Spoken language path.
        for (f in files) {
            val lang = f.nameWithoutExtension.removePrefix("test_").replaceFirstChar { it.uppercase() }
            if (lang.startsWith("test-")) continue
            val forced = GemmaSpeechToText(engine) { lang }
            val t = forced.transcribePcm(WavPcm.read16kMono(f))
            Log.i("LANG", "FORCED ${f.name} | language='${t.language}' | asr_ms=${forced.lastAsrMs} | text=${t.text}")
        }
        engine.close()
    }
}
