package com.example.test_app.ai.tts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.test_app.AppContainer
import com.example.test_app.dialogue.AsrResult
import com.example.test_app.dialogue.DialogueConfig
import com.example.test_app.dialogue.DialogueManager
import com.example.test_app.dialogue.DialogueMode
import com.example.test_app.dialogue.DialogueOutcome
import com.example.test_app.dialogue.FormReadout
import com.example.test_app.dialogue.SpeechIn
import com.example.test_app.domain.model.Clarification
import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.ReportInput
import com.example.test_app.domain.model.TimeSource
import com.example.test_app.domain.model.Validation
import com.example.test_app.domain.model.boolValue
import com.example.test_app.domain.model.textValue
import com.example.test_app.domain.service.ExtractionRequest
import com.example.test_app.domain.service.IncidentExtractionService
import com.example.test_app.domain.service.IncidentJsonCodec
import com.example.test_app.platform.TtsSpeechOut
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A whole session through the app's own code (real `AppContainer`, `DialogueManager`, `TtsSpeechOut`, `FormReadout`, the Sherpa service):
 * the narration is scripted text with the language the speech model reports, everything the app then SPEAKS goes to the real TTS, whose
 * log lines (tag `SherpaTts`: seg, engine, lang, sid, text) show which voice each piece used. The spoken lines are logged under `TTSLOC`.
 * The read-out lines are also rendered to `files/tts_out/<hi|te|en>_loc_*.wav` (+ `.txt` with the text) for the Gemma round trip
 * (`SherpaTtsRoundTripTest`; `TTS.md`). Needs the voice files and the dialogue assets of the app.
 */
@RunWith(AndroidJUnit4::class)
class LocalizedSpeechTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = AppContainer(ctx)
    private val outDir = File(ctx.filesDir, "tts_out").apply { mkdirs() }

    private class ScriptIn(script: List<AsrResult?>) : SpeechIn {
        private val queue = ArrayDeque(script)
        override suspend fun listen(silenceTimeoutSec: Int, maxSeconds: Int): AsrResult? = queue.removeFirstOrNull()
    }

    /** Returns the same report JSON for every extraction call: a water leak with a location, so one or two questions are still asked. */
    private class FixedExtraction(private val withLocation: Boolean) : IncidentExtractionService {
        override suspend fun extract(request: ExtractionRequest): String = IncidentJsonCodec.encode(
            IncidentReport(
                reportId = "INC-2026-000731", status = IncidentReport.STATUS_NEEDS_CLARIFICATION, createdAt = "2026-10-11T09:30:00+05:30",
                input = ReportInput("voice", request.originalTranscript),
                fields = mapOf(
                    "incident_type" to FieldEntry(textValue("Water Leak"), 0.9),
                    "severity_level" to FieldEntry(textValue("Medium"), 0.9),
                    "incident_description" to FieldEntry(textValue("Water is leaking from the ceiling."), 0.9),
                    "incident_location" to if (withLocation) FieldEntry(textValue("KRB washroom, third floor"), 0.9) else FieldEntry(),
                ),
            ),
        )
    }

    private fun session(tag: String, narration: String, language: String, withLocation: Boolean) = runBlocking {
        val out = TtsSpeechOut(container.textToSpeech, onUnavailable = { Log.e(LOC, "$tag: TTS unavailable") }, pauseMs = 0)
        val manager = DialogueManager(
            container.dialogueAssets, ScriptIn(listOf(AsrResult(narration, 0.9f, language), null, null, null)), out,
            FixedExtraction(withLocation), DialogueConfig(useLlmRephrase = false),
        )
        val report = (manager.run() as DialogueOutcome.Review).report
        val pack = container.dialogueAssets.pack(report.input.language)
        for (l in manager.spoken) Log.i(LOC, "$tag dialogue ${l.kind} pack=${pack.code} text=${l.text}")
        // what IncidentFlowViewModel does after the dialogue: the form read-out on Review and the confirmation after Submit
        val review = FormReadout.review(report, pack)
        val submitted = FormReadout.submitted(report, pack)
        Log.i(LOC, "$tag review pack=${pack.code} text=$review")
        container.textToSpeech.speak(review, pack.code)
        Log.i(LOC, "$tag submitted pack=${pack.code} text=$submitted")
        container.textToSpeech.speak(submitted, pack.code)
        Triple(report, pack, manager.spoken)
    }

    @Test
    fun hindiSessionIsSpokenInHindi() {
        val (report, pack, spoken) = session("hi", "तीसरी मंजिल पर पानी लीक हो रहा है", "hindi", withLocation = true)
        assertEquals("hindi", report.input.language)
        assertEquals("hi", pack.code)
        assertTrue(spoken.isNotEmpty())
    }

    @Test
    fun teluguSessionIsSpokenInTelugu() {
        val (report, pack, _) = session("te", "మూడవ అంతస్తులో నీటి లీక్ అవుతోంది", "telugu", withLocation = true)
        assertEquals("telugu", report.input.language)
        assertEquals("te", pack.code)
    }

    @Test
    fun tamilSessionHasNoPackAndIsSpokenInEnglish() {
        val (_, pack, _) = session("ta", "மூன்றாவது மாடியில் தண்ணீர் கசிகிறது", "tamil", withLocation = true)
        assertEquals("en", pack.code)
    }

    @Test
    fun englishSessionIsUnchanged() {
        val (_, pack, _) = session("en", "There is a water leak on the third floor", "en", withLocation = true)
        assertEquals("en", pack.code)
    }

    /** Renders the localized lines to WAV (the same segments, voices and pauses as the service) for the round trip. */
    @Test
    fun renderLocalizedLinesToWav() {
        val assets = container.dialogueAssets
        val synth = SherpaSynthesizer(SherpaModelFiles(File(ctx.filesDir, "tts")))
        fun report(withLocation: Boolean, severity: String, time: String?) = IncidentReport(
            reportId = "INC-2026-665948", status = "x", createdAt = "2026-10-11T09:30:00+05:30", input = ReportInput("voice", "x"),
            fields = buildMap {
                put("incident_type", FieldEntry(textValue("Water Leak"), 0.9))
                put("severity_level", FieldEntry(textValue(severity), 0.9))
                put("injury", FieldEntry(boolValue(false), 0.9))
                if (withLocation) put("incident_location", FieldEntry(textValue("KRB washroom, third floor"), 0.9))
                if (time != null) put("date_time", FieldEntry(textValue(time), 0.9))
            },
            validation = Validation(), clarification = Clarification(), timeSource = if (time != null) TimeSource.SPOKEN else TimeSource.DEFAULT_NOW,
        )
        for (code in listOf("hi", "te")) {
            val pack = assets.pack(code)
            val withLoc = report(true, "Medium", "2026-10-11T15:30:00+05:30")
            val noLoc = report(false, "Low", "2026-10-11T10:00:00+05:30")
            val lines = linkedMapOf(
                "${code}_loc_review_en_location" to FormReadout.review(withLoc, pack),
                "${code}_loc_review_clean" to FormReadout.review(noLoc, pack),
                "${code}_loc_submitted" to FormReadout.submitted(noLoc, pack),
                "${code}_loc_question" to pack.bank.question("location", DialogueMode.PLAIN),
                "${code}_loc_safety" to pack.bank.line("safety_check"),
                "${code}_loc_readback" to pack.bank.line("readback_plain").replace("{incident_type}", pack.glossary.incidentType("Water Leak")!!)
                    .replace("{location}", "KRB washroom, third floor").replace("{time_phrase}", FormReadout.clockPhrase("2026-10-11T10:00:00+05:30", pack.readout, pack.glossary)),
            )
            for ((id, text) in lines) render(synth, id, text, pack.code)
        }
        synth.release()
    }

    private fun render(synth: SherpaSynthesizer, id: String, text: String, hint: String) {
        val plans = SherpaVoices.segments(text, hint)
        val rate = 24000
        val pcm = ArrayList<FloatArray>()
        var synthMs = 0L
        for ((i, plan) in plans.withIndex()) {
            val st = synth.synthesize(plan, 1.0f) { a, r -> pcm += SherpaVoices.resample(a, r, rate); true }
            synthMs += st.synthMs
            if (plan.pauseAfterMs > 0 && i < plans.lastIndex) pcm += FloatArray(rate * plan.pauseAfterMs / 1000)
        }
        val all = FloatArray(pcm.sumOf { it.size }).also { out -> var o = 0; for (p in pcm) { p.copyInto(out, o); o += p.size } }
        val peak = all.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        writeWav(File(outDir, "$id.wav"), all, rate)
        File(outDir, "$id.txt").writeText(text, Charsets.UTF_8)
        Log.i(LOC, "render id=$id segments=${plans.map { "${it.engine}/${it.language}" }} synth_ms=$synthMs audio_s=${"%.2f".format(all.size / rate.toDouble())} peak=${"%.3f".format(peak)} text=$text")
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

    private companion object {
        const val LOC = "TTSLOC"
    }
}
