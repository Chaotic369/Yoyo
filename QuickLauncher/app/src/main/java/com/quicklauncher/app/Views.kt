package com.quicklauncher.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The popup grid as a real View (touch mode: scroll, tap, long-press drag-to-reorder). */
class PopupCardView(ctx: Context) : View(ctx) {
    interface Listener {
        fun onTap(item: Item)
        fun onDelete(item: Item)
        fun onAdd(slot: Int)
        fun onMoved(id: String, slot: Int)
        fun onContext(item: Item, x: Float, y: Float) {}
        fun onEmptyTap() {}
    }

    val r = PopupRenderer(ctx) { invalidate() }
    var listener: Listener? = null
    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var mode = 0 // 0 idle, 1 pending, 2 scrolling, 3 dragging
    private var downX = 0f; private var downY = 0f; private var lastY = 0f
    private var downSlot = -1; private var badge = false; private var badgeItem: Item? = null
    private val longPress = Runnable {
        if (mode != 1) return@Runnable
        val it = r.itemAtSlot(downSlot) ?: return@Runnable
        if (badge) return@Runnable
        mode = 3; r.dragId = it.id
        r.dragX = downX / d; r.dragY = downY / d
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        if (!r.edit) postDelayed(ctxPress, 270)
        invalidate()
    }

    private val ctxPress = Runnable {
        if (mode != 3 || r.edit) return@Runnable
        val it = r.dragId?.let { id -> r.items.firstOrNull { x -> x.id == id } } ?: return@Runnable
        if (hypot(r.dragX * d - downX, r.dragY * d - downY) > slop * 2) return@Runnable
        r.dragId = null; r.overSlot = -1; mode = 4
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
        listener?.onContext(it, downX / d, downY / d)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        r.wDp = w / d; r.hDp = h / d
        r.setData(r.s, r.items)
    }

    override fun onDraw(c: Canvas) { c.save(); c.scale(d, d); r.draw(c); c.restore() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastY = e.y
                badgeItem = r.badgeAt(e.x / d, e.y / d)
                badge = badgeItem != null
                downSlot = if (badge) -1 else r.slotAt(e.x / d, e.y / d)
                mode = 1
                if (downSlot >= 0 && r.itemAtSlot(downSlot) != null && !badge)
                    postDelayed(longPress, if (r.edit) 200L else 280L)
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    3 -> {
                        r.dragX = e.x / d; r.dragY = e.y / d
                        r.overSlot = r.slotAt(r.dragX, r.dragY)
                        if (r.dragY < 36f) r.scroll -= 18f else if (r.dragY > r.hDp - 36f) r.scroll += 18f
                        r.clampScroll(); invalidate()
                    }
                    1 -> if (hypot(e.x - downX, e.y - downY) > slop) {
                        removeCallbacks(longPress); mode = 2
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    2 -> {
                        r.scroll -= (e.y - lastY) / d * 2.2f; r.clampScroll(); invalidate()
                    }
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress); removeCallbacks(ctxPress)
                when (mode) {
                    3 -> {
                        val id = r.dragId; val slot = r.overSlot
                        r.dragId = null; r.overSlot = -1; invalidate()
                        if (id != null && slot >= 0) listener?.onMoved(id, slot)
                    }
                    1 -> {
                        val it = if (downSlot >= 0) r.itemAtSlot(downSlot) else null
                        when {
                            badgeItem != null -> listener?.onDelete(badgeItem!!)
                            it != null -> listener?.onTap(it)
                            downSlot >= 0 && r.edit -> listener?.onAdd(downSlot)
                            else -> listener?.onEmptyTap()
                        }
                    }
                }
                mode = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress); removeCallbacks(ctxPress)
                r.dragId = null; r.overSlot = -1; mode = 0; invalidate()
            }
        }
        return true
    }
}

/** Small rounded control that floats above the popup (settings / edit / add). */
class RoundBtn(ctx: Context, private val glyph: String) : View(ctx) {
    var glyphText = glyph
        set(v) { field = v; invalidate() }
    var on = false
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Fa.typeface(ctx, false) }
    private val rf = RectF()

    fun setHover(h: Boolean) { val s = if (h) 1.3f else 1f; if (scaleX != s) { scaleX = s; scaleY = s } }

    override fun onDraw(c: Canvas) {
        rf.set(1f, 1f, width - 1f, height - 1f)
        p.style = Paint.Style.FILL; p.color = 0xF5121212.toInt()
        c.drawRoundRect(rf, 8 * d, 8 * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = if (on) 1.5f * d else d
        p.color = if (on) ACCENT else 0x14FFFFFF
        c.drawRoundRect(rf, 8 * d, 8 * d, p)
        gp.textSize = 12f * d; gp.color = if (on) ACCENT else 0xFFECECEC.toInt()
        c.drawText(glyphText, width / 2f, height / 2f - (gp.ascent() + gp.descent()) / 2, gp)
    }
}

/** Accent corner (resize) or bottom pill (move) shown in edit mode. */
class HandleView(ctx: Context, private val corner: Boolean) : View(ctx) {
    var radiusDp = 26f
    var leftEdge = false
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; strokeCap = Paint.Cap.ROUND }
    private val rf = RectF()
    override fun onDraw(c: Canvas) {
        if (corner) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f * d
            val r = max(radiusDp, 6f) * d; val inset = 1.5f * d; val sz = height.toFloat()
            val cx = if (leftEdge) sz - inset - r else inset + r
            val cy = sz - inset - r
            rf.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(rf, if (leftEdge) 0f else 90f, 90f, false, p)
        } else {
            p.style = Paint.Style.FILL
            val w = 32f * d; val h = 4.5f * d
            rf.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            c.drawRoundRect(rf, h / 2, h / 2, p)
        }
    }
}

/** Vertical strip of folder nodes beside the popup: slide along it to jump straight into a folder's tree. */
class CornerSliderView(ctx: Context) : View(ctx) {
    var folders: List<Item> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
    var hover = -1
        set(v) { if (field != v) { field = v; invalidate() } }
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Fa.typeface(ctx, false) }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val rf = RectF()
    private val titleH = 16 * d
    private val node = 28 * d
    private val gap = 6 * d
    var onPick: (Item) -> Unit = {}

    fun wantedW() = (42 * d).toInt()
    fun wantedH() = (titleH + 12 * d + folders.size * node + (folders.size - 1).coerceAtLeast(0) * gap).toInt()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(wantedW(), wantedH())

    fun nodeCenterY(i: Int) = 6 * d + titleH + 4 * d + i * (node + gap) + node / 2

    fun nodeAt(y: Float): Int {
        var best = -1; var bd = Float.MAX_VALUE
        for (i in folders.indices) {
            val cy = nodeCenterY(i)
            if (y >= cy - node / 2 - 6 * d && y <= cy + node / 2 + 6 * d) {
                val dd = kotlin.math.abs(y - cy)
                if (dd < bd) { bd = dd; best = i }
            }
        }
        return best
    }

    override fun onDraw(c: Canvas) {
        rf.set(0.5f, 0.5f, width - 0.5f, height - 0.5f)
        p.style = Paint.Style.FILL; p.color = 0xEB121216.toInt()
        c.drawRoundRect(rf, 24 * d, 24 * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = 0x24FFFFFF
        c.drawRoundRect(rf, 24 * d, 24 * d, p)
        tp.textSize = 9f * d; tp.color = 0x8CC8C8C8.toInt()
        c.drawText("FOLDERS", width / 2f, 6 * d + titleH - 5 * d, tp)
        for (i in folders.indices) {
            val cy = nodeCenterY(i); val cx = width / 2f
            val h = i == hover
            val sc = if (h) 1.35f else 1f
            c.save(); c.scale(sc, sc, cx, cy)
            rf.set(cx - node / 2, cy - node / 2, cx + node / 2, cy + node / 2)
            p.style = Paint.Style.FILL; p.color = if (h) 0x33FFFFFF else 0x0FFFFFFF
            c.drawRoundRect(rf, 10 * d, 10 * d, p)
            gp.textSize = 12f * d; gp.color = 0xFFECECEC.toInt()
            c.drawText("\uf07b", cx, cy - (gp.ascent() + gp.descent()) / 2, gp)
            c.restore()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { parent?.requestDisallowInterceptTouchEvent(true); hover = nodeAt(e.y) }
            MotionEvent.ACTION_UP -> { val i = hover; hover = -1; folders.getOrNull(i)?.let(onPick) }
            MotionEvent.ACTION_CANCEL -> hover = -1
        }
        return true
    }
}

/**
 * Full-screen overlay content. Lays out the popup card, header buttons, edit handles and the
 * finger ring in raw screen coordinates so gestures from the trigger window map 1:1.
 */
class LayerView(ctx: Context, private val mgr: OverlayManager) : FrameLayout(ctx) {
    val card = PopupCardView(ctx)
    val btnSet = RoundBtn(ctx, "\uf013")
    val btnEdit = RoundBtn(ctx, "\uf304")
    val btnAdd = RoundBtn(ctx, "\u002b")
    private val rz = HandleView(ctx, true)
    private val bar = HandleView(ctx, false)
    val tree = TreeView(ctx, mgr)
    val group = GroupView(ctx, mgr)
    private val cfs = CornerSliderView(ctx)
    private var fixedCount = 0
    private val d = resources.displayMetrics.density
    private var popX = 0; private var popY = 0; private var popW = 0; private var popH = 0
    private var userTop: Int? = null
    private var groupAx = 0f; private var groupAy = 0f; private var groupHalf = 0f
    private var ringOn = false; private var ringX = 0f; private var ringY = 0f
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val loc = IntArray(2)
    private var hoverBtn: RoundBtn? = null
    private var autoDir = 0
    private var edit = false
    private var lastPx = 0f; private var lastPy = 0f
    private val openFolderDwell = Runnable {
        val h = hoverItem()
        if (h != null && h.t == "folder") openTree(h, true)
        else if (h != null && h.t == "group") openGroup(h, true)
    }
    private var trigHandle = 0      // 1 top cap, 2 bottom cap, 3 width tab
    private var thY = 0f; private var thX = 0f; private var thH = 0f; private var thW = 0f
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    var persistent = false
    private var trigDrag = false; private var trigOff = 0f
    private val dp = { v: Float -> (v * d).toInt() }
    private val autoScroll = object : Runnable {
        override fun run() {
            if (autoDir == 0) return
            val r = card.r
            r.scroll += autoDir * 20f; r.clampScroll()
            if ((autoDir > 0 && !r.canDown()) || (autoDir < 0 && !r.canUp())) { setAuto(0); return }
            card.invalidate(); postOnAnimation(this)
        }
    }

    init {
        clipChildren = false
        setWillNotDraw(false)
        addView(card); addView(btnSet); addView(btnEdit); addView(btnAdd); addView(rz); addView(bar)
        addView(cfs)
        addView(tree)   // first overlay child: sized exactly like the card by the overlay-children loops below
        addView(group)  // second overlay child (same sizing)
        fixedCount = 7
        btnAdd.visibility = GONE; rz.visibility = GONE; bar.visibility = GONE; tree.visibility = GONE; cfs.visibility = GONE
        group.visibility = GONE
        group.onBack = { closeGroup() }
        group.onLaunch = { m -> mgr.launchMember(m, group.touchMode) }
        cfs.onPick = { f -> mgr.haptic(false); openTree(f, false) }
        tree.onBack = { closeTree() }
        tree.onOpenFile = { folder, entry -> mgr.openTreeFile(folder, entry) }
        tree.onOpenFolder = { folder, key -> mgr.openTreeFolder(folder, key) }
        tree.onNeedAccess = { mgr.grantFileAccess() }
        tree.onSearchState = { on -> if (on) showSearchBox() else hideSearchBox() }
        btnSet.setOnClickListener { mgr.openSettings() }
        btnEdit.setOnClickListener { mgr.toggleEdit() }
        btnAdd.setOnClickListener { mgr.openEditor(null, Store.freeSlot()) }
        card.listener = object : PopupCardView.Listener {
            override fun onTap(item: Item) = if (edit) mgr.openEditor(item, item.slot) else if (item.t == "folder") { closeGroup(); openTree(item, false) } else if (item.t == "group") { if (groupOpen() && group.itemId == item.id) closeGroup() else openGroup(item, false) } else mgr.launch(item, true)
            override fun onDelete(item: Item) = showConfirm(item.n) { Store.delete(item.id) }
            override fun onAdd(slot: Int) = mgr.openEditor(null, slot)
            override fun onContext(item: Item, x: Float, y: Float) = showContext(item, x, y)
            override fun onMoved(id: String, slot: Int) = Store.moveToSlot(id, slot)
            override fun onEmptyTap() { if (groupOpen()) closeGroup() }
        }
        installDrags()
    }

    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun imm() = card.r.s.immersive
    private fun insetTop(): Int {
        val ins = rootWindowInsets
        val v = if (ins == null) 0 else if (android.os.Build.VERSION.SDK_INT >= 30)
            ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()).top
        else @Suppress("DEPRECATION") ins.systemWindowInsetTop
        return max(v, dp(24f))
    }
    private fun insetBottom(): Int {
        val ins = rootWindowInsets
        val v = if (ins == null) 0 else if (android.os.Build.VERSION.SDK_INT >= 30)
            ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()).bottom
        else @Suppress("DEPRECATION") ins.systemWindowInsetBottom
        return max(v, dp(12f))
    }

    /** Full-screen backdrop for immersive mode. */
    override fun onDraw(c: Canvas) {
        if (!imm()) return
        scrim.color = Color.argb((card.r.s.glass / 100f * 255).toInt().coerceIn(0, 255), 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); requestApplyInsets() }
    override fun onApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets { requestLayout(); return super.onApplyWindowInsets(insets) }

    fun setData(s: Settings, items: List<Item>, active: Set<String>) {
        val eff = s.forPopup()
        card.r.activeKeys = active
        card.r.cardBg = !eff.immersive
        card.r.setData(eff, items)
        rz.radiusDp = s.popRad; rz.leftEdge = s.isLeft
        cfs.folders = items.filter { it.t == "folder" }
        updateSlider()
        requestLayout(); card.invalidate(); invalidate()
    }

    // ───── folder search box (the layer turns focusable only while it is shown) ─────
    private var searchBox: android.widget.EditText? = null

    private fun showSearchBox() {
        val et = searchBox ?: android.widget.EditText(context).apply {
            setSingleLine(true); textSize = 14f; setTextColor(Color.WHITE); setHintTextColor(0x80FFFFFF.toInt())
            hint = "Search in this folder"
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = GradientDrawable().apply { setColor(0x1FFFFFFF); cornerRadius = 10 * d }
            setPadding(dp(12f), 0, dp(12f), 0)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { tree.setQuery(s?.toString() ?: "") }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            setOnEditorActionListener { tv, _, _ ->
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(tv.windowToken, 0); true
            }
            searchBox = this; addView(this)
        }
        et.setText(""); et.visibility = VISIBLE
        requestLayout()
        mgr.setLayerFocusable(true)
        et.post {
            et.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideSearchBox() {
        val et = searchBox ?: return
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(et.windowToken, 0)
        et.clearFocus(); et.visibility = GONE
        mgr.setLayerFocusable(false)
    }

    override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
        if (e.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (e.action == android.view.KeyEvent.ACTION_UP) {
                when {
                    tree.searching -> tree.stopSearch()
                    treeOpen() -> closeTree()
                    groupOpen() -> closeGroup()
                    else -> mgr.closeLayer()
                }
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    fun setActive(active: Set<String>) { card.r.activeKeys = active; card.invalidate() }

    fun setEdit(on: Boolean) {
        if (on && treeOpen()) closeTree()
        if (on && groupOpen()) closeGroup()
        edit = on; card.r.edit = on; card.r.clampScroll(); updateSlider()
        btnEdit.glyphText = if (on) "\uf00c" else "\uf304"; btnEdit.on = on
        val v = if (on) VISIBLE else GONE
        val hv = if (on && !imm()) VISIBLE else GONE
        btnAdd.visibility = v; rz.visibility = hv; bar.visibility = hv
        requestLayout(); card.invalidate()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec); val h = MeasureSpec.getSize(hSpec)
        setMeasuredDimension(w, h)
        val s = card.r.s
        if (s.immersive) {
            popW = w
            popH = max(dp(180f), h - insetTop() - dp(44f) - insetBottom())
        } else {
            popW = dp(s.w).coerceIn(dp(180f), max(dp(180f), w - dp(12f)))
            popH = dp(s.h).coerceIn(dp(180f), max(dp(180f), h - dp(70f)))
        }
        val ex = { v: Int -> MeasureSpec.makeMeasureSpec(v, MeasureSpec.EXACTLY) }
        card.measure(ex(popW), ex(popH))
        for (b in listOf(btnSet, btnEdit, btnAdd)) b.measure(ex(dp(26f)), ex(dp(26f)))
        cfs.measure(ex(cfs.wantedW()), ex(cfs.wantedH()))
        val rzs = dp(s.popRad + 6f)
        rz.measure(ex(rzs), ex(rzs)); bar.measure(ex(dp(46f)), ex(dp(14f)))
        for (i in fixedCount until childCount) {
            val ch = getChildAt(i)
            if (ch === searchBox) ch.measure(ex(popW - dp(62f)), ex(dp(32f)))
            else if (ch === group) { group.measureFor(popW - dp(8f), popH - dp(8f)); ch.measure(ex(group.wantedW()), ex(group.wantedH())) }
            else ch.measure(ex(popW), ex(popH))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l; val h = b - t
        val s = card.r.s
        if (s.immersive) {
            popX = 0
            popY = insetTop() + dp(44f)
        } else {
            popX = if (s.isLeft) 0 else w - popW
            val minTop = dp(64f); val maxTop = max(minTop, h - popH - dp(54f))
            val top = userTop ?: (mgr.triggerCenterY() - popH / 2)
            popY = top.coerceIn(minTop, maxTop)
        }
        card.layout(popX, popY, popX + popW, popY + popH)
        val by = if (s.immersive) insetTop() + dp(8f) else popY - dp(34f); val bs = dp(26f)
        val xs = if (s.isLeft) intArrayOf(popX + dp(8f), popX + dp(38f), popX + dp(68f))
        else intArrayOf(popX + popW - dp(34f), popX + popW - dp(64f), popX + popW - dp(94f))
        btnSet.layout(xs[0], by, xs[0] + bs, by + bs)
        btnEdit.layout(xs[1], by, xs[1] + bs, by + bs)
        btnAdd.layout(xs[2], by, xs[2] + bs, by + bs)
        val sx = if (s.isLeft) popX + popW + dp(8f) else popX - dp(8f) - cfs.measuredWidth
        val sy = popY + (popH - cfs.measuredHeight) / 2
        cfs.layout(sx, sy, sx + cfs.measuredWidth, sy + cfs.measuredHeight)
        val rzs = rz.measuredWidth
        val rx = if (s.isLeft) popX + popW - rzs else popX
        rz.layout(rx, popY + popH - rzs, rx + rzs, popY + popH)
        val bx = popX + (popW - bar.measuredWidth) / 2
        bar.layout(bx, popY + popH - dp(19f), bx + bar.measuredWidth, popY + popH - dp(5f))
        for (i in fixedCount until childCount) {
            val ch = getChildAt(i)
            if (ch === searchBox) { val sl = popX + dp(52f); ch.layout(sl, popY + dp(7f), sl + ch.measuredWidth, popY + dp(39f)) }
            else if (ch === group) layoutGroup()
            else ch.layout(popX, popY, popX + popW, popY + popH)
        }
    }

    /** Small folder-style panel: centred just above the group's tile (below it only when there is no room above), kept inside the popup. */
    private fun layoutGroup() {
        val gw = group.measuredWidth; val gh = group.measuredHeight
        val lo = popX + dp(4f); val hi = max(lo, popX + popW - gw - dp(4f))
        val gx = (groupAx - gw / 2).toInt().coerceIn(lo, hi)
        val up = (groupAy - groupHalf - dp(6f)).toInt() - gh          // panel bottom sits just above the icon
        val down = (groupAy + groupHalf + dp(8f)).toInt()
        val gy = when {
            up >= popY -> up
            down + gh <= popY + popH -> down
            else -> max(popY, popY + popH - gh)
        }
        group.layout(gx, gy, gx + gw, gy + gh)
    }

    private fun updateSlider() {
        val show = !edit && !treeOpen() && !groupOpen() && !imm() && card.r.s.cornerSlider && cfs.folders.size >= 2
        cfs.visibility = if (show) VISIBLE else GONE
        if (!show) cfs.hover = -1
    }

    fun sliderHoverItem(): Item? = if (cfs.visibility == VISIBLE) cfs.folders.getOrNull(cfs.hover) else null

    /** Long-press menu (Edit / Info / Remove) shown over the popup. */
    fun showContext(item: Item, x: Float, y: Float) {
        val scrim = FrameLayout(context).apply { isClickable = true }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5f), dp(5f), dp(5f), dp(5f))
            background = GradientDrawable().apply { setColor(0xF5121216.toInt()); cornerRadius = 14 * d; setStroke(dp(1f), 0x1FFFFFFF) }
        }
        fun row(glyph: String, label: String, color: Int, tint: Int, click: () -> Unit) {
            val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10f), dp(8f), dp(14f), dp(8f)) }
            r.addView(TextView(context).apply { text = glyph; typeface = Fa.typeface(context, false); setTextColor(tint); textSize = 12f; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(18f), ViewGroup.LayoutParams.WRAP_CONTENT))
            r.addView(TextView(context).apply { text = label; setTextColor(color); textSize = 12f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD); setPadding(dp(9f), 0, 0, 0) })
            r.setOnClickListener { removeView(scrim); click() }
            box.addView(r)
        }
        row("\uf304", "Edit Shortcut", 0xFFECECEC.toInt(), ACCENT) { mgr.openEditor(item, item.slot) }
        row("\uf05a", "Shortcut Info", 0xFFECECEC.toInt(), ACCENT) { context.toast("${item.n} (${TYPES[item.t]?.label ?: item.t}) ${when (item.t) { "system" -> ""; "group" -> "${item.memberItems.size} shortcuts"; else -> item.v }}".trim()) }
        row("\uf2ed", "Remove", 0xFFFF7B72.toInt(), 0xFFFF7B72.toInt()) { showConfirm(item.n) { Store.delete(item.id) } }
        scrim.setOnClickListener { removeView(scrim) }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = (x * d).toInt().coerceIn(dp(4f), max(dp(4f), popW - dp(160f)))
        lp.topMargin = (y * d).toInt().coerceIn(dp(4f), max(dp(4f), popH - dp(140f)))
        scrim.addView(box, lp)
        addView(scrim, FrameLayout.LayoutParams(popW, popH))
    }

    private fun installDrags() {
        var sy = 0f; var st = 0
        bar.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sy = e.rawY; st = popY; true }
                MotionEvent.ACTION_MOVE -> { userTop = st + (e.rawY - sy).toInt(); requestLayout(); true }
                else -> true
            }
        }
        var sx = 0f; var sw = 0f; var sh = 0f
        rz.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; sw = popW / d; sh = popH / d; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (if (card.r.s.isLeft) e.rawX - sx else sx - e.rawX) / d
                    val dy = (e.rawY - sy) / d
                    val maxW = (width / d - 12f); val maxH = ((height - popY) / d - 54f)
                    Store.updateSettings { it.copy(w = (sw + dx).coerceIn(180f, maxW), h = (sh + dy).coerceIn(180f, max(180f, maxH))) }
                    true
                }
                else -> true
            }
        }
    }

    // ───── gesture (finger still down on the trigger) ─────
    fun pointer(rawX: Float, rawY: Float) {
        getLocationOnScreen(loc)
        val x = rawX - loc[0]; val y = rawY - loc[1]
        ringOn = true; ringX = x; ringY = y; lastPx = x; lastPy = y
        val s = card.r.s
        // header buttons
        var hb: RoundBtn? = null
        for (b in listOf(btnSet, btnEdit, btnAdd)) {
            if (b.visibility != VISIBLE) continue
            if (hypot(x - (b.left + b.width / 2f), y - (b.top + b.height / 2f)) <= dp(20f)) { hb = b; break }
        }
        if (hb !== hoverBtn) {
            hoverBtn?.setHover(false); hb?.setHover(true); hoverBtn = hb
            if (hb != null) mgr.haptic(true)
        }
        if (cfs.visibility == VISIBLE) {
            val sx = x - cfs.left; val sy = y - cfs.top
            val within = sx >= -dp(6f) && sx <= cfs.width + dp(6f) && sy >= 0 && sy <= cfs.height
            val idx = if (within) cfs.nodeAt(sy) else -1
            if (idx != cfs.hover) { cfs.hover = idx; if (idx >= 0) mgr.haptic(true) }
        }
        // tree open: it owns the pointer from here on
        if (treeOpen()) { tree.pointer(x - popX, y - popY); invalidate(); return }
        if (groupOpen()) {
            group.pointer(x, y)
            if (group.contains(x, y)) { removeCallbacks(openFolderDwell); setAuto(0); invalidate(); return }
            // outside the panel: fall through so the finger can move on to another group / app
        }
        // arrows + item hover in card space
        val lx = (x - popX) / d; val ly = (y - popY) / d
        val r = card.r
        val dir = if (lx in -40f..(r.wDp + 40f)) r.arrowAt(lx, ly) else 0
        setAuto(dir)
        val inZone = x > popX - dp(48f) && x < popX + popW + dp(48f) && y > popY - dp(48f) && y < popY + popH + dp(48f)
        if (!inZone) {
            removeCallbacks(openFolderDwell)
            if (r.hoverId != null) { r.hoverId = null; card.invalidate() }
        } else {
            val thresh = s.ringSize / 2f + s.icon / 2f + 22f
            val n = r.nearest(lx, ly, thresh)
            if (n == null) {
                removeCallbacks(openFolderDwell)
                if (r.hoverId != null) { r.hoverId = null; card.invalidate() }
            } else if (n.id != r.hoverId) {
                r.hoverId = n.id; mgr.haptic(true); card.invalidate()
                removeCallbacks(openFolderDwell)
                if (!edit && (n.t == "folder" || n.t == "group")) postDelayed(openFolderDwell, card.r.s.dwellDelay.toLong())
            }
        }
        // A hover-opened panel only stays while the finger is on it or on its own icon; anywhere else (another tile,
        // a gap, off the popup) it closes. Hover state is kept so the finger can move straight on to another group.
        if (groupOpen() && !group.touchMode && !group.contains(x, y) && card.r.hoverId != group.itemId) {
            val keep = card.r.hoverId
            closeGroup()
            card.r.hoverId = keep; card.invalidate()
        }
        invalidate()
    }

    fun hoverItem(): Item? = card.r.hoverId?.let { id -> card.r.items.firstOrNull { it.id == id } }

    fun buttonUnderPointer(): RoundBtn? = hoverBtn

    fun treeOpen() = tree.visibility == VISIBLE

    fun openTree(folder: Item, fromGesture: Boolean) {
        if (edit || treeOpen()) return
        if (groupOpen()) closeGroup()
        removeCallbacks(openFolderDwell)
        card.r.hoverId = null
        card.visibility = INVISIBLE
        tree.visibility = VISIBLE; updateSlider()
        tree.open(folder, fromGesture, lastPx - popX, lastPy - popY)
        mgr.haptic(true)
        invalidate()
    }

    fun closeTree() {
        if (!treeOpen()) return
        if (tree.searching) tree.stopSearch()
        tree.close(); tree.visibility = GONE
        card.visibility = VISIBLE; card.invalidate(); updateSlider()
    }

    fun groupOpen() = group.visibility == VISIBLE

    fun openGroup(g: Item, fromGesture: Boolean) {
        if (edit || treeOpen()) return
        if (groupOpen()) { if (group.itemId == g.id) { if (!fromGesture) group.touchMode = true; return }; group.close(); group.visibility = GONE }
        removeCallbacks(openFolderDwell)
        val r = card.r
        r.hoverId = g.id                       // keeps the source tile enlarged while its panel is open
        val slot = r.slotOfItem(g)
        groupAx = popX + r.centerX(slot) * d
        groupAy = popY + r.iconCenterY(slot) * d
        groupHalf = r.s.icon * d / 2f
        group.visibility = VISIBLE; updateSlider()
        group.touchMode = !fromGesture
        group.open(g, fromGesture, lastPx, lastPy)
        requestLayout()
        mgr.haptic(true)
        card.invalidate(); invalidate()
    }

    fun closeGroup() {
        if (!groupOpen()) return
        group.close(); group.visibility = GONE
        card.r.hoverId = null; card.invalidate(); updateSlider()
    }

    /** Finger lifted while an app group is open. True when it was released over the panel. */
    fun releaseGroup(): Boolean = group.release()

    /** Finger lifted while the tree is open. True when it was released over the panel. */
    fun releaseTree(): Boolean = tree.release()

    fun endGesture() {
        ringOn = false; setAuto(0); hoverBtn?.setHover(false); hoverBtn = null
        removeCallbacks(openFolderDwell)
        if (treeOpen()) tree.clearHover()
        if (groupOpen()) group.clearHover()
        cfs.hover = -1
        card.r.hoverId = null; card.invalidate(); invalidate()
    }

    private fun setAuto(dir: Int) {
        if (dir == autoDir) return
        autoDir = dir; card.r.arrowActive = dir
        if (dir != 0) postOnAnimation(autoScroll)
        card.invalidate()
    }

    private val capPath = android.graphics.Path()

    private fun capRect(which: Int, out: RectF): Boolean {
        val s = card.r.s
        getLocationOnScreen(loc)
        val ty = mgr.triggerTop() - loc[1]; val th = mgr.triggerHeightPx().toFloat()
        val tw = s.trigW * d
        val cw = 22 * d; val ch = 24 * d
        val x0 = if (s.isLeft) tw else width - tw - cw
        when (which) {
            1 -> { val t = max(ty - ch / 2, 0f); out.set(x0, t, x0 + cw, t + ch) }
            2 -> { val t = min(ty + th - ch / 2, height - ch); out.set(x0, t, x0 + cw, t + ch) }
            else -> {
                val ww = 26 * d
                val wx = if (s.isLeft) tw else width - tw - ww
                out.set(wx, ty + th / 2 - ww / 2, wx + ww, ty + th / 2 + ww / 2)
            }
        }
        return true
    }

    private fun drawTriggerHandles(c: Canvas) {
        val s = card.r.s; val r = RectF()
        for (w in 1..2) {
            capRect(w, r)
            capPath.reset()
            val l = r.left; val rt = r.right; val t = r.top; val b = r.bottom; val h = b - t; val ww = rt - l
            if (!s.isLeft) {
                capPath.moveTo(l, t + h * .25f); capPath.lineTo(l + ww * .65f, t + h * .25f); capPath.lineTo(rt, t + h * .5f)
                capPath.lineTo(l + ww * .65f, t + h * .75f); capPath.lineTo(l, t + h * .75f)
            } else {
                capPath.moveTo(rt, t + h * .25f); capPath.lineTo(rt - ww * .65f, t + h * .25f); capPath.lineTo(l, t + h * .5f)
                capPath.lineTo(rt - ww * .65f, t + h * .75f); capPath.lineTo(rt, t + h * .75f)
            }
            capPath.close()
            handlePaint.style = Paint.Style.FILL; handlePaint.color = 0xFF3A3842.toInt()
            c.drawPath(capPath, handlePaint)
            handlePaint.style = Paint.Style.STROKE; handlePaint.strokeWidth = d; handlePaint.color = 0x33FFFFFF
            c.drawPath(capPath, handlePaint)
        }
        capRect(3, r)
        handleTxt.textSize = 13f * d; handleTxt.color = 0xD9FFFFFF.toInt()
        c.drawText("\u2039 \u203A", r.centerX(), r.centerY() - (handleTxt.ascent() + handleTxt.descent()) / 2, handleTxt)
    }

    private val dimPaint = Paint().apply { color = Color.argb(115, 0, 0, 0) }

    /** While an app group is open, everything behind it (screen and main popup) is dimmed a bit. */
    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child === group && groupOpen()) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        return super.drawChild(canvas, child, drawingTime)
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        if (persistent && edit && !treeOpen()) drawTriggerHandles(c)
        val hv = cfs.hover
        if (cfs.visibility == VISIBLE && hv >= 0) {
            val f = cfs.folders.getOrNull(hv)
            if (f != null) {
                handleTxt.textSize = 11.5f * d; handleTxt.color = Color.WHITE; handleTxt.textAlign = Paint.Align.LEFT
                val tw = handleTxt.measureText(f.n)
                val bw = tw + 38 * d; val bh = 26 * d
                val cy = cfs.top + cfs.nodeCenterY(hv)
                val left = if (card.r.s.isLeft) cfs.right + 10 * d else cfs.left - 10 * d - bw
                val rr = RectF(left, cy - bh / 2, left + bw, cy + bh / 2)
                handlePaint.style = Paint.Style.FILL; handlePaint.color = 0xF50F0F12.toInt()
                c.drawRoundRect(rr, 8 * d, 8 * d, handlePaint)
                handlePaint.style = Paint.Style.STROKE; handlePaint.strokeWidth = d; handlePaint.color = 0x29FFFFFF
                c.drawRoundRect(rr, 8 * d, 8 * d, handlePaint)
                val gf = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fa.typeface(context, false); textSize = 11f * d; color = 0xFFECECEC.toInt(); textAlign = Paint.Align.LEFT }
                c.drawText("\uf07c", rr.left + 10 * d, rr.centerY() - (gf.ascent() + gf.descent()) / 2, gf)
                c.drawText(f.n, rr.left + 28 * d, rr.centerY() - (handleTxt.ascent() + handleTxt.descent()) / 2, handleTxt)
                handleTxt.textAlign = Paint.Align.CENTER
            }
        }
        if (ringOn) {
            val s = card.r.s
            ringPaint.strokeWidth = s.ringThick * d
            ringPaint.color = Color.argb((s.ringOpacity / 100f * 255).toInt(), 255, 255, 255)
            c.drawCircle(ringX, ringY, (s.ringSize / 2f) * d, ringPaint)
        }
    }

    // ───── touch mode: tap outside closes ─────
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!persistent) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trigHandle = 0
                if (edit && !treeOpen()) {
                    val r = RectF(); val slop = 8 * d
                    getLocationOnScreen(loc)
                    for (w in 1..3) {
                        capRect(w, r); r.inset(-slop, -slop)
                        if (r.contains(e.rawX - loc[0], e.rawY - loc[1])) { trigHandle = w; break }
                    }
                    if (trigHandle != 0) { thY = e.rawY; thX = e.rawX; thH = card.r.s.trigH; thW = card.r.s.trigW; trigDrag = true }
                }
                if (trigHandle == 0 && edit && mgr.hitTrigger(e.rawX, e.rawY)) { trigDrag = true; trigOff = e.rawY - mgr.triggerTop() }
            }
            MotionEvent.ACTION_MOVE -> if (trigDrag) {
                when (trigHandle) {
                    1 -> Store.updateSettings { it.copy(trigH = (thH + (thY - e.rawY) * 2 / d).coerceIn(36f, 1000f)) }
                    2 -> Store.updateSettings { it.copy(trigH = (thH + (e.rawY - thY) * 2 / d).coerceIn(36f, 1000f)) }
                    3 -> Store.updateSettings { it.copy(trigW = (thW + (if (it.isLeft) e.rawX - thX else thX - e.rawX) / d).coerceIn(3f, 32f)) }
                    else -> mgr.moveTrigger(e.rawY - trigOff)
                }
            }
            MotionEvent.ACTION_UP -> { if (!trigDrag) mgr.closeLayer(); trigDrag = false; trigHandle = 0 }
            MotionEvent.ACTION_CANCEL -> { trigDrag = false; trigHandle = 0 }
        }
        return true
    }

    fun showConfirm(name: String, ok: () -> Unit) {
        val dim = FrameLayout(context).apply { setBackgroundColor(0xEB000000.toInt()); isClickable = true }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14f), dp(14f), dp(14f), dp(12f))
            background = GradientDrawable().apply { setColor(0xFA0C0C0C.toInt()); cornerRadius = 18 * d; setStroke(d.toInt().coerceAtLeast(1), 0x14FFFFFF) }
        }
        val msg = TextView(context).apply { text = "Delete \"$name\"?"; setTextColor(0xFFECECEC.toInt()); textSize = 13f; gravity = Gravity.CENTER }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12f), 0, 0) }
        fun btn(label: String, bg: Int, fg: Int, click: () -> Unit) = Button(context).apply {
            text = label; isAllCaps = false; textSize = 12f; setTextColor(fg); minHeight = 0; minimumHeight = 0
            background = GradientDrawable().apply { setColor(bg); cornerRadius = 10 * d }
            setOnClickListener { click() }
        }
        val remove = { removeView(dim) }
        row.addView(btn("Cancel", 0x17FFFFFF, 0xFFECECEC.toInt()) { remove() }, LinearLayout.LayoutParams(0, dp(34f), 1f).apply { rightMargin = dp(6f) })
        row.addView(btn("Delete", 0xFFE5534B.toInt(), Color.WHITE) { remove(); ok() }, LinearLayout.LayoutParams(0, dp(34f), 1f))
        box.addView(msg); box.addView(row)
        dim.addView(box, FrameLayout.LayoutParams(dp(210f), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(dim, FrameLayout.LayoutParams(popW, popH))
    }
}

/** The thin edge handle. Owns the whole gesture: down opens, drag selects, up launches. */
class TriggerView(ctx: Context, private val mgr: OverlayManager) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    var active = false
        set(v) { field = v; invalidate() }
    private var downX = 0f; private var downY = 0f; private var downT = 0L
    private var dragging = false

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (android.os.Build.VERSION.SDK_INT >= 29) systemGestureExclusionRects = listOf(Rect(0, 0, r - l, b - t))
    }

    override fun onDraw(c: Canvas) {
        val s = mgr.state.settings
        val bw = (s.trigW + if (active) 4f else 0f) * d
        val ext = 10 * d
        if (s.isLeft) rf.set(-ext, 0f, bw, height.toFloat()) else rf.set(width - bw, 0f, width + ext, height.toFloat())
        val a = if (s.hideTrigger) 0f else s.trigOpacity / 100f
        p.color = Color.argb((a * 255).toInt().coerceIn(0, 255), 255, 255, 255)
        c.drawRoundRect(rf, 4 * d, 4 * d, p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; downT = SystemClock.uptimeMillis(); active = true
                dragging = false
                mgr.gestureStart(e.rawX, e.rawY)
            }
            MotionEvent.ACTION_MOVE -> mgr.gestureMove(e.rawX, e.rawY)
            MotionEvent.ACTION_UP -> {
                active = false
                val tap = hypot(e.rawX - downX, e.rawY - downY) < 8 * d && SystemClock.uptimeMillis() - downT < 260
                mgr.gestureEnd(tap)
            }
            MotionEvent.ACTION_CANCEL -> { active = false; dragging = false; mgr.gestureCancel() }
        }
        return true
    }
}