package com.aimlock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        val isBootAction = action == Intent.ACTION_BOOT_COMPLETED ||
                action == "android.intent.action.LOCKED_BOOT_COMPLETED" ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED

        if (isBootAction) {
            val prefs = context.getSharedPreferences("aimlock_prefs", Context.MODE_PRIVATE)
            val serverUrl   = prefs.getString("serverUrl",    "") ?: ""
            val deviceId    = prefs.getString("deviceId",     "") ?: ""
            val deviceName  = prefs.getString("deviceName",   "") ?: ""
            val ownerUser   = prefs.getString("ownerUsername","") ?: ""
            val deviceToken = prefs.getString("deviceToken_$deviceId", "") ?: ""

            if (serverUrl.isNotEmpty() && deviceId.isNotEmpty()) {
                val si = Intent(context, SocketService::class.java).apply {
                    putExtra("serverUrl",     serverUrl)
                    putExtra("deviceId",      deviceId)
                    putExtra("deviceName",    deviceName)
                    putExtra("ownerUsername", ownerUser)
                    putExtra("deviceToken",   deviceToken)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(si)
                else
                    context.startService(si)
            }

            val lockPrefs = context.getSharedPreferences("aimlock_lock", Context.MODE_PRIVATE)
            if (lockPrefs.getBoolean("is_locked", false)) {
                val savedText = lockPrefs.getString("lock_text", "DEVICE TERKUNCI") ?: "DEVICE TERKUNCI"
                val savedPin  = lockPrefs.getString("lock_pin",  "1234") ?: "1234"

                val delayMs = when (action) {
                    Intent.ACTION_MY_PACKAGE_REPLACED -> 500L
                    "android.intent.action.LOCKED_BOOT_COMPLETED" -> 1000L
                    else -> 1500L
                }
                try { Thread.sleep(delayMs) } catch (_: InterruptedException) {}

                try {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "psknmrc:bootlock"
                    ).acquire(10000)
                } catch (_: Exception) {}

                val li = Intent(context, LockService::class.java).apply {
                    putExtra("action",   "lock")
                    putExtra("lockText", savedText)
                    putExtra("lockPin",  savedPin)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(li)
                else
                    context.startService(li)

                try { Thread.sleep(1500) } catch (_: InterruptedException) {}
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(Intent(li))
                else
                    context.startService(Intent(li))
            }
        }
    }
}
