package com.quicklauncher.app

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast

/** Live on/off state for toggle-type system shortcuts (drives the accent ring on the tile). */
object SysState {
    @Volatile var torchOn = false
    private var registered = false

    fun init(ctx: Context) {
        if (registered) return
        registered = true
        val cm = ctx.applicationContext.getSystemService(CameraManager::class.java)
        runCatching {
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchOn = enabled }
            }, Handler(Looper.getMainLooper()))
        }
    }

    fun isOn(ctx: Context, key: String): Boolean = runCatching {
        val app = ctx.applicationContext
        when (key) {
            "wifi" -> app.getSystemService(WifiManager::class.java).isWifiEnabled
            "bt" -> BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
            "flash" -> torchOn
            "air" -> Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            "rot" -> Settings.System.getInt(app.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            "dnd" -> app.getSystemService(NotificationManager::class.java).currentInterruptionFilter.let {
                it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            }
            "mute" -> app.getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_SILENT
            else -> false
        }
    }.getOrDefault(false)

    fun activeKeys(ctx: Context, items: List<Item>): Set<String> =
        items.asSequence().filter { it.t == "system" && SYS[it.v]?.toggle == true && isOn(ctx, it.v) }.map { it.v }.toSet()
}

fun Context.toast(msg: String) = Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }

fun Context.startSafe(intent: Intent, failMsg: String = "Nothing found to handle this"): Boolean {
    return try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: ActivityNotFoundException) { if (failMsg.isNotEmpty()) toast(failMsg); false
    } catch (e: SecurityException) { toast("Permission denied"); false
    } catch (e: Exception) { toast("Couldn't open: ${e.javaClass.simpleName}"); false }
}

object SysActions {
    private fun global(ctx: Context, action: Int) {
        val svc: AccessibilityService? = LauncherAccessibilityService.instance
        if (svc == null) {
            ctx.toast("Enable the Quick Launcher accessibility service first")
            ctx.startSafe(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else svc.performGlobalAction(action)
    }

    private fun pkgUri(ctx: Context) = Uri.parse("package:${ctx.packageName}")

    /** Runs a system shortcut. [mgr] provides slider panels for volume / brightness. */
    fun run(ctx: Context, key: String, mgr: OverlayManager) {
        when (key) {
            "screenoff", "lock" -> global(ctx, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            "power" -> global(ctx, AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            "shot" -> global(ctx, AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            "recents" -> global(ctx, AccessibilityService.GLOBAL_ACTION_RECENTS)
            "notif" -> global(ctx, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "home" -> global(ctx, AccessibilityService.GLOBAL_ACTION_HOME)
            "back" -> global(ctx, AccessibilityService.GLOBAL_ACTION_BACK)
            "volume" -> {
                val am = ctx.getSystemService(AudioManager::class.java)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                mgr.showSlider("Volume", "fa-solid fa-volume-high", am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max) { p ->
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (p * max / 100f).toInt().coerceIn(0, max), 0)
                }
            }
            "bright" -> {
                if (!Settings.System.canWrite(ctx)) {
                    ctx.toast("Allow \"Modify system settings\" for brightness")
                    ctx.startSafe(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri(ctx)))
                } else {
                    val cr = ctx.contentResolver
                    val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255
                    mgr.showSlider("Brightness", "fa-solid fa-sun", cur) { p ->
                        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (p * 255 / 100).coerceIn(1, 255))
                    }
                }
            }
            "wifi" -> ctx.startSafe(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS))
            "bt" -> ctx.startSafe(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "air" -> ctx.startSafe(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            "flash" -> {
                val cm = ctx.getSystemService(CameraManager::class.java)
                runCatching {
                    val id = cm.cameraIdList.firstOrNull {
                        val ch = cm.getCameraCharacteristics(it)
                        ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                            ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                    } ?: cm.cameraIdList.first()
                    cm.setTorchMode(id, !SysState.torchOn)
                }.onFailure { ctx.toast("Flashlight unavailable") }
            }
            "rot" -> {
                if (!Settings.System.canWrite(ctx)) {
                    ctx.toast("Allow \"Modify system settings\" for auto-rotate")
                    ctx.startSafe(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri(ctx)))
                } else {
                    Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (SysState.isOn(ctx, "rot")) 0 else 1)
                }
            }
            "dnd" -> {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) {
                    ctx.toast("Grant Do Not Disturb access")
                    ctx.startSafe(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                } else nm.setInterruptionFilter(
                    if (SysState.isOn(ctx, "dnd")) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY
                )
            }
            "mute" -> {
                val am = ctx.getSystemService(AudioManager::class.java)
                try {
                    am.ringerMode = if (am.ringerMode == AudioManager.RINGER_MODE_SILENT) AudioManager.RINGER_MODE_NORMAL else AudioManager.RINGER_MODE_SILENT
                } catch (e: SecurityException) {
                    ctx.toast("Grant Do Not Disturb access to change silent mode")
                    ctx.startSafe(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
            }
            "hotspot" -> {
                val tether = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
                if (!ctx.startSafe(tether, "")) ctx.startSafe(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
        }
    }
}
