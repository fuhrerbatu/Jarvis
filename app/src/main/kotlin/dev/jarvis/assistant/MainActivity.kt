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

        ui.apiKey.setText(prefs.apiKey)
        ui.model.setText(prefs.model)
        ui.saveKey.setOnClickListener {
            prefs.apiKey = ui.apiKey.text?.toString().orEmpty()
            prefs.model = ui.model.text?.toString().orEmpty()
            ui.model.setText(prefs.model)
            Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show()
            refresh()
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

    private fun micGranted() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startVoiceIfPossible() {
        if (!micGranted()) return
        val running = VoiceService.instance
        if (running != null) running.resume() else VoiceService.start(this)
    }

    private fun refresh() {
        val keyOk = prefs.apiKey.isNotBlank()
        val micOk = micGranted()
        val a11yOk = JarvisAccessibilityService.instance != null
        ui.stepApiKey.text = mark(keyOk) + " 1. Claude API anahtarı"
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
