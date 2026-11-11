package com.edvis.core.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * OS-Level Accessibility Service capable of injecting text and triggering send buttons
 * in WhatsApp or placing hands-free phone calls without screen touch.
 */
class EdvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "EdvisAccessibilityService connected and armed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rootNode = rootInActiveWindow ?: return
        val pkg = rootNode.packageName?.toString() ?: ""

        if (pkg.contains("com.whatsapp")) {
            val messageToInject = pendingWhatsAppMessage
            if (messageToInject != null) {
                val injected = injectAndSendWhatsApp(rootNode, messageToInject)
                if (injected) {
                    pendingWhatsAppMessage = null
                    Log.i(TAG, "WhatsApp reply injected and dispatched automatically")
                }
            }

            if (pendingPlayVoiceNote) {
                val played = playLastVoiceMessageInWhatsApp(rootNode)
                if (played) {
                    pendingPlayVoiceNote = false
                    Log.i(TAG, "WhatsApp voice message play button clicked automatically")
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    /**
     * Executes a device action dispatched by E.D.V.I.S. Brain Server.
     */
    fun executeAction(actionType: String, target: String, payload: String) {
        when (actionType.uppercase()) {
            "REPLY_WHATSAPP" -> {
                // First try direct Quick-Reply via NotificationListener if available
                val repliedViaNotification =
                    EdvisNotificationService.instance?.replyToSender(target, payload) ?: false

                if (!repliedViaNotification) {
                    // Fallback: Launch WhatsApp deep link and inject via Accessibility node tree
                    pendingWhatsAppMessage = payload
                    val phoneDigits = target.replace(Regex("[^0-9]"), "")
                    if (phoneDigits.isNotEmpty()) {
                        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phoneDigits")
                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                            setPackage("com.whatsapp")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    } else {
                        // Try injecting into currently active window if WhatsApp is open
                        rootInActiveWindow?.let { injectAndSendWhatsApp(it, payload) }
                    }
                }
            }

            "MAKE_PHONE_CALL" -> {
                val phone = target.replace(Regex("[^0-9+]"), "")
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(callIntent)
            }

            "OPEN_APP" -> {
                launchAnyAppByNameOrPackage(this, target)
            }

            "PLAY_MUSIC" -> {
                try {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                    rootInActiveWindow?.let { root ->
                        clickNodeByTextOrDesc(root, listOf("reproducir", "play", "reanudar", "escuchar"))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error playing music: ${e.message}")
                }
            }

            "PAUSE_MUSIC" -> {
                try {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
                } catch (e: Exception) {
                    Log.e(TAG, "Error pausing music: ${e.message}")
                }
            }

            "NEXT_TRACK" -> {
                try {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
                } catch (e: Exception) {
                    Log.e(TAG, "Error skipping track: ${e.message}")
                }
            }

            "PREV_TRACK" -> {
                try {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                } catch (e: Exception) {
                    Log.e(TAG, "Error prev track: ${e.message}")
                }
            }
        }
    }

    private fun clickNodeByTextOrDesc(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (keywords.any { text.contains(it) || desc.contains(it) }) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickNodeByTextOrDesc(child, keywords)) return true
        }
        return false
    }

    fun triggerPlayLastVoiceMessageInWhatsApp() {
        pendingPlayVoiceNote = true
        rootInActiveWindow?.let { root ->
            if (playLastVoiceMessageInWhatsApp(root)) {
                pendingPlayVoiceNote = false
            }
        }
    }

    private fun playLastVoiceMessageInWhatsApp(root: AccessibilityNodeInfo): Boolean {
        val voiceIds = listOf(
            "com.whatsapp:id/conversation_row_sound_recording_play_button",
            "com.whatsapp:id/play",
            "com.whatsapp:id/play_pause_button",
            "com.whatsapp:id/voice_note_play"
        )
        for (id in voiceIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            val lastBtn = nodes.lastOrNull()
            if (lastBtn != null && lastBtn.isClickable) {
                lastBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        return clickLastNodeByTextOrDesc(root, listOf("reproducir", "play", "escuchar"))
    }

    private fun clickLastNodeByTextOrDesc(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        for (i in (node.childCount - 1) downTo 0) {
            val child = node.getChild(i) ?: continue
            if (clickLastNodeByTextOrDesc(child, keywords)) return true
        }

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (keywords.any { text.contains(it) || desc.contains(it) }) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "EdvisAccessibility"

        @Volatile
        var instance: EdvisAccessibilityService? = null
            private set

        @Volatile
        private var pendingWhatsAppMessage: String? = null

        @Volatile
        private var pendingPlayVoiceNote: Boolean = false

        /**
         * Opens ANY installed application on the phone by matching either its package name
         * or its human-visible label (e.g., "WhatsApp", "YouTube", "Nequi", "Instagram", "Spotify", etc.).
         */
        fun launchAnyAppByNameOrPackage(context: android.content.Context, query: String): Boolean {
            val pm = context.packageManager
            var clean = query.trim().lowercase()
            clean = clean.replace(Regex("^(el|la|los|las|un|una|mi|por\\s+favor|app\\s+de|aplicacion\\s+de)\\s+"), "").trim()
            if (clean.isEmpty()) return false

            // Common instant aliases
            val aliasMap = mapOf(
                "whatsapp" to "com.whatsapp",
                "wasap" to "com.whatsapp",
                "wsp" to "com.whatsapp",
                "youtube" to "com.google.android.youtube",
                "instagram" to "com.instagram.android",
                "ig" to "com.instagram.android",
                "spotify" to "com.spotify.music",
                "musica" to "com.spotify.music",
                "música" to "com.spotify.music",
                "nequi" to "com.nequi.MobileApp",
                "camara" to "com.android.camera",
                "cámara" to "com.android.camera",
                "fotos" to "com.google.android.apps.photos",
                "waze" to "com.waze",
                "maps" to "com.google.android.apps.maps",
                "mapa" to "com.google.android.apps.maps",
                "facebook" to "com.facebook.katana",
                "fb" to "com.facebook.katana",
                "tiktok" to "com.zhiliaoapp.musically",
                "telegram" to "org.telegram.messenger",
                "ajustes" to "com.android.settings",
                "configuracion" to "com.android.settings",
                "configuración" to "com.android.settings",
                "reloj" to "com.google.android.deskclock",
                "alarma" to "com.google.android.deskclock",
                "calculadora" to "com.google.android.calculator"
            )

            val fastPkg = aliasMap[clean]
            if (fastPkg != null) {
                pm.getLaunchIntentForPackage(fastPkg)?.let { intent ->
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    context.startActivity(intent)
                    Log.i(TAG, "Launched fast alias $fastPkg for '$query'")
                    return true
                }
            }

            // 1. Direct package match
            pm.getLaunchIntentForPackage(clean)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(intent)
                return true
            }

            // 2. Search all installed apps by visible label or package substring (enabled by QUERY_ALL_PACKAGES)
            val apps = pm.getInstalledApplications(0)
            val matchedApp = apps.firstOrNull { appInfo ->
                val label = pm.getApplicationLabel(appInfo).toString().lowercase()
                val pkg = appInfo.packageName.lowercase()
                label == clean || label.contains(clean) || clean.contains(label) || pkg.contains(clean)
            }

            if (matchedApp != null) {
                val launchIntent = pm.getLaunchIntentForPackage(matchedApp.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    context.startActivity(launchIntent)
                    Log.i(TAG, "Launched app ${matchedApp.packageName} for query '$query'")
                    return true
                }
            }
            return false
        }
    }

    private fun injectAndSendWhatsApp(root: AccessibilityNodeInfo, text: String): Boolean {
        // Locate message input field (com.whatsapp:id/entry)
        val entryNodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry")
        val inputNode = entryNodes.firstOrNull() ?: return false

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        // Locate and click Send button (com.whatsapp:id/send)
        val sendNodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
        val sendBtn = sendNodes.firstOrNull()
        if (sendBtn != null && sendBtn.isClickable) {
            sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        return false
    }
}
