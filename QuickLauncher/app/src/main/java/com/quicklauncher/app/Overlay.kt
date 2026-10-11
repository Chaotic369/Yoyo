package com.quicklauncher.app

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.res.ColorStateList
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Owns every overlay window: the edge trigger, the full-screen popup layer and the slider panel.
 * Runs inside the accessibility service when enabled (TYPE_ACCESSIBILITY_OVERLAY, no extra permission),
 * otherwise inside [OverlayService] using "Display over other apps".
 */
class OverlayManager(private val ctx: Context, private val winType: Int) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val d get() = ctx.resources.displayMetrics.density
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val vibrator: Vibrator? = ctx.getSystemService(Vibrator::class.java)

    var state: AppState = Store.state.value
        private set(v) { field = v; popupSettings = v.settings.forPopup() }
    /** [Settings.forPopup] of the current settings, cached so draw code can read it every frame. */
    var popupSettings: com.quicklauncher.app.Settings = state.settings.forPopup()
        private set
    var editMode = false
        private set
    private var trigger: TriggerView? = null
    private var trigLp: WindowManager.LayoutParams? = null
    private var layer: LayerView? = null
    private var layerLp: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private val panelClose = Runnable { closePanel() }

    fun start() {
        Store.init(ctx); SysState.init(ctx)
        scope.launch { Store.state.collect { state = it; apply() } }
    }

    fun stop() {
        scope.cancel(); closeLayer(); closePanel()
        trigger?.let { runCatching { wm.removeView(it) } }; trigger = null
    }

    fun onConfigChanged() { apply(); layer?.requestLayout() }

    // ───── geometry ─────
    private fun screen(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            return Rect(0, 0, b.width(), b.height())
        }
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        return Rect(0, 0, m.widthPixels, m.heightPixels)
    }

    private fun dp(v: Float) = (v * d).toInt()

    fun triggerTop(): Float = (trigLp?.y ?: 0).toFloat()
    fun triggerHeightPx(): Int = trigLp?.height ?: 0
    fun triggerCenterY(): Int = (trigLp?.y ?: 0) + (trigLp?.height ?: 0) / 2
    fun layerOpen() = layer != null
    fun hitTrigger(rx: Float, ry: Float): Boolean {
        val lp = trigLp ?: return false
        val sw = screen().width()
        val inX = if (state.settings.isLeft) rx <= lp.width else rx >= sw - lp.width
        return inX && ry >= lp.y && ry <= lp.y + lp.height
    }

    private fun baseFlags() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    // ───── trigger ─────
    private fun apply() {
        val s = state.settings
        val landscape = screen().let { it.width() > it.height() }
        if (!s.triggerEnabled || (s.disableInLandscape && landscape)) {
            trigger?.let { runCatching { wm.removeView(it) } }; trigger = null; trigLp = null
        } else {
            val scr = screen()
            val full = dp(s.trigH) >= scr.height() - dp(54f)   // long enough = whole side
            val th = if (full) scr.height() else dp(s.trigH)
            val y = if (full) 0 else (scr.height() * s.trigY / 100f - th / 2f).toInt().coerceIn(dp(36f), max(dp(36f), scr.height() - th - dp(18f)))
            val lp = trigLp ?: WindowManager.LayoutParams(0, 0, winType,
                baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
            lp.width = dp(max(s.trigW + 4f, 24f)); lp.height = th; lp.y = y; lp.x = 0
            lp.gravity = Gravity.TOP or (if (s.isLeft) Gravity.START else Gravity.END)
            lp.title = "QuickLauncherTrigger"
            trigLp = lp
            if (trigger == null) {
                trigger = TriggerView(ctx, this)
                runCatching { wm.addView(trigger, lp) }
            } else {
                runCatching { wm.updateViewLayout(trigger, lp) }; trigger?.invalidate()
            }
        }
        layer?.setData(s, state.items, SysState.activeKeys(ctx, state.items))
    }

    fun moveTrigger(topPx: Float) {
        val scr = screen(); val th = dp(state.settings.trigH)
        if (th >= scr.height() - dp(54f)) return
        val y = topPx.toInt().coerceIn(dp(36f), max(dp(36f), scr.height() - th - dp(18f)))
        val pct = ((y + th / 2f) / scr.height() * 100f).coerceIn(10f, 90f)
        Store.updateSettings { it.copy(trigY = pct) }
    }

    // ───── gesture ─────
    fun gestureStart(rx: Float, ry: Float) {
        haptic(true)
        if (layer == null) openLayer(false)
        layer?.pointer(rx, ry)
    }

    fun gestureMove(rx: Float, ry: Float) { layer?.pointer(rx, ry) }

    fun gestureEnd(tap: Boolean) {
        val l = layer ?: return
        if (tap && l.groupOpen() && !l.group.touchMode) { l.endGesture(); closeLayer(); return }   // hover-opened group: lifting never leaves it open
        if (tap) { enterTouchMode(); l.endGesture(); return }
        val btn = l.buttonUnderPointer()
        val item = l.hoverItem()
        val fold = l.sliderHoverItem()
        if (l.groupOpen() && btn == null) {
            val touch = l.group.touchMode
            val overApp = l.group.hoveringApp()
            val inside = l.releaseGroup()          // launches the app under the finger, if any
            if (touch) {
                // panel already in tap mode: releasing over it keeps it open, outside falls through
                if (inside) { l.endGesture(); if (layer === l) { l.group.enterTouch(); enterTouchMode() }; return }
                // lifted outside the panel on its own icon / a folder / nothing: close, never fall through to openGroup()
                if (item == null || item.t == "group" || item.t == "folder") { l.endGesture(); if (layer === l) closeLayer(); return }
            } else {
                // hover mode: only an app inside the panel (or a plain app tile) keeps the gesture alive;
                // lifting on the group icon or a bit outside closes everything automatically
                if (overApp) { l.endGesture(); return }
                if (item == null || item.t == "group" || item.t == "folder") { l.endGesture(); if (layer === l) closeLayer(); return }
            }
        }
        if (l.treeOpen() && btn == null) {
            // released over a file opens it; over the panel otherwise keeps the tree open for tapping
            val inside = l.releaseTree()
            l.endGesture()
            if (layer === l) { if (inside) enterTouchMode() else closeLayer() }
            return
        }
        l.endGesture()
        when {
            btn === l.btnSet -> openSettings()
            btn === l.btnEdit -> { enterTouchMode(); toggleEdit() }
            fold != null -> { haptic(false); enterTouchMode(); l.openTree(fold, false) }
            item != null && item.t == "group" -> { haptic(false); enterTouchMode(); l.openGroup(item, false) }
            item != null -> { haptic(false); launch(item, false) }
            else -> closeLayer()
        }
    }

    fun gestureCancel() {
        val l = layer ?: return
        val hoverGroup = l.groupOpen() && !l.group.touchMode      // hover-opened panel must never outlive its finger
        l.endGesture()
        if (hoverGroup || !l.persistent) closeLayer()
    }

    // ───── layer ─────
    private fun openLayer(touchable: Boolean) {
        closePanel()
        val lv = LayerView(ctx, this)
        val flags = baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val lp = WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            winType, flags, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        lp.title = "QuickLauncherPopup"
        lv.persistent = touchable
        lv.setData(state.settings, state.items, SysState.activeKeys(ctx, state.items))
        layer = lv; layerLp = lp
        if (runCatching { wm.addView(lv, lp) }.isFailure) { layer = null; layerLp = null }
        trigger?.active = true
    }

    private fun enterTouchMode() {
        val l = layer ?: return; val lp = layerLp ?: return
        l.persistent = true
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(l, lp) }
    }

    /** The popup window ignores the keyboard by default; it becomes focusable only while the folder search box is open. */
    fun setLayerFocusable(on: Boolean) {
        val l = layer ?: return; val lp = layerLp ?: return
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        runCatching { wm.updateViewLayout(l, lp) }
    }

    fun closeLayer() {
        layer?.let { runCatching { wm.removeView(it) } }
        layer = null; layerLp = null; editMode = false
        trigger?.active = false; trigger?.invalidate()
    }

    fun toggleEdit() {
        editMode = !editMode
        layer?.setEdit(editMode)
        trigger?.invalidate()
    }

    fun openSettings() {
        closeLayer()
        ctx.startSafe(Intent(ctx, MainActivity::class.java).putExtra("from_pop", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    fun openEditor(item: Item?, slot: Int) {
        closeLayer()
        ctx.startSafe(Intent(ctx, EditorActivity::class.java).putExtra("id", item?.id).putExtra("slot", slot))
    }

    /** Reopens the popup in touch mode (used by the settings "Done" button). */
    fun reopenTouch() { if (layer == null) { openLayer(true) } }

    /** Launches one member of an App Group: an app, a call / SMS / email / web shortcut, a system action, ... */
    fun launchMember(member: Item, fromTouch: Boolean) {
        haptic(false)
        launch(member, fromTouch)
    }

    fun launch(item: Item, fromTouch: Boolean) {
        val sys = item.t == "system"
        val def = if (sys) SYS[item.v] else null
        val keepOpen = fromTouch && def?.toggle == true
        if (keepOpen) {
            Launcher.launch(ctx, item, this)
            handler.postDelayed({ layer?.setActive(SysState.activeKeys(ctx, state.items)) }, 350)
            return
        }
        val delay = if (sys) 160L else 0L
        if (sys) {
            closeLayer()
            handler.postDelayed({ Launcher.launch(ctx, item, this) }, delay)
        } else {
            Launcher.launch(ctx, item, this)   // launch first: our visible overlay allows background starts
            if (!fromTouch || state.settings.autoClose) closeLayer()
        }
    }

    // ───── folder tree actions ─────
    fun openTreeFile(folder: Item, e: FsEntry) {
        haptic(false)
        Fs.openFile(ctx, folder.v, e)   // launch first: our visible overlay allows background starts
        closeLayer()
    }

    fun openTreeFolder(folder: Item, key: String?) {
        haptic(false)
        Launcher.folderAt(ctx, folder.v, key)
        closeLayer()
    }

    fun grantFileAccess() {
        closeLayer()
        Fs.openAccessSettings(ctx)
    }

    fun haptic(tick: Boolean) {
        if (!state.settings.haptic) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 29)
                v.vibrate(VibrationEffect.createPredefined(if (tick) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_CLICK))
            else v.vibrate(VibrationEffect.createOneShot(if (tick) 10L else 24L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // ───── slider panel (volume / brightness) ─────
    fun showSlider(title: String, iconCls: String, value: Int, onChange: (Int) -> Unit) {
        closePanel()
        val c = ctx
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(10f), dp(22f), dp(34f))
            background = GradientDrawable().apply {
                setColor(0xFF0D0D0D.toInt())
                val r = 28 * d; cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        val grab = View(c).apply { background = GradientDrawable().apply { setColor(0x38FFFFFF); cornerRadius = 2 * d } }
        box.addView(grab, LinearLayout.LayoutParams(dp(38f), dp(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14f) })
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val g = Fa.glyph(iconCls)
        val ic = TextView(c).apply {
            text = g?.text ?: ""; typeface = Fa.typeface(c, g?.brands == true); setTextColor(0xFFECECEC.toInt()); textSize = 15f
        }
        val tt = TextView(c).apply { text = title; setTextColor(0xFFECECEC.toInt()); textSize = 14f; setPadding(dp(10f), 0, 0, 0) }
        val pct = TextView(c).apply { text = "$value%"; setTextColor(0xFFFFBF00.toInt()); textSize = 14f; gravity = Gravity.END }
        row.addView(ic); row.addView(tt); row.addView(pct, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)
        val sb = SeekBar(c).apply {
            max = 100; progress = value
            progressTintList = ColorStateList.valueOf(0xFFFFBF00.toInt()); thumbTintList = ColorStateList.valueOf(0xFFFFBF00.toInt())
            setPadding(dp(8f), dp(14f), dp(8f), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    pct.text = "$p%"; onChange(p)
                    handler.removeCallbacks(panelClose); handler.postDelayed(panelClose, 4000)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) closePanel(); false }
        val lp = WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.BOTTOM
        if (runCatching { wm.addView(box, lp) }.isSuccess) {
            panel = box; handler.postDelayed(panelClose, 4000)
        }
    }

    fun closePanel() {
        handler.removeCallbacks(panelClose)
        panel?.let { runCatching { wm.removeView(it) } }; panel = null
    }
}

/** Registry so the settings UI can talk to whichever host currently owns the overlay. */
object Host {
    @Volatile var manager: OverlayManager? = null

    fun canDrawOverlays(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun accessibilityEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = "${ctx.packageName}/${LauncherAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(me, true) || it.endsWith(".LauncherAccessibilityService") && it.startsWith(ctx.packageName) }
    }

    /** Starts the fallback foreground service when the accessibility service isn't carrying the overlay. */
    fun ensure(ctx: Context) {
        Store.init(ctx)
        if (LauncherAccessibilityService.instance != null) return
        if (!canDrawOverlays(ctx)) return
        if (!Store.state.value.settings.triggerEnabled) return
        runCatching { ctx.startForegroundService(Intent(ctx, OverlayService::class.java)) }
    }
}

class LauncherAccessibilityService : AccessibilityService() {
    private var mgr: OverlayManager? = null

    override fun onServiceConnected() {
        instance = this
        stopService(Intent(this, OverlayService::class.java))
        mgr = OverlayManager(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY).also { it.start(); Host.manager = it }
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); mgr?.onConfigChanged() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        mgr?.stop(); mgr = null
        if (Host.manager != null) Host.manager = null
        instance = null
        Host.ensure(applicationContext)
        super.onDestroy()
    }

    companion object { @Volatile var instance: LauncherAccessibilityService? = null }
}

class OverlayService : Service() {
    private var mgr: OverlayManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (LauncherAccessibilityService.instance != null || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (mgr == null) mgr = OverlayManager(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY).also { it.start(); Host.manager = it }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ql", "Quick Launcher", NotificationManager.IMPORTANCE_MIN))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "ql").setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Quick Launcher is active").setContentText("Swipe from the screen edge").setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); mgr?.onConfigChanged() }

    override fun onDestroy() {
        mgr?.stop(); mgr = null
        if (LauncherAccessibilityService.instance == null) Host.manager = null
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { Host.ensure(ctx.applicationContext) }
}