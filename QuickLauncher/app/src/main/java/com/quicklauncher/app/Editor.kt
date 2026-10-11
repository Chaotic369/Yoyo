package com.quicklauncher.app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

class EditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        enableEdgeToEdge()
        val id = intent.getStringExtra("id")
        val slot = intent.getIntExtra("slot", -1)
        setContent {
            AppTheme {
                val item = remember { Store.state.value.items.firstOrNull { it.id == id } }
                ShortcutEditor(item, if (slot >= 0) slot else Store.freeSlot()) { finish() }
            }
        }
    }
}

private data class QuickFolder(val label: String, val path: String, val icon: String)

private val QUICK_FOLDERS = listOf(
    QuickFolder("Internal storage", Fs.internalRoot(), "fa-solid fa-hard-drive"),
    QuickFolder("Downloads", Fs.internalRoot() + "/Download", "fa-solid fa-download"),
    QuickFolder("Camera", Fs.internalRoot() + "/DCIM", "fa-solid fa-camera"),
    QuickFolder("Pictures", Fs.internalRoot() + "/Pictures", "fa-solid fa-images"),
    QuickFolder("Documents", Fs.internalRoot() + "/Documents", "fa-solid fa-file-lines"),
    QuickFolder("Music", Fs.internalRoot() + "/Music", "fa-solid fa-music"),
)

private data class ShortcutOpt(val name: String, val action: String?, val create: ComponentName?)

private fun loadShortcuts(ctx: android.content.Context, pkg: String): List<ShortcutOpt> {
    val out = mutableListOf<ShortcutOpt>()
    runCatching {
        val la = ctx.getSystemService(LauncherApps::class.java)
        if (la.hasShortcutHostPermission()) {
            val q = LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            la.getShortcuts(q, Process.myUserHandle())?.forEach { out += ShortcutOpt(it.shortLabel?.toString() ?: it.id, "shortcut:" + it.id, null) }
        }
    }
    runCatching {
        val pm = ctx.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_CREATE_SHORTCUT).setPackage(pkg), 0).forEach {
            out += ShortcutOpt("Choose: " + it.loadLabel(pm), null, ComponentName(pkg, it.activityInfo.name))
        }
    }
    return out
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShortcutEditor(initial: Item?, slot: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember { Apps.list(ctx) }
    var type by remember { mutableStateOf(initial?.t ?: "app") }
    var name by remember { mutableStateOf(initial?.n ?: "") }
    var nameEdited by remember { mutableStateOf(initial != null) }
    var target by remember { mutableStateOf(if (initial != null && initial.t != "app" && initial.t != "system") initial.v else "") }
    var appPkg by remember { mutableStateOf(if (initial?.t == "app") initial.v else apps.firstOrNull()?.pkg ?: "") }
    var sysKey by remember { mutableStateOf(if (initial?.t == "system") initial.v else "screenoff") }
    var icon by remember { mutableStateOf(initial?.i ?: "app") }
    var color by remember { mutableStateOf(initial?.c ?: "#5b8def") }
    var custom by remember { mutableStateOf(initial?.customImg) }
    var touched by remember { mutableStateOf(initial != null) }
    var appAction by remember { mutableStateOf(initial?.appAction) }
    var actionName by remember { mutableStateOf(initial?.actionName) }
    var showPresets by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var showBatch by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<String>().apply { if (initial?.t == "group") addAll(initial.members) } }
    var showGroupPicker by remember { mutableStateOf(false) }
    var cropSrc by remember { mutableStateOf<Bitmap?>(null) }

    fun appLabel(pkg: String) = apps.firstOrNull { it.pkg == pkg }?.label ?: pkg
    fun defaults() {
        if (!touched) when (type) {
            "system" -> SYS[sysKey]?.let { icon = it.i; color = it.c }
            "app" -> { icon = "app"; color = SWATCHES[(appPkg.hashCode() and 0x7fffffff) % SWATCHES.size] }
            "group" -> { icon = "group"; color = TYPES.getValue("group").color }
            else -> TYPES[type]?.let { icon = it.icon; color = it.color }
        }
        if (!nameEdited) name = when (type) {
            "system" -> SYS[sysKey]?.n ?: ""
            "app" -> actionName ?: appLabel(appPkg)
            "group" -> "Apps"
            else -> ""
        }
    }

    val folderL = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            target = uri.toString()
            if (!nameEdited && name.isBlank()) name = Uri.decode(uri.lastPathSegment ?: "Folder").substringAfterLast(':').substringAfterLast('/').ifBlank { "Storage" }
            if (!touched) icon = "fa-solid fa-folder"
        }
    }
    val fileL = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            target = uri.toString()
            if (!nameEdited && name.isBlank()) name = runCatching {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null }
            }.getOrNull() ?: "File"
        }
    }
    val imageL = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }.getOrNull()?.let { cropSrc = it } ?: ctx.toast("Couldn't read that image")
    }
    var contactTick by remember { mutableStateOf(0) }
    var pickedNumber by remember { mutableStateOf<String?>(null) }
    val contactPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { contactTick++ }
    val shortcutL = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data ?: return@rememberLauncherForActivityResult
        val si: Intent? = if (Build.VERSION.SDK_INT >= 33) data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent::class.java)
        else @Suppress("DEPRECATION") data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT)
        if (si == null) { ctx.toast("That app returned no direct shortcut"); return@rememberLauncherForActivityResult }
        appAction = si.toUri(Intent.URI_INTENT_SCHEME)
        actionName = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)
        if (!nameEdited) name = actionName ?: name
    }

    val preview = Item("preview", type, name, "", icon, color, 0, customImg = custom).let {
        when (type) { "app" -> it.copy(v = appPkg); "group" -> it.copy(v = picked.joinToString(",")); else -> it }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 400.dp).heightIn(max = 720.dp), shape = RoundedCornerShape(26.dp), color = Color(0xFA0C0C0C)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (initial == null) "New shortcut" else "Edit shortcut", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (initial == null) SBtn("Add Multiple", color = Amber, size = 11.5f, icon = "fa-solid fa-layer-group", pad = PaddingValues(12.dp, 5.dp)) { showBatch = true }
                }
                Lbl("Type")
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    TYPES.values.toList().chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            row.forEach { t ->
                                val on = type == t.key
                                Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) Amber else Color(0x0FFFFFFF))
                                    .clickable {
                                        type = t.key; appAction = null; actionName = null
                                        if ((icon == "app" && type != "app") || (icon == "group" && type != "group")) touched = false
                                        defaults()
                                    }.padding(horizontal = 4.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    FaText(t.icon, 11.sp, if (on) AmberInk else Dim); Spacer(Modifier.width(5.dp))
                                    Text(t.label, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, color = if (on) AmberInk else Dim, maxLines = 1)
                                }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                Lbl("Name")
                QField(name, { name = it; nameEdited = true }, Modifier.fillMaxWidth(), "Shown under the icon")

                Lbl(when (type) { "system" -> "Action"; "app" -> "Application"; else -> "Target" })
                when (type) {
                    "app" -> {
                        Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(FieldBg).clickable { showApps = true }.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(appLabel(appPkg), fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            FaText("fa-solid fa-chevron-down", 11.sp, Dim)
                        }
                        val opts = remember(appPkg) { loadShortcuts(ctx, appPkg) }
                        if (opts.isNotEmpty()) {
                            Lbl("App Shortcuts", small = true)
                            opts.forEach { o ->
                                val sel = o.action != null && o.action == appAction
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) Acc.copy(alpha = .3f) else Color(0x0DFFFFFF))
                                    .clickable {
                                        if (o.create != null) shortcutL.launch(Intent(Intent.ACTION_CREATE_SHORTCUT).setComponent(o.create))
                                        else { appAction = o.action; actionName = o.name; if (!nameEdited) name = o.name }
                                    }.padding(12.dp, 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(o.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text(if (o.create != null) "Pick" else "Direct Shortcut", fontSize = 10.sp, color = Dim)
                                }
                            }
                        }
                        if (appAction != null) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Direct shortcut: ${actionName ?: "custom"}", fontSize = 11.sp, color = Amber, modifier = Modifier.weight(1f))
                            TextButton({ appAction = null; actionName = null }) { Text("Clear", fontSize = 11.sp, color = Amber) }
                        }
                    }
                    "group" -> Column {
                        Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(FieldBg).clickable { showGroupPicker = true }.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (picked.isEmpty()) "Select apps" else "${picked.size} app${if (picked.size == 1) "" else "s"} selected", fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            FaText("fa-solid fa-chevron-down", 11.sp, Dim)
                        }
                        if (picked.isNotEmpty()) {
                            FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 190.dp).verticalScroll(rememberScrollState())
                                .clip(RoundedCornerShape(14.dp)).background(Color(0x40000000)).padding(6.dp),
                                horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                picked.toList().forEach { pkg ->
                                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable { picked.remove(pkg) }) {
                                        TilePreview(Item("p", "app", appLabel(pkg), pkg, "app", "#5b8def", 0), 40)
                                    }
                                }
                            }
                            Text("Tap an app here to remove it. No limit on how many you add.", fontSize = 10.sp, color = Dim, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    "system" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SYS.values.forEach { d -> Chip(d.n, sysKey == d.key) { sysKey = d.key; defaults() } }
                    }
                    "folder", "file" -> Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            QField(target, { target = it }, Modifier.weight(1f), TYPES[type]?.hint ?: "")
                            Spacer(Modifier.width(8.dp))
                            SBtn("Browse", icon = "fa-solid fa-folder-open", pad = PaddingValues(14.dp, 13.dp)) { if (type == "folder") folderL.launch(null) else fileL.launch(arrayOf("*/*")) }
                        }
                        if (type == "folder") {
                            Lbl("Shortcut Presets", small = true)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                QUICK_FOLDERS.forEach { q ->
                                    Chip(q.label, target == q.path) {
                                        target = q.path
                                        if (!nameEdited) name = q.label
                                        if (!touched) icon = q.icon
                                        if (!Fs.hasFullAccess(ctx)) {
                                            ctx.toast("Turn on file access so the tree can open this folder")
                                            Fs.openAccessSettings(ctx)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "call", "directDial", "sms" -> Column {
                        QField(target, { target = it; pickedNumber = null }, Modifier.fillMaxWidth(), TYPES[type]?.hint ?: "",
                            keyboard = KeyboardOptions(keyboardType = KeyboardType.Phone))
                        val hasPerm = remember(contactTick) { Contacts.granted(ctx) }
                        if (!hasPerm) {
                            Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x0DFFFFFF))
                                .clickable { contactPerm.launch(android.Manifest.permission.READ_CONTACTS) }.padding(12.dp, 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                FaText("fa-solid fa-address-book", 12.sp, Amber); Spacer(Modifier.width(8.dp))
                                Text("Allow contacts to pick by name or number", fontSize = 12.sp, color = Amber, modifier = Modifier.weight(1f))
                            }
                        } else {
                            val fromName = target.isBlank()
                            val q = if (fromName) name else target
                            val hits = remember(q, contactTick) { Contacts.search(ctx, q) }
                            if (hits.isNotEmpty() && target != pickedNumber) {
                                Lbl("Contacts", small = true)
                                hits.forEach { h ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x0DFFFFFF))
                                        .clickable {
                                            target = h.number; pickedNumber = h.number
                                            if (name.isBlank() || fromName || !nameEdited) { name = h.name; nameEdited = true }
                                        }.padding(12.dp, 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                        FaText("fa-solid fa-user", 11.sp, Dim); Spacer(Modifier.width(10.dp))
                                        Text(h.name, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                                        Text(h.number, fontSize = 11.sp, color = Dim, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                    else -> QField(target, { target = it }, Modifier.fillMaxWidth(), TYPES[type]?.hint ?: "",
                        keyboard = KeyboardOptions(keyboardType = when (type) {
                            "email" -> KeyboardType.Email; "web" -> KeyboardType.Uri; else -> KeyboardType.Text }))
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ICON", fontSize = 11.5.sp, color = Dim, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.weight(1f))
                    SBtn("Custom Image", size = 11f, icon = "fa-solid fa-image", pad = PaddingValues(10.dp, 5.dp)) { imageL.launch("image/*") }
                    Spacer(Modifier.width(6.dp))
                    SBtn(if (showPresets) "Hide" else "Presets", size = 11f, icon = if (showPresets) "fa-solid fa-chevron-up" else "fa-solid fa-pen", pad = PaddingValues(10.dp, 5.dp)) { showPresets = !showPresets }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x0DFFFFFF)).padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TilePreview(preview, 36)
                    Spacer(Modifier.width(10.dp))
                    Text(if (custom != null) "Custom cropped image" else when (icon) { "app" -> "App icon"; "group" -> "Mini app grid"; "letter" -> "Monogram (Aa)"; else -> icon.substringAfterLast("fa-") }, fontSize = 12.sp, color = Dim, modifier = Modifier.weight(1f))
                    if (custom != null) TextButton({ custom = null }) { Text("Remove", fontSize = 11.sp, color = Amber) }
                }
                if (showPresets) {
                    val list = remember(type) { (if (type == "app") listOf("app") else if (type == "group") listOf("group") else emptyList()) + listOf("letter") + Fa.PRESETS }
                    FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 170.dp).verticalScroll(rememberScrollState())
                        .clip(RoundedCornerShape(14.dp)).background(Color(0x40000000)).padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        list.forEach { cls ->
                            val on = cls == icon && custom == null
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Amber.copy(alpha = .18f) else Color(0x12FFFFFF))
                                .clickable { icon = cls; custom = null; touched = true; showPresets = false }, contentAlignment = Alignment.Center) {
                                when (cls) {
                                    "letter" -> Text("Aa", fontWeight = FontWeight.Bold, color = if (on) Amber else Ink)
                                    "app" -> Text("App", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (on) Amber else Ink)
                                    "group" -> Text("Grid", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (on) Amber else Ink)
                                    else -> FaText(cls, 15.sp, if (on) Amber else Ink)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (initial != null) SBtn("Delete", color = Color(0xFFFF7B72), bg = Color(0x24E5534B), size = 13f, pad = PaddingValues(18.dp, 12.dp)) { Store.delete(initial.id); onClose() }
                    Spacer(Modifier.weight(1f))
                    SBtn("Cancel", size = 13f, pad = PaddingValues(18.dp, 12.dp), onClick = onClose)
                    SBtn("Save", color = Color.White, bg = Acc, size = 13f, pad = PaddingValues(18.dp, 12.dp), onClick = {
                        val v = when (type) { "system" -> sysKey; "app" -> appPkg; "group" -> picked.joinToString(","); else -> target.trim() }
                        if (v.isEmpty()) { ctx.toast(if (type == "group") "Select at least one app" else "Select a target first"); return@SBtn }
                        val nm = name.trim().ifEmpty { when (type) { "system" -> SYS[sysKey]?.n ?: "Action"; "app" -> actionName ?: appLabel(appPkg); "group" -> "Apps"; else -> v.substringAfterLast('/').take(16) } }
                        Store.upsert(Item(initial?.id ?: Store.newId(), type, nm, v, icon, color, initial?.slot ?: slot,
                            appAction = if (type == "app") appAction else null, actionName = if (type == "app") actionName else null, customImg = custom))
                        ctx.toast(if (initial != null) "Shortcut updated" else "Shortcut added")
                        onClose()
                    })
                }
            }
        }
    }

    if (showApps) AppPicker(apps, onDismiss = { showApps = false }) { a ->
        appPkg = a.pkg; appAction = null; actionName = null; showApps = false
        if (!nameEdited) name = a.label
        touched = initial != null && touched
        defaults()
    }
    if (showGroupPicker) MultiAppPicker(apps, picked.toList(), onDismiss = { showGroupPicker = false }) { list ->
        picked.clear(); picked.addAll(list); showGroupPicker = false
    }
    if (showBatch) BatchDialog(apps, onDismiss = { showBatch = false }) { showBatch = false; onClose() }
    cropSrc?.let { bmp ->
        CropDialog(bmp, { cropSrc = null }) { out -> custom = Icons.encodeCustom(out); touched = true; cropSrc = null }
    }
}

@Composable
private fun Lbl(t: String, small: Boolean = false) {
    Text(t.uppercase(), fontSize = if (small) 10.5.sp else 11.5.sp, color = Dim, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
        modifier = Modifier.padding(top = if (small) 8.dp else 14.dp, bottom = 6.dp))
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Amber else Color(0x0FFFFFFF)).clickable(onClick = onClick).padding(11.dp, 7.dp)) {
        Text(label, fontSize = 11.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, color = if (on) AmberInk else Dim)
    }
}

@Composable
private fun AppPicker(apps: List<AppInfo>, onDismiss: () -> Unit, onPick: (AppInfo) -> Unit) {
    var q by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 400.dp).heightIn(max = 600.dp), shape = RoundedCornerShape(26.dp), color = Color(0xFA0C0C0C)) {
            Column(Modifier.padding(16.dp)) {
                QField(q, { q = it }, Modifier.fillMaxWidth(), "Search apps")
                val shown = apps.filter { it.label.contains(q, true) || it.pkg.contains(q, true) }
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(shown, key = { it.pkg }) { a ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(a) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TilePreview(Item("p", "app", a.label, a.pkg, "app", "#5b8def", 0), 38)
                            Spacer(Modifier.width(12.dp))
                            Column { Text(a.label, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp); Text(a.pkg, fontSize = 10.5.sp, color = Dim, maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}

/** Multi-select app picker for App Groups: search, select all / clear, any number of apps. */
@Composable
private fun MultiAppPicker(apps: List<AppInfo>, selected: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    val picked = remember { mutableStateListOf<String>().apply { addAll(selected) } }
    var q by remember { mutableStateOf("") }
    val shown = remember(q) { apps.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 400.dp).heightIn(max = 660.dp), shape = RoundedCornerShape(26.dp), color = Color(0xFA0C0C0C)) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Select apps", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${picked.size} selected", color = Amber, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
                QField(q, { q = it }, Modifier.fillMaxWidth().padding(top = 10.dp), "Search apps")
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SBtn(if (q.isBlank()) "Select all" else "Select all shown", size = 11f, icon = "fa-solid fa-check", pad = PaddingValues(10.dp, 6.dp)) {
                        shown.forEach { if (it.pkg !in picked) picked.add(it.pkg) }
                    }
                    SBtn(if (q.isBlank()) "Clear" else "Clear shown", size = 11f, icon = "fa-solid fa-xmark", pad = PaddingValues(10.dp, 6.dp)) {
                        if (q.isBlank()) picked.clear() else picked.removeAll(shown.map { it.pkg }.toSet())
                    }
                }
                LazyColumn(Modifier.weight(1f, fill = false).padding(vertical = 10.dp)) {
                    items(shown, key = { it.pkg }) { a ->
                        val on = a.pkg in picked
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (on) Acc.copy(alpha = .25f) else Color(0x0AFFFFFF))
                            .border(1.dp, if (on) Acc else Color.Transparent, RoundedCornerShape(14.dp))
                            .clickable { if (on) picked.remove(a.pkg) else picked.add(a.pkg) }.padding(8.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TilePreview(Item("p", "app", a.label, a.pkg, "app", "#5b8def", 0), 38)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) { Text(a.label, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1); Text(a.pkg, fontSize = 10.5.sp, color = Dim, maxLines = 1) }
                            Checkbox(on, null, colors = CheckboxDefaults.colors(checkedColor = Acc, checkmarkColor = Color.White))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SBtn("Cancel", size = 13f, pad = PaddingValues(18.dp, 12.dp), onClick = onDismiss)
                    Spacer(Modifier.weight(1f))
                    SBtn("Done (${picked.size})", color = Color.White, bg = Acc, size = 13f, pad = PaddingValues(18.dp, 12.dp), onClick = { onDone(picked.toList()) })
                }
            }
        }
    }
}

@Composable
fun BatchDialog(apps: List<AppInfo>, onDismiss: () -> Unit, onAdded: () -> Unit) {
    val ctx = LocalContext.current
    val picked = remember { mutableStateListOf<String>() }
    val existing = remember { Store.state.value.items.filter { it.t == "app" }.map { it.v }.toSet() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 400.dp).heightIn(max = 640.dp), shape = RoundedCornerShape(26.dp), color = Color(0xFA0C0C0C)) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Add Multiple Apps", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${picked.size} selected", color = Amber, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
                LazyColumn(Modifier.weight(1f, fill = false).padding(vertical = 10.dp)) {
                    items(apps, key = { it.pkg }) { a ->
                        val on = a.pkg in picked
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (on) Acc.copy(alpha = .25f) else Color(0x0AFFFFFF))
                            .border(1.dp, if (on) Acc else Color.Transparent, RoundedCornerShape(14.dp))
                            .clickable { if (on) picked.remove(a.pkg) else picked.add(a.pkg) }.padding(8.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TilePreview(Item("p", "app", a.label, a.pkg, "app", "#5b8def", 0), 38)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) { Text(a.label, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp); Text(if (a.pkg in existing) "Already added" else "Tap to select", fontSize = 11.sp, color = Dim) }
                            Checkbox(on, null, colors = CheckboxDefaults.colors(checkedColor = Acc, checkmarkColor = Color.White))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SBtn("Cancel", size = 13f, pad = PaddingValues(18.dp, 12.dp), onClick = onDismiss)
                    Spacer(Modifier.weight(1f))
                    SBtn("Add Selected", color = Color.White, bg = Acc, size = 13f, pad = PaddingValues(18.dp, 12.dp), onClick = {
                        if (picked.isEmpty()) { ctx.toast("Select at least one app"); return@SBtn }
                        val taken = Store.state.value.items.map { it.slot }.toHashSet(); var cur = 0
                        val add = picked.mapIndexedNotNull { idx, pkg ->
                            val a = apps.firstOrNull { it.pkg == pkg } ?: return@mapIndexedNotNull null
                            while (cur in taken) cur++
                            taken += cur
                            Item(Store.newId(), "app", a.label, a.pkg, "app", SWATCHES[idx % SWATCHES.size], cur)
                        }
                        Store.update { it.copy(items = it.items + add) }
                        ctx.toast("${add.size} shortcuts added"); onAdded()
                    })
                }
            }
        }
    }
}

/** Pan / pinch / slider crop, same maths as the prototype (180 box inside a 240 canvas → 256px icon). */
@Composable
private fun CropDialog(bmp: Bitmap, onCancel: () -> Unit, onApply: (Bitmap) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    val unitPx = with(LocalDensity.current) { 240.dp.toPx() } / 240f
    val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
    val aspect = bw / bh
    val dw = if (aspect > 1) 240f * aspect else 240f
    val dh = if (aspect > 1) 240f else 240f / aspect

    fun matrix(): Matrix {
        val fw = dw * scale; val fh = dh * scale
        return Matrix().apply { setScale(fw / bw, fh / bh); postTranslate((240f - fw) / 2 + ox, (240f - fh) / 2 + oy) }
    }

    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(26.dp), color = Color(0xFA0C0C0C)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Crop Icon", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Canvas(Modifier.padding(vertical = 14.dp).size(240.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF0D0918))
                    .pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 4f); ox += pan.x / unitPx; oy += pan.y / unitPx } }) {
                    drawIntoCanvas { c ->
                        val n = c.nativeCanvas
                        val m = matrix().apply { postScale(unitPx, unitPx) }
                        n.drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG))
                        val dim = Path().apply {
                            fillType = Path.FillType.EVEN_ODD
                            addRect(0f, 0f, 240f * unitPx, 240f * unitPx, Path.Direction.CW)
                            addRect(30f * unitPx, 30f * unitPx, 210f * unitPx, 210f * unitPx, Path.Direction.CW)
                        }
                        n.drawPath(dim, Paint().apply { color = 0x73000000 })
                        n.drawRect(30f * unitPx, 30f * unitPx, 210f * unitPx, 210f * unitPx, Paint().apply {
                            color = 0xD9FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * unitPx })
                    }
                }
                Slider(scale, { scale = it }, valueRange = 1f..4f, colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Color(0x26FFFFFF), inactiveTrackColor = Color(0x26FFFFFF)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SBtn("Cancel", size = 13f, pad = PaddingValues(18.dp, 12.dp), onClick = onCancel)
                    Spacer(Modifier.weight(1f))
                    SBtn("Apply Icon", color = Color.White, bg = Acc, size = 13f, pad = PaddingValues(18.dp, 12.dp), onClick = {
                        val out = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                        val m = matrix().apply { postTranslate(-30f, -30f); postScale(256f / 180f, 256f / 180f) }
                        android.graphics.Canvas(out).drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG))
                        onApply(out)
                    })
                }
            }
        }
    }
}
