package com.quicklauncher.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * App Group panel, sized like a Nova Launcher folder: a small solid-black card that wraps its apps
 * (up to 4 columns x 4 rows, then it scrolls) and floats next to the group's tile. The main popup stays
 * visible and live underneath, so the finger can slide out of the panel onto another group and open that
 * one instead. Hover mode: release over an app to launch it. Touch mode: tap an app, drag to scroll.
 *
 * Pointer coordinates handed to [pointer] / [open] are LayerView coordinates; the view converts them with its own left/top.
 */
class GroupView(ctx: Context, private val mgr: OverlayManager) : View(ctx) {
    var onBack: () -> Unit = {}
    var onLaunch: (String) -> Unit = {}
    /** False while the finger that opened the panel is still down (hover mode); true once it is a tap/scroll panel. */
    var touchMode = false
    var itemId = ""
        private set

    private class Cell(val pkg: String, val label: String, val item: Item) { var text: CharSequence = label }

    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val headerH = 34 * d
    private val pad = 10 * d

    private var title = ""
    private var cells: List<Cell> = emptyList()
    private var scroll = 0f
    private var hover = -1
    private var armed = true
    private var openX = 0f
    private var openY = 0f
    private var lastX = -1f
    private var lastY = -1f
    private var autoDir = 0
    private var tMode = 0
    private var downX = 0f
    private var downY = 0f
    private var lastTY = 0f

    // metrics, recomputed by measureFor()
    private var colsN = 3
    private var rowsN = 1
    private var ip = 48 * d
    private var cw = 70 * d
    private var chh = 74 * d
    private var gridL = 0f
    private var wantW = 0
    private var wantH = 0

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val rf = RectF()
    private val clip = Path()
    private val inv: () -> Unit = { invalidate() }

    private val auto = object : Runnable {
        override fun run() {
            if (autoDir == 0) return
            val before = scroll
            scroll += autoDir * 12 * d
            clampScroll()
            if (scroll == before) { autoDir = 0; return }
            if (armed && lastY > headerH) setHover(cellAt(lastX, lastY))
            invalidate()
            postOnAnimation(this)
        }
    }

    // ───── sizing ─────
    fun wantedW() = wantW
    fun wantedH() = wantH

    /** Works out the panel size for the current apps, limited to [maxW] x [maxH] px. */
    fun measureFor(maxW: Int, maxH: Int) {
        val s = mgr.popupSettings
        ip = min(s.icon * d, 52 * d)
        cw = ip + 22 * d
        chh = ip + (if (s.showLbl) 28 else 10) * d
        val n = max(cells.size, 1)
        var c = min(n, 4)
        while (c > 1 && c * cw + 2 * pad > maxW) c--
        colsN = c
        rowsN = ceil(n / c.toFloat()).toInt()
        val visRows = min(rowsN, 4)
        wantW = min(max((c * cw + 2 * pad).toInt(), (132 * d).toInt()), maxW)
        wantH = min((headerH + visRows * chh + pad).toInt(), maxH)
        gridL = (wantW - c * cw) / 2f
        layoutLabels()
    }

    private fun contentH() = rowsN * chh + pad
    private fun maxScroll() = max(0f, contentH() - (height - headerH))
    private fun clampScroll() { scroll = scroll.coerceIn(0f, maxScroll()) }

    private fun cellAt(x: Float, y: Float): Int {
        if (y < headerH) return -1
        val xx = x - gridL
        if (xx < 0f || xx >= colsN * cw) return -1
        val yy = y - headerH + scroll
        if (yy < 0f) return -1
        val col = (xx / cw).toInt()
        val row = (yy / chh).toInt()
        val i = row * colsN + col
        return if (col in 0 until colsN && i in cells.indices) i else -1
    }

    private fun layoutLabels() {
        tp.textSize = mgr.popupSettings.lbl * d
        val w = cw - 4 * d
        cells.forEach { it.text = TextUtils.ellipsize(it.label, tp, w, TextUtils.TruncateAt.END) }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { clampScroll() }

    // ───── lifecycle ─────
    fun open(it: Item, gesture: Boolean, px: Float, py: Float) {
        itemId = it.id
        title = it.n
        val labels = Apps.list(context).associate { a -> a.pkg to a.label }
        cells = it.members.mapNotNull { pkg ->
            labels[pkg]?.let { l -> Cell(pkg, l, Item("m:$pkg", "app", l, pkg, "app")) }   // uninstalled apps are skipped
        }
        scroll = 0f; hover = -1; autoDir = 0
        // A panel that opens under the finger must not react until the finger actually moves.
        armed = !gesture; openX = px; openY = py; lastX = -1f; lastY = -1f
        invalidate()
    }

    fun close() { removeCallbacks(auto); autoDir = 0; cells = emptyList(); itemId = "" }

    override fun onDetachedFromWindow() { removeCallbacks(auto); super.onDetachedFromWindow() }

    fun clearHover() { setAuto(0); hover = -1; invalidate() }

    /** The finger came up on the panel: from now on it behaves as a touch panel. */
    fun enterTouch() { touchMode = true }

    // ───── hover mode ─────
    fun contains(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom

    /** Pointer in LayerView coordinates; may be outside the panel (then nothing is hovered). */
    fun pointer(rx: Float, ry: Float) {
        if (!armed) {
            if (hypot(rx - openX, ry - openY) < 14 * d) return
            armed = true
        }
        val x = rx - left; val y = ry - top
        lastX = x; lastY = y
        val inside = x >= 0f && x <= width && y >= 0f && y <= height
        setHover(if (inside && y >= headerH) cellAt(x, y) else -1)
        val edge = 22 * d
        val dir = when {
            !inside -> 0
            y > headerH && y < headerH + edge && scroll > 1f -> -1
            y > height - edge && scroll < maxScroll() - 1f -> 1
            else -> 0
        }
        setAuto(dir)
        invalidate()
    }

    /** True while the finger is down over one of the panel's apps. */
    fun hoveringApp() = armed && hover >= 0 && hover < cells.size

    private fun setAuto(dir: Int) {
        if (dir == autoDir) return
        autoDir = dir
        if (dir != 0) postOnAnimation(auto)
        invalidate()
    }

    private fun setHover(cell: Int) {
        if (cell == hover) return
        hover = cell
        if (cell >= 0) mgr.haptic(true)
    }

    /** Finger lifted. True when it was over the panel (the caller keeps the panel open in touch mode). */
    fun release(): Boolean {
        setAuto(0)
        if (!armed) return true
        val inside = lastX >= 0f && lastX <= width && lastY >= 0f && lastY <= height
        if (!inside) return false
        if (hover >= 0) cells.getOrNull(hover)?.let { onLaunch(it.pkg) }
        return true
    }

    // ───── touch mode ─────
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastTY = e.y; tMode = 1
                parent?.requestDisallowInterceptTouchEvent(true)
                hover = if (e.y >= headerH) cellAt(e.x, e.y) else -1
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (tMode == 1 && hypot(e.x - downX, e.y - downY) > slop) { tMode = 2; hover = -1 }
                if (tMode == 2) { scroll -= (e.y - lastTY) * 1.6f; clampScroll(); invalidate() }
                lastTY = e.y
            }
            MotionEvent.ACTION_UP -> {
                val wasTap = tMode == 1
                tMode = 0; hover = -1
                if (wasTap) tap(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { tMode = 0; hover = -1; invalidate() }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        if (y < headerH) { onBack(); return }
        val i = cellAt(x, y)
        if (i >= 0) { mgr.haptic(true); onLaunch(cells[i].pkg) }
    }

    // ───── drawing ─────
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = mgr.popupSettings
        val rad = min(s.popRad * d, min(w, h) / 2f)     // same corner radius as the main popup
        rf.set(0f, 0f, w, h)
        bg.color = Color.BLACK                           // solid, no opacity
        c.drawRoundRect(rf, rad, rad, bg)
        c.save()
        clip.reset(); clip.addRoundRect(rf, rad, rad, Path.Direction.CW); c.clipPath(clip)
        val solid = Fa.typeface(context, false)

        c.save()
        c.clipRect(0f, headerH, w, h)
        if (cells.isEmpty()) {
            tp.textAlign = Paint.Align.CENTER; tp.textSize = 11f * d; tp.color = 0x8CC8C8C8.toInt()
            c.drawText("No installed apps", w / 2, headerH + 30 * d, tp)
        } else drawGrid(c, h, s)
        c.restore()

        if (scroll > 1f) drawArrow(c, "\uf077", w / 2, headerH + 8 * d, autoDir == -1, solid)
        if (scroll < maxScroll() - 1f) drawArrow(c, "\uf078", w / 2, h - 8 * d, autoDir == 1, solid)
        drawHeader(c, w)
        c.restore()
    }

    private fun drawGrid(c: Canvas, h: Float, s: Settings) {
        val rad = ip * s.effRadius / 100f
        tp.textAlign = Paint.Align.CENTER; tp.textSize = s.lbl * d
        for (i in cells.indices) {
            if (i == hover) continue
            val top = headerH + (i / colsN) * chh - scroll
            if (top > h || top + chh < headerH) continue
            drawCell(c, cells[i], gridL + (i % colsN) * cw + cw / 2, top, rad, false, s.showLbl)
        }
        val hv = cells.getOrNull(hover)
        if (hv != null) {
            val top = headerH + (hover / colsN) * chh - scroll
            drawCell(c, hv, gridL + (hover % colsN) * cw + cw / 2, top, rad, true, s.showLbl)
        }
    }

    private fun drawCell(c: Canvas, cell: Cell, cx: Float, top: Float, rad: Float, hov: Boolean, showLbl: Boolean) {
        c.save()
        if (hov) c.scale(1.2f, 1.2f, cx, top + ip / 2)
        rf.set(cx - ip / 2, top, cx + ip / 2, top + ip)
        val bmp = Icons.peek(cell.item, context, inv)
        p.reset(); p.isAntiAlias = true
        if (bmp != null) {
            c.save(); clip.reset(); clip.addRoundRect(rf, rad, rad, Path.Direction.CW); c.clipPath(clip)
            p.isFilterBitmap = true
            c.drawBitmap(bmp, null, rf, p)
            c.restore()
        } else {
            p.color = TILE
            c.drawRoundRect(rf, rad, rad, p)
        }
        c.restore()
        if (showLbl) {
            tp.color = if (hov) Color.WHITE else 0xE6F6EFE4.toInt()
            val t = cell.text
            c.drawText(t, 0, t.length, cx, top + ip + 5 * d - tp.ascent(), tp)
        }
    }

    private fun drawArrow(c: Canvas, glyph: String, cx: Float, cy: Float, active: Boolean, solid: Typeface) {
        gp.typeface = solid; gp.textSize = 11 * d
        gp.color = if (active) ACCENT else Color.WHITE
        c.drawText(glyph, cx, cy - (gp.ascent() + gp.descent()) / 2, gp)
    }

    /** Folder title, centred like Nova's. No divider, no buttons. */
    private fun drawHeader(c: Canvas, w: Float) {
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = 12.5f * d; tp.color = 0xFFECECEC.toInt(); tp.isFakeBoldText = true
        val t1 = TextUtils.ellipsize(title, tp, w - 2 * pad, TextUtils.TruncateAt.END)
        c.drawText(t1, 0, t1.length, w / 2, headerH / 2 - (tp.ascent() + tp.descent()) / 2 + 2 * d, tp)
        tp.isFakeBoldText = false
    }
}
