package com.edvis.core.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log
import com.edvis.core.services.EdvisForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Full-Duplex 16kHz PCM Audio Streamer with hardware NoiseSuppression & AcousticEchoCanceler
 * for high-wind motorcycle helmet environments.
 */
class PcmStreamer(
    private val wakeEngine: VoskWakeEngine,
    private val onAudioChunkReady: (ByteArray) -> Unit,
    private val onWakeTriggered: () -> Unit
) {
    companion object {
        private const val TAG = "PcmStreamer"
        const val INPUT_SAMPLE_RATE = 16000
        const val OUTPUT_SAMPLE_RATE = 24000 // Gemini 3.8 Live output PCM rate
        private const val ACTIVE_WINDOW_MS = 25_000L
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureJob: Job? = null

    @Volatile
    private var activeUntilTimestamp: Long = 0L

    fun activateVoiceWindow(durationMs: Long = ACTIVE_WINDOW_MS) {
        activeUntilTimestamp = System.currentTimeMillis() + durationMs
    }

    fun isVoiceWindowActive(): Boolean {
        return System.currentTimeMillis() <= activeUntilTimestamp
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        val minBufSize = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        // Preferimos AudioSource.MIC para máxima sensibilidad omnidireccional ambiental (escucha en cama/mesa)
        var record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufSize * 2
            )
        } catch (_: Exception) { null }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufSize * 2
            )
        }

        audioRecord = record.apply {
            if (AutomaticGainControl.isAvailable()) {
                try {
                    AutomaticGainControl.create(audioSessionId)?.enabled = true
                    Log.i(TAG, "Hardware Automatic Gain Control (AGC) ENABLED for normal voice sensitivity")
                } catch (_: Exception) {}
            }
            if (NoiseSuppressor.isAvailable()) {
                try { NoiseSuppressor.create(audioSessionId)?.enabled = true } catch (_: Exception) {}
            }
            if (AcousticEchoCanceler.isAvailable()) {
                try { AcousticEchoCanceler.create(audioSessionId)?.enabled = true } catch (_: Exception) {}
            }
            try { startRecording() } catch (_: Exception) {}
        }

        val outBufSize = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4800)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(outBufSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }

        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(3200) // 100ms frame at 16kHz 16-bit mono
            Log.i(TAG, "Duplex PCM capture loop started")

            while (isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    val isTtsSpeaking = EdvisForegroundService.instance?.isTtsSpeaking() == true
                    val isWindowActive = isVoiceWindowActive()

                    // If EDVIS TTS is currently speaking, do not process audio into Vosk to prevent echo loops
                    if (!isTtsSpeaking && wakeEngine.isReady) {
                        val woke = wakeEngine.acceptPcmFrame(buffer, read, isWindowActive)
                        if (woke) {
                            activateVoiceWindow()
                            onWakeTriggered()
                        }
                        if (isWindowActive) {
                            onAudioChunkReady(buffer.copyOf(read))
                        }
                    } else if (!isTtsSpeaking) {
                        onAudioChunkReady(buffer.copyOf(read))
                    }
                }
            }
        }
    }

    /**
     * Plays incoming 24kHz PCM audio from Gemini 3.8 Live directly to the helmet speaker.
     */
    fun playServerPcmBase64(pcmBase64: String) {
        try {
            val pcmBytes = Base64.decode(pcmBase64, Base64.DEFAULT)
            activateVoiceWindow(15_000L)
            audioTrack?.write(pcmBytes, 0, pcmBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing server PCM: ${e.message}")
        }
    }

    /**
     * Clears the playback queue immediately when Gemini 3.8 Live signals barge-in (`interrupted: true`).
     */
    fun flushPlaybackOnInterruption() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
            Log.d(TAG, "Flushed AudioTrack queue on operator barge-in")
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing AudioTrack: ${e.message}")
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
    }
}
