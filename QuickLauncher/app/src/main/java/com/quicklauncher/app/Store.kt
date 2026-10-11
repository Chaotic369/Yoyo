package com.quicklauncher.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

data class AppInfo(val label: String, val pkg: String)

object Apps {
    @Volatile private var cache: List<AppInfo>? = null

    fun list(ctx: Context, refresh: Boolean = false): List<AppInfo> {
        if (!refresh) cache?.let { return it }
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val out = pm.queryIntentActivities(intent, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
        cache = out
        return out
    }
}

object Store {
    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true; coerceInputValues = true }
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state
    private val handler = Handler(Looper.getMainLooper())
    private val persist = Runnable { prefs.edit().putString("state", json.encodeToString(_state.value)).apply() }

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("quick_launcher", Context.MODE_PRIVATE)
        val raw = prefs.getString("state", null)
        val loaded = raw?.let { runCatching { json.decodeFromString<AppState>(it) }.getOrNull() }
        _state.value = loaded ?: defaults(ctx)
        if (loaded == null) persist.run()
    }

    fun update(f: (AppState) -> AppState) {
        _state.update(f)
        handler.removeCallbacks(persist)
        handler.postDelayed(persist, 250)
    }

    fun updateSettings(f: (Settings) -> Settings) = update { it.copy(settings = f(it.settings)) }

    fun exportJson(): String = json.encodeToString(_state.value)

    fun importJson(text: String): Boolean {
        val parsed = runCatching { json.decodeFromString<AppState>(text) }.getOrNull() ?: return false
        update { parsed.copy(items = parsed.items.mapIndexed { idx, it -> if (it.id.isBlank()) it.copy(id = newId(), slot = idx) else it }) }
        return true
    }

    fun newId() = UUID.randomUUID().toString()

    fun freeSlot(items: List<Item> = _state.value.items): Int {
        val taken = items.map { it.slot }.toHashSet()
        var s = 0
        while (s in taken) s++
        return s
    }

    fun upsert(item: Item) = update { st ->
        val without = st.items.filter { it.id != item.id && it.slot != item.slot }
        st.copy(items = without + item)
    }

    fun delete(id: String) = update { st -> st.copy(items = st.items.filter { it.id != id }) }

    fun moveToSlot(id: String, slot: Int) = update { st ->
        val item = st.items.firstOrNull { it.id == id } ?: return@update st
        val occupant = st.items.firstOrNull { it.slot == slot && it.id != id }
        st.copy(items = st.items.map {
            when {
                it.id == id -> it.copy(slot = slot)
                occupant != null && it.id == occupant.id -> it.copy(slot = item.slot)
                else -> it
            }
        })
    }

    fun swapOrder(id: String, dir: Int) = update { st ->
        val sorted = st.items.sortedBy { it.slot }
        val i = sorted.indexOfFirst { it.id == id }
        val j = i + dir
        if (i < 0 || j !in sorted.indices) return@update st
        val a = sorted[i]; val b = sorted[j]
        st.copy(items = st.items.map { when (it.id) { a.id -> it.copy(slot = b.slot); b.id -> it.copy(slot = a.slot); else -> it } })
    }

    fun resetSettings() = updateSettings { Settings() }

    /** First-run layout, equivalent to the prototype's default grid but using the apps that really exist. */
    private fun defaults(ctx: Context): AppState {
        val apps = Apps.list(ctx)
        fun find(vararg keys: String): AppInfo? {
            for (k in keys) {
                apps.firstOrNull { it.pkg == k }?.let { return it }
            }
            for (k in keys) {
                apps.firstOrNull { it.label.equals(k, true) }?.let { return it }
            }
            return null
        }
        val wanted = listOf(
            find("com.google.android.GoogleCamera", "com.android.camera", "com.android.camera2", "Camera"),
            find("com.google.android.apps.photos", "com.google.android.gallery3d", "Gallery", "Photos"),
            find("com.google.android.apps.maps", "Maps"),
            find("com.spotify.music", "com.google.android.apps.youtube.music", "Music"),
            find("com.android.settings", "Settings"),
            find("com.android.chrome", "Chrome"),
            find("com.google.android.youtube", "YouTube"),
            find("com.google.android.calculator", "com.android.calculator2", "Calculator"),
        ).filterNotNull()
        val list = mutableListOf<Item>()
        var slot = 0
        wanted.forEachIndexed { idx, a ->
            list += Item(newId(), "app", a.label, a.pkg, "app", SWATCHES[idx % SWATCHES.size], slot++)
        }
        list += Item(newId(), "web", "GitHub", "github.com", "fa-brands fa-github", "#2a2438", slot++)
        list += Item(newId(), "web", "Gmail", "mail.google.com", "fa-solid fa-envelope", "#e5534b", slot++)
        for (k in listOf("screenoff", "power", "shot", "flash", "wifi", "volume", "bright", "recents")) {
            val d = SYS.getValue(k)
            list += Item(newId(), "system", d.n, k, d.i, d.c, slot++)
        }
        return AppState(items = list)
    }
}
