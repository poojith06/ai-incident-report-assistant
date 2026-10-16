package com.example.test_app.ai.llama

import android.util.Log
import com.example.test_app.ai.ChatPrompt
import com.example.test_app.ai.LlmEngine
import com.example.test_app.ai.MediaPart
import com.example.test_app.ai.Prompts
import com.example.test_app.ai.whisper.WavPcm
import com.example.test_app.domain.service.AudioInput
import com.example.test_app.domain.service.SpeechToTextException
import com.example.test_app.domain.service.SpeechToTextService
import com.example.test_app.domain.service.SttHint
import com.example.test_app.domain.service.Transcript
import kotlinx.coroutines.CancellationException

/**
 * Speech-to-text with Gemma 4's native audio input: the recording (16 kHz mono) goes to the model with [Prompts.TRANSCRIBE_INSTRUCTION]
 * (identify the spoken language, transcribe verbatim in that language's own script, answer `Language:` / `Transcript:`), greedy, no
 * thinking; the reply is parsed by [GemmaText.parseTranscriptReply] and the language the model names is returned in [Transcript.language].
 * With a language chosen in Settings the prompt is [Prompts.transcriptionForced] instead (the reply is plain text, the language is the chosen
 * name). No language is special-cased. Gemma is not a dedicated ASR model: expect paraphrase and number-format differences
 * ("a two" vs "a2"); `MODELS_USAGE.md` 5b measured ASR at 0.9-1.9 s per clip (NPU + GPU), not yet measured inside this app.
 * Shares the engine (and its single thread) with extraction and rephrasing. Confidence is always 1: llama.cpp reports none here.
 */
class GemmaSpeechToText(
    private val engine: LlmEngine,
    /** The language chosen in Settings ("Spoken language"), or null for auto-detect. Read on every call (the setting can change at any time). */
    private val forcedLanguage: () -> String? = { null },
) : SpeechToTextService {
    /** Wall time of the last call, for logs and benchmarks. */
    @Volatile var lastAsrMs: Long = 0
        private set

    override suspend fun transcribe(audio: AudioInput, hint: SttHint): Transcript {
        val pcm = try {
            WavPcm.read16kMono(audio.file)
        } catch (e: Exception) {
            throw SpeechToTextException("I couldn't read the recording.", e)
        }
        return transcribePcm(pcm)
    }

    /** Transcribes 16 kHz mono float PCM (used by [transcribe] and the benchmark). */
    suspend fun transcribePcm(pcm: FloatArray): Transcript {
        try {
            engine.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) { // includes UnsatisfiedLinkError when the native libraries are missing
            throw SpeechToTextException("The speech recognition model could not be loaded.", e)
        }
        val forced = forcedLanguage()?.takeIf { it.isNotBlank() }
        val clipped = clip(pcm, MAX_AUDIO_MS)
        val started = System.nanoTime()
        val result = try {
            engine.generate(prompt(forced, MediaPart.Audio(clipped)), MAX_TOKENS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SpeechToTextException("I couldn't make out the audio.", e)
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        lastAsrMs = ms
        val reply = parseReply(result.text, forced)
        val text = reply.text
        val audioMs = clipped.size * 1000L / WavPcm.TARGET_RATE
        Log.i(
            TAG,
            "asr_ms=$ms audio_ms=$audioMs lang=${reply.language} rtf=${"%.2f".format(if (audioMs > 0) ms.toDouble() / audioMs else 0.0)} prefillMs=${result.prefillMs} " +
                "decodeMs=${result.decodeMs} promptTokens=${result.promptTokens} genTokens=${result.generatedTokens} text=\"$text\"",
        )
        return Transcript(text, language = reply.language.orEmpty())
    }

    companion object {
        const val TAG = "GemmaStt"

        /** The transcription prompt: auto-detect ([forced] null) or the user's chosen language ([Prompts.transcriptionForced]). */
        fun prompt(forced: String?, audio: MediaPart.Audio): ChatPrompt =
            if (forced != null) Prompts.transcriptionForced(forced, audio) else Prompts.transcription(audio)

        /** Forced language: a plain-text reply and the language is the one the user chose; auto: the two-line `Language:` / `Transcript:` reply. */
        fun parseReply(raw: String, forced: String?): GemmaText.TranscriptReply =
            if (forced != null) GemmaText.forcedTranscriptReply(raw, forced) else GemmaText.parseTranscriptReply(raw)

        /** Output budget for a transcript (the guide's request used 256). */
        const val MAX_TOKENS = 256

        /**
         * Longest recording sent to the model (~25 audio tokens per second, 4096-token context): longer audio is cut at this length
         * (and logged by the caller's `audio_ms`).
         */
        const val MAX_AUDIO_MS = 90_000L

        /** [pcm] cut to at most [maxMs] milliseconds (same array when it already fits). */
        fun clip(pcm: FloatArray, maxMs: Long): FloatArray {
            val max = (maxMs * WavPcm.TARGET_RATE / 1000).toInt()
            return if (pcm.size <= max) pcm else pcm.copyOf(max)
        }
    }
}
