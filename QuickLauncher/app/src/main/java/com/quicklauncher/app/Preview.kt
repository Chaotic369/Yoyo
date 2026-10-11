package com.quicklauncher.app

import android.annotation.SuppressLint
import android.content.Context
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Loads the current home-screen wallpaper (downscaled) for the live preview. Needs file access on most Android versions. */
object Wallpaper {
    @Volatile var bitmap: Bitmap? = null
        private set
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)

    fun load(ctx: Context, onReady: () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        exec.execute {
            val bmp = try { decode(app) } catch (t: Throwable) { null }
            if (bmp != null) bitmap = bmp
            busy.set(false)
            main.post(onReady)
        }
    }

    private fun decode(ctx: Context): Bitmap? {
        val wm = WallpaperManager.getInstance(ctx)
        val target = 720
        // 1) raw wallpaper file, decoded sampled so it never loads at full size
        runCatching {
            var w = 0; var h = 0
            wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)?.use { pfd ->
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, o)
                w = o.outWidth; h = o.outHeight
            }
            if (w > 0 && h > 0) {
                var sample = 1
                while (min(w, h) / (sample * 2) >= target) sample *= 2
                wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)?.use { pfd ->
                    val o = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, o)?.let { return it }
                }
            }
        }
        // 2) drawable fallback (live / built-in wallpapers)
        val dr = wm.drawable ?: return null
        val iw = dr.intrinsicWidth.coerceAtLeast(1); val ih = dr.intrinsicHeight.coerceAtLeast(1)
        val sc = min(1f, target.toFloat() / min(iw, ih))
        val bw = (iw * sc).toInt().coerceAtLeast(1); val bh = (ih * sc).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        dr.setBounds(0, 0, bw, bh); dr.draw(Canvas(out))
        return out
    }
}

/** Live preview in the settings screen. Uses the very same PopupRenderer as the real overlay. */
class PreviewView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val main = PopupRenderer(ctx) { invalidate() }
    private val ring = PopupRenderer(ctx) { invalidate() }.also { it.colsOverride = 2 }
    private var st = AppState()
    private var ringMode = false
    private var px = Float.NaN; private var py = Float.NaN
    private var cardL = 0f; private var cardT = 0f; private var cardScale = 1f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * d; color = 0x80FFFFFF.toInt(); isFakeBoldText = true }
    private val rf = RectF()
    private val path = Path()
    private val wpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Wallpaper.load(context) { invalidate() }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // coming back from the permission screen: pick up the wallpaper (or a changed one)
        if (hasWindowFocus) Wallpaper.load(context) { invalidate() }
    }

    fun bind(s: AppState, ringMode: Boolean) { st = s; this.ringMode = ringMode; invalidate() }

    override fun onDraw(c: Canvas) {
        val s = st.settings
        rf.set(0f, 0f, width.toFloat(), height.toFloat())
        p.reset(); p.isAntiAlias = true; p.color = 0xFF080808.toInt()
        c.drawRoundRect(rf, 20 * d, 20 * d, p)
        if (ringMode) drawRing(c, s) else drawPhone(c, s)
    }

    private fun drawPhone(c: Canvas, s: Settings) {
        val m = resources.displayMetrics
        val sw = m.widthPixels / d; val sh = m.heightPixels / d
        val phoneH = height * 0.9f; val phoneW = phoneH * sw / sh
        val scale = phoneH / sh
        val l = (width - phoneW) / 2f; val t = (height - phoneH) / 2f + 6 * d
        rf.set(l, t, l + phoneW, t + phoneH)
        p.reset(); p.isAntiAlias = true; p.color = 0xFF000000.toInt(); c.drawRoundRect(rf, 24 * d, 24 * d, p)
        c.save()
        path.reset(); path.addRoundRect(rf, 24 * d, 24 * d, Path.Direction.CW); c.clipPath(path)
        val wp = Wallpaper.bitmap
        if (wp != null) {
            // centre-crop the home wallpaper to the phone's aspect ratio
            val ar = phoneW / phoneH; val bar = wp.width.toFloat() / wp.height
            if (bar > ar) { val cw = (wp.height * ar).toInt(); val x = (wp.width - cw) / 2; src.set(x, 0, x + cw, wp.height) }
            else { val ch = (wp.width / ar).toInt(); val y = (wp.height - ch) / 2; src.set(0, y, wp.width, y + ch) }
            c.drawBitmap(wp, src, rf, wpPaint)
        }
        c.save()
        c.translate(l, t); c.scale(scale, scale)
        val fullTrig = s.trigH >= sh - 54f
        val th = if (fullTrig) sh else s.trigH
        val trigTop = if (fullTrig) 0f else (sh * s.trigY / 100f - th / 2f).coerceIn(36f, max(36f, sh - th - 18f))
        val landscapeHide = s.disableInLandscape && m.widthPixels > m.heightPixels
        if (s.triggerEnabled && !landscapeHide) {
            p.reset(); p.isAntiAlias = true
            p.color = android.graphics.Color.argb(((if (s.hideTrigger) 0f else s.trigOpacity / 100f) * 255).toInt().coerceIn(0, 255), 255, 255, 255)
            val tw = max(s.trigW, 3f)
            if (s.isLeft) c.drawRoundRect(-6f, trigTop, tw, trigTop + th, 4f, 4f, p)
            else c.drawRoundRect(sw - tw, trigTop, sw + 6f, trigTop + th, 4f, 4f, p)
        }
        val ps = s.forPopup()
        val pw = if (s.immersive) sw else s.w.coerceIn(180f, max(180f, sw - 12f)).coerceAtMost(sw - 12f)
        val ph = if (s.immersive) sh - 36f - 12f else s.h.coerceIn(180f, max(180f, sh - 70f))
        val popTop = if (s.immersive) 36f else (trigTop + th / 2 - ph / 2).coerceIn(36f, max(36f, sh - ph - 12f))
        main.colsOverride = null
        main.wDp = pw; main.hDp = ph; main.edit = false; main.hoverId = null
        main.cardBg = !s.immersive
        main.setData(ps, st.items)
        if (s.immersive) {
            p.reset(); p.color = android.graphics.Color.argb((ps.glass / 100f * 255).toInt(), 0, 0, 0)
            c.drawRect(0f, 0f, sw, sh, p)
        }
        c.translate(if (s.immersive) 0f else if (s.isLeft) 12f else sw - pw - 12f, popTop)
        main.draw(c)
        c.restore()   // content transform
        c.restore()   // phone clip
        rf.set(l, t, l + phoneW, t + phoneH)
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * d; p.color = 0x2EFFFFFF
        c.drawRoundRect(rf, 24 * d, 24 * d, p)
        if (wp == null) {
            tag.textAlign = Paint.Align.CENTER
            c.drawText("Wallpaper needs file access (see Overview)", width / 2f, t + phoneH - 8 * d, tag)
            tag.textAlign = Paint.Align.LEFT
        }
        drawTag(c, "LIVE PREVIEW · ${(scale * 100).roundToInt().coerceAtLeast(1)}%")
    }

    private fun drawTag(c: Canvas, text: String) {
        tag.textAlign = Paint.Align.LEFT; tag.textSize = 10f * d
        val tw = tag.measureText(text)
        rf.set(12 * d, 8 * d, 12 * d + tw + 16 * d, 8 * d + 20 * d)
        p.reset(); p.isAntiAlias = true; p.color = 0x66000000
        c.drawRoundRect(rf, 6 * d, 6 * d, p)
        c.drawText(text, rf.left + 8 * d, rf.centerY() - (tag.ascent() + tag.descent()) / 2, tag)
    }

    private fun drawRing(c: Canvas, s: Settings) {
        val sample = st.items.sortedBy { it.slot }.take(4)
        val cols = max(1, s.cols)
        val colW = max(30f, (s.w - 2 * s.pad - (cols - 1) * s.cgap) / cols)
        val cw = colW * 2 + s.cgap + 2 * s.pad
        val rows = max(1, (sample.size + 1) / 2)
        val ch = 2 * s.pad + rows * (s.icon + 20f) + (rows - 1) * s.rgap
        cardScale = min(d, (height - 26 * d) / ch)
        cardL = (width - cw * cardScale) / 2f; cardT = (height - ch * cardScale) / 2f + 8 * d
        ring.wDp = cw; ring.hDp = ch; ring.edit = false
        ring.setData(s, sample)
        c.save(); c.translate(cardL, cardT); c.scale(cardScale, cardScale)
        if (px.isNaN() && sample.isNotEmpty()) { px = ring.centerX(0); py = ring.iconCenterY(0) }
        ring.hoverId = ring.nearest(px, py, s.icon / 2 + 16f)?.id
        ring.draw(c)
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE
        p.strokeWidth = s.ringThick; p.color = android.graphics.Color.argb((s.ringOpacity / 100f * 255).toInt(), 255, 255, 255)
        c.drawCircle(px, py, s.ringSize / 2f, p)
        c.restore()
        drawTag(c, "SELECTION RING PREVIEW")
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!ringMode) return false
        px = (e.x - cardL) / cardScale; py = (e.y - cardT) / cardScale
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        invalidate(); return true
    }
}

/** Single tile rendered with the popup renderer (used in lists and the editor). */
class TileView(ctx: Context, private val sizeDp: Int) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val r = PopupRenderer(ctx) { invalidate() }.also { it.colsOverride = 1; it.cardBg = false }
    var item: Item? = null
    override fun onDraw(c: Canvas) {
        val i = item ?: return
        c.scale(d, d)
        r.wDp = sizeDp.toFloat(); r.hDp = sizeDp.toFloat()
        r.setData(Settings(icon = sizeDp.toFloat(), showLbl = false, pad = 0f, cols = 1), listOf(i))
        r.draw(c)
    }
}
