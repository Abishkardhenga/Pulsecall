package com.example.call

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

class CallSoundEffects {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isSoundEnabled = MutableStateFlow(true)
    val isSoundEnabled: StateFlow<Boolean> = _isSoundEnabled.asStateFlow()

    private var ringbackJob: Job? = null

    @Volatile
    private var activeRingTrack: AudioTrack? = null

    fun toggleSoundEnabled() {
        val next = !_isSoundEnabled.value
        _isSoundEnabled.value = next
        if (!next) {
            stopRingback()
        } else {
            playTogglePip(active = true)
        }
    }

    fun startRingback() {
        if (!_isSoundEnabled.value) return
        if (ringbackJob?.isActive == true) return
        ringbackJob = scope.launch {
            while (isActive && _isSoundEnabled.value) {
                playToneSequence(
                    notes = listOf(
                        ToneSpec(freqA = 440.0, freqB = 480.0, durationMs = 1050, amplitude = 0.16)
                    ),
                    trackReceiver = { activeRingTrack = it },
                    clearTrack = { activeRingTrack = null }
                )
                delay(2400L)
            }
        }
    }

    fun stopRingback() {
        ringbackJob?.cancel()
        ringbackJob = null
        try {
            activeRingTrack?.pause()
            activeRingTrack?.flush()
            activeRingTrack?.release()
        } catch (_: Throwable) {
        } finally {
            activeRingTrack = null
        }
    }

    fun playPeerJoiningChime() {
        if (!_isSoundEnabled.value) return
        scope.launch {
            playToneSequence(
                notes = listOf(
                    ToneSpec(freqA = 523.25, durationMs = 95, amplitude = 0.22),
                    ToneSpec(freqA = 659.25, durationMs = 150, amplitude = 0.24)
                )
            )
        }
    }

    fun playConnectedChime() {
        if (!_isSoundEnabled.value) return
        scope.launch {
            playToneSequence(
                notes = listOf(
                    ToneSpec(freqA = 587.33, freqB = 880.0, durationMs = 110, amplitude = 0.22),
                    ToneSpec(freqA = 880.0, freqB = 1174.66, durationMs = 190, amplitude = 0.20)
                )
            )
        }
    }

    fun playHandoffChime() {
        if (!_isSoundEnabled.value) return
        scope.launch {
            playToneSequence(
                notes = listOf(
                    ToneSpec(freqA = 783.99, durationMs = 85, amplitude = 0.22),
                    ToneSpec(freqA = 659.25, durationMs = 85, amplitude = 0.22),
                    ToneSpec(freqA = 523.25, durationMs = 150, amplitude = 0.20)
                )
            )
        }
    }

    fun playTogglePip(active: Boolean) {
        if (!_isSoundEnabled.value) return
        scope.launch {
            playToneSequence(
                notes = listOf(
                    ToneSpec(
                        freqA = if (active) 700.0 else 460.0,
                        durationMs = 48,
                        amplitude = 0.14
                    )
                )
            )
        }
    }

    fun playEndCallTone() {
        stopRingback()
        if (!_isSoundEnabled.value) return
        scope.launch {
            playToneSequence(
                notes = listOf(
                    ToneSpec(freqA = 493.88, durationMs = 110, amplitude = 0.22),
                    ToneSpec(freqA = 369.99, durationMs = 190, amplitude = 0.20)
                )
            )
        }
    }

    fun release() {
        stopRingback()
        scope.cancel()
    }

    private suspend fun playToneSequence(
        notes: List<ToneSpec>,
        trackReceiver: ((AudioTrack) -> Unit)? = null,
        clearTrack: (() -> Unit)? = null
    ) {
        val pcm = synthesizePcm(notes)
        if (pcm.isEmpty()) return

        var track: AudioTrack? = null
        try {
            val bufferBytes = pcm.size * 2
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(bufferBytes, minBuf))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            trackReceiver?.invoke(track)
            track.write(pcm, 0, pcm.size)
            track.play()

            val totalMs = notes.sumOf { it.durationMs.toLong() } + 40L
            delay(totalMs)
        } catch (t: Throwable) {
            Log.d(TAG, "Sound effect playback skipped: ${t.message}")
        } finally {
            try {
                track?.stop()
                track?.release()
            } catch (_: Throwable) {
            }
            clearTrack?.invoke()
        }
    }

    private fun synthesizePcm(notes: List<ToneSpec>): ShortArray {
        val totalSamples = notes.sumOf { (SAMPLE_RATE * it.durationMs) / 1000 }
        if (totalSamples <= 0) return ShortArray(0)
        val buffer = ShortArray(totalSamples)
        var offset = 0

        for (note in notes) {
            val count = (SAMPLE_RATE * note.durationMs) / 1000
            val fadeSamples = min(count / 3, (SAMPLE_RATE * 20) / 1000).coerceAtLeast(1)

            for (i in 0 until count) {
                val t = i.toDouble() / SAMPLE_RATE
                val waveA = sin(2.0 * PI * note.freqA * t)
                val wave = if (note.freqB != null) {
                    (waveA + sin(2.0 * PI * note.freqB * t)) * 0.5
                } else {
                    waveA
                }
                val env = when {
                    i < fadeSamples -> i.toDouble() / fadeSamples
                    i > count - fadeSamples -> (count - i).toDouble() / fadeSamples
                    else -> 1.0
                }.coerceIn(0.0, 1.0)

                val sample = (wave * env * note.amplitude * Short.MAX_VALUE)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
                buffer[offset + i] = sample
            }
            offset += count
        }
        return buffer
    }

    private data class ToneSpec(
        val freqA: Double,
        val freqB: Double? = null,
        val durationMs: Int,
        val amplitude: Double = 0.20
    )

    companion object {
        private const val TAG = "CallSoundEffects"
        private const val SAMPLE_RATE = 24000
    }
}
