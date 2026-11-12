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
        var lastMessageText: String? = null
            private set

        @Volatile
        var lastMessageTime: Long = 0L
            private set

        @Volatile
        var callAnswerAction: Notification.Action? = null
            private set

        @Volatile
        var callRejectAction: Notification.Action? = null
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
            "com.samsung.android.messaging",
            "com.google.android.dialer",
            "com.samsung.android.incallui",
            "com.android.phone",
            "com.android.server.telecom"
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

        val isWatched = pkg in WATCHED_PACKAGES || pkg.contains("whatsapp") || pkg.contains("dialer") ||
            pkg.contains("telecom") || pkg.contains("phone") || pkg.contains("messenger") || pkg.contains("instagram")
        if (!isWatched) return

        val extras = notification.extras ?: return
        var sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: extras.getString("android.title")
            ?: ""

        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
            ?: ""

        val lowerText = text.lowercase().trim()
        val lowerSender = sender.lowercase().trim()

        // Ignore pure group summary count headers ("3 mensajes de 2 chats")
        val isPureGroupCount = lowerText.contains(Regex("\\d+\\s+mensajes?\\s+de\\s+\\d+\\s+chats?")) ||
            lowerText.contains(Regex("\\d+\\s+nuevos?\\s+mensajes?")) ||
            lowerSender.contains(Regex("\\d+\\s+mensajes?"))
        if (isPureGroupCount) return

        val category = notification.category ?: ""
        val isCall = category == Notification.CATEGORY_CALL ||
            lowerText.contains("llamada") || lowerText.contains("call") ||
            lowerSender.contains("llamada") || (notification.flags and Notification.FLAG_INSISTENT) != 0

        val isAudio = !isCall && (lowerText.contains("audio") || lowerText.contains("nota de voz") ||
            lowerText.contains("mensaje de voz") || lowerText.contains("voice message") ||
            lowerText.contains("ptt") || lowerText.startsWith("🎤") || text.isBlank())

        // Ignore generic sync background notifications
        if (lowerText.contains("checking for new messages") || lowerText.contains("comprobando si hay mensajes nuevos")) return

        if (sender.isBlank()) {
            sender = when {
                pkg.contains("whatsapp") -> "WhatsApp"
                pkg.contains("instagram") -> "Instagram"
                pkg.contains("orca") || pkg.contains("facebook") -> "Messenger"
                pkg.contains("telegram") -> "Telegram"
                else -> "Un contacto"
            }
        }

        if (text.isBlank() && isAudio) {
            text = "Nota de voz"
        }

        // Cache actions for both Quick-Reply and Call Answering
        notification.actions?.forEach { action ->
            val title = action.title?.toString()?.lowercase() ?: ""
            if (title.contains("contesta") || title.contains("responder") || title.contains("aceptar") || title.contains("answer")) {
                callAnswerAction = action
            } else if (title.contains("rechaza") || title.contains("cuelga") || title.contains("rechazar") || title.contains("decline") || title.contains("reject")) {
                callRejectAction = action
            }

            val inputs = action.remoteInputs
            if (!inputs.isNullOrEmpty()) {
                val handle = QuickReplyHandle(action, inputs)
                replyHandles[sender.lowercase().trim()] = handle
                replyHandles["last_sender"] = handle
            }
        }

        lastSenderName = sender
        lastSenderApp = when {
            pkg.contains("whatsapp") -> "WhatsApp"
            pkg.contains("instagram") -> "Instagram"
            pkg.contains("orca") || pkg.contains("facebook") -> "Messenger"
            pkg.contains("tiktok") || pkg.contains("trill") -> "TikTok"
            pkg.contains("telegram") -> "Telegram"
            pkg.contains("dialer") || pkg.contains("telecom") || pkg.contains("phone") -> "Teléfono"
            else -> "Mensaje de texto"
        }
        lastMessageText = text
        lastMessageTime = System.currentTimeMillis()

        Log.i(TAG, "Intercepted [$lastSenderApp] from $sender (isCall=$isCall, isAudio=$isAudio): $text")

        if (isCall) {
            EdvisForegroundService.instance?.handleIncomingVoipCall(
                app = lastSenderApp ?: "Llamada",
                caller = sender
            )
        } else {
            EdvisForegroundService.instance?.handleIncomingNotification(
                app = lastSenderApp ?: "WhatsApp",
                sender = sender,
                text = text,
                isAudio = isAudio
            )
        }

        WsAudioClient.activeInstance?.sendJsonEvent(
            event = "incoming_notification",
            fields = mapOf(
                "app" to pkg,
                "sender" to sender,
                "text" to text,
                "is_call" to isCall,
                "is_audio" to isAudio,
                "timestamp" to System.currentTimeMillis()
            )
        )
    }

    fun answerVoipCall(): Boolean {
        return try {
            val act = callAnswerAction
            if (act != null) {
                act.actionIntent.send()
                callAnswerAction = null
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering VoIP answer intent: ${e.message}")
            false
        }
    }

    fun rejectVoipCall(): Boolean {
        return try {
            val act = callRejectAction
            if (act != null) {
                act.actionIntent.send()
                callRejectAction = null
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering VoIP reject intent: ${e.message}")
            false
        }
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
