package com.example.test_app.ai.llama

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.test_app.ai.LlmIncidentExtractionService
import com.example.test_app.ai.LlmOutputParser
import com.example.test_app.ai.Prompts
import com.example.test_app.domain.config.IncidentConfig
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.InputMode
import com.example.test_app.domain.model.MediaItem
import com.example.test_app.domain.model.displayText
import com.example.test_app.domain.service.ExtractionRequest
import com.example.test_app.domain.service.IncidentJsonCodec
import com.example.test_app.domain.service.IncidentPolicies
import com.example.test_app.platform.DialogueAssetsLoader
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.OffsetDateTime

/**
 * Photo-only reports on the real Gemma pipeline (describe, then extract; and the one-call image path), per image of `files/images_test/`
 * (push: `doc/TESTING.md` "Photo-only reports"). Logs under tag `PHOTOLAB`: the description, the fields, confidences and wall times.
 * Optional instrumentation argument `-e only brokenpipe` limits the run to file names containing the text.
 */
@RunWith(AndroidJUnit4::class)
class PhotoReportLabTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.filesDir, "images_test")

    private fun images(): List<File> {
        val only = InstrumentationRegistry.getArguments().getString("only").orEmpty()
        return dir.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") && f.name.contains(only) }!!.sortedBy { it.name }
    }

    private fun summary(r: IncidentReport): String = listOf(
        IncidentField.INCIDENT_TYPE, IncidentField.SEVERITY_LEVEL, IncidentField.INCIDENT_LOCATION, IncidentField.INCIDENT_DESCRIPTION,
        IncidentField.PERSONS_INVOLVED, IncidentField.INJURY, IncidentField.INJURY_TYPE, IncidentField.IMMEDIATE_ACTIONS_TAKEN, IncidentField.EQUIPMENT_ASSET_INVOLVED,
    ).joinToString(" | ") { f ->
        val e = r.fields[f.key]
        "${f.key}=${e?.displayText(f)?.ifBlank { null } ?: "-"}(${"%.2f".format(e?.confidence ?: 0.0)}${e?.status?.let { s -> "/$s" } ?: ""})"
    }

    @Test
    fun describeThenExtract() = runBlocking {
        // `-e mm none` runs the image encoder on the CPU instead of the Adreno GPU (diagnostic for the run-to-run variation)
        val mm = InstrumentationRegistry.getArguments().getString("mm") ?: LlamaGemmaConfig().mmprojDevice
        val engine = LlamaGemmaEngine(ctx, LlamaGemmaConfig(mmprojDevice = mm))
        val assets = DialogueAssetsLoader.load(ctx)
        val analysis = GemmaImageAnalysis(engine)
        val extraction = LlmIncidentExtractionService(engine)
        val t0 = System.currentTimeMillis()
        engine.load()
        Log.i(TAG, "model loaded in ${System.currentTimeMillis() - t0} ms")
        val repeat = InstrumentationRegistry.getArguments().getString("repeat")?.toIntOrNull() ?: 1
        for (run in 1..repeat) for (f in images()) {
            val media = MediaItem(f.nameWithoutExtension, "image", f.toURI().toString(), OffsetDateTime.now().toString())
            // raw description reply, for the log
            val image = GemmaImageAnalysis.loadRgb(f)
            val d0 = System.currentTimeMillis()
            val raw = engine.generate(Prompts.imageDescription(image), GemmaImageAnalysis.MAX_TOKENS)
            val describeMs = System.currentTimeMillis() - d0
            val hint = analysis.analyze(media)
            val e0 = System.currentTimeMillis()
            val json = extraction.extract(ExtractionRequest(InputMode.PHOTO_ONLY, "", null, null, null, listOf(hint), listOf(media), IncidentConfig()))
            val extractMs = System.currentTimeMillis() - e0
            val report = IncidentPolicies.apply(IncidentJsonCodec.decode(json), cues = assets.severityCues)
            Log.i(TAG, "${f.name} DESCRIBE_RAW(${describeMs} ms, prefill=${raw.prefillMs} decode=${raw.decodeMs}): ${raw.text.replace('\n', ' ')}")
            Log.i(TAG, "${f.name} HINT description=\"${hint.description}\" tags=${hint.tags}")
            Log.i(TAG, "${f.name} run$run TWO_CALL describe_ms=$describeMs extract_ms=$extractMs | ${summary(report)}")
        }
        engine.close()
    }

    @Test
    fun oneCallImageOnly() = runBlocking {
        val engine = LlamaGemmaEngine(ctx)
        engine.load()
        for (f in images()) {
            val image = GemmaImageAnalysis.loadRgb(f)
            val t0 = System.currentTimeMillis()
            val r = engine.generate(Prompts.multimodalExtraction(null, listOf(image)), 512)
            val ms = System.currentTimeMillis() - t0
            val json = LlmOutputParser.parseObject(r.text, Prompts.multimodalPrefix())
            Log.i(TAG, "${f.name} ONE_CALL ms=$ms prefill=${r.prefillMs} decode=${r.decodeMs} tokens=${r.generatedTokens} json=${json?.toString()?.take(700)}")
        }
        engine.close()
    }

    private companion object {
        const val TAG = "PHOTOLAB"
    }

    /** Speech on the two encoder devices (diagnostic, ADR-062): the same clips of `files/lang/` with `-e mm none` (CPU) and without (GPU). */
    @Test
    fun audioWithThisEncoder() = runBlocking<Unit> {
        val mm = InstrumentationRegistry.getArguments().getString("mm") ?: LlamaGemmaConfig().mmprojDevice
        val engine = LlamaGemmaEngine(ctx, LlamaGemmaConfig(mmprojDevice = mm))
        val stt = GemmaSpeechToText(engine)
        engine.load()
        val clips = File(ctx.filesDir, "lang").listFiles { f -> f.extension == "wav" }!!.sortedBy { it.name }.take(4)
        for (f in clips) {
            val t = stt.transcribePcm(com.example.test_app.ai.whisper.WavPcm.read16kMono(f))
            Log.i(TAG, "AUDIO mm=$mm ${f.name} asr_ms=${stt.lastAsrMs} language=${t.language} text=${t.text.take(80)}")
        }
        engine.close()
    }
}
