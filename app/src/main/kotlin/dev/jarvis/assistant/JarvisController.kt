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
import kotlinx.coroutines.delay
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
        val provider = prefs.provider
        val key = prefs.apiKey(provider)
        if (key.isBlank()) {
            service.overlay.showCard("Önce Jarvis uygulamasından API anahtarını girmen gerekiyor.")
            return
        }
        if (job?.isActive == true) return
        stopSpeaking()
        val overlay = service.overlay
        job = scope.launch {
            overlay.hideCard()
            // Durum çubuğunda geçen süre de gösterilir; böylece takılma ile bekleme ayırt edilir.
            var status = "“$command”"
            var since = System.currentTimeMillis()
            fun show() {
                val seconds = (System.currentTimeMillis() - since) / 1000
                overlay.showStatus(if (seconds >= 3) "$status  $seconds sn" else status)
            }
            show()
            val ticker = launch {
                while (true) { delay(1000); show() }
            }
            DebugLog.add("── komut: $command  [$provider / ${prefs.model(provider)}]")
            try {
                val tools = PhoneTools(service)
                val brain: Brain = if (provider == Prefs.CLAUDE) {
                    ClaudeBrain(tools, key, prefs.model(provider))
                } else {
                    GeminiBrain(tools, key, prefs.model(provider))
                }
                val answer = brain.run(command) {
                    status = it
                    since = System.currentTimeMillis()
                    show()
                }
                ticker.cancel()
                DebugLog.add("yanıt: $answer")
                overlay.hideStatus()
                overlay.showCard(answer)
                if (answer.length < 140 && "http" !in answer) {
                    main.postDelayed({ if (job?.isActive != true) overlay.hideCard() }, 7000)
                }
                if (prefs.ttsEnabled) speak(app, answer)
            } catch (e: CancellationException) {
                DebugLog.add("iptal edildi")
                overlay.hideStatus()
                throw e
            } catch (e: Throwable) {
                // Error türleri de (ör. sınıf yükleme hataları) burada yakalanır ki kullanıcı nedenini görsün.
                Log.e(TAG, "command failed", e)
                DebugLog.add("HATA: ${Log.getStackTraceString(e).take(500)}")
                overlay.hideStatus()
                overlay.showCard("Bir sorun oluştu: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                ticker.cancel()
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
