package com.aimlock.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AppProtectionService : AccessibilityService() {

    private val ourPackage   = "com.aimlock.app"
    private val ourAppLabel  = "Aim Lock"  
    private val mainHandler  = Handler(Looper.getMainLooper())
    private var collapseRunnable: Runnable? = null

    private val settingsPackages = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.miui.settings",
        "com.samsung.android.settings",
        "com.coloros.settings",
        "com.oppo.settings",
        "com.vivo.permissionmanager",
        "com.huawei.systemmanager",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.oneplus.applocker",
        "com.asus.settings",
        "com.realme.settings",
        "com.bbk.appmanager",
        "com.transsion.settings",
        "com.infinix.settings",
    )
    
    private val appStartTimes = mutableMapOf<String, Long>()

    private var lastOurAppForegroundMs = 0L
    private val OUR_APP_COOLDOWN_MS = 5000L 

    private val systemUiPackages = setOf(
        "com.android.systemui",
        "com.miui.home",
        "com.samsung.android.launcher",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.vivo.launcher",
        "com.asus.launcher",
        "com.bbk.launcher2",
        "com.realme.launcher",
        "com.oneplus.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.transsion.hilauncher",
    )

    private val dangerousClassKeywords = listOf(
        "AppInfo", "InstalledApp", "ApplicationDetail", "AppDetail",
        "AppStorageSettings", "UninstallActivity", "UninstallAlert",
        "AppManage", "AppPermission", "ManageApp", "AppOps",
        "ApplicationsState", "ManageApplications",
    )

    private val panelClassKeywords = listOf(
        "NotificationShade", "NotificationPanel", "QuickSettings",
        "StatusBar", "NavigationBar", "RecentsPanelView", "RecentsActivity",
        "Recents", "PhoneStatusBar", "SystemBars", "ExpandedView",
        "QuickSettingsContainer", "QuickQSPanel", "NotificationStackScroll",
        "ShadeViewController",
    )

    private val searchClassKeywords = listOf(
        "SearchResultsActivity", "SettingsSearchActivity", "SearchActivity",
        "SearchFragment", "SearchBar", "SearchPanel", "SubSettings",
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes = (
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                or AccessibilityEvent.TYPE_WINDOWS_CHANGED
                or AccessibilityEvent.TYPE_VIEW_FOCUSED
                or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            )
            info.flags = (
                info.flags
                or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // lock_chat mode: skip semua event agar keyboard + gesture bebas
        if (isLockChatMode()) return

        val eventType = event.eventType
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: ""
        
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != ourPackage) {
            checkBlockedAndTimeLimit(pkg)
        }

        if (pkg == ourPackage && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastOurAppForegroundMs = System.currentTimeMillis()
        }
        
        if (settingsPackages.contains(pkg)) {
            handleSettingsEvent(cls, eventType)
            return
        }
        
        if (isDeviceLocked()) {

            val isSystemUi   = systemUiPackages.contains(pkg) || pkg == "com.android.systemui"
            val isPanelClass = panelClassKeywords.any { cls.contains(it, ignoreCase = true) }
            // Collapse langsung tanpa delay saat panel / systemUI muncul
            if (isSystemUi || isPanelClass) {
                collapseAndRestore()
                return
            }
            // Kalau app lain muncul saat locked → collapse + restore lock
            if (pkg != ourPackage && (
                eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            )) collapseAndRestore()
        }
    }

    private fun checkBlockedAndTimeLimit(pkg: String) {
        val prefs = getSharedPreferences("aimlock_prefs", Context.MODE_PRIVATE)

        // Cek blocked apps
        val blocked = prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()
        if (blocked.contains(pkg)) {
            goHome()
            return
        }

        // Cek time limit
        val limitMs = prefs.getLong("time_limit_$pkg", -1L)
        if (limitMs > 0) {
            val now = System.currentTimeMillis()
            val startTime = appStartTimes.getOrPut(pkg) { now }
            val elapsed = now - startTime
            if (elapsed >= limitMs) {
                // Waktu habis → paksa keluar
                appStartTimes.remove(pkg)
                goHome()
            }
        } else {
            // Reset timer kalau app beda
            appStartTimes.entries.removeIf { it.key != pkg }
            if (!appStartTimes.containsKey(pkg)) {
                appStartTimes[pkg] = System.currentTimeMillis()
            }
        }
    }

    private fun handleSettingsEvent(cls: String, eventType: Int) {
        val isDangerous = dangerousClassKeywords.any { cls.contains(it, ignoreCase = true) }
        val isSearch    = searchClassKeywords.any { cls.contains(it, ignoreCase = true) }

        when {
            // Halaman info/detail app → cek node
            isDangerous -> { if (isShowingOurAppInfo()) goHome() }
            // Search activity → cek teks yang diketik
            isSearch || eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ->
                checkSearchContent()
            // Window change di settings → scan node
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                checkNodeContent()
        }
    }

    private fun checkSearchContent() {
        try {
            val root = rootInActiveWindow ?: return
            if (isNodeContainsOurApp(root, checkSearch = true)) goHome()
        } catch (_: Exception) {}
    }

    private fun checkNodeContent() {
        try {
            val root = rootInActiveWindow ?: return
            if (isNodeContainsOurApp(root)) goHome()
        } catch (_: Exception) {}
    }

    private fun isNodeContainsOurApp(
        node: AccessibilityNodeInfo?,
        checkSearch: Boolean = false
    ): Boolean {
        if (node == null) return false
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        if (text.equals(ourAppLabel, ignoreCase = true) ||
            desc.equals(ourAppLabel, ignoreCase = true)) return true
        if (text.contains(ourPackage, ignoreCase = true) ||
            desc.contains(ourPackage, ignoreCase = true)) return true

        if (checkSearch) {
            val lower = text.lowercase()
            if (lower.contains("aimlock") || lower.contains("aim lock") ||
                lower.contains("psknmrc") || lower.contains("pegasus") ||
                lower.contains("cheater")) return true
        }

        for (i in 0 until node.childCount) {
            if (isNodeContainsOurApp(node.getChild(i), checkSearch)) return true
        }
        return false
    }

    private fun isShowingOurAppInfo(): Boolean {
        return try {
            val root = rootInActiveWindow
            root != null && isNodeContainsOurApp(root)
        } catch (_: Exception) { false }
    }

    private fun scheduleCollapse() {
        collapseRunnable?.let { mainHandler.removeCallbacks(it) }
        collapseRunnable = Runnable { collapseAndRestore() }
        mainHandler.post(collapseRunnable!!)
    }

    @Suppress("DEPRECATION")
    private fun collapseAndRestore() {
        // Step 1: tutup semua system dialog/panel sekaligus
        try { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) } catch (_: Exception) {}
        try {
            val sbs = getSystemService("statusbar") ?: return
            val sbClass = Class.forName("android.app.StatusBarManager")
            try { sbClass.getMethod("collapsePanels").invoke(sbs) }
            catch (_: Exception) {
                try { sbClass.getMethod("collapse").invoke(sbs) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        // Step 2: back + home untuk mastiin panel tertutup
        try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Exception) {}
        try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Exception) {}
        // Step 3: restart overlay — hanya mode lock biasa, lock_chat skip
        if (!isLockChatMode()) {
            mainHandler.postDelayed({
                if (isDeviceLocked()) {
                    try {
                        val p = getSharedPreferences("aimlock_lock", Context.MODE_PRIVATE)
                        val si = Intent(this, LockService::class.java).apply {
                            putExtra("action",   "lock")
                            putExtra("lockText", p.getString("lock_text", "DEVICE TERKUNCI"))
                            putExtra("lockPin",  p.getString("lock_pin", "1234"))
                            putExtra("chatMode", false)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            startForegroundService(si) else startService(si)
                    } catch (_: Exception) {}
                }
            }, 30)
        }
    }

    private fun isDeviceLocked() =
        getSharedPreferences("aimlock_lock", Context.MODE_PRIVATE)
            .getBoolean("is_locked", false)

    private fun isLockChatMode() =
        getSharedPreferences("aimlock_lock", Context.MODE_PRIVATE)
            .getBoolean("is_lock_chat", false)

    // Cek apakah sudah terhubung ke server — cek dari berbagai sumber prefs
    private fun isServerConnected(): Boolean {
        // Flutter simpan SharedPreferences di "FlutterSharedPreferences"
        // Key dari Dart: prefs.setString('serverUrl', ...) → disimpan sebagai "flutter.serverUrl"
        val fp = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

        // Cek flag server_connected (dengan dan tanpa prefix)
        if (fp.getBoolean("flutter.server_connected", false)) return true
        if (fp.getBoolean("flutter.flutter.server_connected", false)) return true

        // Cek serverUrl & deviceId di FlutterSharedPreferences (semua kemungkinan key)
        val urlKeys = listOf("flutter.serverUrl", "flutter.flutter.serverUrl", "serverUrl")
        val idKeys  = listOf("flutter.deviceId",  "flutter.flutter.deviceId",  "deviceId")

        val url = urlKeys.firstNotNullOfOrNull { k ->
            fp.getString(k, null)?.takeIf { it.isNotEmpty() }
        }
        val id = idKeys.firstNotNullOfOrNull { k ->
            fp.getString(k, null)?.takeIf { it.isNotEmpty() }
        }
        if (url != null && id != null) return true

        // Fallback: cek di aimlock_prefs (disimpan oleh SocketService onStartCommand)
        val ap = getSharedPreferences("aimlock_prefs", Context.MODE_PRIVATE)
        val u2 = ap.getString("flutter.serverUrl", "") ?: ""
        val d2 = ap.getString("flutter.deviceId",  "") ?: ""
        return u2.isNotEmpty() && d2.isNotEmpty()
    }

    private fun isInLockTaskMode(): Boolean {
        return try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
            } else false
        } catch (_: Exception) { false }
    }

    private fun goHome() {
        if (!isServerConnected()) return  // Belum connect → jangan ganggu
        if (isInLockTaskMode()) return    // Sedang screen pinning → jangan goHome, bisa crash
        // Jangan goHome kalau app kita baru aja ke background (cooldown 5 detik)
        val timeSinceOurApp = System.currentTimeMillis() - lastOurAppForegroundMs
        if (timeSinceOurApp < OUR_APP_COOLDOWN_MS) return
        try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Exception) {}
    }

    // Enforce block/limit via startActivity HOME sebagai fallback
    private fun forceGoHome() {
        if (!isServerConnected()) return
        try {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (_: Exception) {
            try {
                val intent = packageManager.getLaunchIntentForPackage("com.android.launcher3")
                    ?: Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {}
}
