package com.pdfsecuredrive.app.worker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pdfsecuredrive.app.service.PdfMonitorService
import com.pdfsecuredrive.app.storage.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Periodic backup scanner — catches PDFs missed by FileObserver/DownloadManager
class PdfScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!SecurePreferences.isEnabled(applicationContext)) return@withContext Result.success()

        val cutoff = System.currentTimeMillis() - 20 * 60 * 1000L // last 20 min

        val dirs = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            File(Environment.getExternalStorageDirectory(), "LinkedIn"),
            File(Environment.getExternalStorageDirectory(), "WhatsApp/Media/WhatsApp Documents"),
            File(Environment.getExternalStorageDirectory(), "Telegram"),
        ).filter { it.exists() }

        for (dir in dirs) {
            dir.walkTopDown().maxDepth(2).filter { f ->
                f.isFile &&
                f.name.lowercase().endsWith(".pdf") &&
                f.lastModified() > cutoff &&
                f.length() > 0
            }.forEach { file ->
                runCatching {
                    applicationContext.startForegroundService(
                        Intent(applicationContext, PdfMonitorService::class.java).apply {
                            action = PdfMonitorService.ACTION_SCAN_FILE
                            putExtra(PdfMonitorService.EXTRA_FILE_URI, Uri.fromFile(file).toString())
                        }
                    )
                }
            }
        }
        Result.success()
    }
}
