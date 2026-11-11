package dev.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast

/**
 * Mikrofonu dinleyen ön plan servisi. "Jarvis …" ile başlayan cümleleri yakalar
 * ve komutu [JarvisController]'a iletir. Yüzen düğmeye basıldığında uyandırma
 * kelimesi beklemeden tek bir komut dinler.
 */
class VoiceService : Service(), RecognitionListener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var armedUntil = 0L
    private val startRunnable = Runnable { startListening() }

    private val armed get() = SystemClock.elapsedRealtime() < armedUntil

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Jarvis dinleme", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Jarvis hazır")
            .setContentText("“Jarvis …” diyerek komut verebilirsin")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        try {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android 14+: mikrofon servisi yalnızca uygulama öndeyken başlatılabilir.
            Log.w(TAG, "foreground start refused", e)
            Toast.makeText(this, "Dinlemeyi başlatmak için Jarvis uygulamasını bir kez aç", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (instance !== this) return START_NOT_STICKY
        if (intent?.action == ACTION_LISTEN_ONCE) arm()
        schedule(0)
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        main.removeCallbacks(startRunnable)
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    /** Ajan işini bitirdiğinde ya da ayar değiştiğinde dinlemeyi yeniden değerlendirir. */
    fun resume() = schedule(300)

    private fun arm() {
        armedUntil = SystemClock.elapsedRealtime() + ARM_WINDOW_MS
        JarvisAccessibilityService.instance?.overlay?.showStatus("Dinliyorum…", cancellable = false)
    }

    private fun disarm() {
        if (armedUntil == 0L) return
        armedUntil = 0L
        if (!JarvisController.busy) JarvisAccessibilityService.instance?.overlay?.hideStatus()
    }

    private fun schedule(delayMs: Long) {
        main.removeCallbacks(startRunnable)
        main.postDelayed(startRunnable, delayMs)
    }

    private fun startListening() {
        if (listening || JarvisController.busy) return
        if (!armed) disarm()
        if (!prefs.wakeWordEnabled && !armed) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "no speech recognition service on this device")
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.SPEECH_LOCALE)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        listening = true
        r.startListening(intent)
    }

    // ------------------------------------------------------ RecognitionListener

    override fun onResults(results: Bundle?) {
        listening = false
        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        handle(texts)
        schedule(250)
    }

    override fun onError(error: Int) {
        listening = false
        val delay = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 200L
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { recognizer?.cancel(); 800L }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { stopSelf(); return }
            SpeechRecognizer.ERROR_CLIENT -> 500L
            else -> {
                // Ağ / sunucu hataları: tanıyıcıyı yenile ve biraz bekle.
                recognizer?.destroy(); recognizer = null
                3000L
            }
        }
        schedule(delay)
    }

    private fun handle(texts: List<String>) {
        if (texts.isEmpty() || JarvisController.busy) return
        if (armed) {
            val command = stripWake(texts.first())
            if (command.isNotBlank()) {
                armedUntil = 0L
                JarvisController.submit(this, command)
            }
            return
        }
        val heard = texts.firstOrNull { WAKE.containsMatchIn(it) } ?: return
        val command = stripWake(heard)
        if (command.isBlank()) arm() else JarvisController.submit(this, command)
    }

    /** "… Jarvis, WhatsApp'ı aç" → "WhatsApp'ı aç" */
    private fun stripWake(text: String): String {
        val m = WAKE.find(text) ?: return text.trim()
        return text.substring(m.range.last + 1).trim().trimStart(',', '.', '!', '?', ' ')
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    companion object {
        private const val TAG = "JarvisVoice"
        private const val CHANNEL = "listening"
        private const val ACTION_LISTEN_ONCE = "dev.jarvis.assistant.LISTEN_ONCE"
        private const val ARM_WINDOW_MS = 9000L

        /** Türkçe tanıyıcının "Jarvis" için ürettiği yaygın yazımlar. */
        private val WAKE = Regex("""(?i)\b[jcç][ae]rv[iıİ]s\b""")

        @Volatile
        var instance: VoiceService? = null
            private set

        fun start(context: Context, listenOnce: Boolean = false) {
            val intent = Intent(context, VoiceService::class.java)
            if (listenOnce) intent.action = ACTION_LISTEN_ONCE
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "could not start voice service", e)
                Toast.makeText(context, "Dinlemeyi başlatmak için Jarvis uygulamasını bir kez aç", Toast.LENGTH_LONG).show()
            }
        }
    }
}
