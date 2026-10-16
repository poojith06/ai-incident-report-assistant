package com.example.test_app.ai.tts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.test_app.domain.service.TextToSpeechService
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Sherpa TTS on the device (models in `files/tts/piper-amy` and `files/tts/rasa`, `doc/DEVICE_SETUP.md` section 9). Synthesises a fixed
 * sentence set, writes every WAV to `files/tts_out/` (pull: `doc/TTS.md`) and logs one `TTSLAB` line per clip: engine, language,
 * model load ms, synth ms, audio seconds, real-time factor, peak, RMS and the fraction of near-silent samples. Nobody listens to
 * the audio here: `SherpaTtsRoundTripTest` and the PC faster-whisper run check intelligibility.
 */
@RunWith(AndroidJUnit4::class)
class SherpaTtsTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val ttsDir = File(ctx.filesDir, "tts")
    private val outDir = File(ctx.filesDir, "tts_out").apply { mkdirs() }
    private val files = SherpaModelFiles(ttsDir)

    /** One clip to make: [id] names the WAV; [hint] is the language name the speech model would report. */
    data class Clip(val id: String, val text: String, val hint: String?, val sid: Int? = null, val emotion: Int = 0)

    @Test
    fun modelFilesArePresent() {
        assertTrue("files/tts/piper-amy incomplete", files.present(TtsModel.PIPER))
        assertTrue("files/tts/rasa incomplete", files.present(TtsModel.RASA))
    }

    @Test
    fun sentenceSet() {
        val synth = SherpaSynthesizer(files)
        for (c in ENGLISH + INDIC) lab(synth, c)
        synth.release()
    }

    /** Rasa on one Telugu sentence: trained speakers and untrained ones, three emotions. */
    @Test
    fun rasaSpeakersAndEmotions() {
        val synth = SherpaSynthesizer(files)
        for (sid in listOf(0, 1, 19, 100, 500)) for (e in listOf(0, 1, 5)) {
            lab(synth, Clip("grid_te_sid${sid}_e$e", TELUGU_FIRE, "telugu", sid, e))
        }
        synth.release()
    }

    /** Hindi has no trained Rasa speaker and neither have Gujarati and Odia: which borrowed speaker works? */
    @Test
    fun speakersForLanguagesWithoutOne() {
        val synth = SherpaSynthesizer(files)
        for (sid in listOf(4, 6, 10, 12, 14, 17)) lab(synth, Clip("hi_sid$sid", HINDI_FIRE, "hindi", sid))
        for (sid in listOf(0, 2, 3, 8, 12, 19)) lab(synth, Clip("or_sid$sid", ODIA_FIRE, "odia", sid))
        for (sid in listOf(0, 2, 12, 15, 19)) lab(synth, Clip("gu_sid$sid", GUJARATI_FIRE, "gujarati", sid))
        synth.release()
    }

    /** Real-time factor against the thread count (the default is [SherpaSynthesizer.DEFAULT_THREADS]). */
    @Test
    fun threadScaling() {
        for (threads in listOf(1, 2, 4, 6)) {
            val synth = SherpaSynthesizer(files, threads)
            for (c in listOf(ENGLISH[0], INDIC[0])) {
                lab(synth, c.copy(id = "threads${threads}_${c.id}"), warm = true, tag = "threads=$threads")
            }
            synth.release()
        }
    }

    /** Diagnostic only (needs `files/tts_diag/model.onnx` + `tokens.txt`, the fp32 Rasa file): speed of fp32 against the int8 default, by thread count. */
    @Test
    fun rasaFp32Diagnostic() {
        val dir = File(ctx.filesDir, "tts_diag")
        org.junit.Assume.assumeTrue(File(dir, "model.onnx").isFile)
        for (threads in listOf(1, 4)) {
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(model = File(dir, "model.onnx").path, tokens = File(dir, "tokens.txt").path),
                    numThreads = threads,
                ),
            )
            val t0 = System.currentTimeMillis()
            val tts = OfflineTts(config = cfg)
            val loadMs = System.currentTimeMillis() - t0
            for (chunk in SherpaVoices.rasaChunks(TELUGU_FIRE)) {
                val t1 = System.currentTimeMillis()
                val audio = tts.generateWithConfig(chunk, GenerationConfig(sid = 19, extra = mapOf("emotion_id" to "0")))
                val ms = System.currentTimeMillis() - t1
                val sec = audio.samples.size.toDouble() / audio.sampleRate
                Log.i(LAB, "FP32 threads=$threads load_ms=$loadMs synth_ms=$ms audio_s=${"%.2f".format(sec)} rtf=${"%.3f".format(ms / 1000.0 / sec)}")
            }
            tts.release()
        }
    }

    /** The app's own wiring: with the files present the container picks Sherpa, and `textToSpeech` speaks through it (English, then Telugu with the language name). */
    @Test
    fun containerPicksSherpaAndSpeaks() = runBlocking {
        val container = com.example.test_app.AppContainer(ctx)
        assertEquals(com.example.test_app.mock.TtsEngine.SHERPA, container.effectiveTts())
        container.textToSpeech.setSpeechRate(1.25f)
        val t0 = System.currentTimeMillis()
        container.textToSpeech.speak("Report submitted. Reference I N C, 2026, 6 6 5 9 4 8.", "en")
        container.textToSpeech.speak(TELUGU_FIRE, "telugu")
        Log.i(TAG, "container speak en + te at rate 1.25: ${System.currentTimeMillis() - t0} ms wall")
        container.textToSpeech.shutdown()
    }

    /** The real service: plays through the AudioTrack, returns when the audio is over, stops on request, falls back for uncovered text. */
    @Test
    fun serviceSpeaksStopsAndFallsBack() = runBlocking {
        val fallback = CountingTts()
        val service = SherpaTtsService(files, fallback)
        assertTrue(service.available())
        val clip = ENGLISH[0].text
        val t0 = System.currentTimeMillis()
        service.speak(clip, "en")
        val spokenMs = System.currentTimeMillis() - t0
        Log.i(TAG, "service speak english: ${spokenMs} ms wall (includes model load and playback)")
        assertEquals("English must not use the fallback", 0, fallback.spoken.size)

        service.speak(TELUGU_FIRE, "telugu")
        assertEquals(0, fallback.spoken.size)

        service.speak("\u0645\u0631\u062d\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645", "arabic")
        assertEquals("Arabic has no Sherpa voice: the fallback speaks", 1, fallback.spoken.size)

        val long = async(start = CoroutineStart.UNDISPATCHED) { service.speak(ENGLISH[5].text, "en") }
        delay(1500)
        val s0 = System.currentTimeMillis()
        service.stop()
        long.await()
        val stopMs = System.currentTimeMillis() - s0
        Log.i(TAG, "service stop latency: $stopMs ms")
        assertTrue("stop() took $stopMs ms", stopMs < 1500)
        service.shutdown()
    }

    private class CountingTts : TextToSpeechService {
        val spoken = ArrayList<String>()
        override suspend fun speak(text: String) { spoken += text }
        override fun stop() = Unit
        override fun shutdown() = Unit
    }

    private fun lab(synth: SherpaSynthesizer, c: Clip, warm: Boolean = false, tag: String = "") {
        val plan = SherpaVoices.plan(c.text, c.hint, RasaVoiceChoice(c.sid, c.emotion))
        if (plan.engine == VoiceEngine.SYSTEM) {
            Log.i(LAB, "id=${c.id} engine=SYSTEM lang=${plan.language} skipped (no sherpa voice) text=${c.text}")
            return
        }
        val pcm = ArrayList<FloatArray>()
        val rate: Int
        var stats = try {
            synth.synthesize(plan, 1.0f) { a, _ -> pcm += a; true }
        } catch (e: Throwable) {
            Log.e(LAB, "id=${c.id} engine=${plan.engine} lang=${plan.language} sid=${plan.speakerId} emo=${plan.emotionId} FAILED ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        if (warm) { // second run on the loaded model: the timing without the load
            val loadMs = stats.loadMs
            pcm.clear()
            stats = synth.synthesize(plan, 1.0f) { a, _ -> pcm += a; true }.copy(loadMs = loadMs)
        }
        rate = stats.sampleRate
        val all = FloatArray(pcm.sumOf { it.size }).also { out -> var o = 0; for (p in pcm) { p.copyInto(out, o); o += p.size } }
        val peak = all.maxOfOrNull { abs(it) } ?: 0f
        val rms = if (all.isEmpty()) 0.0 else sqrt(all.sumOf { (it * it).toDouble() } / all.size)
        val silent = if (all.isEmpty()) 1.0 else all.count { abs(it) < SILENT } / all.size.toDouble()
        val rtf = if (stats.audioSeconds > 0) stats.synthMs / 1000.0 / stats.audioSeconds else Double.NaN
        writeWav(File(outDir, "${c.id}.wav"), all, rate)
        Log.i(
            LAB,
            "id=${c.id} engine=${plan.engine} lang=${plan.language} sid=${plan.speakerId} emo=${plan.emotionId} rate=$rate $tag load_ms=${stats.loadMs} " +
                "synth_ms=${stats.synthMs} audio_s=${"%.2f".format(stats.audioSeconds)} rtf=${"%.3f".format(rtf)} peak=${"%.3f".format(peak)} " +
                "rms=${"%.4f".format(rms)} silent=${"%.3f".format(silent)} chunks=${plan.chunks.size} text=${c.text}",
        )
    }

    private fun writeWav(file: File, samples: FloatArray, rate: Int) {
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) pcm.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        header.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.capacity())
        file.writeBytes(ByteArrayOutputStream().apply { write(header.array()); write(pcm.array()) }.toByteArray())
    }

    companion object {
        const val LAB = "TTSLAB"
        private const val TAG = "SherpaTtsTest"
        private const val SILENT = 0.01f

        const val TELUGU_FIRE = "మూడవ అంతస్తులో మంటలు చెలరేగాయి. దయచేసి వెంటనే సహాయం పంపండి."
        const val HINDI_FIRE = "तीसरी मंजिल पर आग लगी है। कृपया तुरंत मदद भेजें।"
        const val ODIA_FIRE = "ତୃତୀୟ ମହଲାରେ ନିଆଁ ଲାଗିଛି। ଦୟାକରି ଶୀଘ୍ର ସାହାଯ୍ୟ ପଠାନ୍ତୁ।"
        const val GUJARATI_FIRE = "ત્રીજા માળે આગ લાગી છે. કૃપા કરીને તરત મદદ મોકલો."

        /** English lines: an incident description and the app's own lines (`question_bank.en.json`, `FormReadout`). */
        val ENGLISH = listOf(
            Clip("en_1", "Fire reported on the third floor of the K R B building. Severity high.", "en"),
            Clip("en_2", "Hi, describe your incident.", "en"),
            Clip("en_3", "Are you somewhere safe right now?", "en"),
            Clip("en_4", "Logged: water leak, K R B building, today at 9:30 a.m. Please review and tap submit.", "en"),
            Clip("en_5", "Your report is ready. Fire, at the third floor, today at 9:30 a.m. Severity high. Still missing: injury type. Please review the highlighted fields and tap submit.", "en"),
            Clip("en_6", "Report submitted. Reference I N C, 2026, 6 6 5 9 4 8. You can call the emergency contacts on screen.", "en"),
        )

        /** One sentence per language, same meaning ("Fire on the third floor. Please send help immediately."), plus the Telugu water-leak line. */
        val INDIC = listOf(
            Clip("te_1", TELUGU_FIRE, "telugu"),
            Clip("te_2", "కె.ఆర్. బిల్డింగ్ లో నీటి లీక్ అవుతోంది", "telugu"),
            Clip("ta_1", "மூன்றாவது மாடியில் தீ விபத்து ஏற்பட்டுள்ளது. தயவுசெய்து உடனடியாக உதவி அனுப்புங்கள்.", "tamil"),
            Clip("hi_1", HINDI_FIRE, "hindi"),
            Clip("as_1", "তৃতীয় মহলাত জুই লাগিছে। অনুগ্ৰহ কৰি সোনকালে সহায় পঠিয়াওক।", "assamese"),
            Clip("or_1", ODIA_FIRE, "odia"),
            Clip("kn_1", "ಮೂರನೇ ಮಹಡಿಯಲ್ಲಿ ಬೆಂಕಿ ಅವಘಡ ಸಂಭವಿಸಿದೆ. ದಯವಿಟ್ಟು ತಕ್ಷಣ ಸಹಾಯ ಕಳುಹಿಸಿ.", "kannada"),
            Clip("ml_1", "മൂന്നാം നിലയിൽ തീപിടുത്തം ഉണ്ടായി. ദയവായി ഉടൻ സഹായം അയയ്ക്കുക.", "malayalam"),
        )
    }
}
