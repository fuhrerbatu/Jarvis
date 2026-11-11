package dev.jarvis.assistant

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * Diğer uygulamaların üzerinde çizilen Jarvis arayüzü: yüzen düğme, durum çubuğu
 * ve sonuç kartı. Erişilebilirlik katmanı kullandığı için ayrıca "üstte göster"
 * izni gerekmez.
 */
class Overlay(private val service: JarvisAccessibilityService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    private val accent = Color.parseColor("#37D6FF")
    private val panelBg = Color.parseColor("#F00A1018")

    private var bubble: View? = null
    private var status: View? = null
    private var statusText: TextView? = null
    private var card: View? = null

    private fun dp(v: Int) = (v * density).toInt()

    private fun params(width: Int, height: Int, gravity: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).also { it.gravity = gravity }

    private fun panel(radius: Int) = GradientDrawable().apply {
        setColor(panelBg)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), accent)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    // ---------------------------------------------------------------- düğme

    fun refreshBubble() = onMain {
        val wanted = Prefs(service).bubbleEnabled
        if (wanted && bubble == null) addBubble()
        if (!wanted) bubble?.let { wm.removeView(it); bubble = null }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val view = TextView(service).apply {
            text = "J"
            gravity = Gravity.CENTER
            setTextColor(accent)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(panelBg)
                setStroke(dp(2), accent)
            }
        }
        val lp = params(dp(52), dp(52), Gravity.TOP or Gravity.END)
        lp.x = dp(6)
        lp.y = service.resources.displayMetrics.heightPixels / 2
        var downY = 0f
        var startY = 0
        var dragged = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = lp.y; dragged = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (abs(dy) > dp(8)) dragged = true
                    if (dragged) { lp.y = startY + dy.toInt(); wm.updateViewLayout(view, lp) }
                }
                MotionEvent.ACTION_UP -> if (!dragged) JarvisController.onBubbleTapped(service)
            }
            true
        }
        wm.addView(view, lp)
        bubble = view
    }

    // ---------------------------------------------------------------- durum

    /** Üstte küçük bir durum çubuğu gösterir ("Dinliyorum…", "WhatsApp açılıyor…"). */
    fun showStatus(text: String, cancellable: Boolean = true) = onMain {
        if (status == null) {
            val label = TextView(service).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 2
            }
            val stop = TextView(service).apply {
                this.text = "✕"
                setTextColor(accent)
                textSize = 18f
                setPadding(dp(14), 0, dp(2), 0)
                setOnClickListener { JarvisController.cancel() }
            }
            val row = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = panel(22)
                setPadding(dp(16), dp(9), dp(14), dp(9))
                addView(TextView(service).apply {
                    this.text = "◉ "
                    setTextColor(accent)
                    textSize = 14f
                })
                addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(stop)
            }
            val lp = params(dp(300), WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            lp.y = dp(44)
            wm.addView(row, lp)
            status = row
            statusText = label
            row.tag = stop
        }
        statusText?.text = text
        (status?.tag as? View)?.visibility = if (cancellable) View.VISIBLE else View.GONE
    }

    fun hideStatus() = onMain {
        status?.let { wm.removeView(it) }
        status = null
        statusText = null
    }

    // ----------------------------------------------------------------- kart

    /** Ekranın altında, kaydırılabilir ve bağlantıları tıklanabilir sonuç kartı. */
    fun showCard(body: String) = onMain {
        hideCard()
        val title = TextView(service).apply {
            text = "J.A.R.V.I.S."
            setTextColor(accent)
            textSize = 13f
            letterSpacing = 0.25f
            typeface = Typeface.DEFAULT_BOLD
        }
        val close = TextView(service).apply {
            text = "✕"
            setTextColor(accent)
            textSize = 20f
            setPadding(dp(16), 0, 0, 0)
            setOnClickListener { hideCard() }
        }
        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(close)
        }
        val text = TextView(service).apply {
            setTextColor(Color.WHITE)
            setLinkTextColor(accent)
            textSize = 15f
            setLineSpacing(0f, 1.15f)
            this.text = render(body)
            movementMethod = LinkMovementMethod.getInstance()
        }
        val maxHeight = (service.resources.displayMetrics.heightPixels * 0.5f).toInt()
        val scroll = object : ScrollView(service) {
            override fun onMeasure(w: Int, h: Int) =
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }.apply { addView(text) }
        val column = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = panel(18)
            setPadding(dp(18), dp(14), dp(18), dp(16))
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        val root = FrameLayout(service).apply {
            setPadding(dp(10), 0, dp(10), dp(28))
            addView(column)
        }
        wm.addView(root, params(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        card = root
    }

    fun hideCard() = onMain {
        card?.let { wm.removeView(it) }
        card = null
    }

    /** Ekran görüntüsü alınırken Jarvis'in kendi katmanlarını gizler. */
    fun setHidden(hidden: Boolean) {
        val v = if (hidden) View.INVISIBLE else View.VISIBLE
        listOfNotNull(bubble, status, card).forEach { it.visibility = v }
    }

    /**
     * Jarvis bir dokunma hareketi gönderirken kendi katmanlarının dokunuşu yutmasını engeller
     * (örneğin yüzen düğmenin altındaki "gönder" tuşu).
     */
    fun setTouchPassthrough(enabled: Boolean) {
        for (view in listOfNotNull(bubble, status, card)) {
            val lp = view.layoutParams as? WindowManager.LayoutParams ?: continue
            val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            lp.flags = if (enabled) lp.flags or flag else lp.flags and flag.inv()
            runCatching { wm.updateViewLayout(view, lp) }
        }
    }

    fun destroy() = onMain {
        listOfNotNull(bubble, status, card).forEach { runCatching { wm.removeView(it) } }
        bubble = null; status = null; card = null
    }

    // ------------------------------------------------------- metin biçimleme

    /** Modelin yanıtındaki basit Markdown'u (kalın, başlık, bağlantı) biçimlendirir. */
    private fun render(source: String): CharSequence {
        val out = SpannableStringBuilder()
        val token = Regex("""\[([^\]]+)]\((https?://[^\s)]+)\)|(https?://[^\s<>()\]]+)|\*\*([^*]+)\*\*""")
        source.lines().forEachIndexed { i, raw ->
            if (i > 0) out.append('\n')
            val heading = raw.trimStart().startsWith("#")
            val line = if (heading) raw.trimStart().trimStart('#').trim() else raw
            val lineStart = out.length
            var pos = 0
            for (m in token.findAll(line)) {
                out.append(line, pos, m.range.first)
                val start = out.length
                when {
                    m.groupValues[2].isNotEmpty() -> {
                        out.append(m.groupValues[1]); link(out, start, m.groupValues[2])
                    }
                    m.groupValues[3].isNotEmpty() -> {
                        val url = m.groupValues[3].trimEnd('.', ',', ';', ':')
                        out.append(url); link(out, start, url)
                        out.append(m.groupValues[3].substring(url.length))
                    }
                    else -> {
                        out.append(m.groupValues[4])
                        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                pos = m.range.last + 1
            }
            out.append(line, pos, line.length)
            if (heading) out.setSpan(StyleSpan(Typeface.BOLD), lineStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    private fun link(out: SpannableStringBuilder, start: Int, url: String) {
        out.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                runCatching { service.openUrl(url) }
            }
        }, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
