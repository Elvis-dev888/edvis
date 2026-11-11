package com.edvis.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.edvis.core.services.EdvisAccessibilityService
import com.edvis.core.services.EdvisForegroundService
import org.json.JSONObject
import java.util.Locale

/**
 * Tactical HUD Native Android App combining:
 * 1. Continuous Unrestricted Native SpeechRecognizer listening 24/7 without buttons
 * 2. Native Android Bridge (`window.EdvisNativeAndroid`) with `QUERY_ALL_PACKAGES` permission to launch ANY app on the phone
 * 3. Persistent Foreground Service + Notification Listener + Accessibility Service
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "EdvisMainActivity"
    }

    private var webView: WebView? = null
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isListening = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()

        tts = TextToSpeech(this, { status ->
            if (status == TextToSpeech.SUCCESS) {
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
                    }
                } catch (_: Exception) {}
            }
        }, "com.google.android.tts")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#070B10"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Top Native OS Permissions & Background Service Bar
        val scrollBar = android.widget.HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isHorizontalScrollBarEnabled = false
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0E1621"))
            setPadding(12, 12, 12, 12)
        }

        val svcBtn = Button(this).apply {
            text = if (EdvisForegroundService.isRunning) "🟢 SERVICIO ACTIVO" else "🛡️ ACTIVAR 2DO PLANO"
            textSize = 10f
            setBackgroundColor(Color.parseColor("#00C853"))
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                if (EdvisForegroundService.isRunning) {
                    stopService(Intent(this@MainActivity, EdvisForegroundService::class.java))
                    text = "🛡️ ACTIVAR 2DO PLANO"
                } else {
                    val svcIntent = Intent(this@MainActivity, EdvisForegroundService::class.java)
                    ContextCompat.startForegroundService(this@MainActivity, svcIntent)
                    text = "🟢 SERVICIO ACTIVO"
                }
            }
        }

        val battBtn = Button(this).apply {
            text = "🔋 BATERÍA SIN LÍMITES"
            textSize = 10f
            setOnClickListener {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            }
        }

        val notifBtn = Button(this).apply {
            text = "🔔 NOTIFICACIONES"
            textSize = 10f
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        val accBtn = Button(this).apply {
            text = "⚙️ ACCESIBILIDAD"
            textSize = 10f
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val testVoiceBtn = Button(this).apply {
            text = "🔊 PROBAR VOZ"
            textSize = 10f
            setOnClickListener {
                tts?.speak("Sistemas en línea, señor. Bloquee la pantalla con el botón lateral y llámeme diciendo Edvis.", TextToSpeech.QUEUE_FLUSH, null, "test_voice")
            }
        }

        topBar.addView(svcBtn)
        topBar.addView(battBtn)
        topBar.addView(notifBtn)
        topBar.addView(accBtn)
        topBar.addView(testVoiceBtn)
        scrollBar.addView(topBar)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            addJavascriptInterface(EdvisAndroidBridge(), "EdvisNativeAndroid")

            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    runOnUiThread {
                        request?.grant(request.resources)
                    }
                }
            }

            loadUrl("https://edvis-tactical-ia.netlify.app")
        }

        root.addView(scrollBar)
        root.addView(webView)
        setContentView(root)
    }

    inner class EdvisAndroidBridge {
        @JavascriptInterface
        fun executeDeviceAction(actionType: String, target: String, payload: String) {
            runOnUiThread {
                when (actionType.uppercase()) {
                    "OPEN_APP" -> {
                        EdvisAccessibilityService.launchAnyAppByNameOrPackage(this@MainActivity, target)
                    }
                    "MAKE_PHONE_CALL" -> {
                        val phone = target.replace(Regex("[^0-9+]"), "")
                        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(callIntent)
                    }
                    "REPLY_WHATSAPP" -> {
                        val svc = EdvisAccessibilityService.instance
                        if (svc != null) {
                            svc.executeAction("REPLY_WHATSAPP", target, payload)
                        } else {
                            val phone = target.replace(Regex("[^0-9]"), "")
                            val enc = Uri.encode(payload)
                            val url = if (phone.isNotEmpty()) {
                                "whatsapp://send?phone=$phone&text=$enc"
                            } else {
                                "whatsapp://send?text=$enc"
                            }
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }
                    }
                }
            }
        }

        @JavascriptInterface
        fun speakOutLoud(text: String) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "edvis_native_tts")
        }
    }

    fun startContinuousVoiceRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        runOnUiThread {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            isListening = true
                        }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {
                            runOnUiThread {
                                webView?.evaluateJavascript("if (window.updateAudioLevel) window.updateAudioLevel($rmsdB);", null)
                            }
                        }
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            isListening = false
                        }
                        override fun onError(error: Int) {
                            isListening = false
                            handler.postDelayed({
                                if (!isFinishing && !isDestroyed) {
                                    startContinuousVoiceRecognition()
                                }
                            }, 300L)
                        }
                        override fun onResults(results: Bundle?) {
                            isListening = false
                            val isTts = tts?.isSpeaking == true || EdvisForegroundService.instance?.isTtsSpeaking() == true
                            if (!isTts) {
                                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                val text = matches?.firstOrNull()?.trim() ?: ""
                                if (text.isNotEmpty()) {
                                    handleSpokenVoiceCommand(text)
                                }
                            }
                            handler.postDelayed({
                                if (!isFinishing && !isDestroyed) {
                                    startContinuousVoiceRecognition()
                                }
                            }, 150L)
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val partial = matches?.firstOrNull()?.trim() ?: ""
                            if (partial.isNotEmpty()) {
                                runOnUiThread {
                                    val safe = JSONObject.quote(partial)
                                    webView?.evaluateJavascript("if (window.showLiveVoiceTranscript) window.showLiveVoiceTranscript($safe);", null)
                                }
                            }
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CO")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                }

                speechRecognizer?.startListening(recognizerIntent)
            } catch (e: Exception) {
                Log.e(TAG, "SpeechRecognizer error: ${e.message}")
            }
        }
    }

    fun handleSpokenVoiceCommand(spokenText: String) {
        val lower = spokenText.lowercase().trim()
        val cleaned = lower.replace(Regex("^(oye\\s+|hola\\s+)?(edvis|elvis|ervis|edwin|jarvis|ed)[,\\s]*", RegexOption.IGNORE_CASE), "").trim()

        runOnUiThread {
            val safe = JSONObject.quote(spokenText)
            webView?.evaluateJavascript("if (window.onNativeVoiceCommand) window.onNativeVoiceCommand($safe);", null)
        }

        // 1. If there's an ongoing interactive conversation (e.g. "¿Deseas responder?") or ringing call, prioritize it
        val svc = EdvisForegroundService.instance
        if (svc != null && (svc.hasActiveConversation() || svc.isCallRinging)) {
            svc.processAutonomousVoiceCommand(spokenText)
            return
        }

        if (cleaned.startsWith("abre ") || cleaned.startsWith("abrir ") || lower.startsWith("abre ") || lower.startsWith("abrir ")) {
            val appQuery = (if (cleaned.startsWith("abre ") || cleaned.startsWith("abrir ")) cleaned else lower)
                .substringAfter("abre ").substringAfter("abrir ").trim()
            tts?.speak("Abriendo $appQuery, señor", TextToSpeech.QUEUE_FLUSH, null, "edvis_app")
            EdvisAccessibilityService.launchAnyAppByNameOrPackage(this, appQuery)
            return
        }

        if (cleaned.isEmpty() || cleaned == "estas ahi" || cleaned == "estás ahí" || cleaned == "hola") {
            tts?.speak("En línea, señor. Todos los sistemas operativos listos. ¿Cuál es su orden?", TextToSpeech.QUEUE_FLUSH, null, "edvis_greeting")
            return
        }

        EdvisForegroundService.instance?.processAutonomousVoiceCommand(spokenText)
    }

    override fun onResume() {
        super.onResume()
        if (!EdvisForegroundService.isRunning && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val svcIntent = Intent(this, EdvisForegroundService::class.java)
            ContextCompat.startForegroundService(this, svcIntent)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startContinuousVoiceRecognition()
        }

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (_: Exception) {}
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        isListening = false
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            perms.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 2001)
        } else {
            val svcIntent = Intent(this, EdvisForegroundService::class.java)
            ContextCompat.startForegroundService(this, svcIntent)
            startContinuousVoiceRecognition()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val svcIntent = Intent(this, EdvisForegroundService::class.java)
            ContextCompat.startForegroundService(this, svcIntent)
            startContinuousVoiceRecognition()
        }
    }
}
