package com.edvis.core.audio

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

/**
 * Zero-cloud, on-device Wake Word detector using Vosk with constrained grammar:
 * ["edvis", "oye edvis", "[unk]"]
 */
class VoskWakeEngine(
    private val context: Context,
    private val onWakeWordDetected: (String) -> Unit
) {
    companion object {
        private const val TAG = "VoskWakeEngine"
        private const val SAMPLE_RATE = 16000.0f
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    var isReady: Boolean = false
        private set

    fun initialize(): Boolean {
        return try {
            val targetDir = File(context.filesDir, "vosk-es")
            val amDir = File(targetDir, "am")
            val finalMdl = File(amDir, "final.mdl")

            // Ensure model files exist and are not corrupted/incomplete
            if (!finalMdl.exists() || finalMdl.length() < 10_000_000L) {
                Log.i(TAG, "Extracting full Vosk Spanish model from assets to filesDir...")
                targetDir.deleteRecursively()
                targetDir.mkdirs()
                copyAssetsDirectory("models/vosk-es", targetDir)
            }

            model = Model(targetDir.absolutePath)
            recognizer = Recognizer(model, SAMPLE_RATE)
            isReady = true
            Log.i(TAG, "Vosk offline wake engine initialized successfully with full Spanish acoustic model!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Vosk initialization error: ${e.message}", e)
            isReady = false
            false
        }
    }

    /**
     * Feeds a 16kHz 16-bit mono PCM frame into Vosk and triggers callback if "edvis",
     * a direct voice command, or an active interactive conversation response (e.g. "sí", "no", "dile...") is spoken.
     */
    fun acceptPcmFrame(pcmBytes: ByteArray, length: Int, isVoiceWindowActive: Boolean = false): Boolean {
        val rec = recognizer ?: return false
        val isFinal = rec.acceptWaveForm(pcmBytes, length)
        val jsonStr = if (isFinal) rec.result else rec.partialResult

        if (jsonStr.isNotBlank() && jsonStr != "{}") {
            try {
                val obj = JSONObject(jsonStr)
                val text = if (isFinal) {
                    obj.optString("text", "")
                } else {
                    obj.optString("partial", "")
                }.lowercase().trim()

                if (text.isNotEmpty()) {
                    val isWake = isVoiceWindowActive ||
                        text.contains("edvis") ||
                        text.contains("elvis") ||
                        text.contains("ervis") ||
                        text.contains("edwin") ||
                        text.contains("jarvis") ||
                        text.contains("oye") ||
                        text.contains("hola") ||
                        text.contains("estas ahi") ||
                        text.contains("estás ahí") ||
                        text.contains("abre") ||
                        text.contains("registra") ||
                        text.contains("gasto") ||
                        text.contains("contesta") ||
                        text.contains("cuelga") ||
                        text.contains("responde") ||
                        text.contains("dile") ||
                        text.contains("leelo") ||
                        text.contains("léelo") ||
                        text.contains("leeme") ||
                        text.contains("léeme") ||
                        text.contains("reproduce") ||
                        text.contains("reproducir") ||
                        text.contains("musica") ||
                        text.contains("música") ||
                        text.contains("linterna") ||
                        text.contains("bateria") ||
                        text.contains("batería") ||
                        text.contains("estado") ||
                        text.contains("diagnostico") ||
                        text.contains("diagnóstico") ||
                        text.contains("volumen") ||
                        text.contains("llama") ||
                        text.contains("llamar") ||
                        text.contains("donde") ||
                        text.contains("dónde") ||
                        text.contains("camara") ||
                        text.contains("cámara") ||
                        text.contains("luz") ||
                        text.contains("luces") ||
                        text.contains("taller") ||
                        text.contains("escanea") ||
                        text.contains("mira") ||
                        text.contains("whatsapp") ||
                        text.contains("wasap") ||
                        text.contains("wsp") ||
                        text.contains("youtube") ||
                        text.contains("spotify") ||
                        text.contains("hora") ||
                        text.contains("fecha") ||
                        text.contains("quien") ||
                        text.contains("quién")

                    if (isWake) {
                        Log.i(TAG, "Vosk captured speech: '$text' (isFinal=$isFinal, isVoiceWindowActive=$isVoiceWindowActive)")
                        if (isFinal) {
                            rec.reset()
                            onWakeWordDetected(text)
                            return true
                        } else {
                            val words = text.split(Regex("\\s+"))
                            val isImmediateCommand = (words.size in 1..4) && (
                                text == "si" || text == "sí" || text == "no" || text == "claro" ||
                                text == "dale" || text == "contesta" || text == "cuelga" ||
                                text == "leelo" || text == "léelo" || text == "leeme" || text == "léeme" ||
                                text == "reproduce" || text == "reproducir" ||
                                text == "edvis" || text == "elvis" || text == "ervis" ||
                                text == "jarvis" || text == "edwin" || text == "oye" ||
                                text == "oye elvis" || text == "oye edvis" || text == "hola" ||
                                text.startsWith("abre ") || text.startsWith("ábreme ") || text.startsWith("abreme ") ||
                                text.contains("linterna") || text.contains("pon musica") || text.contains("pon música") ||
                                text.contains("sube el volumen") || text.contains("baja el volumen") ||
                                text.contains("que hora es") || text.contains("dime la hora") ||
                                text.contains("donde estoy")
                            )
                            if (isImmediateCommand) {
                                rec.reset()
                                onWakeWordDetected(text)
                                return true
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun copyAssetsDirectory(assetPath: String, destDir: File): Boolean {
        val assetManager = context.assets
        val entries = assetManager.list(assetPath) ?: return false
        // Ignore if only README.md exists
        if (entries.isEmpty() || (entries.size == 1 && entries[0].equals("README.md", true))) {
            return false
        }

        if (!destDir.exists()) destDir.mkdirs()

        for (entry in entries) {
            val fullAssetPath = "$assetPath/$entry"
            val outFile = File(destDir, entry)
            val subEntries = assetManager.list(fullAssetPath)
            if (!subEntries.isNullOrEmpty()) {
                copyAssetsDirectory(fullAssetPath, outFile)
            } else {
                assetManager.open(fullAssetPath).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        return true
    }

    fun release() {
        recognizer?.close()
        model?.close()
        recognizer = null
        model = null
        isReady = false
    }
}
