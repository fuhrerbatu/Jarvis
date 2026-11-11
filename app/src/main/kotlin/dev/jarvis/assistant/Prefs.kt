package dev.jarvis.assistant

import android.content.Context
import android.content.SharedPreferences

/** Uygulamaya özel (başka uygulamaların okuyamadığı) depoda tutulan ayarlar. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    /** "gemini" (ücretsiz katman) ya da "claude" (ücretli). */
    var provider: String
        get() = sp.getString("provider", GEMINI) ?: GEMINI
        set(v) = sp.edit().putString("provider", v).apply()

    fun apiKey(provider: String): String = sp.getString("api_key_$provider", "") ?: ""

    fun setApiKey(provider: String, key: String) = sp.edit().putString("api_key_$provider", key.trim()).apply()

    fun model(provider: String): String =
        sp.getString("model_$provider", null)?.ifBlank { null } ?: defaultModel(provider)

    fun setModel(provider: String, model: String) = sp.edit().putString("model_$provider", model.trim()).apply()

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
        const val GEMINI = "gemini"
        const val CLAUDE = "claude"

        fun defaultModel(provider: String) = if (provider == CLAUDE) "claude-opus-5-5" else "gemini-3.8-flash"
        const val SPEECH_LOCALE = "tr-TR"
    }
}
