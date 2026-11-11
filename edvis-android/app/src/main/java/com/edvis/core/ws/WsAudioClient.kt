package com.edvis.core.ws

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Full-Duplex WebSocket Client connecting the Android Tactical Client to E.D.V.I.S. Brain Server
 * using a verified Supabase Auth Bearer JWT (or tactical token).
 */
class WsAudioClient(
    private val serverUrl: String,
    private val authToken: String = "",
    private val onServerAudioChunk: (String) -> Unit,
    private val onServerTranscriptOut: (String) -> Unit = {},
    private val onServerInterrupted: () -> Unit = {},
    private val onDeviceActionCommand: (actionType: String, target: String, payload: String) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "WsAudioClient"

        @Volatile
        var activeInstance: WsAudioClient? = null
            private set
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null

    fun connect() {
        val reqBuilder = Request.Builder().url(serverUrl)
        if (authToken.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer $authToken")
            reqBuilder.addHeader("x-edvis-token", authToken)
        }

        webSocket = client.newWebSocket(reqBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to Protected E.D.V.I.S. Brain Server: $serverUrl")
                activeInstance = this@WsAudioClient
                onConnectionStateChanged(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("event")) {
                        "audio_out" -> {
                            val pcmBase64 = json.optString("pcm_base64")
                            if (pcmBase64.isNotEmpty()) {
                                onServerAudioChunk(pcmBase64)
                            }
                        }
                        "interrupted" -> {
                            onServerInterrupted()
                        }
                        "device_action" -> {
                            val actionType = json.optString("action_type")
                            val target = json.optString("target")
                            val payload = json.optString("payload")
                            onDeviceActionCommand(actionType, target, payload)
                        }
                        "transcript_out" -> {
                            val spoken = json.optString("text")
                            Log.d(TAG, "[E.D.V.I.S.]: $spoken")
                            if (spoken.isNotBlank() && json.optBoolean("speak", false)) {
                                onServerTranscriptOut(spoken)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse WS message: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed ($code): $reason")
                if (activeInstance == this@WsAudioClient) activeInstance = null
                onConnectionStateChanged(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                if (activeInstance == this@WsAudioClient) activeInstance = null
                onConnectionStateChanged(false)
            }
        })
    }

    fun sendPcmFrame(pcmBytes: ByteArray) {
        webSocket?.send(pcmBytes.toByteString(0, pcmBytes.size))
    }

    fun sendJsonEvent(event: String, fields: Map<String, Any?> = emptyMap()) {
        val json = JSONObject()
        json.put("event", event)
        for ((k, v) in fields) {
            json.put(k, v)
        }
        webSocket?.send(json.toString())
    }

    fun disconnect() {
        webSocket?.close(1000, "Tactical shutdown")
        webSocket = null
        if (activeInstance == this) activeInstance = null
    }
}
