package dev.jarvis.assistant

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Son komutların adım adım kaydı; uygulamadaki "Çalışma kaydı" bölümünde gösterilir. */
object DebugLog {
    private const val MAX_LINES = 150
    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(message: String) {
        lines.addLast("${clock.format(Date())}  ${message.take(600)}")
        while (lines.size > MAX_LINES) lines.removeFirst()
        android.util.Log.i("Jarvis", message)
    }

    @Synchronized
    fun text(): String = if (lines.isEmpty()) "Henüz bir komut çalıştırılmadı." else lines.joinToString("\n")
}
