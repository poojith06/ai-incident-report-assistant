package com.example.test_app.ai.whisper

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-JVM WAV decoding for the recogniser: PCM16 WAV bytes -> mono float samples in [-1, 1] at 16 kHz.
 * Channels are averaged; other sample rates are linearly resampled. Only 16-bit PCM is supported (the format
 * `AudioRecorder` and the eval recordings use); anything else throws [IllegalArgumentException].
 */
object WavPcm {
    const val TARGET_RATE = 16_000

    fun read16kMono(file: File): FloatArray = decode(file.readBytes(), file.name)

    fun decode(bytes: ByteArray, name: String = "wav"): FloatArray {
        require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") {
            "$name: not a RIFF/WAVE file"
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    require(size >= 16 && body + 16 <= bytes.size) { "$name: bad fmt chunk" }
                    format = bb.getShort(body).toInt() and 0xFFFF
                    channels = bb.getShort(body + 2).toInt() and 0xFFFF
                    rate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt() and 0xFFFF
                }
                "data" -> {
                    require(format == 1) { "$name: only PCM WAV is supported (format tag $format)" }
                    require(bits == 16) { "$name: only 16-bit PCM is supported (found $bits bit)" }
                    require(channels >= 1 && rate > 0) { "$name: bad fmt chunk" }
                    val available = (bytes.size - body).toLong()
                    // Streaming writers may leave the size at 0 or 0xFFFFFFFF: the data then runs to the end of the file.
                    val dataBytes = (if (size == 0L || size > available) available else size).toInt()
                    return resample(toMono(bb, body, dataBytes, channels), rate, TARGET_RATE)
                }
            }
            pos = (body + size + (size and 1)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        throw IllegalArgumentException("$name: no data chunk")
    }

    private fun toMono(bb: ByteBuffer, offset: Int, dataBytes: Int, channels: Int): FloatArray {
        val frames = dataBytes / (2 * channels)
        val out = FloatArray(frames)
        var p = offset
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) {
                sum += bb.getShort(p).toInt()
                p += 2
            }
            out[i] = sum / (channels * 32768f)
        }
        return out
    }

    /** Linear-interpolation resampler (adequate for speech going into a log-mel front end). */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val outSize = (input.size.toLong() * toRate / fromRate).toInt()
        val out = FloatArray(outSize)
        val step = fromRate.toDouble() / toRate
        for (i in 0 until outSize) {
            val src = i * step
            val i0 = src.toInt().coerceAtMost(input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (src - i0).toFloat()
            out[i] = input[i0] * (1f - frac) + input[i1] * frac
        }
        return out
    }
}
