package com.edvis.core.services

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.location.Geocoder
import android.location.LocationManager
import android.media.AudioManager
import android.media.ImageReader
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
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
        AWAITING_REPLY_MESSAGE,          // "¿Qué mensaje le digo a [Persona]?"
        AWAITING_CONTACT_SELECTION       // "¿A cuál de los dos le envío el mensaje?"
    }

    data class ContactMatch(val name: String, val number: String)

    @Volatile
    private var pendingDisambiguationMessage: String? = null
    @Volatile
    private var pendingDisambiguationIsAudio: Boolean = false
    @Volatile
    private var pendingDisambiguationMatches: List<ContactMatch> = emptyList()

    @Volatile
    var isSleepingMode = false
        private set

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

    fun handleIncomingVoipCall(app: String, caller: String) {
        wakeUpScreen()
        ensureAdequateVolume()
        isCallRinging = true
        ringingNumber = caller
        activateVoiceListeningWindow(40_000L)
        speakInFemaleVoice("Llamada entrante de $caller en $app, señor. ¿Deseas que conteste o cuelgue?")
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)
        }

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(callReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(callReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering call receiver: ${e.message}")
        }
    }

    fun answerIncomingCall() {
        val voipAnswered = EdvisNotificationService.instance?.answerVoipCall() ?: false
        if (voipAnswered) {
            isCallRinging = false
            speakInFemaleVoice("Llamada contestada, señor.")
            return
        }

        try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                telecomManager.acceptRingingCall()
                isCallRinging = false
                speakInFemaleVoice("Llamada contestada, señor.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error answering call: ${e.message}")
        }
    }

    fun rejectIncomingCall() {
        val voipRejected = EdvisNotificationService.instance?.rejectVoipCall() ?: false
        if (voipRejected) {
            isCallRinging = false
            speakInFemaleVoice("Llamada rechazada, señor.")
            return
        }

        try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                telecomManager.endCall()
                isCallRinging = false
                speakInFemaleVoice("Llamada rechazada, señor.")
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

        if (intent?.action == "ACTION_MORNING_WAKEUP") {
            val h = intent.getIntExtra(EdvisVoiceAlarmReceiver.EXTRA_ALARM_HOUR, 5)
            val m = intent.getIntExtra(EdvisVoiceAlarmReceiver.EXTRA_ALARM_MINUTE, 0)
            triggerJarvisMorningWakeup(h, m)
        }

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

    private var lastSpokenCommand: String = ""
    private var lastSpokenTime: Long = 0L

    fun processAutonomousVoiceCommand(spokenText: String) {
        val now = System.currentTimeMillis()
        val lower = spokenText.lowercase().trim()

        // Debounce: prevent duplicate immediate execution of the exact same command within 1200ms
        if (lower == lastSpokenCommand && (now - lastSpokenTime) < 1200L) {
            Log.d(TAG, "Dropping duplicate command within 1200ms: $lower")
            return
        }
        lastSpokenCommand = lower
        lastSpokenTime = now

        // Barge-In: if user speaks while assistant is talking, immediately cut off speech like JARVIS
        if (isTtsSpeaking()) {
            Log.i(TAG, "User spoke while TTS is active: interrupting speech immediately")
            tts?.stop()
        }

        val isWakeUpCommand = lower.contains("despierta") || lower.contains("despiértate") || lower.contains("despiertate") ||
            lower.contains("reactívate") || lower.contains("reactivate") || lower.contains("actívate") || lower.contains("activate") ||
            lower.contains("estas ahi") || lower.contains("estás ahí") ||
            lower == "edvis" || lower == "elvis" || lower == "ervis" || lower == "oye edvis" || lower == "hola edvis" ||
            lower.startsWith("edvis ") || lower.startsWith("oye edvis ") ||
            (lower.contains("edvis") && (lower.contains("arriba") || lower.contains("en línea") || lower.contains("en linea") || lower.contains("hola")))

        if (isSleepingMode) {
            if (isWakeUpCommand) {
                isSleepingMode = false
                speakInFemaleVoice("Aquí estoy, señor. Sistemas reactivados. ¿Cuál es su orden?")
                return
            }
            Log.d(TAG, "Assistant in sleep mode, ignoring: $spokenText")
            return
        }

        var cleaned = lower.replace(Regex("^(oye\\s+|hola\\s+)?(edvis|elvis|ervis|edwin|jarvis|ed)[,\\s]*", RegexOption.IGNORE_CASE), "").trim()
        cleaned = cleaned.replace(Regex("[,\\s]+(edvis|elvis|ervis|edwin|jarvis)$", RegexOption.IGNORE_CASE), "").trim()
        cleaned = cleaned.replace(Regex("^(por\\s+favor|por\\s+fa)\\s+"), "").trim()
        cleaned = cleaned.replace(Regex("\\s+(por\\s+favor|por\\s+fa)$"), "").trim()

        val isSleepRequest = cleaned == "descansa" || cleaned == "duérmete" || cleaned == "duermete" ||
            cleaned.contains("por ahora no te necesito") || cleaned.contains("no te necesito") ||
            cleaned.contains("modo reposo") || cleaned.contains("entra en reposo") || cleaned.contains("ve a dormir") ||
            cleaned.contains("silencio edvis") || cleaned == "cállate" || cleaned == "callate" || cleaned == "silencio" ||
            cleaned.contains("apágate") || cleaned.contains("apagate") || cleaned.contains("descansar")

        if (isSleepRequest) {
            isSleepingMode = true
            conversationState = ConversationState.IDLE
            currentReplyTarget = null
            pcmStreamer?.activateVoiceWindow(0L)
            speakInFemaleVoice("Entendido señor, entrando en modo reposo. Para despertarme, solo diga: Edvis despierta.")
            return
        }

        Log.i(TAG, "Autonomous background voice command detected: $spokenText")
        pcmStreamer?.activateVoiceWindow(45_000L)
        wakeUpScreen()
        ensureAdequateVolume()

        // Seamless, fluid conversation without artificial robotic beeps

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
                            speakInFemaleVoice("Reproduciendo nota de voz de $sender, señor.")
                            EdvisAccessibilityService.launchAnyAppByNameOrPackage(this, app)
                            EdvisAccessibilityService.instance?.triggerPlayLastVoiceMessageInWhatsApp()
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

                ConversationState.AWAITING_CONTACT_SELECTION -> {
                    val isCancel = cleaned == "cancelar" || cleaned == "déjalo" || cleaned == "dejalo" || cleaned == "ninguno"
                    if (isCancel) {
                        conversationState = ConversationState.IDLE
                        pendingDisambiguationMatches = emptyList()
                        pendingDisambiguationMessage = null
                        speakInFemaleVoice("Operación cancelada, señor.")
                        return
                    }

                    val normResp = normalizeContactText(cleaned)
                    val selected = pendingDisambiguationMatches.firstOrNull { match ->
                        val normName = normalizeContactText(match.name)
                        normName.contains(normResp) || normResp.contains(normName) ||
                            (cleaned.contains("primero") && match == pendingDisambiguationMatches.getOrNull(0)) ||
                            (cleaned.contains("segundo") && match == pendingDisambiguationMatches.getOrNull(1)) ||
                            (cleaned.contains("tercero") && match == pendingDisambiguationMatches.getOrNull(2))
                    } ?: pendingDisambiguationMatches.firstOrNull()

                    conversationState = ConversationState.IDLE
                    val msg = pendingDisambiguationMessage
                    val isAudio = pendingDisambiguationIsAudio
                    pendingDisambiguationMatches = emptyList()
                    pendingDisambiguationMessage = null

                    if (selected != null) {
                        if (isAudio) {
                            dispatchWhatsAppVoiceNoteToNumber(selected.name, selected.number)
                        } else if (msg != null) {
                            dispatchWhatsAppToNumber(selected.name, selected.number, msg)
                        }
                    } else {
                        speakInFemaleVoice("No pude identificar el contacto, señor. Operación cancelada.")
                    }
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
        if (cleaned.isEmpty() || cleaned == "estas ahi" || cleaned == "estás ahí" || cleaned == "hola" || cleaned == "despierta" || cleaned == "dime" || cleaned == "oye") {
            val naturalResponses = listOf(
                "A su servicio, señor.",
                "Dígame, señor.",
                "En línea, señor.",
                "Sistemas listos, señor. ¿Cuál es su orden?"
            )
            speakInFemaleVoice(naturalResponses.random())
            return
        }

        // 4. Tactical Photographic Capture (Hands-Free Camera2 / Intent)
        val isTakePhoto = cleaned.contains("toma una foto") || cleaned.contains("tómame una foto") ||
            cleaned.contains("tomame una foto") || cleaned.contains("saca una foto") ||
            cleaned.contains("sácame una foto") || cleaned.contains("sacame una foto") ||
            cleaned.contains("haz una foto") || cleaned.contains("captura una foto") ||
            cleaned == "foto" || cleaned == "toma foto" || cleaned == "tomar foto"

        if (isTakePhoto) {
            takeTacticalPhoto()
            return
        }

        // 5. Tactical Alarms & Timers (Includes "despiértame a las 5", "me levantes mañana a las 5", "alarma a las 7 am")
        val isAlarmRequest = cleaned.contains("alarma") || cleaned.contains("despiert") ||
            cleaned.contains("levant") || cleaned.contains("madrug") ||
            (cleaned.contains("a las ") && (cleaned.contains("mañana") || cleaned.contains("tarde") || cleaned.contains("noche") || cleaned.contains("am") || cleaned.contains("pm")))

        if (isAlarmRequest) {
            if (setDeviceAlarmFromVoice(cleaned)) return
        }

        val isTimerRequest = cleaned.contains("temporizador") || cleaned.contains("timer") || cleaned.contains("cuenta regresiva")
        if (isTimerRequest) {
            if (setDeviceTimerFromVoice(cleaned)) return
        }

        // 6.0 Direct Send WhatsApp Voice Note ("envía un audio a [Nombre]", "manda una nota de voz a [Nombre]")
        val isSendVoiceNote = (cleaned.startsWith("envía ") || cleaned.startsWith("envia ") ||
            cleaned.startsWith("manda ") || cleaned.startsWith("mandar ") || cleaned.startsWith("graba ")) &&
            (cleaned.contains("audio") || cleaned.contains("nota de voz") || cleaned.contains("mensaje de voz"))

        if (isSendVoiceNote) {
            if (handleSendWhatsAppVoiceNote(cleaned)) return
        }

        // 6.1 Direct WhatsApp Text Message to Any Contact
        val isSendWhatsAppText = (cleaned.startsWith("envía ") || cleaned.startsWith("envia ") ||
            cleaned.startsWith("manda ") || cleaned.startsWith("mandar ") || cleaned.startsWith("escribe ") ||
            cleaned.startsWith("dile a ") || cleaned.startsWith("dile al "))

        if (isSendWhatsAppText) {
            if (handleDirectWhatsAppVoiceCommand(cleaned)) return
        }

        // 6.2 Incoming Voice Note Playback Shortcut (Playing received audio notes)
        val isVoiceNotePlay = (cleaned.contains("reproduce el audio") || cleaned.contains("reproducir el audio") ||
            cleaned.contains("escucha el audio") || cleaned.contains("escuchar el audio") ||
            cleaned.contains("reproduce la nota") || cleaned.contains("reproducir la nota") ||
            cleaned.contains("reproduce el mensaje de voz") || cleaned.contains("reproducir el mensaje de voz") ||
            (cleaned.contains("reproduce") && (cleaned.contains("audio") || cleaned.contains("nota"))) ||
            (cleaned.contains("audio") && cleaned.contains("whatsapp"))) &&
            !isSendVoiceNote && !isSendWhatsAppText

        if (isVoiceNotePlay) {
            var targetPerson = ""
            val deMatch = Regex("\\s+(de|del)\\s+([a-zA-ZáéíóúÁÉÍÓÚñÑ]+)", RegexOption.IGNORE_CASE).find(cleaned)
            if (deMatch != null) {
                val candidate = deMatch.groupValues[2].trim().lowercase()
                if (candidate != "whatsapp" && candidate != "wasap" && candidate != "voz") {
                    targetPerson = deMatch.groupValues[2].trim()
                }
            }

            if (targetPerson.isNotEmpty()) {
                playVoiceNoteFromSpecificContact(targetPerson)
            } else {
                val app = currentReplyApp ?: EdvisNotificationService.lastSenderApp ?: "WhatsApp"
                val sender = currentReplyTarget ?: EdvisNotificationService.lastSenderName ?: "el contacto"
                speakInFemaleVoice("Reproduciendo audio de WhatsApp de $sender, señor.")
                EdvisAccessibilityService.launchAnyAppByNameOrPackage(this, "whatsapp")
                EdvisAccessibilityService.instance?.triggerPlayLastVoiceMessageInWhatsApp()
            }
            return
        }

        val isReadLastMessage = cleaned.contains("leeme el mensaje") || cleaned.contains("léeme el mensaje") ||
            cleaned.contains("leeme el ultimo mensaje") || cleaned.contains("léeme el último mensaje") ||
            cleaned.contains("que me escribieron") || cleaned.contains("qué me escribieron") ||
            cleaned.contains("lee el mensaje") || cleaned.contains("leer mensaje")

        if (isReadLastMessage) {
            val sender = currentReplyTarget ?: EdvisNotificationService.lastSenderName ?: "el contacto"
            val app = currentReplyApp ?: EdvisNotificationService.lastSenderApp ?: "WhatsApp"
            val msg = currentPendingMessageText ?: EdvisNotificationService.lastMessageText ?: ""
            if (msg.isNotBlank()) {
                conversationState = ConversationState.AWAITING_REPLY_CONFIRMATION
                conversationExpiresAt = System.currentTimeMillis() + 45_000L
                activateVoiceListeningWindow(45_000L)
                speakInFemaleVoice("$sender en $app dice: '$msg'. ¿Deseas responder?")
            } else {
                speakInFemaleVoice("No tengo registros de mensajes recientes sin leer, señor.")
            }
            return
        }

        // 7. Universal Music Control & Playback (Spotify / YouTube / Any Installed Player)
        val isPauseMusic = cleaned.contains("pausa la musica") || cleaned.contains("pausa la música") ||
            cleaned == "pausa" || cleaned == "para la musica" || cleaned == "para la música" ||
            cleaned == "deten la musica" || cleaned == "detén la música" || cleaned == "detente"

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

        val isPlayMusicUniversal = (cleaned.startsWith("pon ") || cleaned.startsWith("reproduce ") ||
            cleaned.startsWith("reproducir ") || cleaned.startsWith("ponme ") || cleaned.startsWith("escuchar ") ||
            cleaned == "musica" || cleaned == "música" || cleaned == "play" || cleaned == "dale play") &&
            !cleaned.contains("audio") && !cleaned.contains("nota") && !cleaned.contains("mensaje") &&
            !cleaned.contains("whatsapp") && !cleaned.contains("wasap") && !cleaned.contains("contacto")

        if (isPlayMusicUniversal) {
            playUniversalMusicVoice(cleaned)
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
            cleaned.contains("monitore") || cleaned.contains("monitorea") ||
            cleaned.contains("revisa el tel") || cleaned.contains("revisa los sistemas") ||
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
            cleaned.contains("súbele") || cleaned.contains("subele") || cleaned.contains("sube volumen") ||
            cleaned.contains("aumenta el volumen") || cleaned.contains("más volumen") || cleaned.contains("mas volumen")
        val isVolumeDown = cleaned.contains("baja el volumen") || cleaned.contains("bajar volumen") ||
            cleaned.contains("bájale") || cleaned.contains("bajale") || cleaned.contains("baja volumen") ||
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
                            put("text", "Eres E.D.V.I.S., el asistente táctico personal de Elvis inspirado en J.A.R.V.I.S. y E.D.I.T.H., con la inteligencia profunda y el conocimiento de ChatGPT. Tienes control total del teléfono: puedes enviar mensajes de WhatsApp, reproducir notas de voz o audios de WhatsApp, leer mensajes, contestar llamadas y tomar fotos. Si el usuario te pide reproducir un audio o nota de WhatsApp, llama SIEMPRE a play_whatsapp_voice_note. Si pide enviar un mensaje, llama a send_whatsapp_message. NUNCA digas que no puedes gestionar mensajes o audios de WhatsApp porque sí cuentas con esa herramienta.")
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
                                put("name", "play_whatsapp_voice_note")
                                put("description", "Abre WhatsApp y reproduce la última nota de voz o mensaje de audio")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject())
                                })
                            })
                            put(JSONObject().apply {
                                put("name", "send_whatsapp_message")
                                put("description", "Envía un mensaje de texto por WhatsApp a un contacto")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject().apply {
                                        put("contact", JSONObject().put("type", "STRING"))
                                        put("message", JSONObject().put("type", "STRING"))
                                    })
                                    put("required", org.json.JSONArray().put("contact").put("message"))
                                })
                            })
                            put(JSONObject().apply {
                                put("name", "set_device_alarm")
                                put("description", "Configura una alarma o despierta al usuario a una hora especificada")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject().apply {
                                        put("time_description", JSONObject().put("type", "STRING").put("description", "Hora solicitada, por ejemplo '5 am' o '7 y media'"))
                                    })
                                    put("required", org.json.JSONArray().put("time_description"))
                                })
                            })
                            put(JSONObject().apply {
                                put("name", "play_music")
                                put("description", "Reproduce música o busca canciones en cualquier reproductor")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject().apply {
                                        put("query", JSONObject().put("type", "STRING").put("description", "Nombre de la canción, artista o género"))
                                    })
                                    put("required", org.json.JSONArray().put("query"))
                                })
                            })
                            put(JSONObject().apply {
                                put("name", "take_tactical_photo")
                                put("description", "Captura una fotografía manos libres con la cámara y la guarda en la galería")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject())
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
                    .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val modelCandidates = listOf("gemini-3.1-flash-lite", "gemini-3.5-flash-lite", "gemini-flash-lite-latest")
                var respStr = ""

                for (model in modelCandidates) {
                    try {
                        val req = Request.Builder()
                            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$geminiKey")
                            .post(jsonBody.toRequestBody("application/json".toMediaType()))
                            .build()

                        val resp = client.newCall(req).execute()
                        if (resp.isSuccessful) {
                            respStr = resp.body?.string() ?: ""
                            resp.close()
                            Log.i(TAG, "Gemini inference successful with model: $model")
                            break
                        } else {
                            Log.w(TAG, "Gemini $model returned HTTP ${resp.code}, attempting fallback model")
                            resp.close()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Model $model network error: ${e.message}")
                    }
                }

                var spokenReply = ""

                if (respStr.isNotBlank()) {
                    val respJson = JSONObject(respStr)
                    val candidate = respJson.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                    val parts = candidate?.optJSONArray("parts")

                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i)
                            val fn = part?.optJSONObject("functionCall")
                            if (fn != null) {
                                val fnName = fn.optString("name")
                                val args = fn.optJSONObject("args")
                                when (fnName) {
                                    "dispatch_device_action" -> {
                                        val target = args?.optString("target", "") ?: ""
                                        val actionType = args?.optString("action_type", "OPEN_APP") ?: "OPEN_APP"
                                        if (actionType.contains("CALL", ignoreCase = true)) {
                                            makeDirectPhoneCall(target)
                                            return@launch
                                        } else {
                                            EdvisAccessibilityService.launchAnyAppByNameOrPackage(this@EdvisForegroundService, target)
                                            spokenReply = "Abriendo $target, señor."
                                        }
                                    }
                                    "play_whatsapp_voice_note" -> {
                                        withContext(Dispatchers.Main) {
                                            speakInFemaleVoice("Reproduciendo audio de WhatsApp, señor.")
                                            EdvisAccessibilityService.launchAnyAppByNameOrPackage(this@EdvisForegroundService, "whatsapp")
                                            EdvisAccessibilityService.instance?.triggerPlayLastVoiceMessageInWhatsApp()
                                        }
                                        return@launch
                                    }
                                    "send_whatsapp_message" -> {
                                        val contact = args?.optString("contact", "") ?: ""
                                        val msg = args?.optString("message", "") ?: ""
                                        withContext(Dispatchers.Main) {
                                            sendDirectWhatsAppToContact(contact, msg)
                                        }
                                        return@launch
                                    }
                                    "set_device_alarm" -> {
                                        val timeDesc = args?.optString("time_description", "") ?: ""
                                        withContext(Dispatchers.Main) {
                                            setDeviceAlarmFromVoice(timeDesc)
                                        }
                                        return@launch
                                    }
                                    "play_music" -> {
                                        val songQuery = args?.optString("query", "") ?: ""
                                        withContext(Dispatchers.Main) {
                                            playUniversalMusicVoice(songQuery)
                                        }
                                        return@launch
                                    }
                                    "take_tactical_photo" -> {
                                        withContext(Dispatchers.Main) {
                                            takeTacticalPhoto()
                                        }
                                        return@launch
                                    }
                                    "record_finance_transaction" -> {
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
                                        spokenReply = "Gasto de ${amount.toInt()} pesos en $category registrado en base de datos, señor."
                                    }
                                }
                            }
                            val t = part?.optString("text")
                            if (!t.isNullOrBlank()) {
                                spokenReply = t
                            }
                        }
                    }
                }

                if (spokenReply.isBlank()) {
                    val launched = EdvisAccessibilityService.launchAnyAppByNameOrPackage(this@EdvisForegroundService, prompt)
                    if (launched) {
                        spokenReply = "Abriendo $prompt, señor."
                    } else if (prompt.contains("que haces", ignoreCase = true) || prompt.contains("qué haces", ignoreCase = true)) {
                        spokenReply = "Monitoreando todos los sistemas, señor. Todo se encuentra bajo control, ¿cuál es su orden?"
                    } else {
                        spokenReply = "Entendido, señor. ¿Cuál es su orden?"
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

    private fun takeTacticalPhoto() {
        speakInFemaleVoice("Tomando fotografía, sonría.")
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull() ?: "0"

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)
            val width = jpegSizes?.firstOrNull()?.width ?: 1920
            val height = jpegSizes?.firstOrNull()?.height ?: 1080

            val imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2)
            val cameraThread = android.os.HandlerThread("CameraBackground").apply { start() }
            val cameraHandler = android.os.Handler(cameraThread.looper)

            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        image.close()

                        saveImageToGallery(bytes)
                        speakInFemaleVoice("Fotografía guardada con éxito en su galería, señor.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving captured image: ${e.message}", e)
                    } finally {
                        reader.close()
                        cameraThread.quitSafely()
                    }
                }
            }, cameraHandler)

            @Suppress("MissingPermission")
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(imageReader.surface)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.JPEG_ORIENTATION, 90)
                        }

                        camera.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                            super.onCaptureCompleted(session, request, result)
                                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                try { camera.close() } catch (_: Exception) {}
                                            }, 1000L)
                                        }
                                    }, cameraHandler)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Capture session error: ${e.message}", e)
                                    camera.close()
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                camera.close()
                            }
                        }, cameraHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Session creation error: ${e.message}", e)
                        camera.close()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraThread.quitSafely()
                    fallbackLaunchCameraIntent()
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error taking photo via Camera2: ${e.message}", e)
            fallbackLaunchCameraIntent()
        }
    }

    private fun saveImageToGallery(bytes: ByteArray) {
        try {
            val fileName = "EDVIS_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(bytes)
                    outputStream.flush()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }
                Log.i(TAG, "Image successfully saved to MediaStore: $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing image to MediaStore: ${e.message}", e)
        }
    }

    private fun fallbackLaunchCameraIntent() {
        try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            speakInFemaleVoice("Cámara lista para captura, señor.")
        } catch (e: Exception) {
            EdvisAccessibilityService.launchAnyAppByNameOrPackage(this, "camara")
        }
    }

    private fun setDeviceAlarmFromVoice(query: String): Boolean {
        try {
            val lower = query.lowercase().trim()
            var hour = -1
            var minute = 0
            val isPm = lower.contains("pm") || lower.contains("de la tarde") || lower.contains("de la noche") || lower.contains("tarde") || lower.contains("noche")
            val isAm = lower.contains("am") || lower.contains("de la mañana") || lower.contains("mañana") || lower.contains("madrugada")

            val timeRegex = Regex("(\\d{1,2})(?:[:\\s]+|\\s+y\\s+)(\\d{1,2})")
            val matchTime = timeRegex.find(lower)
            if (matchTime != null) {
                hour = matchTime.groupValues[1].toIntOrNull() ?: -1
                minute = matchTime.groupValues[2].toIntOrNull() ?: 0
            } else {
                val hourPrefixRegex = Regex("(?:a las|las|para las|para la|la)\\s+(\\d{1,2})")
                val hourMatch = hourPrefixRegex.find(lower)
                if (hourMatch != null) {
                    hour = hourMatch.groupValues[1].toIntOrNull() ?: -1
                } else {
                    val anyNumRegex = Regex("\\b(\\d{1,2})\\b")
                    val anyMatch = anyNumRegex.find(lower)
                    if (anyMatch != null) {
                        hour = anyMatch.groupValues[1].toIntOrNull() ?: -1
                    }
                }
                if (lower.contains("y media")) {
                    minute = 30
                } else if (lower.contains("y cuarto")) {
                    minute = 15
                }
            }

            if (hour in 1..12) {
                if (isPm && hour < 12) hour += 12
                if (isAm && hour == 12) hour = 0
            }

            if (hour in 0..23) {
                // 1. Programar despertar táctico por voz propia (Protocolo JARVIS)
                scheduleVoiceAlarm(hour, minute)

                // 2. Programar alarma estándar de respaldo en el reloj
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, "Alarma E.D.V.I.S.")
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                val minStr = if (minute < 10) "0$minute" else "$minute"
                val amPmDesc = if (hour < 12) "de la mañana" else "de la tarde"
                speakInFemaleVoice("Alarma programada para las $hour:$minStr $amPmDesc, señor.")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting alarm: ${e.message}", e)
        }
        return false
    }

    private fun setDeviceTimerFromVoice(query: String): Boolean {
        try {
            var totalSeconds = 0
            val minRegex = Regex("(\\d+)\\s*(?:minutos?|min)")
            val secRegex = Regex("(\\d+)\\s*(?:segundos?|seg)")
            val hourRegex = Regex("(\\d+)\\s*(?:horas?|hr)")

            minRegex.find(query)?.let {
                val mins = it.groupValues[1].toIntOrNull() ?: 0
                totalSeconds += mins * 60
            }
            secRegex.find(query)?.let {
                val secs = it.groupValues[1].toIntOrNull() ?: 0
                totalSeconds += secs
            }
            hourRegex.find(query)?.let {
                val hrs = it.groupValues[1].toIntOrNull() ?: 0
                totalSeconds += hrs * 3600
            }

            if (totalSeconds == 0) {
                val numRegex = Regex("\\b(\\d+)\\b")
                numRegex.find(query)?.let {
                    val num = it.groupValues[1].toIntOrNull() ?: 0
                    totalSeconds = num * 60
                }
            }

            if (totalSeconds > 0) {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds)
                    putExtra(AlarmClock.EXTRA_MESSAGE, "Temporizador E.D.V.I.S.")
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                val durationDesc = if (totalSeconds >= 60) "${totalSeconds / 60} minutos" else "$totalSeconds segundos"
                speakInFemaleVoice("Temporizador de $durationDesc iniciado, señor.")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting timer: ${e.message}", e)
        }
        return false
    }

    private fun handleSendWhatsAppVoiceNote(query: String): Boolean {
        try {
            var text = query.replace(Regex("^(por\\s+favor\\s+|por\\s+fa\\s+)?(envía|envia|manda|mandar|graba)(le)?(\\s+un|\\s+una)?(\\s+audio|\\s+nota\\s+de\\s+voz|\\s+mensaje\\s+de\\s+voz)?(\\s+por|\\s+de)?(\\s+whatsapp|\\s+wasap|\\s+wsp)?(\\s+a|\\s+al)?\\s+", RegexOption.IGNORE_CASE), "").trim()
            val targetName = text.replace(Regex("^(el|la|mi)\\s+"), "").trim()
            if (targetName.isNotEmpty()) {
                serviceScope.launch(Dispatchers.IO) {
                    val matches = findAllMatchingContacts(targetName)
                    withContext(Dispatchers.Main) {
                        if (matches.size > 1) {
                            pendingDisambiguationMessage = null
                            pendingDisambiguationIsAudio = true
                            pendingDisambiguationMatches = matches
                            conversationState = ConversationState.AWAITING_CONTACT_SELECTION
                            conversationExpiresAt = System.currentTimeMillis() + 45_000L
                            activateVoiceListeningWindow(45_000L)

                            val options = matches.take(3).joinToString(" o ") { it.name }
                            speakInFemaleVoice("Encontré varios contactos: $options, señor. ¿A cuál de ellos le envío el audio?")
                            return@withContext
                        }

                        val target = matches.firstOrNull()
                        if (target != null) {
                            dispatchWhatsAppVoiceNoteToNumber(target.name, target.number)
                        } else {
                            speakInFemaleVoice("No encontré el contacto de $targetName en su agenda telefónica, señor. Abriendo WhatsApp.")
                            EdvisAccessibilityService.launchAnyAppByNameOrPackage(this@EdvisForegroundService, "whatsapp")
                            EdvisAccessibilityService.instance?.triggerRecordVoiceNoteInWhatsApp()
                        }
                    }
                }
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleSendWhatsAppVoiceNote: ${e.message}", e)
        }
        return false
    }

    private fun handleDirectWhatsAppVoiceCommand(query: String): Boolean {
        try {
            var text = query.replace(Regex("^(por\\s+favor\\s+|por\\s+fa\\s+)?(envía|envia|manda|mandar|escribe|dile)(le)?(\\s+un)?(\\s+mensaje)?(\\s+por|\\s+de)?(\\s+whatsapp|\\s+wasap|\\s+wsp)?(\\s+a|\\s+al)?\\s+", RegexOption.IGNORE_CASE), "").trim()

            val separatorRegex = Regex("\\s+(que|dici[eé]ndole que|diciendo que|con el mensaje|dile que)\\s+", RegexOption.IGNORE_CASE)
            val match = separatorRegex.find(text)

            var targetName = ""
            var messageBody = ""

            if (match != null) {
                targetName = text.substring(0, match.range.first).trim()
                messageBody = text.substring(match.range.last + 1).trim()
            } else {
                val parts = text.split(Regex("[:\\-]"), limit = 2)
                if (parts.size == 2) {
                    targetName = parts[0].trim()
                    messageBody = parts[1].trim()
                }
            }

            targetName = targetName.replace(Regex("^(el|la|mi)\\s+"), "").trim()
            if (targetName.isNotEmpty() && messageBody.isNotEmpty()) {
                sendDirectWhatsAppToContact(targetName, messageBody)
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing direct WhatsApp command: ${e.message}", e)
        }
        return false
    }

    private fun findAllMatchingContacts(queryName: String): List<ContactMatch> {
        val cleanQuery = normalizeContactText(queryName)
        if (cleanQuery.isEmpty()) return emptyList()
        val matches = mutableListOf<ContactMatch>()
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val seenNumbers = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val number = cursor.getString(numIdx) ?: continue
                    val normName = normalizeContactText(name)
                    val cleanNum = number.replace(Regex("[^0-9+]"), "")
                    if (cleanNum in seenNumbers) continue
                    if (normName == cleanQuery || normName.contains(cleanQuery) || cleanQuery.contains(normName)) {
                        seenNumbers.add(cleanNum)
                        matches.add(ContactMatch(name, number))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up contact matches: ${e.message}", e)
        }
        return matches
    }

    private fun normalizeContactText(str: String): String {
        val nfd = java.text.Normalizer.normalize(str.lowercase().trim(), java.text.Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").replace(Regex("[^a-z0-9]"), "")
    }

    private fun sendDirectWhatsAppToContact(targetName: String, message: String) {
        serviceScope.launch(Dispatchers.IO) {
            val matches = findAllMatchingContacts(targetName)
            withContext(Dispatchers.Main) {
                if (matches.size > 1) {
                    pendingDisambiguationMessage = message
                    pendingDisambiguationIsAudio = false
                    pendingDisambiguationMatches = matches
                    conversationState = ConversationState.AWAITING_CONTACT_SELECTION
                    conversationExpiresAt = System.currentTimeMillis() + 45_000L
                    activateVoiceListeningWindow(45_000L)

                    val options = matches.take(3).joinToString(" o ") { it.name }
                    speakInFemaleVoice("Encontré varios contactos con ese nombre: $options, señor. ¿A cuál de ellos le envío el mensaje?")
                    return@withContext
                }

                val target = matches.firstOrNull()
                if (target != null) {
                    dispatchWhatsAppToNumber(target.name, target.number, message)
                } else {
                    speakInFemaleVoice("No encontré el contacto de $targetName en su agenda telefónica, señor. Abriendo WhatsApp para buscarlo.")
                    EdvisAccessibilityService.launchAnyAppByNameOrPackage(this@EdvisForegroundService, "whatsapp")
                }
            }
        }
    }

    private fun dispatchWhatsAppToNumber(targetName: String, phoneNumber: String, message: String) {
        speakInFemaleVoice("Enviando WhatsApp a $targetName, señor.")
        val cleanPhone = phoneNumber.replace(" ", "").replace("-", "").replace("(", "").replace(")", "").replace("+", "")
        val formattedPhone = if (cleanPhone.length == 10 && cleanPhone.startsWith("3")) "57$cleanPhone" else cleanPhone
        try {
            val url = "https://api.whatsapp.com/send?phone=$formattedPhone&text=${Uri.encode(message)}"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            EdvisAccessibilityService.instance?.triggerSendWhatsAppMessage()
        } catch (e: Exception) {
            Log.e(TAG, "Direct WhatsApp intent error: ${e.message}", e)
            EdvisAccessibilityService.launchAnyAppByNameOrPackage(this, "whatsapp")
        }
    }

    private fun dispatchWhatsAppVoiceNoteToNumber(targetName: String, phoneNumber: String) {
        speakInFemaleVoice("Abriendo chat con $targetName para su mensaje de voz, señor.")
        val cleanPhone = phoneNumber.replace(" ", "").replace("-", "").replace("(", "").replace(")", "").replace("+", "")
        val formattedPhone = if (cleanPhone.length == 10 && cleanPhone.startsWith("3")) "57$cleanPhone" else cleanPhone
        try {
            val url = "https://api.whatsapp.com/send?phone=$formattedPhone"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            EdvisAccessibilityService.instance?.triggerRecordVoiceNoteInWhatsApp()
        } catch (e: Exception) {
            Log.e(TAG, "Direct WhatsApp intent error: ${e.message}", e)
            EdvisAccessibilityService.launchAnyAppByNameOrPackage(this, "whatsapp")
        }
    }

    private fun playVoiceNoteFromSpecificContact(targetPerson: String) {
        speakInFemaleVoice("Buscando audio de $targetPerson en WhatsApp, señor.")
        serviceScope.launch(Dispatchers.IO) {
            val matches = findAllMatchingContacts(targetPerson)
            val phone = matches.firstOrNull()?.number
            withContext(Dispatchers.Main) {
                if (!phone.isNullOrBlank()) {
                    val cleanPhone = phone.replace(" ", "").replace("-", "").replace("(", "").replace(")", "").replace("+", "")
                    val formattedPhone = if (cleanPhone.length == 10 && cleanPhone.startsWith("3")) "57$cleanPhone" else cleanPhone
                    val url = "https://api.whatsapp.com/send?phone=$formattedPhone"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        setPackage("com.whatsapp")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } else {
                    EdvisAccessibilityService.launchAnyAppByNameOrPackage(this@EdvisForegroundService, "whatsapp")
                }
                EdvisAccessibilityService.instance?.triggerPlayLastVoiceMessageInWhatsApp()
            }
        }
    }

    private fun playUniversalMusicVoice(query: String) {
        val lowerQ = query.lowercase().trim()
        if (lowerQ.contains("audio") || lowerQ.contains("whatsapp") || lowerQ.contains("wasap") ||
            lowerQ.contains("nota") || lowerQ.contains("mensaje")) {
            speakInFemaleVoice("Reproduciendo audio de WhatsApp, señor.")
            EdvisAccessibilityService.launchAnyAppByNameOrPackage(this, "whatsapp")
            EdvisAccessibilityService.instance?.triggerPlayLastVoiceMessageInWhatsApp()
            return
        }

        var song = query.replace(Regex("^(por\\s+favor\\s+|por\\s+fa\\s+)?(ponme|pon|reproduce|reproducir|escuchar)\\s+", RegexOption.IGNORE_CASE), "").trim()
        song = song.replace(Regex("^(la\\s+canción|la\\s+cancion|música\\s+de|musica\\s+de|canciones\\s+de|a)\\s+", RegexOption.IGNORE_CASE), "").trim()
        val isYouTube = song.contains("youtube") || query.contains("en youtube") || query.contains("de youtube")
        song = song.replace(Regex("\\s+(en|por|de)\\s+(youtube|spotify)$", RegexOption.IGNORE_CASE), "").trim()

        if (song.isEmpty() || song == "musica" || song == "música" || song == "algo") {
            val targetApp = if (isYouTube) "youtube" else "spotify"
            speakInFemaleVoice("Reproduciendo música en $targetApp, señor.")
            EdvisAccessibilityService.launchAnyAppByNameOrPackage(this, targetApp)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
                    am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
                    EdvisAccessibilityService.instance?.executeAction("PLAY_MUSIC", "", "")
                } catch (_: Exception) {}
            }, 1400L)
            return
        }

        if (isYouTube) {
            speakInFemaleVoice("Buscando $song en YouTube, señor.")
            try {
                val ytIntent = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", song)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(ytIntent)
            } catch (_: Exception) {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(song)}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(webIntent)
            }
        } else {
            speakInFemaleVoice("Reproduciendo $song en Spotify, señor.")
            try {
                val spotifyUri = Uri.parse("spotify:search:" + Uri.encode(song))
                val spotifyIntent = Intent(Intent.ACTION_VIEW, spotifyUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(spotifyIntent)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
                        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
                    } catch (_: Exception) {}
                }, 1800L)
            } catch (_: Exception) {
                val generalIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(SearchManager.QUERY, song)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(generalIntent)
            }
        }
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

    private fun scheduleVoiceAlarm(hour: Int, minute: Int) {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val calendar = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, minute)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                if (before(java.util.Calendar.getInstance())) {
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
            }

            val intent = Intent(this, EdvisVoiceAlarmReceiver::class.java).apply {
                putExtra(EdvisVoiceAlarmReceiver.EXTRA_ALARM_HOUR, hour)
                putExtra(EdvisVoiceAlarmReceiver.EXTRA_ALARM_MINUTE, minute)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                1002,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            }
            Log.i(TAG, "Scheduled exact voice wakeup for: ${calendar.time}")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling voice alarm: ${e.message}", e)
        }
    }

    fun triggerJarvisMorningWakeup(hour: Int, minute: Int) {
        wakeUpScreen()
        ensureAdequateVolume()
        val minStr = if (minute < 10) "0$minute" else "$minute"
        val greeting = "Buenos días, Elvis. Son las $hour:$minStr de la mañana. Es hora de levantarse. Todos los sistemas se encuentran nominales y listos a su servicio."
        speakInFemaleVoice(greeting)
        activateVoiceListeningWindow(60_000L)
    }
}
