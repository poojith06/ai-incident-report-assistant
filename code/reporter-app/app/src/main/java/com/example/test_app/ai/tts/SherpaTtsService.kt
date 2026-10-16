package com.example.test_app.ai.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.test_app.domain.service.TextToSpeechService
import com.example.test_app.domain.service.TtsUnavailableException
import com.example.test_app.platform.TtsConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Where the model files are and how a model is configured: `files/tts/<[TtsModel.dirName]>/` (copy commands: `doc/DEVICE_SETUP.md`). */
class SherpaModelFiles(private val root: File) {
    fun dir(model: TtsModel) = File(root, model.dirName)

    /** The Rasa weights: the int8 file, else the fp32 file. The Piper file is fixed. */
    fun modelFile(model: TtsModel): File? = when (model) {
        TtsModel.PIPER -> File(dir(model), "en_US-amy-medium.onnx")
        TtsModel.RASA -> listOf("indic_model_int8.onnx", "model.onnx").map { File(dir(model), it) }.firstOrNull { it.isFile }
    }?.takeIf { it.isFile }

    fun present(model: TtsModel): Boolean {
        val m = modelFile(model) ?: return false
        if (!File(dir(model), "tokens.txt").isFile) return false
        return model != TtsModel.PIPER || File(dir(model), "espeak-ng-data").isDirectory
    }

    fun anyPresent() = TtsModel.entries.any { present(it) }

    fun config(model: TtsModel, numThreads: Int): OfflineTtsConfig {
        val d = dir(model)
        val vits = OfflineTtsVitsModelConfig(
            model = modelFile(model)!!.path,
            tokens = File(d, "tokens.txt").path,
            dataDir = if (model == TtsModel.PIPER) File(d, "espeak-ng-data").path else "",
        )
        return OfflineTtsConfig(model = OfflineTtsModelConfig(vits = vits, numThreads = numThreads, debug = false, provider = "cpu"))
    }
}

/** What one synthesis cost: [loadMs] > 0 only when this call had to load the model. [audioSeconds] counts the audio handed to the sink (pauses included). */
data class SynthStats(val engine: VoiceEngine, val loadMs: Long, val synthMs: Long, val audioSeconds: Double, val sampleRate: Int)

/**
 * The sherpa-onnx models, loaded lazily one by one (a model is read on its first use) and released together by [release].
 * Not thread-safe for concurrent [synthesize] calls: use one thread (the service does). JNI: `com.k2fsa.sherpa.onnx.OfflineTts`.
 */
class SherpaSynthesizer(private val files: SherpaModelFiles, private val numThreads: Int = DEFAULT_THREADS) {
    private val engines = HashMap<TtsModel, OfflineTts>()

    /** Milliseconds the last model load took (0 until a load happened). */
    @Volatile var lastLoadMs: Long = 0
        private set

    @Synchronized private fun engine(model: TtsModel): OfflineTts {
        engines[model]?.let { return it }
        val t0 = SystemClock.elapsedRealtime()
        val tts = OfflineTts(config = files.config(model, numThreads))
        lastLoadMs = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "loaded ${model.dirName} in $lastLoadMs ms, rate=${tts.sampleRate()}, speakers=${tts.numSpeakers()}, threads=$numThreads")
        engines[model] = tts
        return tts
    }

    /**
     * Synthesises [plan] and hands each piece of audio (mono float, sample rate) to [sink] as it is produced; [sink] returns false
     * to stop. [speed] is the sherpa speed (1.0 = normal). Throws when the model cannot be loaded or the plan is SYSTEM.
     */
    fun synthesize(plan: VoicePlan, speed: Float, sink: (FloatArray, Int) -> Boolean): SynthStats {
        val model = requireNotNull(plan.model) { "A SYSTEM plan is not synthesised by sherpa." }
        val wasLoaded = synchronized(this) { engines.containsKey(model) }
        val tts = engine(model)
        val loadMs = if (wasLoaded) 0L else lastLoadMs
        val rate = tts.sampleRate()
        val t1 = SystemClock.elapsedRealtime()
        val timed = TimedSink(sink, rate)
        when (model) {
            TtsModel.PIPER -> plan.chunks.forEach { chunk ->
                if (timed.going) tts.generateWithCallback(chunk, sid = 0, speed = speed, callback = timed)
            }
            TtsModel.RASA -> plan.chunks.forEachIndexed { i, chunk ->
                if (timed.going && i > 0) timed(FloatArray(rate * SherpaVoices.CHUNK_PAUSE_MS / 1000))
                if (timed.going) {
                    val cfg = GenerationConfig(speed = speed, sid = plan.speakerId, extra = mapOf("emotion_id" to plan.emotionId.toString()))
                    tts.generateWithConfigAndCallback(chunk, cfg, timed)
                }
            }
        }
        val synthMs = SystemClock.elapsedRealtime() - t1 - timed.sinkMs
        return SynthStats(plan.engine, loadMs, synthMs, timed.samples.toDouble() / rate, rate)
    }

    /**
     * The callback handed to the JNI. It MUST be a class, not a lambda: the native side looks up `invoke([F)Ljava/lang/Integer;`, which the
     * indy lambdas of Kotlin 2 do not have (the process aborts with NoSuchMethodError inside JNI). Returns 1 to go on, 0 to stop.
     */
    private class TimedSink(private val sink: (FloatArray, Int) -> Boolean, private val rate: Int) : (FloatArray) -> Int {
        var samples = 0L
        var sinkMs = 0L
        var going = true

        override fun invoke(audio: FloatArray): Int {
            val s = SystemClock.elapsedRealtime()
            samples += audio.size
            going = sink(audio, rate)
            sinkMs += SystemClock.elapsedRealtime() - s
            return if (going) 1 else 0
        }
    }

    /** Frees every loaded model. The next [synthesize] loads again. */
    @Synchronized fun release() {
        engines.values.forEach { runCatching { it.release() } }
        engines.clear()
    }

    companion object {
        const val DEFAULT_THREADS = 4
        private const val TAG = "SherpaTts"
    }
}

/**
 * [TextToSpeechService] over sherpa-onnx: Piper (English) and Rasa (Indic scripts) on the CPU, no network. The text picks the model
 * ([SherpaVoices.plan]); a language without a voice, or a model whose files are missing or fail to load, is spoken by [fallback]
 * (the Android TTS) instead. Synthesis runs on one background thread and the audio is streamed to an `AudioTrack` as the model
 * produces it, so speech starts after the first sentence. [speak] suspends until the audio has played (or [stop] / cancellation).
 * The speech rate setting is the sherpa `speed` ([SherpaVoices.speed]). Models load on first use and are freed by [shutdown].
 * [voiceChoice] is the developer-menu Rasa speaker / emotion. See `doc/TTS.md`.
 */
class SherpaTtsService(
    private val files: SherpaModelFiles,
    private val fallback: TextToSpeechService? = null,
    private val voiceChoice: () -> RasaVoiceChoice = { RasaVoiceChoice() },
) : TextToSpeechService {
    private val synthesizer = SherpaSynthesizer(files)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sherpa-tts").apply { isDaemon = true } }
    @Volatile private var rate = TtsConfig.SPEECH_RATE
    @Volatile private var epoch = 0

    /** True when at least one voice model is on the device: otherwise the app should use the fallback engine directly. */
    fun available(): Boolean = files.anyPresent()

    private class Utterance(val epoch: Int) {
        @Volatile var aborted = false
    }

    private sealed interface Outcome {
        data object Done : Outcome
        data class Failed(val cause: Throwable, val audioPlayed: Boolean) : Outcome
    }

    override suspend fun speak(text: String) = speak(text, null)

    override suspend fun speak(text: String, language: String?) {
        val plans = SherpaVoices.segments(text, language, voiceChoice())
        if (plans.isEmpty()) return
        // One run without a voice (a script the models do not cover) or without its model files: the whole utterance goes to the fallback.
        val unvoiced = plans.firstOrNull { p -> p.model.let { it == null || !files.present(it) } }
        if (unvoiced != null) {
            Log.i(TAG, "no sherpa voice for language=${unvoiced.language} (engine=${unvoiced.engine}, files=${unvoiced.model?.let { files.present(it) }}): fallback for \"${text.take(40)}\"")
            return speakWithFallback(text, language, null)
        }
        val utterance = Utterance(epoch)
        Log.i(TAG, "speak begin epoch=${utterance.epoch} segments=${plans.size} text=\"${text.take(40)}\"")
        val outcome = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { utterance.aborted = true }
            worker.execute {
                val result = try {
                    if (utterance.aborted || utterance.epoch != epoch) Outcome.Done else play(plans, utterance)
                } catch (e: Throwable) {
                    Outcome.Failed(e, audioPlayed = false)
                }
                if (cont.isActive) cont.resume(result)
            }
        }
        Log.i(TAG, "speak end epoch=${utterance.epoch} now=$epoch aborted=${utterance.aborted} outcome=${outcome.javaClass.simpleName}")
        if (outcome is Outcome.Failed) {
            Log.w(TAG, "sherpa synthesis failed (${outcome.cause.javaClass.simpleName}: ${outcome.cause.message}); engines=${plans.map { it.engine }}")
            if (!outcome.audioPlayed) speakWithFallback(text, language, outcome.cause)
        }
    }

    private suspend fun speakWithFallback(text: String, language: String?, cause: Throwable?) {
        val fb = fallback ?: throw TtsUnavailableException("No Sherpa voice for this text and no fallback engine.", cause)
        fb.speak(text, language)
    }

    /**
     * Runs on the worker thread: synthesise each segment and stream it to one AudioTrack (the rate of the first segment; the others are
     * resampled to it); returns when the audio has played or the utterance was aborted.
     */
    private fun play(plans: List<VoicePlan>, u: Utterance): Outcome {
        val speed = SherpaVoices.speed(rate)
        var player: PcmPlayer? = null
        var played = false
        val live = { !u.aborted && u.epoch == epoch }
        try {
            for ((i, plan) in plans.withIndex()) {
                if (!live()) break
                val stats = synthesizer.synthesize(plan, speed) { audio, sampleRate ->
                    if (!live()) return@synthesize false
                    val p = player ?: PcmPlayer(sampleRate).also { player = it }
                    played = true
                    p.write(audio, sampleRate, live)
                }
                Log.i(
                    TAG,
                    "spoke seg=${i + 1}/${plans.size} engine=${plan.engine} lang=${plan.language} sid=${plan.speakerId} emo=${plan.emotionId} " +
                        "load_ms=${stats.loadMs} synth_ms=${stats.synthMs} audio_s=${"%.2f".format(stats.audioSeconds)} speed=$speed text=\"${plan.chunks.joinToString(" | ").take(60)}\"",
                )
                if (plan.pauseAfterMs > 0 && i < plans.lastIndex && live()) player?.writeSilence(plan.pauseAfterMs, live)
            }
            player?.finish(live)
            return Outcome.Done
        } catch (e: Throwable) {
            return Outcome.Failed(e, audioPlayed = played)
        } finally {
            player?.release()
        }
    }

    override fun setSpeechRate(rate: Float) {
        this.rate = SherpaVoices.speed(rate)
        fallback?.setSpeechRate(rate)
    }

    /** Stops the current utterance (and any queued one); [speak] returns. Models stay loaded. */
    override fun stop() {
        epoch++
        fallback?.stop()
    }

    /** Stops speaking and frees the models (they load again on the next [speak]). */
    override fun shutdown() {
        stop()
        worker.execute { synthesizer.release() }
        fallback?.shutdown()
    }

    /** Direct access for benchmarks: synthesise without playing. */
    fun synthesizeOnly(plan: VoicePlan, speed: Float, sink: (FloatArray, Int) -> Boolean): SynthStats = synthesizer.synthesize(plan, speed, sink)

    /** Float PCM stream to one `AudioTrack`; writes in slices so an abort is noticed within ~100 ms. */
    private class PcmPlayer(private val sampleRate: Int) {
        private val track: AudioTrack
        private val plan = PcmBufferPolicy.plan(Build.VERSION.SDK_INT, sampleRate)
        private var framesWritten = 0L
        private var started = false

        init {
            val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(min, plan.bufferFrames * 4)) // float samples
                .build()
            // without a start threshold a stream track plays only once its buffer is full (PcmBufferPolicy)
            plan.startThresholdFrames?.let { if (Build.VERSION.SDK_INT >= 31) track.startThresholdInFrames = it }
        }

        /** Blocks while the buffer is full; false when [live] turned false. [srcRate] differing from the track's rate is resampled. */
        fun write(samples: FloatArray, srcRate: Int, live: () -> Boolean): Boolean {
            val data = if (srcRate == sampleRate) samples else SherpaVoices.resample(samples, srcRate, sampleRate)
            val slice = sampleRate / 10
            var off = 0
            while (off < data.size) {
                if (!live()) return false
                val n = minOf(slice, data.size - off)
                val written = track.write(data, off, n, AudioTrack.WRITE_BLOCKING)
                if (written < 0) throw IllegalStateException("AudioTrack.write failed: $written")
                off += written
                framesWritten += written
                if (!started) {
                    track.play(); started = true
                }
            }
            return true
        }

        /** [ms] of silence (the pause between two voices). */
        fun writeSilence(ms: Int, live: () -> Boolean) {
            write(FloatArray(sampleRate * ms / 1000), sampleRate, live)
        }

        /** Waits until everything written has been played (or [live] turns false). */
        fun finish(live: () -> Boolean) {
            if (!started) return
            val pad = PcmBufferPolicy.padFrames(plan, framesWritten)
            if (pad > 0) track.write(FloatArray(pad), 0, pad, AudioTrack.WRITE_BLOCKING)
            val deadline = SystemClock.elapsedRealtime() + framesWritten * 1000 / sampleRate + 2000
            while (live() && track.playbackHeadPosition < framesWritten && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            if (live() && track.playbackHeadPosition == 0) Log.w(TAG, "the AudioTrack never started playing (head 0 after $framesWritten frames): the utterance was silent")
            Log.i(TAG, "playback finished head=${track.playbackHeadPosition} written=$framesWritten state=${track.playState} live=${live()} rate=$sampleRate")
        }

        fun release() {
            runCatching { track.pause(); track.flush() }
            runCatching { track.release() }
        }
    }

    companion object {
        /** True when [dir] (`files/tts`) holds at least one complete voice model. */
        fun modelsPresent(dir: File) = SherpaModelFiles(dir).anyPresent()

        private const val TAG = "SherpaTts"
    }
}
