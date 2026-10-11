package com.quicklauncher.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.DocumentsContract
import android.util.Base64
import android.util.LruCache
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

/** Executes a shortcut. Returns true if something was started. */
object Launcher {
    fun launch(ctx: Context, it: Item, mgr: OverlayManager): Boolean = when (it.t) {
        "system" -> { SysActions.run(ctx, it.v, mgr); true }
        "app" -> app(ctx, it)
        "call" -> ctx.startSafe(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(it.v))))
        "directDial" -> {
            if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
                ctx.startSafe(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(it.v))))
            else {
                ctx.toast("Phone permission not granted, opening dialer")
                ctx.startSafe(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(it.v))))
            }
        }
        "sms" -> ctx.startSafe(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(it.v))))
        "email" -> ctx.startSafe(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + it.v)))
        "web" -> ctx.startSafe(Intent(Intent.ACTION_VIEW, Uri.parse(if (Regex("^[a-z]+://", RegexOption.IGNORE_CASE).containsMatchIn(it.v)) it.v else "https://" + it.v)))
        "intent" -> runCatching { Intent.parseUri(it.v, Intent.URI_INTENT_SCHEME) }
            .map { i -> i.selector = null; ctx.startSafe(i) }
            .getOrElse { ctx.toast("Invalid intent"); false }
        "folder" -> folder(ctx, it.v)
        "file" -> file(ctx, it.v)
        else -> false
    }

    private fun app(ctx: Context, it: Item): Boolean {
        val act = it.appAction
        if (!act.isNullOrBlank()) {
            if (act.startsWith("shortcut:")) {
                return try {
                    ctx.getSystemService(LauncherApps::class.java)
                        .startShortcut(it.v, act.removePrefix("shortcut:"), null, null, Process.myUserHandle()); true
                } catch (e: Exception) { ctx.toast("Shortcut unavailable (set as default launcher to use these)"); false }
            }
            runCatching { Intent.parseUri(act, 0) }.getOrNull()?.let { i -> return ctx.startSafe(i) }
        }
        val i = ctx.packageManager.getLaunchIntentForPackage(it.v)
        if (i == null) { ctx.toast("App not installed"); return false }
        return ctx.startSafe(i)
    }

    private fun folder(ctx: Context, v: String): Boolean = folderAt(ctx, v, null)

    /** Opens a folder in the file manager. [key] is a sub-folder (document id or absolute path) inside shortcut [v]; null = the shortcut's own folder. */
    fun folderAt(ctx: Context, v: String, key: String?): Boolean {
        val dir = DocumentsContract.Document.MIME_TYPE_DIR
        fun go(i: Intent): Boolean = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
            catch (e: Exception) { false }

        if (Fs.isTreeUri(v)) {
            // we hold a persisted grant for this tree, so passing it on is allowed
            val tree = Uri.parse(v)
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, key ?: DocumentsContract.getTreeDocumentId(tree))
            if (go(Intent(Intent.ACTION_VIEW).setDataAndType(uri, dir).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))) return true
            ctx.toast("No file manager can open this folder"); return false
        }

        // Raw path. Never add a URI grant flag for the externalstorage provider: we can't grant what we don't own,
        // and Android answers with a SecurityException ("Permission denied"). The system file picker app can open it by itself.
        val abs = Fs.normalizePath(key ?: v)
        val rel = abs.removePrefix("/storage/emulated/0").trim('/')
        val docUri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
        for (pkg in listOf("com.google.android.documentsui", "com.android.documentsui")) {
            if (go(Intent(Intent.ACTION_VIEW).setDataAndType(docUri, dir).setPackage(pkg))) return true
        }
        if (go(Intent(Intent.ACTION_VIEW).setDataAndType(docUri, dir))) return true
        // other file managers: hand over the folder through our own FileProvider (we can grant that one)
        runCatching { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(abs)) }.getOrNull()?.let { fp ->
            for (mime in listOf("resource/folder", dir))
                if (go(Intent(Intent.ACTION_VIEW).setDataAndType(fp, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))) return true
        }
        // last resort: the generic file browser
        if (go(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").putExtra("android.provider.extra.INITIAL_URI", docUri))) return true
        ctx.toast("No file manager can open this folder")
        return false
    }

    private fun file(ctx: Context, v: String): Boolean {
        val uri: Uri; val mime: String?
        if (v.startsWith("content://")) {
            uri = Uri.parse(v); mime = ctx.contentResolver.getType(uri)
        } else {
            val f = File(v)
            uri = runCatching { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f) }.getOrNull()
                ?: run { ctx.toast("File not accessible"); return false }
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
        }
        return ctx.startSafe(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "No app can open this file")
    }
}

/** Async bitmap cache for real app icons and user supplied custom images. Keeps drawing allocation-free. */
object Icons {
    private val cache = object : LruCache<String, Bitmap>(300) {}
    private val pending = HashSet<String>()
    private val exec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun peek(it: Item, ctx: Context, onReady: () -> Unit): Bitmap? {
        val key = key(it) ?: return null
        cache.get(key)?.let { return it }
        synchronized(pending) { if (!pending.add(key)) return null }
        val app = ctx.applicationContext
        exec.execute {
            val bmp = runCatching { if (it.customImg != null) decode(it.customImg) else appIcon(app, it.v) }.getOrNull()
            if (bmp != null) cache.put(key, bmp)
            synchronized(pending) { pending.remove(key) }
            if (bmp != null) main.post(onReady)
        }
        return null
    }

    private fun key(it: Item): String? = when {
        it.customImg != null -> "img:${it.id}:${it.customImg.length}:${it.customImg.hashCode()}"
        it.t == "app" && it.i == "app" -> "app:${it.v}"
        else -> null
    }

    private fun decode(b64: String): Bitmap? {
        val raw = Base64.decode(b64.substringAfter("base64,"), Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(raw, 0, raw.size)
    }

    private fun appIcon(ctx: Context, pkg: String): Bitmap? {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val size = 160
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size); d.draw(Canvas(bmp))
        return bmp
    }

    fun encodeCustom(bmp: Bitmap): String {
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }
}
