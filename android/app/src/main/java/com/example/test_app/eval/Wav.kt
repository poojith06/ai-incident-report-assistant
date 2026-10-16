package com.example.test_app.eval

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The facts of a PCM WAV file that matter for speech recognition. */
data class WavInfo(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val dataBytes: Long) {
    val durationMs: Long
        get() {
            val bytesPerSecond = sampleRate.toLong() * channels * (bitsPerSample / 8)
            return if (bytesPerSecond == 0L) 0 else dataBytes * 1000 / bytesPerSecond
        }

    /** True for the format every recogniser input in this app uses: 16 kHz, mono, 16-bit PCM. */
    val isRecognizerFormat: Boolean get() = sampleRate == 16_000 && channels == 1 && bitsPerSample == 16

    override fun toString() = "$sampleRate Hz, $channels channel(s), $bitsPerSample bit"

    companion object {
        /** Reads the RIFF header of [file]. Throws [IllegalArgumentException] when it is not a PCM WAV file. */
        fun read(file: File): WavInfo = RandomAccessFile(file, "r").use { raf -> readHeader(file, raf) }

        private fun readHeader(file: File, raf: RandomAccessFile): WavInfo {
            val head = ByteArray(12)
            require(raf.length() >= 12) { "${file.name}: too short for a WAV file" }
            raf.readFully(head)
            require(String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WAVE") {
                "${file.name}: not a RIFF/WAVE file"
            }
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var format = 0
            val chunk = ByteArray(8)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(chunk)
                val id = String(chunk, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(16)
                        require(size >= 16) { "${file.name}: bad fmt chunk" }
                        raf.readFully(fmt)
                        val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        format = b.short.toInt() and 0xFFFF
                        channels = b.short.toInt() and 0xFFFF
                        sampleRate = b.int
                        b.int // byte rate
                        b.short // block align
                        bits = b.short.toInt() and 0xFFFF
                        raf.seek(raf.filePointer + (size - 16) + (size and 1))
                    }
                    "data" -> {
                        require(format == 1) { "${file.name}: only PCM WAV is supported (format tag $format)" }
                        // A streaming writer may leave the size at 0 or 0xFFFFFFFF: then the data runs to the end of the file.
                        val available = raf.length() - raf.filePointer
                        val bytes = if (size == 0L || size > available) available else size
                        return WavInfo(sampleRate, channels, bits, bytes)
                    }
                    else -> raf.seek(raf.filePointer + size + (size and 1))
                }
            }
            throw IllegalArgumentException("${file.name}: no data chunk")
        }
    }
}
