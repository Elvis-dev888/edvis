package com.edvis.core.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Manages Bluetooth SCO full-duplex audio routing exclusively through the motorcycle helmet headset.
 */
class BluetoothScoManager(
    private val context: Context,
    private val onScoStateChanged: (Boolean) -> Unit = {}
) {
    companion object {
        private const val TAG = "BluetoothScoManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var receiverRegistered = false

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) {
                val state = intent.getIntExtra(
                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_ERROR
                )
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        Log.i(TAG, "Helmet Bluetooth SCO channel CONNECTED")
                        onScoStateChanged(true)
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        Log.w(TAG, "Helmet Bluetooth SCO channel DISCONNECTED")
                        onScoStateChanged(false)
                    }
                }
            }
        }
    }

    fun startScoChannel() {
        try {
            if (!receiverRegistered) {
                context.registerReceiver(
                    scoReceiver,
                    IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                )
                receiverRegistered = true
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val scoDevice = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (scoDevice != null) {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    val routed = audioManager.setCommunicationDevice(scoDevice)
                    Log.i(TAG, "setCommunicationDevice(${scoDevice.productName}) = $routed")
                    onScoStateChanged(routed)
                    return
                }
            }

            // Si NO hay audífonos Bluetooth conectados, mantenemos MODE_NORMAL
            // para que el altavoz y el micrófono ambiental funcionen a todo volumen sin modo llamada
            audioManager.mode = AudioManager.MODE_NORMAL
            Log.i(TAG, "Sin audífonos Bluetooth -> Modo normal (Micrófono ambiental + Altavoz a volumen completo)")
            onScoStateChanged(false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure audio channel: ${e.message}")
        }
    }

    fun stopScoChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
            }
            audioManager.mode = AudioManager.MODE_NORMAL

            if (receiverRegistered) {
                context.unregisterReceiver(scoReceiver)
                receiverRegistered = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Bluetooth SCO: ${e.message}")
        }
    }
}
