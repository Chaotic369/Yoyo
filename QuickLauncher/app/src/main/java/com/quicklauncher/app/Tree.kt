package com.quicklauncher.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One file or folder. [key] is a SAF document id (tree shortcuts) or an absolute path (raw shortcuts). */
data class FsEntry(val name: String, val dir: Boolean, val key: String, val mime: String?)

class FsResult(val entries: List<FsEntry>, val denied: Boolean)

/** Lists / opens folders for both Storage Access Framework trees and raw /storage/emulated/0 paths. */
object Fs {
    private const val PRIMARY = "/storage/emulated/0"
    private val exec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val order = compareBy<FsEntry>({ !it.dir }, { it.name.lowercase() })

    fun isTreeUri(v: String) = v.startsWith("content://")
    fun internalRoot() = PRIMARY

    fun normalizePath(v: String): String {
        var p = v.trim()
        if (p.startsWith("file://")) p = Uri.parse(p).path ?: p
        if (p == "/sdcard" || p.startsWith("/sdcard/")) p = PRIMARY + p.removePrefix("/sdcard")
        if (p.startsWith("/storage/self/primary")) p = PRIMARY + p.removePrefix("/storage/self/primary")
        if (!p.startsWith("/")) p = "$PRIMARY/$p"
        return p.trimEnd('/').ifEmpty { "/" }
    }

    fun displayPath(v: String): String =
        if (isTreeUri(v)) Uri.decode(Uri.parse(v).lastPathSegment ?: v).substringAfter(':', "").let { if (it.isEmpty()) "Storage" else "Internal storage/$it" }
        else normalizePath(v).replace(PRIMARY, "Internal storage")

    fun rootKey(v: String): String =
        if (isTreeUri(v)) runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(v)) }.getOrDefault("") else normalizePath(v)

    fun isManager() = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /** True when raw paths can be listed (and the home wallpaper can be read). */
    fun hasFullAccess(ctx: Context): Boolean {
        val read = ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 -> isManager()
            Build.VERSION.SDK_INT >= 30 -> isManager() && read
            else -> read
        }
    }

    fun openAccessSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            if (!ctx.startSafe(i, "")) ctx.startSafe(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } else {
            ctx.startSafe(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }
    }

    /** One tap "Internal storage" shortcut (opens as a tree). */
    fun addInternalStorage(ctx: Context) {
        if (Store.state.value.items.any { it.t == "folder" && it.v == PRIMARY }) { ctx.toast("Internal storage is already added"); return }
        Store.upsert(Item(Store.newId(), "folder", "Internal storage", PRIMARY, "fa-solid fa-hard-drive", "#1b1b1f", Store.freeSlot()))
        if (hasFullAccess(ctx)) ctx.toast("Internal storage added")
        else { ctx.toast("Added. Allow file access so the tree can open it"); openAccessSettings(ctx) }
    }

    fun list(ctx: Context, v: String, key: String): FsResult {
        if (isTreeUri(v)) {
            return try { FsResult(listSaf(ctx, Uri.parse(v), key), false) }
            catch (e: SecurityException) { FsResult(emptyList(), true) }
            catch (e: Exception) { FsResult(emptyList(), false) }
        }
        if (!hasFullAccess(ctx)) return FsResult(emptyList(), true)
        return try { FsResult(listRaw(key), false) } catch (e: Exception) { FsResult(emptyList(), false) }
    }

    fun listAsync(ctx: Context, v: String, key: String, cb: (FsResult) -> Unit) {
        val app = ctx.applicationContext
        exec.execute { val r = list(app, v, key); main.post { cb(r) } }
    }

    class Hit(val e: FsEntry, val chain: List<FsEntry>)

    private val searchExec = Executors.newSingleThreadExecutor()

    /** Breadth-first name search below [rootKey]. [alive] turns false when a newer search replaces this one. */
    fun search(ctx: Context, v: String, rootKey: String, query: String, alive: () -> Boolean, cb: (List<Hit>, Boolean) -> Unit) {
        val app = ctx.applicationContext
        val q = query.trim().lowercase()
        searchExec.execute {
            val hits = ArrayList<Hit>()
            val queue = ArrayDeque<Pair<String, List<FsEntry>>>()
            queue.add(rootKey to emptyList())
            var dirs = 0; var lastPost = 0
            while (queue.isNotEmpty() && alive() && hits.size < 300 && dirs < 8000) {
                val (key, chain) = queue.removeFirst(); dirs++
                val res = list(app, v, key)
                for (e in res.entries) {
                    if (e.name.lowercase().contains(q)) hits.add(Hit(e, chain))
                    if (e.dir) queue.add(e.key to (chain + e))
                }
                if (hits.size - lastPost >= 12) { lastPost = hits.size; val snap = ArrayList(hits); main.post { if (alive()) cb(snap, true) } }
            }
            val snap = ArrayList(hits)
            main.post { if (alive()) cb(snap, false) }
        }
    }

    private fun listRaw(path: String): List<FsEntry> {
        val files = File(path).listFiles() ?: return emptyList()
        return files.filter { !it.name.startsWith(".") }
            .map { FsEntry(it.name, it.isDirectory, it.absolutePath, null) }.sortedWith(order)
    }

    private fun listSaf(ctx: Context, tree: Uri, docId: String): List<FsEntry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val out = ArrayList<FsEntry>()
        ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: id.substringAfterLast('/')
                val mime = c.getString(2)
                if (name.startsWith(".")) continue
                out.add(FsEntry(name, mime == DocumentsContract.Document.MIME_TYPE_DIR, id, mime))
            }
        }
        return out.sortedWith(order)
    }

    private fun mimeFor(name: String, given: String?): String {
        if (!given.isNullOrBlank() && given != "application/octet-stream") return given
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: given ?: "*/*"
    }

    fun openFile(ctx: Context, v: String, e: FsEntry): Boolean {
        val uri: Uri = if (isTreeUri(v)) {
            DocumentsContract.buildDocumentUriUsingTree(Uri.parse(v), e.key)
        } else {
            runCatching { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(e.key)) }.getOrNull()
                ?: run { ctx.toast("File not accessible"); return false }
        }
        return ctx.startSafe(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeFor(e.name, e.mime)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "No app can open this file",
        )
    }
}

/**
 * Hover-to-expand file tree shown in place of the popup grid when a folder shortcut is hovered / tapped.
 * Only one folder per level stays expanded, so the open chain is just [path].
 * Hover mode: slide the finger over rows, a short dwell expands a folder, release on a file opens it.
 * Touch mode: tap folders to expand / collapse, tap a file to open it, drag to scroll.
 */
class TreeView(ctx: Context, private val mgr: OverlayManager) : View(ctx) {
    var onBack: () -> Unit = {}
    var onOpenFile: (Item, FsEntry) -> Unit = { _, _ -> }
    var onOpenFolder: (Item, String?) -> Unit = { _, _ -> }
    var onNeedAccess: () -> Unit = {}
    var onSearchState: (Boolean) -> Unit = {}

    private class Row(val kind: Int, val e: FsEntry?, val level: Int, val chain: List<FsEntry>? = null, val msg: String? = null)

    private companion object {
        const val K_ENTRY = 0
        const val K_LOADING = 1
        const val K_EMPTY = 2
        const val K_DENIED = 3
        const val K_MSG = 4
        const val DWELL_MS = 240L
    }

    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val headerH = 46 * d
    private val rowH = 34 * d
    private val pad = 8 * d
    private val indent = 16 * d

    private var item: Item? = null
    private var rootKey = ""
    private var session = 0
    private val cache = HashMap<String, FsResult>()
    private val loading = HashSet<String>()
    private var path: List<FsEntry> = emptyList()
    private var rows: List<Row> = emptyList()
    private var scroll = 0f
    private var hoverRow = -1
    private var hoverHdr = 0
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

    // ───── search ─────
    var searching = false; private set
    private var query = ""
    private var hits: List<Fs.Hit> = emptyList()
    private var searchBusy = false
    private var searchId = 0
    private val runSearch = Runnable {
        val itm = item ?: return@Runnable
        val q = query.trim()
        val id = ++searchId
        if (q.isEmpty()) { hits = emptyList(); searchBusy = false; relayout(null); invalidate(); return@Runnable }
        searchBusy = true
        relayout(null); invalidate()
        Fs.search(context, itm.v, rootKey, q, { id == searchId && searching }) { res, busy ->
            hits = res; searchBusy = busy; relayout(null); invalidate()
        }
    }

    fun startSearch() {
        if (searching) return
        searching = true; query = ""; hits = emptyList(); searchBusy = false; searchId++
        removeCallbacks(dwell); hoverRow = -1; hoverHdr = 0
        scroll = 0f; rebuild(); invalidate(); onSearchState(true)
    }

    fun stopSearch() {
        if (!searching) return
        searching = false; searchId++; removeCallbacks(runSearch)
        hits = emptyList(); searchBusy = false
        scroll = 0f; rebuild(); clampScroll(); invalidate(); onSearchState(false)
    }

    fun setQuery(q: String) {
        if (!searching || q == query) return
        query = q
        removeCallbacks(runSearch); postDelayed(runSearch, 280)
    }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val rf = RectF()
    private val clip = Path()

    private val dwell = Runnable { applyDwell() }
    private val auto = object : Runnable {
        override fun run() {
            if (autoDir == 0) return
            val before = scroll
            scroll += autoDir * 16 * d
            clampScroll()
            if (scroll == before) { autoDir = 0; return }
            if (armed && lastY > headerH) setHover(0, rowAt(lastY))
            invalidate()
            postOnAnimation(this)
        }
    }

    // ───── lifecycle ─────
    fun open(it: Item, gesture: Boolean, px: Float, py: Float) {
        item = it
        rootKey = Fs.rootKey(it.v)
        cache.clear(); loading.clear(); path = emptyList(); session++
        scroll = 0f; hoverRow = -1; hoverHdr = 0; autoDir = 0
        searching = false; searchId++; removeCallbacks(runSearch); hits = emptyList(); searchBusy = false
        // A tree that opens under the finger must not react until the finger actually moves.
        armed = !gesture; openX = px; openY = py; lastX = px; lastY = py
        removeCallbacks(dwell)
        ensure(rootKey)
        rebuild()
        invalidate()
    }

    fun close() {
        removeCallbacks(dwell); removeCallbacks(auto)
        autoDir = 0; session++; item = null
        searching = false; searchId++; removeCallbacks(runSearch)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(dwell); removeCallbacks(auto); session++
        super.onDetachedFromWindow()
    }

    fun clearHover() {
        removeCallbacks(dwell); setAuto(0)
        hoverRow = -1; hoverHdr = 0
        invalidate()
    }

    // ───── data ─────
    private fun ensure(key: String) {
        val itm = item ?: return
        if (cache.containsKey(key) || !loading.add(key)) return
        val sess = session
        Fs.listAsync(context, itm.v, key) { res ->
            if (sess != session) return@listAsync
            loading.remove(key)
            cache[key] = res
            relayout(hoverKey())
            invalidate()
        }
    }

    private fun hoverKey(): String? = rows.getOrNull(hoverRow)?.e?.key

    private fun rebuild() {
        val out = ArrayList<Row>()
        if (searching) {
            when {
                query.isBlank() -> out.add(Row(K_MSG, null, 0, msg = "Type to search everything in here"))
                else -> {
                    for (h in hits) out.add(Row(K_ENTRY, h.e, 0, h.chain))
                    if (searchBusy) out.add(Row(K_MSG, null, 0, msg = "Searching\u2026"))
                    else if (hits.isEmpty()) out.add(Row(K_MSG, null, 0, msg = "No results"))
                }
            }
            rows = out
            return
        }
        fun add(dirKey: String, level: Int) {
            val res = cache[dirKey]
            if (res == null) { out.add(Row(K_LOADING, null, level)); return }
            if (res.denied) { out.add(Row(K_DENIED, null, level)); return }
            if (res.entries.isEmpty()) { out.add(Row(K_EMPTY, null, level)); return }
            for (e in res.entries) {
                out.add(Row(K_ENTRY, e, level))
                if (e.dir && path.size > level && path[level].key == e.key) add(e.key, level + 1)
            }
        }
        add(rootKey, 0)
        rows = out
    }

    /** Rebuilds the row list and keeps the row with [anchorKey] at the same place on screen. */
    private fun relayout(anchorKey: String?) {
        val oldIdx = if (anchorKey == null) -1 else rows.indexOfFirst { it.e?.key == anchorKey }
        rebuild()
        if (anchorKey != null && oldIdx >= 0) {
            val ni = rows.indexOfFirst { it.e?.key == anchorKey }
            if (ni >= 0) {
                scroll += (ni - oldIdx) * rowH
                if (hoverRow >= 0) hoverRow = ni
            }
        }
        clampScroll()
    }

    private fun maxScroll() = max(0f, rows.size * rowH + 2 * pad - (height - headerH))
    private fun clampScroll() { scroll = scroll.coerceIn(0f, maxScroll()) }

    private fun rowAt(y: Float): Int {
        val cy = y - headerH + scroll - pad
        if (cy < 0f) return -1
        val i = (cy / rowH).toInt()
        return if (i in rows.indices) i else -1
    }

    private fun hdrAt(x: Float): Int = when {
        x < 52 * d -> 1
        searching -> 0
        x > width - 52 * d -> 2
        x > width - 92 * d -> 3
        else -> 0
    }

    private fun currentDir(): String? = path.lastOrNull()?.key

    // ───── hover mode ─────
    fun pointer(x: Float, y: Float) {
        if (!armed) {
            if (hypot(x - openX, y - openY) < 14 * d) return
            armed = true
        }
        lastX = x; lastY = y
        val inside = x >= 0f && x <= width && y >= 0f && y <= height
        var hdr = 0
        var row = -1
        if (inside) { if (y < headerH) hdr = hdrAt(x) else row = rowAt(y) }
        setHover(hdr, row)
        val edge = 34 * d
        val dir = when {
            !inside -> 0
            y > headerH && y < headerH + edge && scroll > 1f -> -1
            y > height - edge && scroll < maxScroll() - 1f -> 1
            else -> 0
        }
        setAuto(dir)
        invalidate()
    }

    private fun setAuto(dir: Int) {
        if (dir == autoDir) return
        autoDir = dir
        if (dir != 0) postOnAnimation(auto)
        invalidate()
    }

    private fun setHover(hdr: Int, row: Int) {
        if (hdr == hoverHdr && row == hoverRow) return
        hoverHdr = hdr; hoverRow = row
        removeCallbacks(dwell)
        if (hdr != 0 || row >= 0) mgr.haptic(true)
        val e = if (searching) null else rows.getOrNull(row)?.takeIf { it.kind == K_ENTRY }?.e
        if (e != null) {
            if (e.dir) ensure(e.key)   // start loading before the dwell fires so the expand feels instant
            postDelayed(dwell, DWELL_MS)
        }
    }

    private fun applyDwell() {
        if (searching) return
        val r = rows.getOrNull(hoverRow) ?: return
        val e = r.e ?: return
        val np = if (e.dir) path.take(r.level) + e else path.take(r.level)
        if (np.map { it.key } == path.map { it.key }) return
        path = np
        relayout(e.key)
        invalidate()
    }

    /** Finger lifted. Returns true when it was over the panel (the caller then keeps the tree open in touch mode). */
    fun release(): Boolean {
        removeCallbacks(dwell); setAuto(0)
        if (!armed) return true
        val inside = lastX >= 0f && lastX <= width && lastY >= 0f && lastY <= height
        if (!inside) return false
        val itm = item ?: return true
        when {
            hoverHdr == 1 -> if (searching) stopSearch() else onBack()
            hoverHdr == 2 -> onOpenFolder(itm, currentDir())
            hoverHdr == 3 -> startSearch()
            hoverRow >= 0 -> rows.getOrNull(hoverRow)?.let { activate(it, false) }
        }
        return true
    }

    private fun activate(r: Row, toggle: Boolean) {
        val itm = item ?: return
        when (r.kind) {
            K_DENIED -> if (Fs.isTreeUri(itm.v)) context.toast("Folder access expired. Remove and re-add this shortcut") else onNeedAccess()
            K_ENTRY -> {
                val e = r.e ?: return
                if (!e.dir) { onOpenFile(itm, e); return }
                if (searching) {
                    // jump into the tree at this folder
                    val chain = (r.chain ?: emptyList()) + e
                    stopSearch()
                    path = chain
                    ensure(rootKey); chain.forEach { ensure(it.key) }
                    rebuild(); scroll = (chain.size - 1).coerceAtLeast(0) * rowH; clampScroll(); invalidate()
                    return
                }
                val open = path.size > r.level && path[r.level].key == e.key
                val np = if (open && toggle) path.take(r.level) else path.take(r.level) + e
                if (np.map { it.key } != path.map { it.key }) {
                    path = np
                    ensure(e.key)
                    relayout(e.key)
                    invalidate()
                }
            }
        }
    }

    // ───── touch mode ─────
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastTY = e.y; tMode = 1
                parent?.requestDisallowInterceptTouchEvent(true)
                if (e.y >= headerH) { hoverRow = rowAt(e.y); hoverHdr = 0 } else { hoverHdr = hdrAt(e.x); hoverRow = -1 }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (tMode == 1 && hypot(e.x - downX, e.y - downY) > slop) { tMode = 2; hoverRow = -1; hoverHdr = 0 }
                if (tMode == 2) { scroll -= (e.y - lastTY) * 2.2f; clampScroll(); invalidate() }
                lastTY = e.y
            }
            MotionEvent.ACTION_UP -> {
                val wasTap = tMode == 1
                tMode = 0; hoverRow = -1; hoverHdr = 0
                if (wasTap) tap(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { tMode = 0; hoverRow = -1; hoverHdr = 0; invalidate() }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        val itm = item ?: return
        if (y < headerH) {
            when (hdrAt(x)) { 1 -> if (searching) stopSearch() else onBack(); 2 -> onOpenFolder(itm, currentDir()); 3 -> startSearch() }
            return
        }
        val r = rows.getOrNull(rowAt(y)) ?: return
        mgr.haptic(true)
        activate(r, true)
    }

    // ───── drawing ─────
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = mgr.popupSettings
        val rad = s.popRad * d
        rf.set(0f, 0f, w, h)
        bg.color = Color.argb((max(s.glass, 90f) / 100f * 255).toInt().coerceAtMost(255), 0, 0, 0)
        c.drawRoundRect(rf, rad, rad, bg)
        c.save()
        clip.reset(); clip.addRoundRect(rf, rad, rad, Path.Direction.CW); c.clipPath(clip)

        val solid = Fa.typeface(context, false)
        c.save()
        c.clipRect(0f, headerH, w, h)
        if (rows.isNotEmpty()) {
            val first = max(0, ((scroll - pad) / rowH).toInt())
            val last = min(rows.lastIndex, ((scroll - pad + h - headerH) / rowH).toInt())
            for (i in first..last) drawRow(c, i, w, solid)
        }
        c.restore()

        if (scroll > 1f) drawArrow(c, "\uf077", w / 2, headerH + 14 * d, autoDir == -1, solid)
        if (scroll < maxScroll() - 1f) drawArrow(c, "\uf078", w / 2, h - 14 * d, autoDir == 1, solid)
        drawHeader(c, w, solid)
        c.restore()
    }

    private fun drawArrow(c: Canvas, glyph: String, cx: Float, cy: Float, active: Boolean, solid: Typeface) {
        gp.typeface = solid; gp.textSize = 15 * d
        gp.color = if (active) ACCENT else Color.WHITE
        gp.setShadowLayer(3 * d, 0f, 1.5f * d, 0xD9000000.toInt())
        c.drawText(glyph, cx, cy - (gp.ascent() + gp.descent()) / 2, gp)
        gp.clearShadowLayer()
    }

    private fun drawHeader(c: Canvas, w: Float, solid: Typeface) {
        p.reset(); p.isAntiAlias = true
        p.color = 0xF8080808.toInt()
        c.drawRect(0f, 0f, w, headerH, p)
        p.color = 0x12FFFFFF
        c.drawRect(0f, headerH - d, w, headerH, p)
        val bs = 30 * d; val px = 10 * d
        for (b in 1..3) {
            if (searching && b != 1) continue
            val l = if (b == 1) px else if (b == 2) w - px - bs else w - px - 2 * bs - 6 * d
            val t = (headerH - bs) / 2
            rf.set(l, t, l + bs, t + bs)
            p.reset(); p.isAntiAlias = true
            p.color = if (hoverHdr == b) 0x59B3202B else 0x14FFFFFF
            c.drawRoundRect(rf, 10 * d, 10 * d, p)
            gp.typeface = solid; gp.textSize = 12 * d
            gp.color = if (hoverHdr == b) ACCENT else 0xFFECECEC.toInt()
            c.drawText(if (b == 1) (if (searching) "\uf00d" else "\uf053") else if (b == 2) "\uf08e" else "\uf002", rf.centerX(), rf.centerY() - (gp.ascent() + gp.descent()) / 2, gp)
        }
        if (searching) return
        val title = item?.n ?: "Folder"
        val full = item?.let { Fs.displayPath(it.v) } ?: ""
        val cur = path.lastOrNull()?.name
        val sub = if (cur != null) "$full \u203A $cur" else full
        val tx = px + bs + 8 * d; val tw = w - tx - (px + 2 * bs + 14 * d)
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = 12.5f * d; tp.color = 0xFFECECEC.toInt(); tp.isFakeBoldText = true
        val t1 = TextUtils.ellipsize(title, tp, tw, TextUtils.TruncateAt.END)
        c.drawText(t1, 0, t1.length, tx, headerH / 2 - 2 * d, tp)
        tp.isFakeBoldText = false
        tp.textSize = 9.5f * d; tp.color = 0x8CC8C8C8.toInt()
        val t2 = TextUtils.ellipsize(sub, tp, tw, TextUtils.TruncateAt.START)
        c.drawText(t2, 0, t2.length, tx, headerH / 2 + 11 * d, tp)
    }

    private fun drawRow(c: Canvas, i: Int, w: Float, solid: Typeface) {
        val r = rows[i]
        val top = headerH + pad + i * rowH - scroll
        val cy = top + rowH / 2
        val x0 = pad + r.level * indent

        val hov = i == hoverRow
        val e = r.e
        if (hov) {
            rf.set(4 * d, top + 1 * d, w - 4 * d, top + rowH - 1 * d)
            p.reset(); p.isAntiAlias = true
            p.color = 0x4DB3202B
            c.drawRoundRect(rf, 10 * d, 10 * d, p)
        }

        if (e == null) {
            val msg = r.msg ?: when (r.kind) {
                K_LOADING -> "Loading\u2026"
                K_EMPTY -> "Empty folder"
                else -> if (item?.let { Fs.isTreeUri(it.v) } == true) "Access expired. Re-add this folder" else "Tap to allow file access"
            }
            tp.textAlign = Paint.Align.LEFT; tp.isFakeBoldText = false
            tp.textSize = 12 * d
            tp.color = if (r.kind == K_DENIED) ACCENT else 0x8CC8C8C8.toInt()
            val t = TextUtils.ellipsize(msg, tp, w - x0 - 28 * d - 12 * d, TextUtils.TruncateAt.END)
            c.drawText(t, 0, t.length, x0 + 28 * d, cy - (tp.ascent() + tp.descent()) / 2, tp)
            return
        }
        if (searching) {
            gp.typeface = solid; gp.textSize = 14 * d
            gp.color = if (hov) ACCENT else if (e.dir) 0xFFECECEC.toInt() else fileColor(e.name)
            c.drawText(String(Character.toChars(if (e.dir) 0xf07b else fileGlyph(e.name))), x0 + 18 * d, cy - (gp.ascent() + gp.descent()) / 2, gp)
            val sx = x0 + 34 * d
            tp.textAlign = Paint.Align.LEFT; tp.isFakeBoldText = false
            tp.textSize = 12.5f * d; tp.color = if (hov) ACCENT else 0xE6F6EFE4.toInt()
            val n1 = TextUtils.ellipsize(e.name, tp, w - sx - 12 * d, TextUtils.TruncateAt.END)
            c.drawText(n1, 0, n1.length, sx, cy - 3 * d, tp)
            tp.textSize = 9f * d; tp.color = 0x8CC8C8C8.toInt()
            val sub = (r.chain ?: emptyList()).joinToString(" \u203A ") { it.name }.ifEmpty { "/" }
            val n2 = TextUtils.ellipsize(sub, tp, w - sx - 12 * d, TextUtils.TruncateAt.START)
            c.drawText(n2, 0, n2.length, sx, cy + 10 * d, tp)
            return
        }
        val open = e.dir && path.size > r.level && path[r.level].key == e.key

        // caret (left), icon, name – same order as the prototype
        if (e.dir) {
            gp.typeface = solid; gp.textSize = 9 * d
            gp.color = if (hov) ACCENT else 0x8CC8C8C8.toInt()
            c.drawText(if (open) "\uf078" else "\uf054", x0 + 6 * d, cy - (gp.ascent() + gp.descent()) / 2, gp)
        }
        val cp = if (e.dir) (if (open) 0xf07c else 0xf07b) else fileGlyph(e.name)
        gp.typeface = solid; gp.textSize = 14 * d
        gp.color = if (hov) ACCENT else if (e.dir) 0xFFECECEC.toInt() else 0xFF8F9BB3.toInt()
        c.drawText(String(Character.toChars(cp)), x0 + 22 * d, cy - (gp.ascent() + gp.descent()) / 2, gp)

        val nameX = x0 + 40 * d
        tp.textAlign = Paint.Align.LEFT; tp.isFakeBoldText = false
        tp.textSize = 12.5f * d
        tp.color = if (hov) ACCENT else 0xE6F6EFE4.toInt()
        val t = TextUtils.ellipsize(e.name, tp, w - nameX - 12 * d, TextUtils.TruncateAt.END)
        c.drawText(t, 0, t.length, nameX, cy - (tp.ascent() + tp.descent()) / 2, tp)
    }

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    private fun fileGlyph(name: String): Int = when (ext(name)) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg" -> 0xf1c5
        "pdf" -> 0xf1c1
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v" -> 0xf1c8
        "mp3", "wav", "flac", "m4a", "ogg", "aac", "opus" -> 0xf1c7
        "zip", "rar", "7z", "tar", "gz" -> 0xf1c6
        "doc", "docx", "odt" -> 0xf1c2
        "xls", "xlsx", "csv", "ods" -> 0xf1c3
        "ppt", "pptx", "odp" -> 0xf1c4
        "txt", "md", "log", "rtf" -> 0xf15c
        "kt", "java", "py", "js", "ts", "html", "css", "json", "xml", "sh", "c", "cpp", "h" -> 0xf1c9
        else -> 0xf15b
    }

    /** Black theme: every file type uses the same neutral tint. */
    private fun fileColor(name: String): Int = 0xFFB8C0D4.toInt()
}
