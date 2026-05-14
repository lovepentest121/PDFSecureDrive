package com.pdfsecuredrive.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pdfsecuredrive.app.service.PdfMonitorService
import com.pdfsecuredrive.app.storage.SecurePreferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Strict action whitelist — prevents component hijacking
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        if (SecurePreferences.isEnabled(context)) {
            context.startForegroundService(Intent(context, PdfMonitorService::class.java))
        }
    }
}
