package dev.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Jarvis'in "elleri ve gözleri": ekrandaki öğeleri okur, ekran görüntüsü alır,
 * dokunur, yazar, kaydırır ve uygulama açar.
 *
 * Modele verilen tüm koordinatlar, ekran görüntüsüyle aynı ölçekli uzaydadır
 * (uzun kenar en fazla [MAX_EDGE] piksel); gerçek piksele burada çevrilir.
 */
class JarvisAccessibilityService : AccessibilityService() {

    lateinit var overlay: Overlay
        private set

    private val nodes = HashMap<Int, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = Overlay(this)
        instance = this
        overlay.refreshBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        overlay.destroy()
        return super.onUnbind(intent)
    }

    // ---------------------------------------------------------------- ölçek

    private fun screenBounds(): Rect =
        getSystemService(WindowManager::class.java).currentWindowMetrics.bounds

    private fun scale(): Float {
        val b = screenBounds()
        return minOf(1f, MAX_EDGE / max(b.width(), b.height()).toFloat())
    }

    // ------------------------------------------------------------ ekran oku

    /** Ekrandaki anlamlı öğelerin numaralı listesi. Numaralar tap/type_text için kullanılır. */
    fun describeScreen(): String {
        nodes.clear()
        val s = scale()
        val b = screenBounds()
        val sb = StringBuilder()
        val roots = windows
            .sortedByDescending { it.layer }
            .mapNotNull { it.root }
            .filter { it.packageName != packageName }
            .ifEmpty { listOfNotNull(rootInActiveWindow?.takeIf { it.packageName != packageName }) }

        val foreground = rootInActiveWindow?.packageName?.toString()
            ?: roots.firstOrNull()?.packageName?.toString() ?: "?"
        sb.append("foreground_app: ").append(appLabel(foreground)).append(" (").append(foreground).append(")\n")
        sb.append("screen: ").append((b.width() * s).roundToInt()).append('x')
            .append((b.height() * s).roundToInt()).append('\n')
        sb.append("elements ([id] Type \"text\" flags @center_x,center_y):\n")

        var count = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= MAX_NODES || depth > 60) return
            if (node.isVisibleToUser) {
                val text = if (node.isPassword) "•••" else node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val interesting = text.isNotBlank() || desc.isNotBlank() || node.isClickable ||
                    node.isEditable || node.isScrollable || node.isCheckable
                if (interesting) {
                    val r = Rect().also(node::getBoundsInScreen)
                    if (!r.isEmpty) {
                        val id = ++count
                        nodes[id] = node
                        sb.append('[').append(id).append("] ")
                            .append(node.className?.toString()?.substringAfterLast('.') ?: "View")
                        if (text.isNotBlank()) sb.append(" \"").append(text.clip()).append('"')
                        if (desc.isNotBlank() && desc != text) sb.append(" desc=\"").append(desc.clip()).append('"')
                        if (node.isEditable) sb.append(" editable")
                        if (node.isClickable) sb.append(" clickable")
                        if (node.isScrollable) sb.append(" scrollable")
                        if (node.isCheckable) sb.append(if (node.isChecked) " checked" else " unchecked")
                        if (node.isFocused) sb.append(" focused")
                        sb.append(" @").append((r.centerX() * s).roundToInt()).append(',')
                            .append((r.centerY() * s).roundToInt()).append('\n')
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { visit(it, depth + 1) }
            }
        }
        roots.forEach { visit(it, 0) }
        if (count == 0) sb.append("(no readable elements — use take_screenshot)\n")
        if (count >= MAX_NODES) sb.append("(list truncated)\n")
        return sb.toString()
    }

    private fun String.clip() = replace('\n', ' ').let { if (it.length > 200) it.take(200) + "…" else it }

    // ------------------------------------------------------- ekran görüntüsü

    /** Jarvis'in kendi katmanlarını gizleyip ekranın JPEG görüntüsünü (base64) döndürür. */
    suspend fun screenshotJpegBase64(): String {
        withContext(Dispatchers.Main) { overlay.setHidden(true) }
        try {
            delay(180)
            val bitmap = try {
                captureOnce()
            } catch (e: ScreenshotException) {
                // Sistem art arda görüntü almayı ~1 sn ile sınırlar; bir kez tekrar dene.
                delay(1100)
                captureOnce()
            }
            val s = scale()
            val scaled = if (s < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap, (bitmap.width * s).roundToInt(), (bitmap.height * s).roundToInt(), true
                )
            } else bitmap
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            withContext(Dispatchers.Main + NonCancellable) { overlay.setHidden(false) }
        }
    }

    private class ScreenshotException(code: Int) : Exception("screenshot failed (code $code)")

    private suspend fun captureOnce(): Bitmap {
        val result = CompletableDeferred<Bitmap>()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(shot: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                shot.hardwareBuffer.close()
                if (soft != null) result.complete(soft) else result.completeExceptionally(ScreenshotException(-1))
            }

            override fun onFailure(errorCode: Int) {
                result.completeExceptionally(ScreenshotException(errorCode))
            }
        })
        return result.await()
    }

    // -------------------------------------------------------------- eylemler

    fun openApp(name: String): String {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0).map {
            it.loadLabel(pm).toString() to it.activityInfo.packageName
        }
        val q = name.trim().lowercase()
        val match = apps.firstOrNull { it.first.lowercase() == q }
            ?: apps.firstOrNull { it.second.lowercase() == q }
            ?: apps.firstOrNull { it.first.lowercase().contains(q) || q.contains(it.first.lowercase()) }
            ?: apps.firstOrNull { it.second.lowercase().contains(q.replace(" ", "")) }
        if (match == null) {
            return "No installed app matches \"$name\". Installed apps: " +
                apps.map { it.first }.distinct().sorted().joinToString(", ")
        }
        val intent = pm.getLaunchIntentForPackage(match.second)
            ?: return "App ${match.first} has no launchable activity."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return "Opened ${match.first} (${match.second})."
    }

    fun openUrl(url: String): String {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return "Only http/https links can be opened."
        startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opened $url"
    }

    suspend fun tapElement(id: Int, long: Boolean): String {
        val node = nodes[id] ?: return "Unknown element id $id. Call read_screen for fresh ids."
        if (!node.refresh()) return "Element $id is no longer on screen. Call read_screen for fresh ids."
        if (!long) {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Tapped element $id."
        }
        val r = Rect().also(node::getBoundsInScreen)
        return if (gestureTap(r.exactCenterX(), r.exactCenterY(), long)) "Tapped element $id." else "Tap gesture was cancelled."
    }

    suspend fun tapPoint(x: Double, y: Double, long: Boolean): String {
        val s = scale()
        return if (gestureTap((x / s).toFloat(), (y / s).toFloat(), long)) "Tapped ($x, $y)." else "Tap gesture was cancelled."
    }

    private suspend fun gestureTap(x: Float, y: Float, long: Boolean): Boolean {
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        return gesture(path, if (long) 750L else 60L)
    }

    /** direction: içeriğin hangi yönünü görmek istediğimiz (down = aşağıdaki içerik). */
    suspend fun scroll(direction: String): String {
        val b = screenBounds()
        val cx = b.width() / 2f
        val cy = b.height() / 2f
        val dy = b.height() * 0.28f
        val dx = b.width() * 0.35f
        val path = Path()
        when (direction) {
            "down" -> { path.moveTo(cx, cy + dy); path.lineTo(cx, cy - dy) }
            "up" -> { path.moveTo(cx, cy - dy); path.lineTo(cx, cy + dy) }
            "right" -> { path.moveTo(cx + dx, cy); path.lineTo(cx - dx, cy) }
            "left" -> { path.moveTo(cx - dx, cy); path.lineTo(cx + dx, cy) }
            else -> return "Unknown direction \"$direction\"."
        }
        return if (gesture(path, 350L)) "Scrolled $direction." else "Scroll gesture was cancelled."
    }

    private suspend fun gesture(path: Path, durationMs: Long): Boolean {
        val done = CompletableDeferred<Boolean>()
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        withContext(Dispatchers.Main) { overlay.setTouchPassthrough(true) }
        try {
            delay(60)
            val dispatched = dispatchGesture(description, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { done.complete(true) }
                override fun onCancelled(g: GestureDescription?) { done.complete(false) }
            }, null)
            return dispatched && done.await()
        } finally {
            withContext(Dispatchers.Main + NonCancellable) { overlay.setTouchPassthrough(false) }
        }
    }

    suspend fun typeText(id: Int?, text: String, submit: Boolean): String {
        var target: AccessibilityNodeInfo? = null
        if (id != null) {
            val node = nodes[id] ?: return "Unknown element id $id. Call read_screen for fresh ids."
            if (!node.refresh()) return "Element $id is no longer on screen. Call read_screen for fresh ids."
            if (node.isEditable) {
                target = node
            } else {
                tapElement(id, false)
                delay(400)
            }
        }
        target = target ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "No text field is focused. Tap a text field first."
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return "The field rejected the text."
        }
        if (submit) {
            delay(250)
            val ok = target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            return if (ok) "Typed the text and pressed enter." else
                "Typed the text, but this field has no enter action — tap the send/search button instead."
        }
        return "Typed the text."
    }

    fun pressKey(key: String): String {
        val action = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return "Unknown key \"$key\"."
        }
        return if (performGlobalAction(action)) "Pressed $key." else "Could not press $key."
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    companion object {
        const val MAX_EDGE = 1568
        private const val MAX_NODES = 350

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set
    }
}
