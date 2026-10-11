package com.quicklauncher.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner

val Bg = Color(0xFF050505)
val Panel = Color(0xFF0D0D0D)
val Card = Color(0xCC121212)
/** Red accent (popup, edit handles, primary buttons, section icons). */
val Acc = Color(0xFFB3202B)
/** Amber accent (toggles, sliders, active tabs, selected chips). */
val Amber = Color(0xFFFFBF00)
val AmberInk = Color(0xFF1C1304)
val Dim = Color(0x8CC8C8C8)
val Ink = Color(0xFFECECEC)
val Faint = Color(0x0FFFFFFF)
val Btn = Color(0x17FFFFFF)
val FieldBg = Color(0x14FFFFFF)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val Bricolage = FontFamily(
    Font(R.font.bricolage, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.bricolage, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.bricolage, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.bricolage, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private fun Typography.withFont(f: FontFamily) = Typography(
    displayLarge.copy(fontFamily = f), displayMedium.copy(fontFamily = f), displaySmall.copy(fontFamily = f),
    headlineLarge.copy(fontFamily = f), headlineMedium.copy(fontFamily = f), headlineSmall.copy(fontFamily = f),
    titleLarge.copy(fontFamily = f), titleMedium.copy(fontFamily = f), titleSmall.copy(fontFamily = f),
    bodyLarge.copy(fontFamily = f), bodyMedium.copy(fontFamily = f), bodySmall.copy(fontFamily = f),
    labelLarge.copy(fontFamily = f), labelMedium.copy(fontFamily = f), labelSmall.copy(fontFamily = f),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Acc, onPrimary = Color.White, background = Bg, surface = Panel,
        onSurface = Ink, onBackground = Ink, surfaceVariant = Color(0xFF1B1B1B), outline = Color(0x33FFFFFF),
    )
    MaterialTheme(colorScheme = scheme, typography = Typography().withFont(Bricolage)) {
        CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = Bricolage, color = Ink)) { content() }
    }
}

class MainActivity : ComponentActivity() {
    private val fromPop = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        enableEdgeToEdge()
        fromPop.value = intent.getBooleanExtra("from_pop", false)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            AppTheme {
                SettingsScreen(fromPop.value) {
                    Host.manager?.reopenTouch()
                    fromPop.value = false
                    moveTaskToBack(true)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fromPop.value = intent.getBooleanExtra("from_pop", false)
    }

    override fun onResume() { super.onResume(); Host.ensure(this) }
}

private val TABS = listOf("general" to "Overview", "items" to "Shortcuts", "grid" to "Grid", "icons" to "Icons",
    "popup" to "Popup", "trigger" to "Trigger", "folder" to "Folder", "ring" to "Selection Ring")

// ───────── shared building blocks (same look as the HTML design) ─────────

@Composable
fun Tg(on: Boolean, onChange: (Boolean) -> Unit) {
    val x = if (on) 20.dp else 0.dp
    Box(Modifier.size(44.dp, 24.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Amber else Color(0x2EFFFFFF))
        .clickable { onChange(!on) }.padding(3.dp)) {
        Box(Modifier.offset(x = x).size(18.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
fun SBtn(label: String, modifier: Modifier = Modifier, color: Color = Ink, bg: Color = Btn, size: Float = 12.5f,
         pad: PaddingValues = PaddingValues(16.dp, 10.dp), icon: String? = null, iconColor: Color = color, onClick: () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick).padding(pad),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) { FaText(icon, (size - .5f).sp, iconColor); Spacer(Modifier.width(7.dp)) }
        Text(label, fontSize = size.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

/** Flat text field: soft fill, no outline. */
@Composable
fun QField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "",
           keyboard: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default) {
    Box(modifier.height(44.dp).clip(RoundedCornerShape(14.dp)).background(FieldBg).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, fontSize = 14.sp, color = Dim, maxLines = 1)
        androidx.compose.foundation.text.BasicTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 14.sp, fontFamily = Bricolage),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Amber), keyboardOptions = keyboard)
    }
}

@Composable
fun SetCard(title: String, icon: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(18.dp)).background(Card).padding(14.dp, 14.dp, 14.dp, 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            FaText(icon, 13.sp, Acc); Spacer(Modifier.width(10.dp)); Text(title, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
        }
        content()
    }
}

@Composable
fun FaText(cls: String, size: androidx.compose.ui.unit.TextUnit, color: Color) {
    val ctx = LocalContext.current
    val g = Fa.glyph(cls) ?: return
    val tf = remember(g.brands) { Fa.typeface(ctx, g.brands) }
    AndroidView(factory = { c -> android.widget.TextView(c).apply { typeface = tf; includeFontPadding = false } },
        update = { it.text = g.text; it.textSize = size.value; it.setTextColor(color.toArgb()) })
}

@Composable
fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (sub.isNotEmpty()) Text(sub, fontSize = 11.sp, color = Dim)
        }
        Tg(checked, onChange)
    }
}

@Composable
fun SliderRow(label: String, v: Float, min: Float, max: Float, step: Float, unit: String, onChange: (Float) -> Unit) {
    Column(Modifier.padding(bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text((if (v % 1f == 0f) v.toInt().toString() else v.toString()) + unit, color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Slider(
            value = v, onValueChange = { nv -> onChange((Math.round(nv / step) * step).coerceIn(min, max)) },
            valueRange = min..max, steps = ((max - min) / step).toInt() - 1,
            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Color(0x26FFFFFF), inactiveTrackColor = Color(0x26FFFFFF),
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
        )
    }
}

@Composable
fun ConfirmDialog(name: String, onCancel: () -> Unit, onOk: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Surface(Modifier.widthIn(max = 230.dp), shape = RoundedCornerShape(18.dp), color = Color(0xFA0C0C0C)) {
            Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Delete \"$name\"?", fontSize = 12.sp, color = Dim, modifier = Modifier.padding(bottom = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SBtn("Cancel", Modifier.weight(1f), size = 11f, pad = PaddingValues(8.dp, 7.dp), onClick = onCancel)
                    SBtn("Delete", Modifier.weight(1f), color = Color.White, bg = Color(0xFFE5534B), size = 11f, pad = PaddingValues(8.dp, 7.dp), onClick = onOk)
                }
            }
        }
    }
}

// ───────── settings screen ─────────

@Composable
fun SettingsScreen(fromPop: Boolean, onDone: () -> Unit) {
    val st by Store.state.collectAsState()
    var tab by remember { mutableStateOf("general") }
    var editing by remember { mutableStateOf<Pair<Item?, Int>?>(null) }
    var showBatch by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Item?>(null) }
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
        if (fromPop) Row(Modifier.fillMaxWidth().padding(14.dp, 12.dp, 14.dp, 2.dp), horizontalArrangement = Arrangement.End) {
            SBtn("Done", color = Color.White, bg = Acc, pad = PaddingValues(16.dp, 6.dp), icon = "fa-solid fa-check", onClick = onDone)
        }
        AndroidView(
            factory = { PreviewView(it) },
            update = { it.bind(st, tab == "ring") },
            modifier = Modifier.padding(14.dp, if (fromPop) 4.dp else 14.dp, 14.dp, 4.dp).fillMaxWidth().height(245.dp),
        )
        Column(Modifier.fillMaxWidth().background(Color(0x59000000)).padding(14.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TABS.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (k, label) ->
                        val on = tab == k
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (on) Amber else Color(0x08FFFFFF))
                                .clickable { tab = k }.padding(horizontal = 2.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, fontSize = 11.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, color = if (on) Color.Black else Dim, maxLines = 1) }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (tab == "items") {
            Column(Modifier.weight(1f).padding(14.dp, 12.dp, 14.dp, 14.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SBtn("Add Shortcut", Modifier.weight(1f), color = AmberInk, bg = Amber, icon = "fa-solid fa-plus", pad = PaddingValues(12.dp, 12.dp)) { editing = null to Store.freeSlot() }
                    SBtn("Add Multiple", color = Amber, icon = "fa-solid fa-layer-group", pad = PaddingValues(14.dp, 12.dp)) { showBatch = true }
                }
                ItemsGrid(st, Modifier.weight(1f).fillMaxWidth(), onEdit = { i, slot -> editing = i to slot }, del = { confirm = it })
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp, 12.dp, 14.dp, 28.dp)) {
                if (tab == "general") General(st) else SliderTab(tab, st.settings)
            }
        }
    }
    editing?.let { (item, slot) -> ShortcutEditor(item, slot) { editing = null } }
    if (showBatch) BatchDialog(Apps.list(ctx), onDismiss = { showBatch = false }) { showBatch = false }
    confirm?.let { c -> ConfirmDialog(c.n, { confirm = null }) { Store.delete(c.id); confirm = null; ctx.toast("Shortcut removed") } }
}

/** The popup grid in edit mode, exactly as drawn on the overlay: tap to edit, x to delete, long-press and drag to reorder. */
@Composable
fun ItemsGrid(st: AppState, modifier: Modifier, onEdit: (Item?, Int) -> Unit, del: (Item) -> Unit) {
    AndroidView(
        factory = { c -> PopupCardView(c).apply { r.edit = true; r.cardColor = 0xCC121212.toInt(); r.cardRad = 18f } },
        update = { v ->
            v.listener = object : PopupCardView.Listener {
                override fun onTap(item: Item) = onEdit(item, item.slot)
                override fun onDelete(item: Item) = del(item)
                override fun onAdd(slot: Int) = onEdit(null, slot)
                override fun onMoved(id: String, slot: Int) = Store.moveToSlot(id, slot)
            }
            v.r.setData(st.settings, st.items); v.invalidate()
        },
        modifier = modifier,
    )
}

@Composable
fun General(st: AppState) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    val s = st.settings
    val phonePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val filesPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        tick++
        if (Build.VERSION.SDK_INT >= 30 && !Fs.isManager()) Fs.openAccessSettings(ctx)
    }
    val exportL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(Store.exportJson().toByteArray()) } }
            .onSuccess { ctx.toast("Configuration exported") }.onFailure { ctx.toast("Export failed") }
    }
    val importL = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val ok = runCatching { ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } }.getOrNull()?.let { Store.importJson(it) } == true
            ctx.toast(if (ok) "Backup restored" else "Invalid launcher backup file")
        }
    }
    key(tick) {
        SetCard("Master Trigger & Behavior", "fa-solid fa-toggle-on") {
            SwitchRow("Floating Trigger Handle", "Show side trigger on screen edge", s.triggerEnabled) { v ->
                Store.updateSettings { it.copy(triggerEnabled = v) }
                if (v) Host.ensure(ctx)
            }
            SliderRow("Trigger Opacity", s.trigOpacity, 0f, 100f, 5f, "%") { v -> Store.updateSettings { it.copy(trigOpacity = v) } }
            SwitchRow("Haptic Vibration", "Tick on hover, firm pulse on launch", s.haptic) { v -> Store.updateSettings { it.copy(haptic = v) } }
            SwitchRow("Auto-close on launch", "Dismiss launcher when opening an item", s.autoClose) { v -> Store.updateSettings { it.copy(autoClose = v) } }
            SwitchRow("Disable in landscape", "Hide trigger during horizontal view", s.disableInLandscape) { v -> Store.updateSettings { it.copy(disableInLandscape = v) } }
        }
        SetCard("Backup & Restore", "fa-solid fa-database") {
            Text("Save your layout, triggers, shortcuts, and custom icons to a JSON file or restore a previous export.", fontSize = 12.sp, color = Dim, lineHeight = 17.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                SBtn("Export JSON", Modifier.weight(1f), icon = "fa-solid fa-file-export", pad = PaddingValues(10.dp)) { exportL.launch("quick_launcher_backup_${System.currentTimeMillis()}.json") }
                SBtn("Import JSON", Modifier.weight(1f), icon = "fa-solid fa-file-import", pad = PaddingValues(10.dp)) { importL.launch(arrayOf("application/json", "text/*", "*/*")) }
            }
        }
        SetCard("System Permissions", "fa-solid fa-shield-halved") {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            SwitchRow("Display Over Other Apps", "Floating trigger & popup (when accessibility is off)", Host.canDrawOverlays(ctx)) {
                ctx.startSafe(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }
            SwitchRow("Accessibility Service", "Overlay without extra permission · lock, recents, screenshot, power", Host.accessibilityEnabled(ctx)) {
                ctx.startSafe(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            SwitchRow("Modify System Settings", "Brightness slider and auto-rotate", AndroidSettings.System.canWrite(ctx)) {
                ctx.startSafe(Intent(AndroidSettings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }
            SwitchRow("Do Not Disturb Access", "Do-not-disturb and silent toggles", nm.isNotificationPolicyAccessGranted) {
                ctx.startSafe(Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
            SwitchRow("Files & Wallpaper Access", "Browse folders in the tree and show your home wallpaper in the preview", Fs.hasFullAccess(ctx)) {
                if (Build.VERSION.SDK_INT < 33 && ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                    filesPerm.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                else Fs.openAccessSettings(ctx)
            }
            SwitchRow("Phone", "Required for Direct Dial shortcuts", ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                phonePerm.launch(Manifest.permission.CALL_PHONE)
            }
        }
    }
}

/** Renders an item tile using the same canvas code as the popup. */
@Composable
fun TilePreview(item: Item, sizeDp: Int) {
    AndroidView(
        factory = { c -> TileView(c, sizeDp) },
        update = { v -> v.item = item; v.invalidate() },
        modifier = Modifier.size(sizeDp.dp),
    )
}

@Composable
fun SliderTab(tab: String, s: Settings) {
    val keys = TAB_KEYS[tab] ?: return
    SetCard(TAB_TITLES[tab] ?: "", "fa-solid fa-sliders") {
        keys.mapNotNull { CTRL_MAP[it] }.forEach { c ->
            SliderRow(c.label, c.get(s), c.min, c.max, c.step, c.unit) { v -> Store.updateSettings { c.put(it, v) } }
        }
        if (tab == "popup") SwitchRow("Immersive mode", "Popup covers the whole screen with bigger icons (Width / Height are ignored)", s.immersive) { v -> Store.updateSettings { it.copy(immersive = v) } }
        if (tab == "icons") SwitchRow("Show labels", "", s.showLbl) { v -> Store.updateSettings { it.copy(showLbl = v) } }
        if (tab == "trigger") {
            SwitchRow("Hide trigger indicator", "", s.hideTrigger) { v -> Store.updateSettings { it.copy(hideTrigger = v) } }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Screen Edge Placement", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                listOf("left" to "Left", "right" to "Right").forEach { (k, l) ->
                    val on = s.trigEdge == k
                    Text(l, color = if (on) Amber else Dim, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, fontSize = 11.5.sp,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { Store.updateSettings { it.copy(trigEdge = k) } }.padding(14.dp, 9.dp))
                }
            }
            Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x0DFFFFFF)).padding(12.dp)) {
                Text(androidx.compose.ui.text.buildAnnotatedString {
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = Acc, fontWeight = FontWeight.Bold)); append("Puller & Resizers: "); pop()
                    append("When in Edit mode, use the arrow puller tab to drag the trigger anywhere, the top/bottom bars to stretch length, and the side bar to adjust width.")
                }, fontSize = 12.sp, color = Dim, lineHeight = 17.sp)
            }
        }
        if (tab == "folder") {
            SwitchRow("Corner Fast Slider", "Slide along the popup corner to quick-jump folders", s.cornerSlider) { v -> Store.updateSettings { it.copy(cornerSlider = v) } }
        }
    }
    SBtn("Reset Tab to Defaults", Modifier.fillMaxWidth(), pad = PaddingValues(11.dp)) {
        Store.updateSettings { cur ->
            var n = cur; val def = Settings()
            keys.mapNotNull { CTRL_MAP[it] }.forEach { n = it.put(n, it.get(def)) }
            if (tab == "icons") n = n.copy(showLbl = def.showLbl)
            if (tab == "popup") n = n.copy(immersive = def.immersive)
            if (tab == "trigger") n = n.copy(trigEdge = def.trigEdge, hideTrigger = def.hideTrigger)
            if (tab == "folder") n = n.copy(cornerSlider = def.cornerSlider)
            n
        }
    }
}
