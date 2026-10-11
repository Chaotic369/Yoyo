package com.quicklauncher.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Same JSON shape as the HTML prototype export, so backups are interchangeable. */
@Serializable
data class Item(
    val id: String,
    val t: String = "app",
    val n: String = "",
    val v: String = "",
    /** FontAwesome class, "letter" (monogram) or "app" (real app icon). */
    val i: String = "app",
    val c: String = "#5b8def",
    val slot: Int = 0,
    val appAction: String? = null,
    val actionName: String? = null,
    val customImg: String? = null,
)

@Serializable
data class Settings(
    val icon: Float = 46f,
    val radius: Float = 28f,
    val cols: Int = 4,
    val rows: Int = 5,
    val cgap: Float = 8f,
    val rgap: Float = 10f,
    val pad: Float = 10f,
    val lbl: Float = 9.5f,
    val showLbl: Boolean = true,
    val w: Float = 280f,
    val h: Float = 420f,
    val glass: Float = 74f,
    val popRad: Float = 26f,
    val trigH: Float = 72f,
    val trigW: Float = 5f,
    val trigY: Float = 50f,
    val trigEdge: String = "right",
    val haptic: Boolean = true,
    val autoClose: Boolean = true,
    val ringSize: Float = 42f,
    val ringThick: Float = 2.5f,
    val ringOpacity: Float = 40f,
    val triggerEnabled: Boolean = true,
    val hideTrigger: Boolean = false,
    val trigOpacity: Float = 80f,
    val dwellDelay: Float = 300f,
    val disableInLandscape: Boolean = false,
    val cornerSlider: Boolean = true,
    /** "default" | "circle" | "squircle" | "square" – overrides icon roundness. */
    val shapeMask: String = "default",
    /** Remembered popup top (dp) when the user moved it in edit mode; null = follow trigger. */
    val popTop: Float? = null,
    /** Popup covers the whole screen (edge to edge, bigger icons) instead of a card beside the trigger. */
    val immersive: Boolean = false,
) {
    val isLeft get() = trigEdge == "left"
    /** Icon corner radius in percent, honouring the shape mask. */
    val effRadius: Float get() = when (shapeMask) { "circle" -> 50f; "squircle" -> 32f; "square" -> 18f; else -> radius }
}

@Serializable
data class AppState(
    val items: List<Item> = emptyList(),
    @SerialName("set") val settings: Settings = Settings(),
)

class Ctl(
    val key: String, val label: String, val min: Float, val max: Float, val step: Float, val unit: String,
    val get: (Settings) -> Float, val put: (Settings, Float) -> Settings,
)

val CTRLS: List<Ctl> = listOf(
    Ctl("icon", "Icon size", 30f, 78f, 1f, "px", { it.icon }, { s, v -> s.copy(icon = v) }),
    Ctl("radius", "Icon roundness", 0f, 50f, 1f, "%", { it.radius }, { s, v -> s.copy(radius = v) }),
    Ctl("cols", "Columns", 2f, 7f, 1f, "", { it.cols.toFloat() }, { s, v -> s.copy(cols = v.toInt()) }),
    Ctl("rows", "Rows", 2f, 50f, 1f, "", { it.rows.toFloat() }, { s, v -> s.copy(rows = v.toInt()) }),
    Ctl("cgap", "Column spacing", 0f, 34f, 1f, "px", { it.cgap }, { s, v -> s.copy(cgap = v) }),
    Ctl("rgap", "Row spacing", 0f, 38f, 1f, "px", { it.rgap }, { s, v -> s.copy(rgap = v) }),
    Ctl("pad", "Popup padding", 4f, 34f, 1f, "px", { it.pad }, { s, v -> s.copy(pad = v) }),
    Ctl("lbl", "Label size", 7f, 15f, .5f, "px", { it.lbl }, { s, v -> s.copy(lbl = v) }),
    Ctl("w", "Popup width", 240f, 420f, 1f, "px", { it.w }, { s, v -> s.copy(w = v) }),
    Ctl("h", "Popup height", 260f, 780f, 1f, "px", { it.h }, { s, v -> s.copy(h = v) }),
    Ctl("glass", "Glass opacity", 30f, 100f, 1f, "%", { it.glass }, { s, v -> s.copy(glass = v) }),
    Ctl("popRad", "Popup corner radius", 0f, 40f, 1f, "px", { it.popRad }, { s, v -> s.copy(popRad = v) }),
    Ctl("trigH", "Trigger length", 40f, 1000f, 4f, "px", { it.trigH }, { s, v -> s.copy(trigH = v) }),
    Ctl("trigW", "Trigger width", 3f, 20f, 1f, "px", { it.trigW }, { s, v -> s.copy(trigW = v) }),
    Ctl("trigY", "Trigger location", 10f, 90f, 1f, "%", { it.trigY }, { s, v -> s.copy(trigY = v) }),
    Ctl("ringSize", "Ring diameter", 30f, 90f, 1f, "px", { it.ringSize }, { s, v -> s.copy(ringSize = v) }),
    Ctl("ringThick", "Ring thickness", 1f, 8f, .5f, "px", { it.ringThick }, { s, v -> s.copy(ringThick = v) }),
    Ctl("ringOpacity", "Ring opacity", 10f, 100f, 2f, "%", { it.ringOpacity }, { s, v -> s.copy(ringOpacity = v) }),
    Ctl("trigOpacity", "Trigger opacity", 0f, 100f, 5f, "%", { it.trigOpacity }, { s, v -> s.copy(trigOpacity = v) }),
    Ctl("dwellDelay", "Hover dwell delay", 150f, 600f, 25f, "ms", { it.dwellDelay }, { s, v -> s.copy(dwellDelay = v) }),
)
val CTRL_MAP = CTRLS.associateBy { it.key }

/** Grouped exactly like the prototype's settings tabs. */
val TAB_KEYS = mapOf(
    "grid" to listOf("cols", "rows", "cgap", "rgap", "pad"),
    "icons" to listOf("icon", "radius", "lbl"),
    "popup" to listOf("w", "h", "glass", "popRad"),
    "trigger" to listOf("trigH", "trigW", "trigY", "trigOpacity", "dwellDelay"),
    "folder" to listOf("dwellDelay"),
    "ring" to listOf("ringSize", "ringThick", "ringOpacity"),
)
val TAB_TITLES = mapOf(
    "grid" to "Grid Management", "icons" to "Icon Size & Style", "popup" to "Pop-up Size & Styling",
    "trigger" to "Trigger Settings", "folder" to "Folder Hover & Dwell", "ring" to "Selection Ring (Zone Pointer)",
)

data class SysDef(val key: String, val n: String, val i: String, val c: String, val toggle: Boolean = false)

val SYS: LinkedHashMap<String, SysDef> = linkedMapOf(
    "screenoff" to SysDef("screenoff", "Screen off", "fa-solid fa-moon", "#3a3f66"),
    "power" to SysDef("power", "Power menu", "fa-solid fa-power-off", "#d4574f"),
    "lock" to SysDef("lock", "Lock screen", "fa-solid fa-lock", "#5f6b8a"),
    "shot" to SysDef("shot", "Screenshot", "fa-solid fa-expand", "#3d8f9e"),
    "recents" to SysDef("recents", "Recents", "fa-solid fa-clone", "#5c6ad6"),
    "notif" to SysDef("notif", "Notifications", "fa-solid fa-bell", "#c9843a"),
    "home" to SysDef("home", "Home", "fa-solid fa-house", "#4a8f6a"),
    "back" to SysDef("back", "Back", "fa-solid fa-chevron-left", "#6b6f80"),
    "volume" to SysDef("volume", "Volume", "fa-solid fa-volume-high", "#7a58d6"),
    "bright" to SysDef("bright", "Brightness", "fa-solid fa-sun", "#e0a22e"),
    "wifi" to SysDef("wifi", "Wi-Fi", "fa-solid fa-wifi", "#3b82d6", true),
    "bt" to SysDef("bt", "Bluetooth", "fa-brands fa-bluetooth-b", "#3563c9", true),
    "flash" to SysDef("flash", "Flashlight", "fa-solid fa-lightbulb", "#d6a21f", true),
    "air" to SysDef("air", "Airplane", "fa-solid fa-plane", "#5e7ea6", true),
    "rot" to SysDef("rot", "Auto-rotate", "fa-solid fa-rotate", "#4d9a86", true),
    "dnd" to SysDef("dnd", "Do not disturb", "fa-solid fa-bell-slash", "#8a5bc2", true),
    "hotspot" to SysDef("hotspot", "Hotspot", "fa-solid fa-tower-broadcast", "#e65100", true),
    "mute" to SysDef("mute", "Mute / Silent", "fa-solid fa-volume-xmark", "#c2185b", true),
)

data class TypeDef(val key: String, val label: String, val color: String, val icon: String, val hint: String)

val TYPES: LinkedHashMap<String, TypeDef> = linkedMapOf(
    "app" to TypeDef("app", "App", "#5b8def", "fa-solid fa-cube", "Pick an installed app"),
    "group" to TypeDef("group", "App Group", "#1b1b1f", "fa-solid fa-layer-group", "Pick any number of apps"),
    "call" to TypeDef("call", "Call", "#3cc47c", "fa-solid fa-phone", "Phone number (opens dialer)"),
    "directDial" to TypeDef("directDial", "Direct Dial", "#2fb872", "fa-solid fa-phone-flip", "Phone number (calls immediately)"),
    "sms" to TypeDef("sms", "SMS", "#26a69a", "fa-solid fa-comment-sms", "Phone number"),
    "email" to TypeDef("email", "Email", "#ea4335", "fa-solid fa-envelope", "name@domain.com"),
    "web" to TypeDef("web", "Website", "#4aa3ff", "fa-solid fa-globe", "https://example.com"),
    "intent" to TypeDef("intent", "Custom Intent", "#9c27b0", "fa-solid fa-terminal", "intent:#Intent;action=...;end  or URI"),
    "folder" to TypeDef("folder", "Folder", "#f0a93b", "fa-solid fa-folder-open", "Tap Browse to pick a folder"),
    "file" to TypeDef("file", "File", "#8f9bb3", "fa-solid fa-file-lines", "Tap Browse to pick a file"),
    "system" to TypeDef("system", "System", "#6c63ff", "fa-solid fa-sliders", ""),
)

val SWATCHES = listOf("#5b8def", "#2fb872", "#f0a93b", "#f2685b", "#a06bf0", "#e24d9b", "#4aa3ff", "#3f8f87", "#6c63ff", "#7a8699", "#d9a62e", "#2a2438")

/** Package names inside an App Group shortcut (type "group"), in the order the user picked them. Unlimited. */
val Item.members: List<String>
    get() = if (t == "group") v.split(',').map { it.trim() }.filter { it.isNotEmpty() } else emptyList()

/** The settings the popup really draws with: in immersive mode icons and labels grow and the corners go square. */
fun Settings.forPopup(): Settings =
    if (!immersive) this
    else copy(icon = (icon * 1.3f).coerceAtMost(88f), lbl = lbl * 1.15f, pad = pad + 6f, popRad = 0f, glass = glass.coerceAtLeast(88f))
