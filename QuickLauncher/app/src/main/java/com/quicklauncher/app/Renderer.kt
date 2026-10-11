package com.quicklauncher.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

const val ACCENT = 0xFFB3202B.toInt()
/** Every glyph / group tile is drawn on this black-theme fill (the per-shortcut colour is no longer used). */
const val TILE = 0xFF000000.toInt()

/**
 * Draws the popup grid directly on a Canvas in dp units (the caller scales the canvas by density).
 * One renderer instance serves the real overlay, the settings live preview and the ring preview.
 */
class PopupRenderer(private val ctx: Context, var invalidate: () -> Unit = {}) {
    var s = Settings(); private set
    var items: List<Item> = emptyList(); private set
    var wDp = 280f
    var hDp = 420f
    var edit = false
    var hoverId: String? = null
    var scroll = 0f
    var colsOverride: Int? = null
    var activeKeys: Set<String> = emptySet()
    var dragId: String? = null
    var dragX = 0f
    var dragY = 0f
    var overSlot = -1
    var arrowActive = 0 // -1 up, 1 down while auto scrolling
    var cardBg = true
    var cardColor: Int? = null
    var cardRad: Float? = null
    var showAddCells = true

    private var bySlot = HashMap<Int, Item>()
    private var maxSlot = -1
    private var slots = 0
    private val colors = HashMap<String, Int>()
    private val labelCache = HashMap<String, CharSequence>()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.WHITE }
    private val rect = RectF()
    private val path = Path()
    private val dash = DashPathEffect(floatArrayOf(4f, 3f), 0f)

    fun setData(settings: Settings, list: List<Item>) {
        s = settings; items = list
        labelCache.clear()
        bySlot = HashMap()
        val co = colsOverride
        if (co != null) {
            list.forEachIndexed { i, it -> bySlot[i] = it }
            maxSlot = list.size - 1
            slots = list.size
        } else {
            list.forEach { bySlot[it.slot] = it }
            maxSlot = list.maxOfOrNull { it.slot } ?: -1
            val c = cols()
            slots = max(c * max(1, s.rows), ceil((maxSlot + 2) / c.toFloat()).toInt() * c)
        }
        clampScroll()
    }

    fun cols() = max(1, colsOverride ?: s.cols)
    private fun cellW() = (wDp - 2 * s.pad - (cols() - 1) * s.cgap) / cols()
    private fun cellH() = s.icon + 20f
    private fun stride() = cellH() + s.rgap
    private fun rowsTotal() = ceil(slots / cols().toFloat()).toInt()
    fun contentH() = 2 * s.pad + rowsTotal() * cellH() + (rowsTotal() - 1) * s.rgap
    private fun filledBottom(): Float {
        val r = if (maxSlot < 0) 0 else maxSlot / cols()
        return s.pad + (r + 1) * cellH() + r * s.rgap
    }
    fun maxScroll() = if (edit) max(0f, contentH() - hDp) else max(0f, filledBottom() + s.pad - hDp)
    fun clampScroll() { scroll = scroll.coerceIn(0f, maxScroll()) }
    fun canUp() = scroll > 6f
    fun canDown() = scroll < maxScroll() - 6f && !edit

    private fun cellLeft(slot: Int) = s.pad + (slot % cols()) * (cellW() + s.cgap)
    private fun cellTop(slot: Int) = s.pad + (slot / cols()) * stride()   // content coords
    fun centerX(slot: Int) = cellLeft(slot) + cellW() / 2
    fun iconCenterY(slot: Int) = cellTop(slot) + s.icon / 2 - scroll
    fun slotOfItem(it: Item): Int = if (colsOverride != null) items.indexOf(it) else it.slot

    /** Slot under a point in popup-local dp, or -1. */
    fun slotAt(x: Float, y: Float): Int {
        val cy = y + scroll
        if (x < s.pad || cy < s.pad) return -1
        val colF = (x - s.pad) / (cellW() + s.cgap); val col = colF.toInt()
        if ((colF - col) * (cellW() + s.cgap) > cellW() || col >= cols()) return -1
        val rowF = (cy - s.pad) / stride(); val row = rowF.toInt()
        if ((rowF - row) * stride() > cellH()) return -1
        val slot = row * cols() + col
        return if (slot in 0 until slots) slot else -1
    }

    fun itemAtSlot(slot: Int): Item? = bySlot[slot]

    /** Badge radius scales with the icon so it stays tappable at small sizes / many columns. */
    private fun badgeR() = (s.icon * 0.22f).coerceIn(9f, 13f)

    /** The delete badge under (x,y) in edit mode. Generous hit area that may extend past the tile. */
    fun badgeAt(x: Float, y: Float): Item? {
        if (!edit) return null
        var best: Item? = null; var bd = max(badgeR() + 12f, 22f)
        for ((slot, it) in bySlot) {
            val bx = centerX(slot) + s.icon / 2 - 3f
            val by = cellTop(slot) - scroll + 3f
            val dd = hypot(x - bx, y - by)
            if (dd <= bd) { bd = dd; best = it }
        }
        return best
    }

    fun nearest(x: Float, y: Float, thresh: Float): Item? {
        var best: Item? = null; var bd = thresh
        for ((slot, it) in bySlot) {
            val cy = iconCenterY(slot)
            if (cy < -s.icon || cy > hDp + s.icon) continue
            val d = hypot(x - centerX(slot), y - cy)
            if (d < bd) { bd = d; best = it }
        }
        return best
    }

    fun arrowAt(x: Float, y: Float): Int {
        if (canUp() && hypot(x - wDp / 2, y - 15f) <= 26f) return -1
        if (canDown() && hypot(x - wDp / 2, y - (hDp - 15f)) <= 26f) return 1
        return 0
    }

    private fun colorOf(hex: String) = colors.getOrPut(hex) { runCatching { Color.parseColor(hex) }.getOrDefault(0xFF5B8DEF.toInt()) }

    fun draw(c: Canvas) {
        val glass = s.glass / 100f
        p.reset(); p.isAntiAlias = true
        p.color = cardColor ?: if (s.glass >= 100f) Color.BLACK else Color.argb((glass * 255).toInt(), 0, 0, 0)
        rect.set(0f, 0f, wDp, hDp)
        val cr = cardRad ?: s.popRad
        if (cardBg) c.drawRoundRect(rect, cr, cr, p)

        c.save()
        if (cardBg) { path.reset(); path.addRoundRect(rect, cr, cr, Path.Direction.CW); c.clipPath(path) }
        c.translate(0f, -scroll)
        var hovered: Item? = null; var hoverSlot = -1
        val cw = cellW(); val ch = cellH()
        for (slot in 0 until slots) {
            val top = cellTop(slot)
            if (top - scroll > hDp || top - scroll + ch < 0) continue
            val it = bySlot[slot]
            val cx = centerX(slot)
            if (it == null) {
                if (edit && showAddCells) drawEmpty(c, cx, top, slot == overSlot)
                continue
            }
            if (it.id == dragId) { drawTile(c, it, cx, top, 1f, 0.25f); drawLabel(c, it, cx, top, 0.25f); continue }
            if (it.id == hoverId) { hovered = it; hoverSlot = slot; drawLabel(c, it, cx, top, 1f); continue }
            drawTile(c, it, cx, top, 1f, 1f)
            drawLabel(c, it, cx, top, 1f)
            if (edit) { drawBadge(c, cx, top); drawPen(c, cx, top) }
        }
        if (hovered != null) drawTile(c, hovered, centerX(hoverSlot), cellTop(hoverSlot), 1.35f, 1f)
        if (edit && overSlot >= 0 && bySlot[overSlot] != null && dragId != null) {
            p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = ACCENT
            val l = cellLeft(overSlot); val t = cellTop(overSlot)
            rect.set(l, t, l + cw, t + ch); c.drawRoundRect(rect, 12f, 12f, p)
        }
        c.restore()

        if (canUp()) drawArrow(c, "\uf077", wDp / 2, 15f, arrowActive == -1)
        if (canDown()) drawArrow(c, "\uf078", wDp / 2, hDp - 15f, arrowActive == 1)

        val dId = dragId
        if (dId != null) items.firstOrNull { it.id == dId }?.let {
            val cx = dragX; val top = dragY - s.icon / 2
            drawTile(c, it, cx, top, 1.15f, 0.95f)
        }
    }

    private fun drawArrow(c: Canvas, glyph: String, cx: Float, cy: Float, active: Boolean) {
        gp.typeface = Fa.typeface(ctx, false); gp.textSize = 15f
        gp.color = if (active) ACCENT else Color.WHITE
        gp.setShadowLayer(3f, 0f, 1.5f, 0xD9000000.toInt())
        c.save(); if (active) c.scale(1.25f, 1.25f, cx, cy)
        c.drawText(glyph, cx, cy - (gp.ascent() + gp.descent()) / 2, gp)
        c.restore(); gp.clearShadowLayer(); gp.color = Color.WHITE
    }

    private fun drawEmpty(c: Canvas, cx: Float, top: Float, over: Boolean) {
        val half = s.icon / 2; val rad = s.icon * s.effRadius / 100f
        rect.set(cx - half, top, cx + half, top + s.icon)
        p.reset(); p.isAntiAlias = true
        p.color = if (over) 0x40B3202B else 0x05FFFFFF
        c.drawRoundRect(rect, rad, rad, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.pathEffect = dash
        p.color = if (over) ACCENT else 0x26FFFFFF
        c.drawRoundRect(rect, rad, rad, p)
        gp.typeface = Fa.typeface(ctx, false); gp.textSize = 16f; gp.color = if (over) ACCENT else 0x66FFFFFF
        c.drawText("\u002b", cx, top + half - (gp.ascent() + gp.descent()) / 2, gp)
        gp.color = Color.WHITE
    }

    private fun drawBadge(c: Canvas, cx: Float, top: Float) {
        val bx = cx + s.icon / 2 - 3f; val by = top + 3f; val br = badgeR()
        p.reset(); p.isAntiAlias = true
        p.color = 0xFFE5534B.toInt()
        c.drawCircle(bx, by, br, p); p.clearShadowLayer()
        gp.typeface = Fa.typeface(ctx, false); gp.textSize = br * 1.1f; gp.color = Color.WHITE
        c.drawText("\uf00d", bx, by - (gp.ascent() + gp.descent()) / 2, gp)
    }

    private fun drawPen(c: Canvas, cx: Float, top: Float) {
        val br = badgeR() * 0.85f
        val bx = cx + s.icon / 2 - 3f; val by = top + s.icon - 3f
        p.reset(); p.isAntiAlias = true; p.color = 0xFF3A3842.toInt()
        c.drawCircle(bx, by, br, p)
        gp.typeface = Fa.typeface(ctx, false); gp.textSize = br * 1.0f; gp.color = Color.WHITE
        c.drawText("\uf304", bx, by - (gp.ascent() + gp.descent()) / 2, gp)
    }

    private fun drawLabel(c: Canvas, it: Item, cx: Float, top: Float, alpha: Float) {
        if (!s.showLbl) return
        tp.textSize = s.lbl
        tp.color = Color.argb((0.9f * alpha * 255).toInt(), 246, 239, 228)
        val w = cellW()
        val key = it.id + "|" + it.n + "|" + w + "|" + s.lbl
        val txt = labelCache.getOrPut(key) { TextUtils.ellipsize(it.n, tp, w, TextUtils.TruncateAt.END) }
        val base = top + s.icon + 6f - tp.ascent()
        c.drawText(txt, 0, txt.length, cx, base, tp)
    }

    private fun drawTile(c: Canvas, it: Item, cx: Float, top: Float, scale: Float, alpha: Float) {
        val size = s.icon; val half = size / 2f; val rad = size * s.effRadius / 100f
        c.save()
        if (scale != 1f) c.scale(scale, scale, cx, top + half)
        rect.set(cx - half, top, cx + half, top + size)
        val on = it.t == "system" && it.v in activeKeys

        if (scale > 1f) {
            p.reset(); p.isAntiAlias = true; p.color = 0xFF000000.toInt(); p.setShadowLayer(10f, 0f, 5f, 0x99000000.toInt())
            c.drawRoundRect(rect, rad, rad, p); p.clearShadowLayer()
        }
        if (scale == 1f && alpha >= 1f) {
            p.reset(); p.isAntiAlias = true; p.color = TILE
            c.drawRoundRect(rect, rad, rad, p)
        }
        val isGroup = it.t == "group" && it.i == "group" && it.customImg == null
        val bmp = if (!isGroup && (it.customImg != null || (it.t == "app" && it.i == "app"))) Icons.peek(it, ctx, invalidate) else null
        p.reset(); p.isAntiAlias = true; p.alpha = (alpha * 255).toInt()
        if (bmp != null) {
            c.save(); path.reset(); path.addRoundRect(rect, rad, rad, Path.Direction.CW); c.clipPath(path)
            p.isFilterBitmap = true
            c.drawBitmap(bmp, null, rect, p)
            c.restore()
        } else {
            p.color = TILE; p.alpha = (alpha * 255).toInt()
            c.drawRoundRect(rect, rad, rad, p)
            if (isGroup) drawMini(c, it, cx - half, top, size, alpha) else drawGlyph(c, it, cx, top + half, size, alpha)
        }
        if (on) {
            p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f; p.color = ACCENT
            rect.inset(-1.25f, -1.25f); c.drawRoundRect(rect, rad + 1f, rad + 1f, p)
        }
        c.restore()
    }

    private val memberCache = HashMap<String, Item>()
    private fun memberItem(pkg: String) = memberCache.getOrPut(pkg) { Item("m:$pkg", "app", "", pkg, "app") }
    private val mini = RectF()

    /** App-group tile: the member apps as a 2x2 / 3x3 grid of mini icons, "+N" in the last cell when there are more. */
    private fun drawMini(c: Canvas, it: Item, l: Float, t: Float, size: Float, alpha: Float) {
        val pk = it.members
        val n = pk.size
        if (n == 0) return
        val g = if (n <= 4) 2 else 3
        val inset = size * 0.1f
        val gap = size * (if (g == 2) 0.08f else 0.05f)
        val cell = (size - 2 * inset - (g - 1) * gap) / g
        val cr = cell * 0.26f
        val total = g * g
        val overflow = n > total
        val shown = if (overflow) total - 1 else n
        val cells = if (overflow) total else n
        for (k in 0 until cells) {
            val x = l + inset + (k % g) * (cell + gap)
            val y = t + inset + (k / g) * (cell + gap)
            mini.set(x, y, x + cell, y + cell)
            p.reset(); p.isAntiAlias = true
            if (k < shown) {
                val bmp = Icons.peek(memberItem(pk[k]), ctx, invalidate)
                if (bmp != null) {
                    p.alpha = (alpha * 255).toInt(); p.isFilterBitmap = true
                    c.save(); path.reset(); path.addRoundRect(mini, cr, cr, Path.Direction.CW); c.clipPath(path)
                    c.drawBitmap(bmp, null, mini, p)
                    c.restore()
                } else {
                    p.color = Color.argb((alpha * 255).toInt(), 0, 0, 0)
                    c.drawRoundRect(mini, cr, cr, p)
                }
            } else {
                p.color = Color.argb((alpha * 46).toInt(), 255, 255, 255)
                c.drawRoundRect(mini, cr, cr, p)
                gp.typeface = Typeface.create("sans-serif", Typeface.BOLD); gp.textSize = cell * 0.42f
                gp.color = Color.argb((alpha * 230).toInt(), 255, 255, 255)
                c.drawText("+${n - shown}", mini.centerX(), mini.centerY() - (gp.ascent() + gp.descent()) / 2, gp)
                gp.color = Color.WHITE
            }
        }
    }

    private fun drawGlyph(c: Canvas, it: Item, cx: Float, cy: Float, size: Float, alpha: Float) {
        gp.color = Color.argb((alpha * 255).toInt(), 255, 255, 255)
        if (it.i == "letter" || (Fa.glyph(it.i) == null)) {
            gp.typeface = Typeface.create("sans-serif", Typeface.BOLD); gp.textSize = size * .46f
            val ch = (it.n.firstOrNull() ?: '?').uppercaseChar().toString()
            c.drawText(ch, cx, cy - (gp.ascent() + gp.descent()) / 2, gp)
            return
        }
        val g = Fa.glyph(it.i)!!
        gp.typeface = Fa.typeface(ctx, g.brands); gp.textSize = size * .44f
        c.drawText(g.text, cx, cy - (gp.ascent() + gp.descent()) / 2, gp)
    }
}
