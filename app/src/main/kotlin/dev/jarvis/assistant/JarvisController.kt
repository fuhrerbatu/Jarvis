package dev.jarvis.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/** Ses, ekran servisi ve Claude ajanını birbirine bağlar; aynı anda tek komut çalışır. */
object JarvisController {

    private const val TAG = "Jarvis"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var job: Job? = null
    @Volatile private var speaking = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    val busy: Boolean get() = job?.isActive == true || speaking

    fun submit(context: Context, command: String) {
        val app = context.applicationContext
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            toast(app, "Jarvis erişilebilirlik servisi kapalı")
            return
        }
        val prefs = Prefs(app)
        if (prefs.apiKey.isBlank()) {
            service.overlay.showCard("Önce Jarvis uygulamasından Claude API anahtarını girmen gerekiyor.")
            return
        }
        if (job?.isActive == true) return
        stopSpeaking()
        val overlay = service.overlay
        job = scope.launch {
            overlay.hideCard()
            overlay.showStatus("“$command”")
            try {
                val answer = JarvisAgent(service, prefs.apiKey, prefs.model).run(command) { overlay.showStatus(it) }
                overlay.hideStatus()
                overlay.showCard(answer)
                if (answer.length < 140 && "http" !in answer) {
                    main.postDelayed({ if (job?.isActive != true) overlay.hideCard() }, 7000)
                }
                if (prefs.ttsEnabled) speak(app, answer)
            } catch (e: CancellationException) {
                overlay.hideStatus()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "command failed", e)
                overlay.hideStatus()
                overlay.showCard("Bir sorun oluştu: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                main.post { VoiceService.instance?.resume() }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        stopSpeaking()
    }

    fun onBubbleTapped(context: Context) {
        if (busy) cancel() else VoiceService.start(context, listenOnce = true)
    }

    // ------------------------------------------------------------------ TTS

    private fun speak(app: Context, answer: String) = main.post {
        val text = spokenSummary(answer)
        if (text.isBlank()) return@post
        val engine = tts
        if (engine != null && ttsReady) {
            say(engine, text)
            return@post
        }
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val created = tts ?: return@TextToSpeech
                created.language = Locale.forLanguageTag(Prefs.SPEECH_LOCALE)
                created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { speaking = true }
                    override fun onDone(id: String?) = finished()
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) = finished()
                    override fun onStop(id: String?, interrupted: Boolean) = finished()
                })
                ttsReady = true
                say(created, text)
            }
        }
    }

    private fun say(engine: TextToSpeech, text: String) {
        speaking = true
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis") != TextToSpeech.SUCCESS) finished()
    }

    private fun finished() {
        speaking = false
        main.post { VoiceService.instance?.resume() }
    }

    private fun stopSpeaking() {
        if (speaking) main.post { tts?.stop() }
        speaking = false
    }

    /** Kartın tamamı yerine yalnızca ilk paragraf, bağlantılar ve biçimlendirme olmadan okunur. */
    private fun spokenSummary(answer: String): String =
        answer.trim().substringBefore("\n\n")
            .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
            .replace(Regex("""https?://\S+"""), "")
            .replace(Regex("""[*#_`>|]"""), "")
            .trim()
            .take(300)

    private fun toast(app: Context, text: String) =
        main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
}
