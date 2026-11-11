package dev.jarvis.assistant

import android.content.Context
import android.content.SharedPreferences

/** Uygulamaya özel (başka uygulamaların okuyamadığı) depoda tutulan ayarlar. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL)?.ifBlank { DEFAULT_MODEL } ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim()).apply()

    var wakeWordEnabled: Boolean
        get() = sp.getBoolean("wake", true)
        set(v) = sp.edit().putBoolean("wake", v).apply()

    var ttsEnabled: Boolean
        get() = sp.getBoolean("tts", true)
        set(v) = sp.edit().putBoolean("tts", v).apply()

    var bubbleEnabled: Boolean
        get() = sp.getBoolean("bubble", true)
        set(v) = sp.edit().putBoolean("bubble", v).apply()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val SPEECH_LOCALE = "tr-TR"
    }
}
