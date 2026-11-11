package dev.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import dev.jarvis.assistant.databinding.ActivityMainBinding

/** Kurulum ekranı: API anahtarı, izinler, seçenekler ve yazarak komut deneme. */
class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        ui.providerGroup.check(if (prefs.provider == Prefs.CLAUDE) R.id.providerClaude else R.id.providerGemini)
        showProvider()
        ui.providerGroup.setOnCheckedChangeListener { _, id ->
            prefs.provider = if (id == R.id.providerClaude) Prefs.CLAUDE else Prefs.GEMINI
            showProvider()
            refresh()
        }
        ui.saveKey.setOnClickListener {
            val provider = prefs.provider
            prefs.setApiKey(provider, ui.apiKey.text?.toString().orEmpty())
            prefs.setModel(provider, ui.model.text?.toString().orEmpty())
            showProvider()
            Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show()
            refresh()
        }
        ui.getKey.setOnClickListener {
            val url = if (prefs.provider == Prefs.CLAUDE) "https://console.anthropic.com/settings/keys"
            else "https://aistudio.google.com/apikey"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        ui.grantMic.setOnClickListener {
            val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
            permissions.launch(wanted.toTypedArray())
        }
        ui.openAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        ui.openAppInfo.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        }

        ui.wakeSwitch.isChecked = prefs.wakeWordEnabled
        ui.ttsSwitch.isChecked = prefs.ttsEnabled
        ui.bubbleSwitch.isChecked = prefs.bubbleEnabled
        ui.wakeSwitch.setOnCheckedChangeListener { _, on ->
            prefs.wakeWordEnabled = on
            startVoiceIfPossible()
        }
        ui.ttsSwitch.setOnCheckedChangeListener { _, on -> prefs.ttsEnabled = on }
        ui.bubbleSwitch.setOnCheckedChangeListener { _, on ->
            prefs.bubbleEnabled = on
            JarvisAccessibilityService.instance?.overlay?.refreshBubble()
        }

        ui.runCommand.setOnClickListener {
            val command = ui.command.text?.toString()?.trim().orEmpty()
            if (command.isNotEmpty()) JarvisController.submit(this, command)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Seçili sağlayıcının anahtarını, modelini ve açıklamasını alanlara yükler. */
    private fun showProvider() {
        val provider = prefs.provider
        ui.apiKey.setText(prefs.apiKey(provider))
        ui.model.setText(prefs.model(provider))
        ui.providerNote.text = if (provider == Prefs.CLAUDE) {
            "Ücretli: her komut Claude API kredinden düşer. Web araması yapabilir."
        } else {
            "Ücretsiz: Google hesabınla AI Studio'dan kart girmeden anahtar alınır. Günlük kullanım sınırı vardır."
        }
    }

    private fun micGranted() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startVoiceIfPossible() {
        if (!micGranted()) return
        val running = VoiceService.instance
        if (running != null) running.resume() else VoiceService.start(this)
    }

    private fun refresh() {
        val keyOk = prefs.apiKey(prefs.provider).isNotBlank()
        val micOk = micGranted()
        val a11yOk = JarvisAccessibilityService.instance != null
        ui.stepApiKey.text = mark(keyOk) + " 1. Yapay zekâ ve API anahtarı"
        ui.stepMic.text = mark(micOk) + " 2. Mikrofon ve bildirim izni"
        ui.stepAccessibility.text = mark(a11yOk) + " 3. Erişilebilirlik servisi"
        ui.status.text = if (keyOk && micOk && a11yOk) {
            "Hazır. Herhangi bir ekranda “Jarvis, …” de ya da ekrandaki J düğmesine dokunup konuş."
        } else {
            "Yukarıdaki adımları tamamlayınca Jarvis kullanıma hazır olur."
        }
        startVoiceIfPossible()
    }

    private fun mark(ok: Boolean) = if (ok) "✓" else "○"
}
