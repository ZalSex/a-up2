package com.aimlock.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

class ScreenTextService : Service() {

    companion object {
        @Volatile var instance: ScreenTextService? = null
        const val CHANNEL_ID = "screen_text_channel"
        const val NOTIF_ID = 201
    }

    private var windowManager: WindowManager? = null
    private var overlayView: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text") ?: ""
        if (text.isNotEmpty()) showText(text) else removeText()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        removeText()
        instance = null
    }

    private fun showText(text: String) {
        removeText()
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            x = 0
            y = 0
        }

        val tv = TextView(this).apply {
            this.text = text
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(32, 16, 32, 16)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            // Shadow untuk readability
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
            // Rounded corners via background drawable
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#CC000000"))
                cornerRadius = 24f
            }
            background = bg
            elevation = 8f
        }

        overlayView = tv
        try {
            windowManager?.addView(tv, params)
        } catch (_: Exception) {}
    }

    private fun removeText() {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen Text", NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                setSound(null, null)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aim Lock")
            .setContentText("Berjalan di latar belakang")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
}