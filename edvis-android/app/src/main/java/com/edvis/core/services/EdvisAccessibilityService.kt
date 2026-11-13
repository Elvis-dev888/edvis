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
        // FAST ZERO-CPU BAILOUT: Do absolutely nothing unless an automated action is pending
        if (pendingWhatsAppMessage == null && !pendingPlayVoiceNote && !pendingSendWhatsApp && !pendingRecordVoiceNote) {
            return
        }

        val eventPkg = event?.packageName?.toString() ?: ""
        if (!eventPkg.contains("whatsapp")) {
            return
        }

        val rootNode = rootInActiveWindow ?: return
        val pkg = rootNode.packageName?.toString() ?: ""
        if (!pkg.contains("whatsapp")) return

        val messageToInject = pendingWhatsAppMessage
        if (messageToInject != null) {
            val injected = injectAndSendWhatsApp(rootNode, messageToInject)
            if (injected) {
                pendingWhatsAppMessage = null
                Log.i(TAG, "WhatsApp reply injected and dispatched automatically")
            }
        }

        if (pendingSendWhatsApp) {
            val sent = clickSendButtonInWhatsApp(rootNode)
            if (sent) {
                pendingSendWhatsApp = false
                Log.i(TAG, "WhatsApp send button clicked automatically")
            }
        }

        if (pendingPlayVoiceNote) {
            val played = playLastVoiceMessageInWhatsApp(rootNode)
            if (played) {
                pendingPlayVoiceNote = false
                Log.i(TAG, "WhatsApp voice message play button clicked automatically")
            }
        }

        if (pendingRecordVoiceNote) {
            val micClicked = clickVoiceNoteMicButtonInWhatsApp(rootNode)
            if (micClicked) {
                pendingRecordVoiceNote = false
                Log.i(TAG, "WhatsApp voice note mic button triggered")
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

            "TYPE_AND_SEND_WHATSAPP" -> {
                triggerSendWhatsAppMessage()
            }

            "RECORD_VOICE_NOTE_WHATSAPP" -> {
                triggerRecordVoiceNoteInWhatsApp()
            }
        }
    }

    fun triggerSendWhatsAppMessage() {
        pendingSendWhatsApp = true
        rootInActiveWindow?.let { root ->
            if (clickSendButtonInWhatsApp(root)) {
                pendingSendWhatsApp = false
                return
            }
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        for (delay in listOf(300L, 700L, 1200L, 1800L, 2500L, 3500L)) {
            handler.postDelayed({
                if (pendingSendWhatsApp) {
                    rootInActiveWindow?.let { root ->
                        if (clickSendButtonInWhatsApp(root)) {
                            pendingSendWhatsApp = false
                            Log.i(TAG, "WhatsApp send button clicked on retry $delay ms")
                        }
                    }
                }
            }, delay)
        }
    }

    private fun clickSendButtonInWhatsApp(root: AccessibilityNodeInfo): Boolean {
        val sendIds = listOf("com.whatsapp:id/send", "com.whatsapp:id/send_button", "com.whatsapp:id/btn_send")
        for (id in sendIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            val btn = nodes.firstOrNull()
            if (btn != null) {
                if (btn.isClickable) {
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                btn.parent?.let { parent ->
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                }
            }
        }
        return clickNodeByTextOrDesc(root, listOf("enviar", "send"))
    }

    fun triggerRecordVoiceNoteInWhatsApp() {
        pendingRecordVoiceNote = true
        rootInActiveWindow?.let { root ->
            if (clickVoiceNoteMicButtonInWhatsApp(root)) {
                pendingRecordVoiceNote = false
                return
            }
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        for (delay in listOf(500L, 1000L, 1600L, 2400L, 3200L)) {
            handler.postDelayed({
                if (pendingRecordVoiceNote) {
                    rootInActiveWindow?.let { root ->
                        if (clickVoiceNoteMicButtonInWhatsApp(root)) {
                            pendingRecordVoiceNote = false
                            Log.i(TAG, "WhatsApp voice note mic button triggered on retry $delay ms")
                        }
                    }
                }
            }, delay)
        }
    }

    private fun clickVoiceNoteMicButtonInWhatsApp(root: AccessibilityNodeInfo): Boolean {
        val micIds = listOf("com.whatsapp:id/voice_note_btn", "com.whatsapp:id/btn_voice_note", "com.whatsapp:id/mic_button")
        for (id in micIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            val btn = nodes.firstOrNull()
            if (btn != null) {
                if (btn.isClickable) {
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                btn.parent?.let { parent ->
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                }
            }
        }
        return clickNodeByTextOrDesc(root, listOf("mensaje de voz", "grabar mensaje de voz", "voice message", "record voice message"))
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

    fun clickAt(x: Float, y: Float): Boolean {
        return try {
            val path = android.graphics.Path().apply {
                moveTo(x, y)
            }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 100L)
            val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
            val dispatched = dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.i(TAG, "Hardware gesture tap completed at ($x, $y)")
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "Hardware gesture tap cancelled at ($x, $y)")
                }
            }, null)
            Log.i(TAG, "dispatchGesture at ($x, $y) returned $dispatched")
            dispatched
        } catch (e: Exception) {
            Log.e(TAG, "dispatchGesture failed: ${e.message}")
            false
        }
    }

    private fun clickNodeWithHardwareTapFallback(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.i(TAG, "Clicked node via ACTION_CLICK")
            return true
        }
        var p = node.parent
        var depth = 0
        while (p != null && depth < 3) {
            if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "Clicked parent node via ACTION_CLICK at depth $depth")
                return true
            }
            p = p.parent
            depth++
        }
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            val cx = bounds.centerX().toFloat()
            val cy = bounds.centerY().toFloat()
            Log.i(TAG, "Dispatching hardware touch gesture tap at ($cx, $cy)")
            return clickAt(cx, cy)
        }
        return false
    }

    fun triggerPlayLastVoiceMessageInWhatsApp() {
        pendingPlayVoiceNote = true
        rootInActiveWindow?.let { root ->
            if (playLastVoiceMessageInWhatsApp(root)) {
                pendingPlayVoiceNote = false
                return
            }
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val delays = listOf(300L, 600L, 1000L, 1500L, 2200L, 3000L, 4000L, 5500L, 7000L)
        for (delay in delays) {
            handler.postDelayed({
                if (pendingPlayVoiceNote) {
                    rootInActiveWindow?.let { root ->
                        if (playLastVoiceMessageInWhatsApp(root)) {
                            pendingPlayVoiceNote = false
                            Log.i(TAG, "WhatsApp voice message play button clicked on retry $delay ms")
                        }
                    }
                }
            }, delay)
        }
    }

    private fun playLastVoiceMessageInWhatsApp(root: AccessibilityNodeInfo): Boolean {
        // If we are currently looking at WhatsApp's chat list, tap the top chat to open it
        val chatListNodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversations_row_holder")
            .ifEmpty { root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversations_row_contact_name") }
            .ifEmpty { root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/chat_title") }

        val voiceIds = listOf(
            "com.whatsapp:id/conversation_row_sound_recording_play_button",
            "com.whatsapp:id/conversation_row_sound_recording_play_pause_button",
            "com.whatsapp:id/play",
            "com.whatsapp:id/play_pause_button",
            "com.whatsapp:id/btn_play_pause",
            "com.whatsapp:id/voice_note_play",
            "com.whatsapp:id/btn_play",
            "com.whatsapp:id/play_btn",
            "com.whatsapp:id/control_btn",
            "com.whatsapp:id/voice_note_slider",
            "com.whatsapp:id/waveform_slider",
            "com.whatsapp:id/voice_status_playback_view",
            "com.whatsapp:id/audio_player_view",
            "com.whatsapp:id/voice_note_view"
        )

        val candidateNodes = mutableListOf<AccessibilityNodeInfo>()
        for (id in voiceIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                candidateNodes.addAll(nodes)
            }
        }

        collectVoiceNodesByTextOrDesc(root, candidateNodes)

        if (candidateNodes.isNotEmpty()) {
            // Sort candidates by screen bottom descending to pick the newest (bottom-most) audio in chat
            val sorted = candidateNodes.distinct().sortedByDescending { n ->
                val r = android.graphics.Rect()
                n.getBoundsInScreen(r)
                r.bottom
            }

            for (btn in sorted) {
                val r = android.graphics.Rect()
                btn.getBoundsInScreen(r)
                if (r.width() <= 0 || r.height() <= 0) continue

                if (clickNodeWithHardwareTapFallback(btn)) {
                    Log.i(TAG, "Triggered WhatsApp voice note button at (${r.centerX()}, ${r.centerY()})")
                    return true
                }
            }
        }

        // Fallback: If no voice note buttons found, but we are on the chats list, open the first chat
        if (chatListNodes.isNotEmpty()) {
            val firstChat = chatListNodes.firstOrNull()
            if (firstChat != null) {
                Log.i(TAG, "On chats list: opening topmost chat to find voice note")
                clickNodeWithHardwareTapFallback(firstChat)
                return false
            }
        }

        return false
    }

    private fun collectVoiceNodesByTextOrDesc(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""

        val isPlay = desc.contains("reproducir") || desc.contains("play") || desc.contains("escuchar") ||
            desc.contains("mensaje de voz") || desc.contains("nota de voz") ||
            text.contains("reproducir") || text.contains("play") || text.contains("escuchar")

        val isPause = desc.contains("pausar") || desc.contains("pause") || text.contains("pausar") || text.contains("pause")

        if (isPlay && !isPause) {
            list.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectVoiceNodesByTextOrDesc(child, list)
        }
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

        @Volatile
        private var pendingSendWhatsApp: Boolean = false

        @Volatile
        private var pendingRecordVoiceNote: Boolean = false

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
        val inputNode = entryNodes.firstOrNull()
        if (inputNode != null) {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        return clickSendButtonInWhatsApp(root)
    }
}
