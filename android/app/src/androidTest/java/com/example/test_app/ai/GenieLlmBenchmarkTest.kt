package com.example.test_app.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.test_app.ai.genie.GenieLlmEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Level 3 (LLM only, text input) smoke + latency benchmark on the QIDK. Needs the model in
 * `<externalFilesDir>/qwen4b` (doc/DEVICE_SETUP.md). Raw outputs go to `files/llm_bench/raw.jsonl`
 * (copy into src/test/resources/llm_outputs/ as parser fixtures). Docs: doc/TESTING.md.
 */
@RunWith(AndroidJUnit4::class)
class GenieLlmBenchmarkTest {
    private fun prompt(t: String) = Prompts.extraction(t)

    private val transcripts = listOf(
        "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area.",
        "Something is broken in the lab.",
        "Someone slipped near the cafeteria stairs and hurt his ankle",
        "Hello, can you hear me?",
        "There's a fire in the canteen kitchen, please come fast",
        "The AC in block C is leaking.",
        "Nobody was hurt but the printer gave off a spark in room 4.",
        "Someone has been harassing me near the parking lot.",
    )

    @Test
    fun benchmark() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = GenieLlmEngine(ctx)
        val t0 = System.currentTimeMillis()
        engine.load()
        val loadMs = System.currentTimeMillis() - t0
        val out = File(ctx.getExternalFilesDir(null), "llm_bench").apply { mkdirs() }
        val raw = File(out, "raw.jsonl").apply { writeText("") }
        val prefill = mutableListOf<Long>(); val decode = mutableListOf<Long>()
        repeat(3) { round ->
            for (t in transcripts) {
                val r = engine.generate(prompt(t))
                prefill += r.prefillMs; decode += r.decodeMs
                raw.appendText("{\"round\":$round,\"transcript\":${q(t)},\"prompt_chars\":${r.promptChars},\"prefill_ms\":${r.prefillMs},\"decode_ms\":${r.decodeMs},\"raw\":${q(r.text)}}\n")
                Log.i("BENCH", "[$round] $t -> ${r.text}")
                assertTrue("empty output for: $t", r.text.isNotBlank())
            }
        }
        fun pct(l: List<Long>, p: Double) = l.sorted()[((l.size - 1) * p).toInt()]
        Log.i("BENCH", "SUMMARY loadMs=$loadMs n=${prefill.size} prefill_median=${pct(prefill, .5)} prefill_p90=${pct(prefill, .9)} decode_median=${pct(decode, .5)} decode_p90=${pct(decode, .9)}")
        engine.close()
    }

    /** Latency of the Update and Rephrase prompts (production prompts from [Prompts]). */
    @Test
    fun benchmarkUpdateAndRephrase() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = GenieLlmEngine(ctx)
        engine.load()
        val form = mapOf("incident_type" to "Water Leak", "location" to null, "datetime" to "around 10 AM")
        val req = com.example.test_app.dialogue.RephraseRequest(
            field = "location",
            mode = com.example.test_app.dialogue.DialogueMode.PLAIN,
            template = "Where did this happen? You can say 'here' if it's where you are.",
            transcriptSoFar = "There's water all over the floor near the pantry.",
            language = "en",
        )
        repeat(3) {
            val u = engine.generate(Prompts.update(form, Prompts.askedKey("location"), "it's in block B second floor near the lift"))
            Log.i("BENCH", "UPDATE promptChars=${u.promptChars} prefillMs=${u.prefillMs} decodeMs=${u.decodeMs} out=${u.text}")
            val r = engine.generate(Prompts.rephrase(req))
            Log.i("BENCH", "REPHRASE promptChars=${r.promptChars} prefillMs=${r.prefillMs} decodeMs=${r.decodeMs} out=${r.text}")
        }
        engine.close()
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
