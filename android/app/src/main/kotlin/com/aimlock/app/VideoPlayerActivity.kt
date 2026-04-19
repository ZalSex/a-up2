package com.aimlock.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.core.content.FileProvider
import java.io.File

class VideoPlayerActivity : Activity() {

    private var videoView: VideoView? = null
    private var stopReceiver: BroadcastReceiver? = null
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        // requestWindowFeature HARUS sebelum super.onCreate
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val videoPath = intent.getStringExtra("videoPath")
        if (videoPath.isNullOrEmpty()) { finish(); return }
        val videoFile = File(videoPath)
        if (!videoFile.exists()) { finish(); return }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val vv = VideoView(this)
        videoView = vv
        root.addView(vv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))
        setContentView(root)

        // Set URI SETELAH setContentView agar SurfaceHolder sudah ready
        val videoUri: Uri = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", videoFile)
            } else {
                Uri.fromFile(videoFile)
            }
        } catch (_: Exception) {
            Uri.fromFile(videoFile)
        }

        vv.setVideoURI(videoUri)
        vv.setOnPreparedListener { mp ->
            mediaPlayer = mp
            mp.isLooping = true
            try { mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING) } catch (_: Exception) {}
            mp.start()
            hideSystemUI()
        }
        vv.setOnErrorListener { _, _, _ ->
            // Fallback ke Uri.fromFile
            try { vv.setVideoURI(Uri.fromFile(videoFile)); vv.start() } catch (_: Exception) {}
            true
        }
        vv.requestFocus()
        vv.start()

        // Receiver stop dari SocketService
        stopReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                handler.post { stopAndFinish() }
            }
        }
        val filter = IntentFilter("com.aimlock.app.STOP_VIDEO")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(stopReceiver, filter)
            }
        } catch (_: Exception) {}
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    private fun stopAndFinish() {
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { videoView?.stopPlayback() } catch (_: Exception) {}
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_SEARCH -> true
            else -> super.dispatchKeyEvent(event)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        try { if (videoView?.isPlaying == false) videoView?.start() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { stopReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        try { videoView?.stopPlayback() } catch (_: Exception) {}
        mediaPlayer = null
        videoView = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* block */ }
}
