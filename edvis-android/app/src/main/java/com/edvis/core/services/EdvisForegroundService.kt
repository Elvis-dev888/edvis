package com.edvis.core.services

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import java.io.File
import java.util.Locale
import androidx.core.app.NotificationCompat
import com.edvis.core.MainActivity
import com.edvis.core.audio.BluetoothScoManager
import com.edvis.core.audio.PcmStreamer
import com.edvis.core.audio.VoskWakeEngine
import com.edvis.core.telemetry.GpsTracker
import com.edvis.core.ws.WsAudioClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Persistent Foreground Service orchestrating Helmet Bluetooth SCO, Vosk Local Wake Word,
 * Full-Duplex PCM WebSocket streaming, and GPS Telemetry.
 */
class EdvisForegroundService : Service() {

    companion object {
        private const val TAG = "EdvisForegroundSvc"
        private const val CHANNEL_ID = "edvis_tactical_channel"
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_WS_URL = "extra_ws_url"
        const val EXTRA_AUTH_TOKEN = "extra_auth_token"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var instance: EdvisForegroundService? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var bluetoothScoManager: BluetoothScoManager
    private lateinit var voskWakeEngine: VoskWakeEngine
    private var pcmStreamer: PcmStreamer? = null
    private var wsAudioClient: WsAudioClient? = null
    private var gpsTracker: GpsTracker? = null
    private var tts: TextToSpeech? = null

    @Volatile
    var isCallRinging = false
        private set
    private var ringingNumber: String? = null
    private var callReceiver: BroadcastReceiver? = null

    enum class ConversationState {
        IDLE,
        AWAITING_READ_CONFIRMATION,      // "Señor, tiene un nuevo mensaje de [Persona] en [App]. ¿Desea que se lo lea?"
        AWAITING_REPLY_CONFIRMATION,     // "¿Deseas responder?"
        AWAITING_REPLY_MESSAGE           // "¿Qué mensaje le digo a [Persona]?"
    }

    @Volatile
    var conversationState = ConversationState.IDLE
        private set

    @Volatile
    var currentReplyTarget: String? = null
        private set

    @Volatile
    var currentReplyApp: String? = null
        private set

    @Volatile
    var currentPendingMessageText: String? = null
        private set

    @Volatile
    var isPendingMessageAudio: Boolean = false
        private set

    @Volatile
    var conversationExpiresAt: Long = 0L
        private set

    fun hasActiveConversation(): Boolean {
        if (conversationState != ConversationState.IDLE && System.currentTimeMillis() <= conversationExpiresAt) {
            return true
        }
        if (conversationState != ConversationState.IDLE && System.currentTimeMillis() > conversationExpiresAt) {
            conversationState = ConversationState.IDLE
            currentReplyTarget = null
            currentReplyApp = null
            currentPendingMessageText = null
            isPendingMessageAudio = false
        }
        return false
    }

    fun isTtsSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    fun handleIncomingNotification(app: String, sender: String, text: String, isAudio: Boolean = false) {
        wakeUpScreen()
        currentReplyTarget = sender
        currentReplyApp = app
        currentPendingMessageText = text
        isPendingMessageAudio = isAudio
        conversationState = ConversationState.AWAITING_READ_CONFIRMATION
        conversationExpiresAt = System.currentTimeMillis() + 45_000L
        activateVoiceListeningWindow(45_000L)

        if (isAudio) {
            speakInFemaleVoice("Señor, tiene una nota de voz de $sender en $app. ¿Desea que la reproduzca?")
        } else {
            speakInFemaleVoice("Señor, tiene un mensaje de $sender en $app. ¿Desea que se lo lea?")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        acquireWakeLock()
        ensureAdequateVolume()
        initFemaleTts()
        registerCallReceiver()
    }

    private fun initFemaleTts() {
        tts = TextToSpeech(this, { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureFemaleVoice()
            }
        }, "com.google.android.tts")
    }

    fun configureFemaleVoice() {
        tts?.language = Locale("es", "CO")
        tts?.setPitch(1.08f)
        tts?.setSpeechRate(1.05f)

        try {
            val voices = tts?.voices
            val femaleVoice = voices?.firstOrNull { voice ->
                val name = voice.name.lowercase()
                voice.locale.language == "es" && (name.contains("female") || name.contains("mujer") || name.contains("sfb") || name.contains("eea") || name.contains("eed"))
            } ?: voices?.firstOrNull { it.locale.language == "es" }

            if (femaleVoice != null) {
                tts?.voice = femaleVoice
                Log.i(TAG, "Selected natural female voice: ${femaleVoice.name}")
            }
        } catch (_: Exception) {}
    }

    fun speakInFemaleVoice(text: String) {
        ensureAdequateVolume()
        val currentTts = tts
        if (currentTts != null) {
            currentTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "edvis_female_voice")
        } else {
            initFemaleTts()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "edvis_female_voice")
            }, 600L)
        }
    }

    fun activateVoiceListeningWindow(durationMs: Long = 30_000L) {
        pcmStreamer?.activateVoiceWindow(durationMs)
    }

    private fun registerCallReceiver() {
        try {
            callReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                        val incomingNum = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

                        when (stateStr) {
                            TelephonyManager.EXTRA_STATE_RINGING -> {
                                isCallRinging = true
                                ringingNumber = incomingNum
                                val callerName = resolveContactName(incomingNum) ?: incomingNum ?: "Desconocido"
                                wakeUpScreen()
                                activateVoiceListeningWindow(35_000L)
                                speakInFemaleVoice("Llamada entrante de $callerName, señor. ¿Deseas que conteste o cuelgue?")
                            }
                            TelephonyManager.EXTRA_STATE_OFFHOOK, TelephonyManager.EXTRA_STATE_IDLE -> {
                                isCallRinging = false
                                ringingNumber = null
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            registerReceiver(callReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering call receiver: ${e.message}")
        }
    }

    fun answerIncomingCall() {
        try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                telecomManager.acceptRingingCall()
                isCallRinging = false
                speakInFemaleVoice("Llamada conectada.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error answering call: ${e.message}")
        }
    }

    fun rejectIncomingCall() {
        try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager.endCall()
                isCallRinging = false
                speakInFemaleVoice("Llamada rechazada.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rejecting call: ${e.message}")
        }
    }

    private fun resolveContactName(phoneNumber: String?): String? {
        if (phoneNumber.isNullOrBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val cursor = contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(0)
                } else null
            }
        } catch (_: Exception) { null }
    }

    private fun ensureAdequateVolume() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (curVol < (maxVol * 0.7f).toInt()) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 0.85f).toInt(), 0)
            }
        } catch (_: Exception) {}
    }

    fun wakeUpScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val screenLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "EDVIS::ScreenWakeTrigger"
            )
            screenLock.acquire(4000L)
        } catch (e: Exception) {
            Log.e(TAG, "Error waking screen: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val wsUrl = intent?.getStringExtra(EXTRA_WS_URL)
            ?: "ws://192.168.1.7:8080/ws/edvis"
        val authToken = intent?.getStringExtra(EXTRA_AUTH_TOKEN) ?: ""

        // Android 14+ STRICT requirement: Pass foregroundServiceType when microphone is used in background
        val notification = buildNotification("ONLINE — Escucha 24/7 Pantalla Apagada Activa")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true

        // 1. Route audio exclusively through Motorcycle Helmet Bluetooth SCO (or speakerphone)
        bluetoothScoManager = BluetoothScoManager(this) { connected ->
            Log.i(TAG, "SCO Headset Connected: $connected")
        }
        bluetoothScoManager.startScoChannel()

        // 2. Initialize Offline Vosk Wake Word Engine ("edvis")
        voskWakeEngine = VoskWakeEngine(this) { spokenText ->
            processAutonomousVoiceCommand(spokenText)
            wsAudioClient?.sendJsonEvent("wake_word_detected", mapOf("text" to spokenText))
        }

        // Initialize Vosk in background so startup doesn't freeze
        serviceScope.launch(Dispatchers.IO) {
            val ok = voskWakeEngine.initialize()
            withContext(Dispatchers.Main) {
                if (ok) {
                    try {
                        val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 95)
                        tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
                    } catch (_: Exception) {}
                    tts?.speak("E.D.V.I.S. en línea y escuchando. Puede bloquear la pantalla con total tranquilidad.", TextToSpeech.QUEUE_FLUSH, null, "edvis_startup")
                }
            }
        }

        // 3. Connect Full-Duplex WebSocket to E.D.V.I.S. Brain Server
        wsAudioClient = WsAudioClient(
            serverUrl = wsUrl,
            authToken = authToken,
            onServerAudioChunk = { pcmBase64 ->
                pcmStreamer?.playServerPcmBase64(pcmBase64)
            },
            onServerTranscriptOut = { spokenText ->
                pcmStreamer?.activateVoiceWindow(30_000L)
                tts?.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, "edvis_utterance")
            },
            onServerInterrupted = {
                pcmStreamer?.flushPlaybackOnInterruption()
                tts?.stop()
            },
            onDeviceActionCommand = { actionType, target, payload ->
                EdvisAccessibilityService.instance?.executeAction(actionType, target, payload)
                    ?: EdvisNotificationService.instance?.replyToSender(target, payload)
            },
            onConnectionStateChanged = { connected ->
                val statusText = if (connected) {
                    "CONECTADO — Canal Táctico Duplex Activo (Gemini 3.8 Live)"
                } else {
                    "EN ESPERA — Vosk Offline Autónomo Listo en 2do Plano"
                }
                updateNotification(statusText)
            }
        ).also { it.connect() }

        // 4. Start 16kHz PCM Audio Streamer
        pcmStreamer = PcmStreamer(
            wakeEngine = voskWakeEngine,
            onAudioChunkReady = { pcmBytes ->
                wsAudioClient?.sendPcmFrame(pcmBytes)
            },
            onWakeTriggered = {
                // Triggered in VoskWakeEngine
            }
        ).also { it.start(serviceScope) }

        // 5. Start Real-Time GPS Telemetry for Dynamic Route Bypass
        gpsTracker = GpsTracker(this) { lat, lng, speedKmh, bearing ->
            wsAudioClient?.sendJsonEvent(
                event = "gps_telemetry",
                fields = mapOf(
                    "lat" to lat,
                    "lng" to lng,
                    "speed_kmh" to speedKmh,
                    "bearing" to bearing
                )
            )
        }.also { it.startTracking() }

        return START_STICKY
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        isRunning = false
        try {
            callReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {}
        callReceiver = null
        gpsTracker?.stopTracking()
        pcmStreamer?.stop()
        voskWakeEngine.release()
        wsAudioClient?.disconnect()
        bluetoothScoManager.stopScoChannel()
        tts?.stop()
        tts?.shutdown()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun processAutonomousVoiceCommand(spokenText: String) {
        if (isTtsSpeaking()) {
            Log.d(TAG, "Ignoring audio command while TTS is speaking")
            return
        }

        Log.i(TAG, "Autonomous background voice command detected: $spokenText")
        pcmStreamer?.activateVoiceWindow(45_000L)
        wakeUpScreen()
        ensureAdequateVolume()

        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 95)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
        } catch (_: Exception) {}

        val lower = spokenText.lowercase().trim()
        var cleaned = lower.replace(Regex("^(oye\\s+|hola\\s+)?(edvis|elvis|ervis|edwin|jarvis|ed)[,\\s]*", RegexOption.IGNORE_CASE), "").trim()
        cleaned = cleaned.replace(Regex("[,\\s]+(edvis|elvis|ervis|edwin|jarvis)$", RegexOption.IGNORE_CASE), "").trim()
        cleaned = cleaned.replace(Regex("^(por\\s+favor|por\\s+fa)\\s+"), "").trim()
        cleaned = cleaned.replace(Regex("\\s+(por\\s+favor|por\\s+fa)$"), "").trim()

        // 1. Phone Call Interception Handling (Answer / Reject)
        if (isCallRinging) {
            if (lower.contains("contesta") || lower.contains("atender") || lower.contains("responder") || lower.contains("acepta") || lower == "si" || lower == "sí" || lower == "alo" || lower == "aló") {
                answerIncomingCall()
                return
            }
            if (lower.contains("cuelga") || lower.contains("rechaza") || lower.contains("rechazar") || lower.contains("no contestes") || lower.contains("cortar") || lower == "no") {
                rejectIncomingCall()
                return
            }
        }

        // 2. Interactive Conversational State Machine (Notification Read & Reply Flow)
        if (hasActiveConversation()) {
            when (conversationState) {
                ConversationState.AWAITING_READ_CONFIRMATION -> {
                    val isReadYes = cleaned == "si" || cleaned == "sí" || cleaned == "leelo" || cleaned == "léelo" ||
                        cleaned == "leeme" || cleaned == "léeme" || cleaned == "leeme el mensaje" || cleaned == "léeme el mensaje" ||
                        cleaned == "leemelo" || cleaned == "léemelo" || cleaned == "escuchar" || cleaned == "reproduce" ||
                        cleaned == "reproducir" || cleaned == "reproduce el audio" || cleaned == "reproduce la nota" ||
                        cleaned == "claro" || cleaned == "dale" || cleaned == "por favor" || cleaned == "adelante" ||
                        lower == "si" || lower == "sí" || lower.contains("leelo") || lower.contains("léelo") || lower.contains("reproduce")

                    val isNo = cleaned == "no" || cleaned == "ahora no" || cleaned == "después" || cleaned == "despues" ||
                        cleaned == "mas tarde" || cleaned == "más tarde" || cleaned == "luego" || cleaned == "cancelar" ||
                        cleaned == "déjalo" || cleaned == "dejalo" || lower == "no"

                    if (isReadYes) {
                        val sender = currentReplyTarget ?: "el contacto"
                        val app = currentReplyApp ?: "WhatsApp"
                        val msg = currentPendingMessageText ?: ""

                        if (isPendingMessageAudio) {
                            speakInFemaleVoice("Abriendo $app para reproducir la nota de voz, señor.")
                            EdvisAccessibilityService.launchAnyAppByNameOrPackage(this, app)
                            conversationState = ConversationState.IDLE
                            currentReplyTarget = null
                            currentReplyApp = null
                            currentPendingMessageText = null
                        } else {
                            conversationState = ConversationState.AWAITING_REPLY_CONFIRMATION
                            conversationExpiresAt = System.currentTimeMillis() + 45_000L
                            activateVoiceListeningWindow(45_000L)
                            speakInFemaleVoice("$sender dice: '$msg'. ¿Deseas responder?")
                        }
                        return
                    }

                    if (isNo) {
                        conversationState = ConversationState.IDLE
                        currentReplyTarget = null
                        currentReplyApp = null
                        currentPendingMessageText = null
                        speakInFemaleVoice("Entendido, señor.")
                        return
                    }

                    // Direct shortcut: if user directly tells EDVIS what to reply without hearing the message
                    if (cleaned.startsWith("dile ") || cleaned.startsWith("respóndele ") || cleaned.startsWith("respondele ")) {
                        val replyBody = extractReplyContent(cleaned)
                        sendReplyToCurrentTarget(replyBody)
                        return
                    }
                }

                ConversationState.AWAITING_REPLY_CONFIRMATION -> {
                    val isYes = cleaned == "si" || cleaned == "sí" || cleaned == "claro" || cleaned == "dale" ||
                        cleaned == "afirmativo" || cleaned == "por favor" || cleaned == "por fa" ||
                        cleaned == "responder" || cleaned == "quiero responder" || cleaned == "bueno" || cleaned == "ok" ||
                        lower == "si" || lower == "sí" || lower.startsWith("si ") || lower.startsWith("sí ")

                    val isDirectMessage = cleaned.startsWith("dile ") || cleaned.startsWith("respóndele ") || cleaned.startsWith("respondele ") ||
                        cleaned.startsWith("si dile ") || cleaned.startsWith("sí dile ") || cleaned.startsWith("contéstale ") || cleaned.startsWith("contestale ") ||
                        lower.startsWith("dile ") || lower.startsWith("respóndele ") || lower.startsWith("respondele ")

                    val isNo = cleaned == "no" || cleaned == "cancelar" || cleaned == "déjalo" || cleaned == "dejalo" ||
                        cleaned == "después" || cleaned == "despues" || cleaned == "luego" || cleaned == "no responder" ||
                        lower == "no"

                    if (isDirectMessage) {
                        val replyBody = extractReplyContent(if (cleaned.isNotEmpty()) cleaned else lower)
                        sendReplyToCurrentTarget(replyBody)
                        return
                    }

                    if (isYes) {
                        val target = currentReplyTarget ?: "el contacto"
                        conversationState = ConversationState.AWAITING_REPLY_MESSAGE
                        conversationExpiresAt = System.currentTimeMillis() + 45_000L
                        activateVoiceListeningWindow(45_000L)
                        speakInFemaleVoice("¿Qué mensaje le digo a $target?")
                        return
                    }

                    if (isNo) {
                        conversationState = ConversationState.IDLE
                        currentReplyTarget = null
                        currentReplyApp = null
                        speakInFemaleVoice("Entendido, señor. No responderé.")
                        return
                    }

                    // If user dictated something else substantial, treat as the reply message
                    if (cleaned.length > 3 && !cleaned.contains("edvis") && !cleaned.contains("estas ahi")) {
                        sendReplyToCurrentTarget(cleaned)
                        return
                    }
                }

                ConversationState.AWAITING_REPLY_MESSAGE -> {
                    val isCancel = cleaned == "cancelar" || cleaned == "déjalo" || cleaned == "dejalo" || cleaned == "no" || cleaned == "nada"
                    if (isCancel) {
                        conversationState = ConversationState.IDLE
                        currentReplyTarget = null
                        currentReplyApp = null
                        speakInFemaleVoice("Envío cancelado, señor.")
                        return
                    }

                    val replyBody = extractReplyContent(if (cleaned.isNotEmpty()) cleaned else lower)
                    sendReplyToCurrentTarget(replyBody)
                    return
                }

                else -> {}
            }
        }

        // 3. Incoming Notification Direct Reply anytime without prior prompt ("Edvis dile a Carlos que ya salí")
        val isExplicitReply = cleaned.startsWith("respóndele ") || cleaned.startsWith("respondele ") ||
            cleaned.startsWith("dile ") || cleaned.startsWith("contéstale ") || cleaned.startsWith("contestale ") ||
            lower.startsWith("respóndele ") || lower.startsWith("respondele ") || lower.startsWith("dile ")

        if (isExplicitReply) {
            val replyBody = extractReplyContent(if (cleaned.isNotEmpty()) cleaned else lower)
            sendReplyToCurrentTarget(replyBody)
            return
        }

        // 4. System Greetings & Presence Check
        if (cleaned.isEmpty() || cleaned == "estas ahi" || cleaned == "estás ahí" || cleaned == "hola" || cleaned == "despierta") {
            speakInFemaleVoice("En línea, señor. Todos los sistemas operativos listos. ¿Cuál es su orden?")
            return
        }

        // 4. Music Playback Control (Spotify / System Music Player)
        val isPlayMusic = cleaned == "pon musica" || cleaned == "pon música" ||
            cleaned == "reproduce musica" || cleaned == "reproduce música" ||
            cleaned.contains("pon musica") || cleaned.contains("pon música") ||
            cleaned.contains("reproduce musica") || cleaned.contains("reproduce música") ||
            cleaned.startsWith("play ") || cleaned == "play" || cleaned == "reproducir" ||
            cleaned.contains("abre spotify y reproduce") || cleaned.contains("abre la musica y reproduce")

        if (isPlayMusic) {
            speakInFemaleVoice("Reproduciendo música, señor.")
            EdvisAccessibilityService.launchAnyAppByNameOrPackage(this, "spotify")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
                    am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
                    EdvisAccessibilityService.instance?.executeAction("PLAY_MUSIC", "", "")
                } catch (_: Exception) {}
            }, 1200L)
            return
        }

        val isPauseMusic = cleaned.contains("pausa la musica") || cleaned.contains("pausa la música") ||
            cleaned == "pausa" || cleaned == "para la musica" || cleaned == "para la música" ||
            cleaned == "deten la musica" || cleaned == "detén la música"

        if (isPauseMusic) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
            } catch (_: Exception) {}
            speakInFemaleVoice("Música pausada, señor.")
            return
        }

        val isNextTrack = cleaned.contains("siguiente cancion") || cleaned.contains("siguiente canción") ||
            cleaned.contains("pasa la cancion") || cleaned.contains("pasa la canción") || cleaned == "siguiente"

        if (isNextTrack) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
            } catch (_: Exception) {}
            speakInFemaleVoice("Siguiente canción, señor.")
            return
        }

        // 5. Intelligent System App Launching
        // Matches: "abre...", "abrir...", "ábreme...", "inicia...", "entra a...", "pon..."
        // Or direct app names: "whatsapp", "youtube", "spotify", "camara", "cámara", "instagram", "tiktok", "facebook", "maps", "waze", "nequi", "calculadora", "ajustes", "configuracion", "reloj"
        val knownApps = listOf(
            "whatsapp", "wasap", "wsp", "youtube", "spotify", "musica", "música",
            "camara", "cámara", "fotos", "galeria", "galería", "instagram", "facebook",
            "tiktok", "maps", "mapas", "mapa", "waze", "nequi", "calculadora",
            "ajustes", "configuracion", "configuración", "reloj", "alarma", "telegram", "netflix"
        )

        var detectedApp: String? = null
        val openPrefixes = listOf(
            "abre la aplicación de ", "abre la aplicacion de ", "abre la app de ",
            "abrir la aplicación de ", "abrir la aplicacion de ", "abrir la app de ",
            "ábreme la aplicación de ", "abreme la aplicacion de ", "ábreme la app de ", "abreme la app de ",
            "ábreme el ", "abreme el ", "ábreme la ", "abreme la ", "ábreme ", "abreme ",
            "abre el ", "abre la ", "abre los ", "abre las ", "abre mi ", "abre ",
            "abrir el ", "abrir la ", "abrir los ", "abrir las ", "abrir mi ", "abrir ",
            "inicia el ", "inicia la ", "inicia ", "iniciar ",
            "entra a el ", "entra al ", "entra a la ", "entra a ", "entrar a ",
            "lanzar ", "lanza ", "ejecuta ", "ejecutar "
        )

        for (prefix in openPrefixes) {
            if (cleaned.startsWith(prefix) || lower.startsWith(prefix)) {
                detectedApp = (if (cleaned.startsWith(prefix)) cleaned.substring(prefix.length) else lower.substring(prefix.length)).trim()
                break
            } else if (cleaned.contains(" $prefix")) {
                detectedApp = cleaned.substringAfter(" $prefix").trim()
                break
            }
        }

        // Direct app name shouted (e.g. "edvis whatsapp", "edvis youtube", "cámara", etc.)
        if (detectedApp.isNullOrBlank()) {
            for (app in knownApps) {
                if (cleaned == app || cleaned == "el $app" || cleaned == "la $app" || cleaned == "mi $app") {
                    detectedApp = app
                    break
                }
            }
        }

        if (!detectedApp.isNullOrBlank()) {
            val appQuery = detectedApp.replace(Regex("^(el|la|los|las|mi|un|una)\\s+"), "").trim()
            speakInFemaleVoice("Abriendo $appQuery, señor.")
            val launched = EdvisAccessibilityService.launchAnyAppByNameOrPackage(this, appQuery)
            if (!launched) {
                EdvisAccessibilityService.launchAnyAppByNameOrPackage(this, detectedApp)
            }
            return
        }

        // 5.1 Reloj y Hora Actual
        if (cleaned.contains("que hora es") || cleaned.contains("qué hora es") ||
            cleaned.contains("dime la hora") || cleaned.contains("hora actual") || cleaned == "hora") {
            val now = java.text.SimpleDateFormat("h:mm a", Locale("es", "CO")).format(java.util.Date())
            speakInFemaleVoice("Son las $now, señor.")
            return
        }

        // 5.2 Fecha Actual
        if (cleaned.contains("que dia es") || cleaned.contains("qué día es") ||
            cleaned.contains("que fecha es") || cleaned.contains("qué fecha es") ||
            cleaned.contains("fecha de hoy")) {
            val today = java.text.SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "CO")).format(java.util.Date())
            speakInFemaleVoice("Hoy es $today, señor.")
            return
        }

        // 5.3 Identidad del Asistente
        if (cleaned.contains("quien eres") || cleaned.contains("quién eres") ||
            cleaned.contains("como te llamas") || cleaned.contains("cómo te llamas") ||
            cleaned.contains("que eres") || cleaned.contains("qué eres")) {
            speakInFemaleVoice("Soy E.D.V.I.S., su sistema táctico de inteligencia artificial personal.")
            return
        }

        // 6. Linterna / Hardware Flashlight Control
        val isTorchOn = cleaned.contains("enciende la linterna") || cleaned.contains("prende la linterna") ||
            cleaned.contains("activa la linterna") || cleaned == "linterna" || cleaned == "linterna on" ||
            cleaned.contains("prende linterna") || cleaned.contains("enciende linterna")
        val isTorchOff = cleaned.contains("apaga la linterna") || cleaned.contains("desactiva la linterna") ||
            cleaned.contains("apagar linterna") || cleaned == "linterna off" || cleaned.contains("apaga linterna")

        if (isTorchOn) {
            setTorchMode(true)
            return
        }
        if (isTorchOff) {
            setTorchMode(false)
            return
        }

        // 7. Telemetría y Diagnóstico de Sistemas en Tiempo Real (Batería, RAM, GPS)
        val isTelemetry = cleaned.contains("estado del sistema") || cleaned.contains("diagnostico") ||
            cleaned.contains("diagnóstico") || cleaned.contains("reporte de sistemas") ||
            cleaned.contains("telemetria") || cleaned.contains("telemetría") ||
            cleaned.contains("cuanta bateria") || cleaned.contains("cuánta batería") ||
            cleaned == "bateria" || cleaned == "batería" || cleaned.contains("estado de la bateria") ||
            cleaned.contains("estado de la batería") || cleaned.contains("memoria ram") ||
            cleaned.contains("como estan los sistemas") || cleaned.contains("cómo están los sistemas")

        if (isTelemetry) {
            reportSystemTelemetry()
            return
        }

        // 8. Control de Volumen de Hardware
        val isVolumeUp = cleaned.contains("sube el volumen") || cleaned.contains("subir volumen") ||
            cleaned.contains("aumenta el volumen") || cleaned.contains("más volumen") || cleaned.contains("mas volumen")
        val isVolumeDown = cleaned.contains("baja el volumen") || cleaned.contains("bajar volumen") ||
            cleaned.contains("disminuye el volumen") || cleaned.contains("menos volumen")
        val isVolumeMax = cleaned.contains("volumen al maximo") || cleaned.contains("volumen al máximo") ||
            cleaned.contains("volumen 100") || cleaned.contains("todo el volumen")
        val isVolumeMute = cleaned.contains("silencio") || cleaned.contains("mute") || cleaned.contains("silencia")

        if (isVolumeUp) {
            adjustDeviceVolume(AudioManager.ADJUST_RAISE)
            return
        }
        if (isVolumeDown) {
            adjustDeviceVolume(AudioManager.ADJUST_LOWER)
            return
        }
        if (isVolumeMax) {
            setDeviceVolumeMax()
            return
        }
        if (isVolumeMute) {
            setDeviceVolumeMute()
            return
        }

        // 9. Llamadas Telefónicas Directas a Contactos o Números
        val isPhoneCall = cleaned.startsWith("llama a ") || cleaned.startsWith("llamar a ") ||
            cleaned.startsWith("marca a ") || cleaned.startsWith("marcar a ") ||
            lower.startsWith("llama a ") || lower.startsWith("llamar a ")

        if (isPhoneCall) {
            val callTarget = (if (cleaned.startsWith("llama a ")) cleaned.substringAfter("llama a ")
                else if (cleaned.startsWith("llamar a ")) cleaned.substringAfter("llamar a ")
                else if (cleaned.startsWith("marca a ")) cleaned.substringAfter("marca a ")
                else if (cleaned.startsWith("marcar a ")) cleaned.substringAfter("marcar a ")
                else if (lower.startsWith("llama a ")) lower.substringAfter("llama a ")
                else lower.substringAfter("llamar a ")).trim()
            makeDirectPhoneCall(callTarget)
            return
        }

        // 10. Ubicación Satelital GPS y Geocodificación Inversa
        val isLocationQuery = cleaned.contains("donde estoy") || cleaned.contains("dónde estoy") ||
            cleaned.contains("ubicacion actual") || cleaned.contains("ubicación actual") ||
            cleaned.contains("cual es mi posicion") || cleaned.contains("cuál es mi posición") ||
            cleaned.contains("donde me encuentro") || cleaned.contains("dónde me encuentro")

        if (isLocationQuery) {
            reportCurrentLocation()
            return
        }

        // 11. Escaneo Visual Táctico E.D.I.T.H. (Cámara y Análisis Óptico)
        val isVisionQuery = cleaned.contains("que estoy viendo") || cleaned.contains("qué estoy viendo") ||
            cleaned.contains("que tengo al frente") || cleaned.contains("qué tengo al frente") ||
            cleaned.contains("analiza esto") || cleaned.contains("mira esto") ||
            cleaned.contains("escanea esto") || cleaned.contains("analiza la camara") ||
            cleaned.contains("analiza la cámara") || cleaned.contains("vision edith") ||
            cleaned.contains("visión edith") || cleaned.contains("que hay al frente") ||
            cleaned.contains("qué hay al frente")

        if (isVisionQuery) {
            launchCameraVisionAnalysis()
            return
        }

        // 12. Domótica y Control de Taller / Hogar Inteligente
        val isDomotics = cleaned.contains("luz del taller") || cleaned.contains("luces del taller") ||
            cleaned.contains("protocolo taller") || cleaned.contains("apaga el taller") ||
            cleaned.contains("enciende el taller") || cleaned.contains("luces del laboratorio")

        if (isDomotics) {
            triggerDomoticsAction(cleaned)
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val prompt = if (cleaned.isNotEmpty()) cleaned else spokenText
                val geminiKey = String(android.util.Base64.decode(
                    "QVEuQWI4Uk42TDFxZndJZ1ppcTlzb3k2Y0NuLXJkR095c2ZQNHpmYndGX01jZFpGX0dCaVE=",
                    android.util.Base64.DEFAULT
                )).trim()
                val sbUrl = "https://rrollluxkbyjlimqosdu.supabase.co"
                val sbKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InJyb2xsbHV4a2J5amxpbXFvc2R1Iiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc5NDI4MTE1NSwiZXhwIjoyMTA5ODU3MTU1fQ._NOoB2qdARrTLCNagmPCX1URzYmB5OG5wMzQC5qpn-U"

                val jsonBody = JSONObject().apply {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", org.json.JSONArray().put(JSONObject().apply {
                            put("text", "Eres E.D.V.I.S., asistente táctico personal de Elvis inspirado en JARVIS de Iron Man. Responde siempre en español, conciso (máximo 2 oraciones), tono táctico y seguro. Si el usuario pide abrir una app o hacer una llamada, utiliza obligatoriamente la función dispatch_device_action.")
                        }))
                    })
                    put("contents", org.json.JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", org.json.JSONArray().put(JSONObject().apply {
                            put("text", prompt)
                        }))
                    }))
                    put("tools", org.json.JSONArray().put(JSONObject().apply {
                        put("functionDeclarations", org.json.JSONArray().apply {
                            put(JSONObject().apply {
                                put("name", "dispatch_device_action")
                                put("description", "Abre una aplicación en el teléfono o realiza llamadas")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject().apply {
                                        put("action_type", JSONObject().put("type", "STRING"))
                                        put("target", JSONObject().put("type", "STRING"))
                                    })
                                    put("required", org.json.JSONArray().put("action_type").put("target"))
                                })
                            })
                            put(JSONObject().apply {
                                put("name", "record_finance_transaction")
                                put("description", "Registra un gasto o ingreso")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject().apply {
                                        put("type", JSONObject().put("type", "STRING"))
                                        put("amount", JSONObject().put("type", "NUMBER"))
                                        put("category", JSONObject().put("type", "STRING"))
                                        put("description", JSONObject().put("type", "STRING"))
                                    })
                                    put("required", org.json.JSONArray().put("type").put("amount").put("category"))
                                })
                            })
                        })
                    }))
                }.toString()

                val client = OkHttpClient.Builder()
                    .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(18, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=$geminiKey")
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()

                val resp = client.newCall(req).execute()
                val respStr = resp.body?.string() ?: ""
                Log.i(TAG, "Gemini 3.8 Flash HTTP ${resp.code}: $respStr")
                val respJson = JSONObject(respStr)

                val candidate = respJson.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                val parts = candidate?.optJSONArray("parts")

                var spokenReply = ""
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i)
                        val fn = part?.optJSONObject("functionCall")
                        if (fn != null) {
                            val fnName = fn.optString("name")
                            val args = fn.optJSONObject("args")
                            if (fnName == "dispatch_device_action") {
                                val target = args?.optString("target", "") ?: ""
                                val actionType = args?.optString("action_type", "OPEN_APP") ?: "OPEN_APP"
                                if (actionType.contains("CALL", ignoreCase = true)) {
                                    makeDirectPhoneCall(target)
                                    return@launch
                                } else {
                                    EdvisAccessibilityService.launchAnyAppByNameOrPackage(this@EdvisForegroundService, target)
                                    spokenReply = "Abriendo $target, señor."
                                }
                            } else if (fnName == "record_finance_transaction") {
                                val amount = args?.optDouble("amount", 0.0) ?: 0.0
                                val category = args?.optString("category", "General") ?: "General"
                                val type = args?.optString("type", "egreso") ?: "egreso"
                                val desc = args?.optString("description", "Voz E.D.V.I.S.") ?: "Voz E.D.V.I.S."

                                val txJson = JSONObject().apply {
                                    put("type", type)
                                    put("amount", amount)
                                    put("currency", "COP")
                                    put("category", category)
                                    put("description", desc)
                                }.toString()

                                val sbReq = Request.Builder()
                                    .url("$sbUrl/rest/v1/transactions")
                                    .header("apikey", sbKey)
                                    .header("Authorization", "Bearer $sbKey")
                                    .header("Content-Type", "application/json")
                                    .post(txJson.toRequestBody("application/json".toMediaType()))
                                    .build()

                                client.newCall(sbReq).execute().close()
                                spokenReply = "Gasto de ${amount.toInt()} pesos en $category registrado en Supabase, señor."
                            }
                        }
                        val t = part?.optString("text")
                        if (!t.isNullOrBlank()) {
                            spokenReply = t
                        }
                    }
                }

                if (spokenReply.isBlank()) {
                    val launched = EdvisAccessibilityService.launchAnyAppByNameOrPackage(this@EdvisForegroundService, prompt)
                    if (launched) {
                        spokenReply = "Abriendo $prompt, señor."
                    } else {
                        spokenReply = "A la orden, señor. ¿En qué le puedo colaborar?"
                    }
                }

                withContext(Dispatchers.Main) {
                    speakInFemaleVoice(spokenReply)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in autonomous turn: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    val prompt = if (cleaned.isNotEmpty()) cleaned else spokenText
                    val launched = EdvisAccessibilityService.launchAnyAppByNameOrPackage(this@EdvisForegroundService, prompt)
                    if (launched) {
                        speakInFemaleVoice("Abriendo $prompt, señor.")
                    } else {
                        speakInFemaleVoice("No pude procesar la orden en este momento, señor.")
                    }
                }
            }
        }
    }

    private fun sendReplyToCurrentTarget(messageText: String) {
        val target = currentReplyTarget ?: EdvisNotificationService.lastSenderName ?: "el contacto"
        val app = currentReplyApp ?: EdvisNotificationService.lastSenderApp ?: "WhatsApp"
        val sent = EdvisNotificationService.instance?.replyToSender(target, messageText) ?: false

        if (sent) {
            speakInFemaleVoice("Mensaje enviado a $target: $messageText")
        } else {
            speakInFemaleVoice("Abriendo $app para enviar el mensaje a $target")
            EdvisAccessibilityService.launchAnyAppByNameOrPackage(this, app)
        }

        conversationState = ConversationState.IDLE
        currentReplyTarget = null
        currentReplyApp = null
    }

    private fun extractReplyContent(text: String): String {
        var result = text.trim()
        val prefixes = listOf(
            "sí dile que ", "si dile que ", "sí dile ", "si dile ",
            "respóndele que ", "respondele que ", "respóndele ", "respondele ",
            "contéstale que ", "contestale que ", "contéstale ", "contestale ",
            "dile que ", "dile ", "que "
        )
        for (prefix in prefixes) {
            val regex = Regex("^$prefix", RegexOption.IGNORE_CASE)
            if (result.contains(regex)) {
                result = result.replaceFirst(regex, "").trim()
                break
            }
        }
        return result.ifBlank { text }
    }

    private var isTorchActive = false

    private fun setTorchMode(enabled: Boolean) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: cameraManager.cameraIdList.firstOrNull() ?: "0"

            cameraManager.setTorchMode(cameraId, enabled)
            isTorchActive = enabled
            val status = if (enabled) "encendida" else "apagada"
            speakInFemaleVoice("Linterna $status, señor.")
        } catch (e: Exception) {
            Log.e(TAG, "Error controlling torch: ${e.message}", e)
            speakInFemaleVoice("No pude controlar la linterna del dispositivo.")
        }
    }

    private fun reportSystemTelemetry() {
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val isCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) bm.isCharging else false

            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val availGb = String.format(Locale.US, "%.1f", memInfo.availMem / (1024.0 * 1024.0 * 1024.0))
            val totalGb = String.format(Locale.US, "%.1f", memInfo.totalMem / (1024.0 * 1024.0 * 1024.0))

            val loc = getBestCurrentLocation()
            val speedKmh = ((loc?.speed ?: 0.0f) * 3.6f).toInt()
            val chargeText = if (isCharging) "cargando activamente" else "en descarga"

            val report = "Sistemas nominales, señor. Batería al $batteryPct por ciento, $chargeText. Memoria RAM disponible: $availGb gigabytes de $totalGb gigabytes. Velocidad de desplazamiento: $speedKmh kilómetros por hora."
            speakInFemaleVoice(report)
        } catch (e: Exception) {
            Log.e(TAG, "Error reporting telemetry: ${e.message}", e)
            speakInFemaleVoice("Sistemas activos y operativos, señor.")
        }
    }

    private fun adjustDeviceVolume(direction: Int) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val pct = (current * 100) / max
            val label = if (direction == AudioManager.ADJUST_RAISE) "aumentado al" else "reducido al"
            speakInFemaleVoice("Volumen $label $pct por ciento, señor.")
        } catch (e: Exception) {
            speakInFemaleVoice("Ajustando volumen, señor.")
        }
    }

    private fun setDeviceVolumeMax() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
            speakInFemaleVoice("Volumen al máximo, señor.")
        } catch (_: Exception) {}
    }

    private fun setDeviceVolumeMute() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            speakInFemaleVoice("Audio silenciado, señor.")
        } catch (_: Exception) {}
    }

    private fun makeDirectPhoneCall(target: String) {
        val cleanTarget = target.trim()
        if (cleanTarget.isEmpty()) {
            speakInFemaleVoice("¿A quién desea que llame, señor?")
            return
        }

        val isNumber = cleanTarget.replace(" ", "").replace("+", "").replace("-", "").all { it.isDigit() }
        var numberToCall: String? = if (isNumber) cleanTarget.replace(" ", "") else null
        var resolvedName = cleanTarget

        if (numberToCall == null) {
            try {
                val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
                val selectionArgs = arrayOf("%$cleanTarget%")
                val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)

                cursor?.use {
                    if (it.moveToFirst()) {
                        numberToCall = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                        resolvedName = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Contacts query error: ${e.message}", e)
            }
        }

        if (!numberToCall.isNullOrBlank()) {
            speakInFemaleVoice("Llamando a $resolvedName, señor.")
            try {
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:${Uri.encode(numberToCall)}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(callIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Direct call failed: ${e.message}, falling back to dial intent")
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:${Uri.encode(numberToCall)}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(dialIntent)
            }
        } else {
            speakInFemaleVoice("No encontré el contacto $cleanTarget en su agenda telefónica, señor.")
        }
    }

    private fun getBestCurrentLocation(): android.location.Location? {
        GpsTracker.lastKnownLocation?.let { return it }
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            @Suppress("MissingPermission")
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            null
        }
    }

    private fun reportCurrentLocation() {
        try {
            val loc = getBestCurrentLocation()
            if (loc != null) {
                val geocoder = Geocoder(this, Locale("es", "CO"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    geocoder.getFromLocation(loc.latitude, loc.longitude, 1) { addresses ->
                        val addr = addresses.firstOrNull()
                        val text = if (addr != null) {
                            val thoroughfare = addr.thoroughfare ?: addr.featureName ?: "Vía principal"
                            val subLoc = addr.subLocality ?: ""
                            val city = addr.locality ?: addr.adminArea ?: "Colombia"
                            "Se encuentra en $thoroughfare, $subLoc, $city, señor."
                        } else {
                            "Coordenadas actuales: latitud ${String.format(Locale.US, "%.4f", loc.latitude)}, longitud ${String.format(Locale.US, "%.4f", loc.longitude)}, señor."
                        }
                        speakInFemaleVoice(text)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                    val addr = addresses?.firstOrNull()
                    val text = if (addr != null) {
                        val thoroughfare = addr.thoroughfare ?: addr.featureName ?: "Vía principal"
                        val subLoc = addr.subLocality ?: ""
                        val city = addr.locality ?: addr.adminArea ?: "Colombia"
                        "Se encuentra en $thoroughfare, $subLoc, $city, señor."
                    } else {
                        "Coordenadas actuales: latitud ${String.format(Locale.US, "%.4f", loc.latitude)}, longitud ${String.format(Locale.US, "%.4f", loc.longitude)}, señor."
                    }
                    speakInFemaleVoice(text)
                }
            } else {
                speakInFemaleVoice("Adquiriendo fijación de satélites GPS. Por favor espere un momento, señor.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obtaining location: ${e.message}", e)
            speakInFemaleVoice("Sistemas GPS activos, calculando posición satelital.")
        }
    }

    private fun launchCameraVisionAnalysis() {
        speakInFemaleVoice("Iniciando escaneo óptico táctico E.D.I.T.H., señor.")
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                action = "ACTION_TACTICAL_VISION"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening tactical vision: ${e.message}", e)
        }
    }

    private fun triggerDomoticsAction(command: String) {
        val isTurnOn = command.contains("enciende") || command.contains("prende") || command.contains("activa")
        val state = if (isTurnOn) "encendidas" else "apagadas"
        speakInFemaleVoice("Protocolo domótico ejecutado: Luces del taller $state, señor.")

        serviceScope.launch(Dispatchers.IO) {
            try {
                val webhookUrl = System.getenv("HOME_ASSISTANT_WEBHOOK") ?: ""
                if (webhookUrl.isNotBlank()) {
                    val client = OkHttpClient()
                    val reqBody = JSONObject().apply {
                        put("entity", "workshop_lights")
                        put("state", if (isTurnOn) "on" else "off")
                    }.toString().toRequestBody("application/json".toMediaType())

                    val req = Request.Builder().url(webhookUrl).post(reqBody).build()
                    client.newCall(req).execute().close()
                }
            } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EDVIS::TacticalCoreWakeLock"
        ).apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "E.D.V.I.S. Tactical Core",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene activo el enlace de voz por casco y telemetría GPS"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("E.D.V.I.S. TACTICAL SYSTEM")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(statusText))
    }
}
