package com.edvis.core.services

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.edvis.core.ws.WsAudioClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Intercepts incoming WhatsApp, SMS, and system notifications in real time,
 * forwards them to E.D.V.I.S. Brain Server via WebSocket, and caches RemoteInput actions
 * for instant zero-UI background replies.
 */
class EdvisNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "EdvisNotifService"

        @Volatile
        var instance: EdvisNotificationService? = null
            private set

        @Volatile
        var lastSenderName: String? = null
            private set

        @Volatile
        var lastSenderApp: String? = null
            private set

        @Volatile
        var lastMessageTime: Long = 0L
            private set

        private val WATCHED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.instagram.android",
            "com.facebook.orca",
            "com.facebook.katana",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "org.telegram.messenger",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging"
        )
    }

    private data class QuickReplyHandle(
        val action: Notification.Action,
        val remoteInputs: Array<RemoteInput>
    )

    private val replyHandles = ConcurrentHashMap<String, QuickReplyHandle>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "EdvisNotificationService connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return

        if (pkg !in WATCHED_PACKAGES) return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notification.extras ?: return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return

        // Ignore empty or system status texts
        if (text.isBlank() || text.contains("mensajes nuevos", true) || text.contains("checking for new messages", true)) return

        // Cache RemoteInput action for instant hands-free reply without opening the screen
        notification.actions?.forEach { action ->
            val inputs = action.remoteInputs
            if (!inputs.isNullOrEmpty()) {
                val handle = QuickReplyHandle(action, inputs)
                replyHandles[sender.lowercase().trim()] = handle
                replyHandles["last_sender"] = handle
            }
        }

        lastSenderName = sender
        lastSenderApp = when (pkg) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "com.instagram.android" -> "Instagram"
            "com.facebook.orca", "com.facebook.katana" -> "Messenger"
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> "TikTok"
            "org.telegram.messenger" -> "Telegram"
            else -> "Mensaje de texto"
        }
        lastMessageTime = System.currentTimeMillis()

        val lowerText = text.lowercase().trim()
        val isAudio = lowerText.contains("audio") || lowerText.contains("nota de voz") ||
            lowerText.contains("mensaje de voz") || lowerText.contains("voice message") ||
            lowerText.contains("ptt") || lowerText.startsWith("🎤")

        Log.i(TAG, "Intercepted [$lastSenderApp] from $sender (isAudio=$isAudio): $text")

        // Wake screen, play tone and discreetly announce in natural female voice, entering interactive read/reply flow
        EdvisForegroundService.instance?.let { svc ->
            svc.handleIncomingNotification(
                app = lastSenderApp ?: "WhatsApp",
                sender = sender,
                text = text,
                isAudio = isAudio
            )
        }

        // Forward to WebSocket if connected
        WsAudioClient.activeInstance?.sendJsonEvent(
            event = "incoming_notification",
            fields = mapOf(
                "app" to pkg,
                "sender" to sender,
                "text" to text,
                "timestamp" to System.currentTimeMillis()
            )
        )
    }

    /**
     * Dispatches an instant background reply using Android's Notification RemoteInput
     * (works even with screen locked inside pocket/backpack).
     */
    fun replyToSender(targetSender: String, messageText: String): Boolean {
        val key = targetSender.lowercase().trim()
        val handle = replyHandles[key]
            ?: replyHandles.entries.firstOrNull { it.key.contains(key) || key.contains(it.key) }?.value
            ?: replyHandles["last_sender"]
            ?: replyHandles["LAST_SENDER"]
            ?: return false

        return try {
            val fillInIntent = Intent()
            val bundle = Bundle()
            for (remoteInput in handle.remoteInputs) {
                bundle.putCharSequence(remoteInput.resultKey, messageText)
            }
            RemoteInput.addResultsToIntent(handle.remoteInputs, fillInIntent, bundle)
            handle.action.actionIntent.send(applicationContext, 0, fillInIntent)
            Log.i(TAG, "Dispatched background QuickReply to '$targetSender': $messageText")
            true
        } catch (e: Exception) {
            Log.e(TAG, "QuickReply failed: ${e.message}")
            false
        }
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }
}
