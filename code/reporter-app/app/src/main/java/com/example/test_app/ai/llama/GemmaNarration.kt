package com.example.test_app.ai.llama

import android.util.Log
import com.example.test_app.ai.LlmEngine
import com.example.test_app.ai.LlmOutputParser
import com.example.test_app.ai.MediaPart
import com.example.test_app.ai.PrecomputedFirstPass
import com.example.test_app.ai.Prompts
import com.example.test_app.ai.whisper.WavPcm
import com.example.test_app.dialogue.LanguageDetect
import com.example.test_app.domain.model.MediaItem
import com.example.test_app.domain.service.AudioInput
import com.example.test_app.domain.service.SpeechToTextException
import com.example.test_app.domain.service.Transcript
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.URI

/**
 * Optional ONE-call path (developer menu "Gemma one-call narration"): the narration audio (and, when given, photos; the app passes none since
 * gallery photos are described separately, ADR-053) go to Gemma in a
 * single call that returns the transcript, a photo description and the 7-key extraction JSON ([Prompts.multimodalExtraction]).
 * The transcript is returned to the dialogue as usual; the JSON is kept and handed to `LlmIncidentExtractionService` through
 * [PrecomputedFirstPass] when the dialogue asks for the first-pass extraction of exactly that transcript, saving the second call.
 * Follow-up answers still use the normal STT and update prompts. If the combined reply has no transcript, the plain transcription
 * prompt is used (and the first pass runs as usual). Not yet measured on the device: whether the transcript of the combined call is
 * as good as the dedicated transcription prompt, and the end-to-end latency (`MODELS_USAGE.md` 5b: audio + photo -> JSON 7.0 s).
 */
class GemmaNarrationExtractor(
    private val engine: LlmEngine,
    private val stt: GemmaSpeechToText,
    /** The language chosen in Settings ("Spoken language"), or null for auto-detect; read on every call. */
    private val forcedLanguage: () -> String? = { null },
    private val loadImage: (File) -> MediaPart.Image = { GemmaImageAnalysis.loadRgb(it) },
) : PrecomputedFirstPass {
    private class Pending(val transcript: String, val json: JsonObject)

    @Volatile private var pending: Pending? = null

    /** Transcribes [audio] (and looks at [media]) in one call; see the class comment. */
    suspend fun transcribeAndExtract(audio: AudioInput, media: List<MediaItem>): Transcript {
        pending = null
        val pcm = try {
            GemmaSpeechToText.clip(WavPcm.read16kMono(audio.file), MAX_AUDIO_MS)
        } catch (e: Exception) {
            throw SpeechToTextException("I couldn't read the recording.", e)
        }
        val photos = try {
            withContext(Dispatchers.IO) {
                media.filter { it.type == "image" }.take(MAX_PHOTOS).map { loadImage(File(URI(it.localUri))) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList() // a photo that cannot be read never blocks the narration
        }
        try {
            engine.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw SpeechToTextException("The speech recognition model could not be loaded.", e)
        }
        val forced = forcedLanguage()?.takeIf { it.isNotBlank() }
        val result = try {
            engine.generate(Prompts.multimodalExtraction(MediaPart.Audio(pcm), photos, forced), MAX_TOKENS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SpeechToTextException("I couldn't make out the audio.", e)
        }
        val json = LlmOutputParser.parseObject(result.text, Prompts.multimodalPrefix(forced))
        val transcript = (json?.get("transcript") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let { GemmaText.cleanTranscript(it) }.orEmpty()
        val language = (if (forced != null) LanguageDetect.normalizeName(forced) else LanguageDetect.normalizeName((json?.get("language") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content)).orEmpty()
        Log.i(
            TAG,
            "one-call prefillMs=${result.prefillMs} decodeMs=${result.decodeMs} promptTokens=${result.promptTokens} genTokens=${result.generatedTokens} " +
                "photos=${photos.size} parsed=${json != null} lang=$language transcript=\"$transcript\"",
        )
        if (json == null || transcript.isEmpty()) return stt.transcribePcm(pcm) // fall back to the dedicated prompt; the first pass runs normally
        pending = Pending(normalise(transcript), json)
        return Transcript(transcript, language = language)
    }

    override fun take(transcript: String): JsonObject? {
        val p = pending ?: return null
        pending = null
        return if (p.transcript == normalise(transcript)) p.json else null
    }

    private fun normalise(s: String) = s.trim().replace(Regex("\\s+"), " ")

    companion object {
        const val TAG = "GemmaNarration"

        /** JSON with transcript + description + 7 keys: the guide's runs generated 118-155 tokens; room for a long narration. */
        const val MAX_TOKENS = 512

        /** Photos and audio share the 4096-token context (~25 audio tokens per second, ~276 per photo, ~350 prompt, 512 output). */
        const val MAX_AUDIO_MS = 60_000L
        const val MAX_PHOTOS = 3
    }
}
