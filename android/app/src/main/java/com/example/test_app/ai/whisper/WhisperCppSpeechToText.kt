package com.example.test_app.ai.whisper

import android.content.Context
import android.util.Log
import com.example.test_app.domain.service.AudioInput
import com.example.test_app.domain.service.SpeechToTextException
import com.example.test_app.domain.service.SpeechToTextService
import com.example.test_app.domain.service.SttHint
import com.example.test_app.domain.service.Transcript
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * **CPU STOPGAP** speech-to-text: whisper.cpp (ggml, CPU, English forced, transcribe mode). The board has no NPU Whisper
 * model; this runs the real audio pipeline end to end until it has. It is NOT the production ASR.
 *
 * - Reads the WAV the app already records ([AudioInput.file]), converts to 16 kHz mono float ([WavPcm]).
 * - One dedicated thread (the ggml context is not thread-safe); the model is loaded once by [load].
 * - Logs `asr_ms`, audio length and real-time factor under tag `WhisperCpp`.
 * - [initialPrompt] (optional) biases the decoder toward the place names of the app; null = no prompt.
 *
 * Docs: doc/MODEL_INTEGRATION.md.
 */
class WhisperCppSpeechToText(
    private val modelFile: File,
    private val threads: Int = DEFAULT_THREADS,
    private val initialPrompt: () -> String? = { null },
) : SpeechToTextService, AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "whisper-cpp") }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val native = WhisperNative()
    private var handle: Long = 0

    /** Timing of the last call, for benchmarks. */
    @Volatile var lastAsrMs: Long = 0
        private set

    val isLoaded: Boolean get() = handle != 0L

    val modelName: String get() = modelFile.name

    /** Loads the model once (measured 85-155 ms: the file is read once). Throws when the file is missing or invalid. */
    suspend fun load() = withContext(dispatcher) {
        if (handle != 0L) return@withContext
        check(modelFile.canRead()) { "Whisper model not found or unreadable: ${modelFile.path}. See doc/DEVICE_SETUP.md." }
        val started = System.currentTimeMillis()
        handle = native.nativeCreate(modelFile.absolutePath)
        Log.i(TAG, "model ${modelFile.name} loaded in ${System.currentTimeMillis() - started} ms (threads=$threads)")
    }

    override suspend fun transcribe(audio: AudioInput, hint: SttHint): Transcript {
        val pcm = try {
            WavPcm.read16kMono(audio.file)
        } catch (e: Exception) {
            throw SpeechToTextException("I couldn't read the recording.", e)
        }
        return transcribePcm(pcm)
    }

    /** Transcribes 16 kHz mono float PCM (used by the benchmark and by [transcribe]). */
    suspend fun transcribePcm(pcm: FloatArray): Transcript {
        try {
            load()
        } catch (e: Exception) {
            throw SpeechToTextException("The speech recognition model could not be loaded.", e)
        }
        val prompt = initialPrompt()
        return withContext(dispatcher) {
            val started = System.nanoTime()
            val text = try {
                native.nativeTranscribe(handle, pcm, threads, LANGUAGE, prompt)
            } catch (e: Exception) {
                throw SpeechToTextException("I couldn't make out the audio.", e)
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            lastAsrMs = ms
            val audioMs = pcm.size * 1000L / WavPcm.TARGET_RATE
            val rtf = if (audioMs > 0) ms.toDouble() / audioMs else 0.0
            Log.i(TAG, "asr_ms=$ms audio_ms=$audioMs rtf=${"%.2f".format(rtf)} model=${modelFile.name} threads=$threads prompt=${prompt != null} text=\"${text.trim()}\"")
            Transcript(text.trim()) // language unknown: Whisper is forced to English decoding here, the dialogue falls back to the script
        }
    }

    override fun close() {
        executor.execute {
            if (handle != 0L) native.nativeFree(handle)
            handle = 0
        }
        executor.shutdown()
    }

    companion object {
        const val TAG = "WhisperCpp"
        const val LANGUAGE = "en"
        const val DEFAULT_THREADS = 6
        const val MODEL_DIR = "whisper"
        const val BASE_EN = "ggml-base.en.bin"
        const val SMALL_EN = "ggml-small.en.bin"

        /** Default model file name (chosen by the measured WER / latency, doc/MODEL_INTEGRATION.md). */
        const val DEFAULT_MODEL = SMALL_EN

        /** Place names of the app's domain, used as the optional decoder prompt (developer-menu flag). */
        const val PLACE_NAMES_PROMPT = "Incident report. Places: conference room, server room, pantry, lab, canteen, Block B."

        /** Model folders in search order: app external files dir, then internal files dir (debug-friendly via `run-as`). */
        fun candidateFiles(context: Context, name: String = DEFAULT_MODEL): List<File> =
            listOfNotNull(context.getExternalFilesDir(null), context.filesDir).map { File(File(it, MODEL_DIR), name) }

        /** The first readable copy of [name], or null when the model is not on the device. */
        fun findModel(context: Context, name: String = DEFAULT_MODEL): File? = candidateFiles(context, name).firstOrNull { it.canRead() }
    }
}
