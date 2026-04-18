package com.aimlock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class CheatOverlayService : Service() {

    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private val CHANNEL_ID = "cheat_overlay_ch"
    private val NOTIF_ID   = 204

    // State toggle — dikelola penuh di Kotlin
    private var antiBanned = false
    private var cheatOn    = false

    // Drag state
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var initX = 0
    private var initY = 0
    private var dragging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    // ── Build + show overlay ────────────────────────────────────────────────
    private fun showOverlay() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val d   = resources.displayMetrics.density
        val w   = (158 * d).toInt()

        params = WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = (130 * d).toInt()
        }

        root = buildPanel()
        attachDrag(root!!)

        try { wm.addView(root, params) } catch (_: Exception) {}
    }

    // ── Build panel view ────────────────────────────────────────────────────
    private fun buildPanel(): LinearLayout {
        val d = resources.displayMetrics.density
        val pad = (13 * d).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // Background
        val bg = GradientDrawable().apply {
            setColor(Color.argb(230, 10, 22, 40))
            cornerRadii = floatArrayOf(
                14 * d, 14 * d,   // top-left
                0f, 0f,            // top-right (nempel kanan)
                0f, 0f,            // bottom-right
                14 * d, 14 * d    // bottom-left
            )
            setStroke((1 * d).toInt(), Color.argb(60, 0, 229, 255))
        }
        container.background = bg

        // ── Header ──────────────────────────────────────────────────────────
        val header = TextView(this).apply {
            text = "CHEATER ONLINE"
            textSize = 7.5f
            typeface = loadFont("fonts/Orbitron-Bold.ttf")
            setTextColor(Color.argb(180, 0, 229, 255))
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
        }
        container.addView(header)

        // Divider
        container.addView(makeDivider(d))

        // ── Anti Banned toggle ───────────────────────────────────────────────
        val (antiBannedRow, refreshAntiBanned) = makeToggleRow(
            label = "Anti Banned",
            color = Color.parseColor("#4CAF50"),
            isOn  = { antiBanned }
        ) {
            antiBanned = !antiBanned
        }
        container.addView(antiBannedRow)
        container.addView(makeSpacer(d, 10))

        // ── Cheat On toggle ──────────────────────────────────────────────────
        val (cheatRow, refreshCheat) = makeToggleRow(
            label = "Cheat On",
            color = Color.parseColor("#FF5252"),
            isOn  = { cheatOn }
        ) {
            cheatOn = !cheatOn
        }
        container.addView(cheatRow)

        return container
    }

    // ── Toggle row factory ──────────────────────────────────────────────────
    private fun makeToggleRow(
        label: String,
        color: Int,
        isOn: () -> Boolean,
        onTap: () -> Unit
    ): Pair<LinearLayout, () -> Unit> {
        val d = resources.displayMetrics.density

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val labelView = TextView(this).apply {
            text = label
            textSize = 10.5f
            typeface = loadFont("fonts/ShareTechMono-Regular.ttf")
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(if (isOn()) color else Color.argb(130, 255, 255, 255))
        }

        val tw = (38 * d).toInt()
        val th = (22 * d).toInt()
        val ts = (16 * d).toInt()
        val tm = (3 * d).toInt()

        val trackBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 11 * d
            setColor(if (isOn()) Color.argb(60, Color.red(color), Color.green(color), Color.blue(color))
                     else Color.argb(25, 255, 255, 255))
            setStroke((1 * d).toInt(),
                if (isOn()) Color.argb(170, Color.red(color), Color.green(color), Color.blue(color))
                else Color.argb(50, 255, 255, 255))
        }

        val thumbBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (isOn()) color else Color.argb(70, 255, 255, 255))
        }

        val thumb = View(this).apply {
            background = thumbBg
            layoutParams = LinearLayout.LayoutParams(ts, ts).apply {
                leftMargin  = if (isOn()) tw - ts - tm else tm
                topMargin   = tm
                bottomMargin = tm
            }
        }

        val track = LinearLayout(this).apply {
            background = trackBg
            layoutParams = LinearLayout.LayoutParams(tw, th)
            addView(thumb)
        }

        fun refresh() {
            val on = isOn()
            labelView.setTextColor(if (on) color else Color.argb(130, 255, 255, 255))
            trackBg.setColor(if (on) Color.argb(60, Color.red(color), Color.green(color), Color.blue(color))
                             else Color.argb(25, 255, 255, 255))
            trackBg.setStroke((1 * d).toInt(),
                if (on) Color.argb(170, Color.red(color), Color.green(color), Color.blue(color))
                else Color.argb(50, 255, 255, 255))
            thumbBg.setColor(if (on) color else Color.argb(70, 255, 255, 255))
            (thumb.layoutParams as LinearLayout.LayoutParams).apply {
                leftMargin = if (on) tw - ts - tm else tm
            }
            thumb.requestLayout()
        }

        row.addView(labelView)
        row.addView(track)

        row.setOnClickListener {
            onTap()
            refresh()
        }

        return Pair(row, ::refresh)
    }

    // ── Drag support ────────────────────────────────────────────────────────
    private fun attachDrag(v: View) {
        v.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX; dragStartY = event.rawY
                    initX = p.x; initY = p.y
                    dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX
                    val dy = event.rawY - dragStartY
                    if (!dragging && (Math.abs(dx) > 6 || Math.abs(dy) > 6)) dragging = true
                    if (dragging) {
                        p.x = (initX - dx).toInt().coerceAtLeast(0)
                        p.y = (initY + dy).toInt()
                        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { dragging = false; true }
                else -> false
            }
        }
    }

    // ── Helper views ────────────────────────────────────────────────────────
    private fun makeDivider(d: Float): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()).apply {
            topMargin    = (8 * d).toInt()
            bottomMargin = (10 * d).toInt()
        }
        setBackgroundColor(Color.argb(35, 0, 229, 255))
    }

    private fun makeSpacer(d: Float, dp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, (dp * d).toInt())
    }

    private fun loadFont(path: String) = try {
        android.graphics.Typeface.createFromAsset(assets, path)
    } catch (_: Exception) {
        android.graphics.Typeface.MONOSPACE
    }

    // ── Notification ────────────────────────────────────────────────────────
    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "System",
                NotificationManager.IMPORTANCE_NONE).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { root?.let { wm.removeView(it) } } catch (_: Exception) {}
    }
}
