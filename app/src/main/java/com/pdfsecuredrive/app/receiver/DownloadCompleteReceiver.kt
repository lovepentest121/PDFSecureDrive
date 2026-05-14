package com.pdfsecuredrive.app.receiver

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pdfsecuredrive.app.service.PdfMonitorService
import com.pdfsecuredrive.app.storage.SecurePreferences

class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Strict action check — prevents spoofed broadcasts
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        if (!SecurePreferences.isEnabled(context)) return

        val dlId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (dlId == -1L) return

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        val cursor = dm.query(DownloadManager.Query().setFilterById(dlId)) ?: return

        cursor.use {
            if (!it.moveToFirst()) return

            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) return

            val mime  = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE))
            val local = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))

            if (mime == "application/pdf" || local?.lowercase()?.endsWith(".pdf") == true) {
                context.startForegroundService(
                    Intent(context, PdfMonitorService::class.java).apply {
                        action = PdfMonitorService.ACTION_SCAN_FILE
                        putExtra(PdfMonitorService.EXTRA_FILE_URI, local)
                    }
                )
            }
        }
    }
}
