package com.example.test_app.ai.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/**
 * Does an AudioTrack with a given buffer length actually play (playbackHeadPosition advances)? Logs head position over time (tag `ATPROBE`).
 * Diagnostic for the "question is not spoken" report; keeps the findings reproducible (`doc/TTS.md`).
 */
@RunWith(AndroidJUnit4::class)
class AudioTrackProbeTest {
    private fun probe(label: String, encoding: Int, bufferSeconds: Int, rate: Int = 22050, startBeforeWrite: Boolean = false, threshold: Int = 0) {
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, encoding)
        val size = maxOf(min, rate * bytesPerSample * bufferSeconds)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(encoding).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(size).build()
        if (threshold > 0 && android.os.Build.VERSION.SDK_INT >= 31) track.startThresholdInFrames = threshold
        val frames = rate * 2
        if (startBeforeWrite) track.play()
        var written = 0
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val data = FloatArray(frames) { (0.3 * sin(2 * PI * 440 * it / rate)).toFloat() }
            written = track.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
        } else {
            val data = ShortArray(frames) { (9000 * sin(2 * PI * 440 * it / rate)).toInt().toShort() }
            written = track.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
        }
        if (!startBeforeWrite) track.play()
        val heads = StringBuilder()
        repeat(12) { heads.append(track.playbackHeadPosition).append(' '); Thread.sleep(250) }
        Log.i("ATPROBE", "$label enc=$encoding bufSeconds=$bufferSeconds bufBytes=${track.bufferSizeInFrames} written=$written heads(250ms)=$heads underruns=${track.underrunCount} state=${track.playState}")
        track.stop(); track.release()
    }

    @Test
    fun headAdvances() {
        probe("float 1s", AudioFormat.ENCODING_PCM_FLOAT, 1)
        probe("float 30s", AudioFormat.ENCODING_PCM_FLOAT, 30)
        probe("float 30s play-first", AudioFormat.ENCODING_PCM_FLOAT, 30, startBeforeWrite = true)
        probe("pcm16 1s", AudioFormat.ENCODING_PCM_16BIT, 1)
        probe("pcm16 30s", AudioFormat.ENCODING_PCM_16BIT, 30)
        probe("pcm16 30s play-first 24k", AudioFormat.ENCODING_PCM_16BIT, 30, 24000, true)
        probe("float 2s", AudioFormat.ENCODING_PCM_FLOAT, 2)
        probe("float 4s", AudioFormat.ENCODING_PCM_FLOAT, 4)
        probe("float 8s", AudioFormat.ENCODING_PCM_FLOAT, 8)
        probe("float 30s threshold 0.1s", AudioFormat.ENCODING_PCM_FLOAT, 30, threshold = 2205)
    }

    /** The shipped configuration ([PcmBufferPolicy]): a line much shorter than the buffer must start by itself and be played completely. */
    @Test
    fun policyTrackPlaysAShortLine() {
        val rate = 22050
        val plan = PcmBufferPolicy.plan(android.os.Build.VERSION.SDK_INT, rate)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(plan.bufferFrames * 4).build()
        plan.startThresholdFrames?.let { if (android.os.Build.VERSION.SDK_INT >= 31) track.startThresholdInFrames = it }
        val frames = rate / 3 // 0.33 s
        track.write(FloatArray(frames) { (0.3 * sin(2 * PI * 440 * it / rate)).toFloat() }, 0, frames, AudioTrack.WRITE_BLOCKING)
        track.play()
        val pad = PcmBufferPolicy.padFrames(plan, frames.toLong())
        if (pad > 0) track.write(FloatArray(pad), 0, pad, AudioTrack.WRITE_BLOCKING)
        Thread.sleep(1200)
        val head = track.playbackHeadPosition
        track.stop(); track.release()
        Log.i("ATPROBE", "policy short line head=$head of $frames")
        org.junit.Assert.assertTrue("the short line never started (head $head)", head >= frames)
    }
}
