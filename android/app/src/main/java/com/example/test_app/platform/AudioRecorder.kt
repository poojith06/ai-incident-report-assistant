package com.example.test_app.platform

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.test_app.domain.service.AudioInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Real microphone capture via [AudioRecord]: 16 kHz mono PCM16 written to a WAV file in the cache dir.
 * Exposes a normalized 0..1 [level] for the live meter. The RECORD_AUDIO permission must already be granted.
 * See `doc/ARCHITECTURE.md`.
 */
class AudioRecorder(private val context: Context) {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    @Volatile private var recording = false
    private var job: Job? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recording

    /** Starts capture. @throws IllegalStateException if the microphone can't be opened. */
    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        check(!recording) { "Already recording" }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuf > 0) { "Microphone not available" }
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("Microphone could not be opened")
        }
        val out = File(context.cacheDir, "rec_${System.currentTimeMillis()}.wav")
        file = out
        recording = true
        startedAt = System.currentTimeMillis()
        record.startRecording()
        job = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(minBuf / 2)
            RandomAccessFile(out, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(WAV_HEADER_SIZE))
                var bytes = 0L
                val bb = ByteBuffer.allocate(buffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                while (recording) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    bb.clear()
                    var sum = 0.0
                    for (i in 0 until n) {
                        bb.putShort(buffer[i])
                        sum += buffer[i] * buffer[i].toDouble()
                    }
                    raf.write(bb.array(), 0, n * 2)
                    bytes += n * 2
                    _level.value = (sqrt(sum / n) / 6000.0).coerceIn(0.0, 1.0).toFloat()
                }
                raf.seek(0)
                raf.write(wavHeader(bytes))
            }
            record.stop()
            record.release()
            _level.value = 0f
        }
    }

    /** Stops capture and returns the clip, or null if nothing was recording. */
    suspend fun stop(): AudioInput? {
        if (!recording) return null
        recording = false
        job?.join()
        job = null
        val f = file ?: return null
        file = null
        return AudioInput(f, System.currentTimeMillis() - startedAt)
    }

    /** Stops capture and deletes the clip. */
    suspend fun cancel() {
        stop()?.file?.delete()
    }

    private fun wavHeader(dataBytes: Long): ByteArray {
        val bb = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt())
        bb.put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
        bb.putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(dataBytes.toInt())
        return bb.array()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val WAV_HEADER_SIZE = 44
    }
}
