package com.carlo.gbafg

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * On-screen GBA controls. Every button can be moved and resized (edit mode), and the
 * layout is saved separately for portrait and landscape.
 */
@SuppressLint("ViewConstructor")
class ControlsView(ctx: Context, private val prefs: Prefs) : View(ctx) {

    enum class Kind { DPAD, ROUND, PILL, TOGGLE, ACTION }

    class Btn(
        val id: String, val label: String, val kind: Kind, val key: Int,
        var cx: Float, var cy: Float, var size: Float,
    ) {
        val rect = RectF()
        var pressed = false
        var on = false // toggles (fast forward)
    }

    interface Listener {
        fun onKeys(keys: Int)
        fun onFastForward(on: Boolean)
        fun onAction(id: String) // "save", "load", "menu"
    }

    var listener: Listener? = null
    var portrait = false
        private set
    private var buttons: List<Btn> = emptyList()
    private var opacity = 0.55f
    var editMode = false
        set(v) {
            field = v
            selected = null
            invalidate()
        }
    private var selected: Btn? = null
    private var keys = 0
    private var ffOn = false

    // pointer id -> button (play mode) ; pointer tracking for edit mode
    private val pointerBtn = HashMap<Int, Btn>()
    private val pointerKeys = HashMap<Int, Int>()
    private var dragId = -1
    private var dragDx = 0f
    private var dragDy = 0f
    private var pinchStart = 0f
    private var pinchSize = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val path = Path()

    // edit-mode toolbar
    private val toolbar = listOf("OK", "Default", "Size −", "Size +", "Opac −", "Opac +")
    private val toolRects = List(toolbar.size) { RectF() }

    // ------------------------------------------------------------------
    // layouts
    // ------------------------------------------------------------------
    private fun defaults(portrait: Boolean): List<Btn> = if (!portrait) listOf(
        Btn("dpad", "", Kind.DPAD, 0, 0.13f, 0.60f, 0.42f),
        Btn("a", "A", Kind.ROUND, Native.KEY_A, 0.92f, 0.55f, 0.17f),
        Btn("b", "B", Kind.ROUND, Native.KEY_B, 0.80f, 0.70f, 0.17f),
        Btn("l", "L", Kind.PILL, Native.KEY_L, 0.08f, 0.10f, 0.22f),
        Btn("r", "R", Kind.PILL, Native.KEY_R, 0.92f, 0.10f, 0.22f),
        Btn("select", "SELECT", Kind.PILL, Native.KEY_SELECT, 0.78f, 0.93f, 0.17f),
        Btn("start", "START", Kind.PILL, Native.KEY_START, 0.92f, 0.93f, 0.17f),
        Btn("ff", "▶▶", Kind.TOGGLE, 0, 0.06f, 0.30f, 0.12f),
        Btn("menu", "☰", Kind.ACTION, 0, 0.94f, 0.30f, 0.12f),
        Btn("save", "SAVE", Kind.ACTION, 0, 0.05f, 0.92f, 0.13f),
        Btn("load", "LOAD", Kind.ACTION, 0, 0.17f, 0.92f, 0.13f),
    ) else listOf(
        Btn("dpad", "", Kind.DPAD, 0, 0.24f, 0.72f, 0.40f),
        Btn("a", "A", Kind.ROUND, Native.KEY_A, 0.86f, 0.66f, 0.17f),
        Btn("b", "B", Kind.ROUND, Native.KEY_B, 0.70f, 0.75f, 0.17f),
        Btn("l", "L", Kind.PILL, Native.KEY_L, 0.12f, 0.49f, 0.20f),
        Btn("r", "R", Kind.PILL, Native.KEY_R, 0.88f, 0.49f, 0.20f),
        Btn("select", "SELECT", Kind.PILL, Native.KEY_SELECT, 0.40f, 0.93f, 0.17f),
        Btn("start", "START", Kind.PILL, Native.KEY_START, 0.60f, 0.93f, 0.17f),
        Btn("ff", "▶▶", Kind.TOGGLE, 0, 0.32f, 0.49f, 0.11f),
        Btn("save", "SAVE", Kind.ACTION, 0, 0.44f, 0.49f, 0.11f),
        Btn("load", "LOAD", Kind.ACTION, 0, 0.56f, 0.49f, 0.11f),
        Btn("menu", "☰", Kind.ACTION, 0, 0.68f, 0.49f, 0.11f),
    )

    fun setOrientation(portrait: Boolean) {
        this.portrait = portrait
        val base = defaults(portrait)
        val json = prefs.layoutJson(portrait)
        if (json != null) {
            try {
                val o = JSONObject(json)
                opacity = o.optDouble("opacity", 0.55).toFloat()
                val arr = o.getJSONArray("buttons")
                for (i in 0 until arr.length()) {
                    val b = arr.getJSONObject(i)
                    base.find { it.id == b.getString("id") }?.let {
                        it.cx = b.getDouble("cx").toFloat()
                        it.cy = b.getDouble("cy").toFloat()
                        it.size = b.getDouble("size").toFloat()
                    }
                }
            } catch (_: Exception) {
            }
        } else {
            opacity = 0.55f
        }
        base.find { it.id == "ff" }?.on = ffOn
        buttons = base
        requestLayout()
        invalidate()
    }

    fun saveLayout() {
        val arr = JSONArray()
        for (b in buttons) arr.put(JSONObject().put("id", b.id).put("cx", b.cx.toDouble())
            .put("cy", b.cy.toDouble()).put("size", b.size.toDouble()))
        prefs.setLayoutJson(portrait, JSONObject().put("opacity", opacity.toDouble()).put("buttons", arr).toString())
    }

    fun resetLayout() {
        prefs.setLayoutJson(portrait, null)
        setOrientation(portrait)
    }

    fun setFastForwardState(on: Boolean) {
        ffOn = on
        buttons.find { it.id == "ff" }?.on = on
        invalidate()
    }

    // ------------------------------------------------------------------
    // geometry
    // ------------------------------------------------------------------
    private fun unit() = min(width, height).toFloat()

    private fun layoutRect(b: Btn) {
        val u = unit()
        val w: Float
        val h: Float
        when (b.kind) {
            Kind.DPAD, Kind.ROUND, Kind.TOGGLE, Kind.ACTION -> { w = b.size * u; h = w }
            Kind.PILL -> { w = b.size * u; h = w * 0.42f }
        }
        val x = b.cx * width
        val y = b.cy * height
        b.rect.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
    }

    // ------------------------------------------------------------------
    // drawing
    // ------------------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        val a = (opacity * 255).toInt().coerceIn(20, 255)
        for (b in buttons) {
            layoutRect(b)
            val active = b.pressed || b.on
            fill.color = if (active) Color.argb(min(255, a + 70), 255, 255, 255) else Color.argb(a / 2, 255, 255, 255)
            stroke.color = Color.argb(a, 255, 255, 255)
            if (editMode && b == selected) stroke.color = Color.argb(255, 255, 210, 80)
            text.color = if (active) Color.argb(255, 40, 40, 60) else Color.argb(min(255, a + 60), 255, 255, 255)
            val r = b.rect
            when (b.kind) {
                Kind.DPAD -> drawDpad(canvas, b)
                Kind.ROUND, Kind.TOGGLE, Kind.ACTION -> {
                    canvas.drawOval(r, fill)
                    canvas.drawOval(r, stroke)
                    text.textSize = r.height() * if (b.label.length > 2) 0.24f else 0.42f
                    canvas.drawText(b.label, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
                }
                Kind.PILL -> {
                    val rr = r.height() / 2
                    canvas.drawRoundRect(r, rr, rr, fill)
                    canvas.drawRoundRect(r, rr, rr, stroke)
                    text.textSize = r.height() * 0.42f
                    canvas.drawText(b.label, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
                }
            }
        }
        if (editMode) drawToolbar(canvas)
    }

    private fun drawDpad(canvas: Canvas, b: Btn) {
        val r = b.rect
        val cx = r.centerX()
        val cy = r.centerY()
        val s = r.width() / 2
        val t = s * 0.36f
        path.reset()
        path.moveTo(cx - t, cy - s); path.lineTo(cx + t, cy - s); path.lineTo(cx + t, cy - t)
        path.lineTo(cx + s, cy - t); path.lineTo(cx + s, cy + t); path.lineTo(cx + t, cy + t)
        path.lineTo(cx + t, cy + s); path.lineTo(cx - t, cy + s); path.lineTo(cx - t, cy + t)
        path.lineTo(cx - s, cy + t); path.lineTo(cx - s, cy - t); path.lineTo(cx - t, cy - t)
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
        // highlight the pressed directions
        val hl = Paint(fill).apply { color = Color.argb(200, 255, 255, 255) }
        if (keys and Native.KEY_UP != 0) canvas.drawRect(cx - t, cy - s, cx + t, cy - t, hl)
        if (keys and Native.KEY_DOWN != 0) canvas.drawRect(cx - t, cy + t, cx + t, cy + s, hl)
        if (keys and Native.KEY_LEFT != 0) canvas.drawRect(cx - s, cy - t, cx - t, cy + t, hl)
        if (keys and Native.KEY_RIGHT != 0) canvas.drawRect(cx + t, cy - t, cx + s, cy + t, hl)
    }

    private fun drawToolbar(canvas: Canvas) {
        val u = unit()
        val bw = u * 0.17f
        val bh = u * 0.085f
        val gap = u * 0.015f
        val total = toolbar.size * bw + (toolbar.size - 1) * gap
        var x = (width - total) / 2
        val y = height * 0.30f
        text.textSize = bh * 0.42f
        for (i in toolbar.indices) {
            toolRects[i].set(x, y, x + bw, y + bh)
            fill.color = Color.argb(220, 40, 36, 70)
            canvas.drawRoundRect(toolRects[i], bh / 3, bh / 3, fill)
            stroke.color = Color.argb(255, 255, 210, 80)
            canvas.drawRoundRect(toolRects[i], bh / 3, bh / 3, stroke)
            text.color = Color.WHITE
            canvas.drawText(toolbar[i], toolRects[i].centerX(),
                toolRects[i].centerY() - (text.descent() + text.ascent()) / 2, text)
            x += bw + gap
        }
        text.textSize = bh * 0.38f
        canvas.drawText("Drag to move · pinch or Size −/+ to resize", width / 2f, y - bh * 0.4f, text)
    }

    // ------------------------------------------------------------------
    // touch
    // ------------------------------------------------------------------
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editMode) return editTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                pointerDown(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) pointerMove(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> pointerUp(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> {
                pointerBtn.clear(); pointerKeys.clear()
                buttons.forEach { it.pressed = false }
                updateKeys()
            }
        }
        return true
    }

    private fun hit(x: Float, y: Float): Btn? {
        // the closest button whose (slightly enlarged) area contains the point
        var best: Btn? = null
        var bestD = Float.MAX_VALUE
        for (b in buttons) {
            val r = b.rect
            val padX = r.width() * 0.18f
            val padY = r.height() * 0.18f
            if (x >= r.left - padX && x <= r.right + padX && y >= r.top - padY && y <= r.bottom + padY) {
                val d = hypot(x - r.centerX(), y - r.centerY())
                if (d < bestD) { bestD = d; best = b }
            }
        }
        return best
    }

    private fun dpadKeys(b: Btn, x: Float, y: Float): Int {
        val dx = x - b.rect.centerX()
        val dy = y - b.rect.centerY()
        if (hypot(dx, dy) < b.rect.width() * 0.10f) return 0
        val ang = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) // 0 = right, 90 = down
        val sector = (((ang + 360 + 22.5) % 360) / 45).toInt()
        return when (sector) {
            0 -> Native.KEY_RIGHT
            1 -> Native.KEY_RIGHT or Native.KEY_DOWN
            2 -> Native.KEY_DOWN
            3 -> Native.KEY_DOWN or Native.KEY_LEFT
            4 -> Native.KEY_LEFT
            5 -> Native.KEY_LEFT or Native.KEY_UP
            6 -> Native.KEY_UP
            else -> Native.KEY_UP or Native.KEY_RIGHT
        }
    }

    private fun pointerDown(id: Int, x: Float, y: Float) {
        val b = hit(x, y) ?: return
        when (b.kind) {
            Kind.TOGGLE -> {
                b.on = !b.on
                ffOn = b.on
                b.pressed = true
                pointerBtn[id] = b
                listener?.onFastForward(b.on)
                buzz()
            }
            Kind.ACTION -> {
                b.pressed = true
                pointerBtn[id] = b
                buzz()
            }
            else -> {
                pointerBtn[id] = b
                pointerKeys[id] = keyFor(b, x, y)
                b.pressed = b.kind != Kind.DPAD
                updateKeys()
            }
        }
        invalidate()
    }

    private fun keyFor(b: Btn, x: Float, y: Float) = if (b.kind == Kind.DPAD) dpadKeys(b, x, y) else b.key

    private fun pointerMove(id: Int, x: Float, y: Float) {
        val cur = pointerBtn[id] ?: return
        if (cur.kind == Kind.TOGGLE || cur.kind == Kind.ACTION) return
        if (cur.kind == Kind.DPAD) {
            // the d-pad keeps the finger even when it slides a little outside
            pointerKeys[id] = dpadKeys(cur, x, y)
            updateKeys()
            return
        }
        // slide between face buttons
        val b = hit(x, y)
        if (b != null && b != cur && (b.kind == Kind.ROUND || b.kind == Kind.PILL)) {
            cur.pressed = false
            b.pressed = true
            pointerBtn[id] = b
            pointerKeys[id] = b.key
            updateKeys()
            invalidate()
        }
    }

    private fun pointerUp(id: Int) {
        val b = pointerBtn.remove(id)
        pointerKeys.remove(id)
        if (b != null) {
            if (pointerBtn.values.none { it == b }) b.pressed = false
            if (b.kind == Kind.ACTION) listener?.onAction(b.id)
        }
        updateKeys()
        invalidate()
    }

    private fun updateKeys() {
        var k = 0
        for (v in pointerKeys.values) k = k or v
        if (k != keys) {
            val newPress = k and keys.inv()
            keys = k
            listener?.onKeys(k)
            if (newPress != 0) buzz()
            invalidate()
        }
    }

    private fun buzz() {
        if (prefs.haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    // ------------------------------------------------------------------
    // edit mode
    // ------------------------------------------------------------------
    private fun editTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = e.x
                val y = e.y
                for (i in toolRects.indices) if (toolRects[i].contains(x, y)) {
                    toolbarAction(i)
                    return true
                }
                val b = hit(x, y)
                selected = b
                if (b != null) {
                    dragId = e.getPointerId(0)
                    dragDx = b.cx * width - x
                    dragDy = b.cy * height - y
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2 && selected != null) {
                    pinchStart = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    pinchSize = selected!!.size
                    dragId = -1
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val b = selected ?: return true
                if (e.pointerCount >= 2 && pinchStart > 0) {
                    val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    b.size = (pinchSize * d / pinchStart).coerceIn(0.06f, 0.7f)
                } else if (dragId >= 0) {
                    val i = e.findPointerIndex(dragId)
                    if (i >= 0) {
                        b.cx = ((e.getX(i) + dragDx) / width).coerceIn(0f, 1f)
                        b.cy = ((e.getY(i) + dragDy) / height).coerceIn(0f, 1f)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragId = -1
                pinchStart = 0f
            }
            MotionEvent.ACTION_POINTER_UP -> pinchStart = 0f
        }
        return true
    }

    var onEditDone: (() -> Unit)? = null

    private fun toolbarAction(i: Int) {
        when (i) {
            0 -> { saveLayout(); editMode = false; onEditDone?.invoke() }
            1 -> { resetLayout(); editMode = true }
            2 -> selected?.let { it.size = max(0.06f, it.size * 0.9f) }
            3 -> selected?.let { it.size = min(0.7f, it.size * 1.1f) }
            4 -> opacity = max(0.1f, opacity - 0.1f)
            5 -> opacity = min(1f, opacity + 0.1f)
        }
        invalidate()
    }
}
