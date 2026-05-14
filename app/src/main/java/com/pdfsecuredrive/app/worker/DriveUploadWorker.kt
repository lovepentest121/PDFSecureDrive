package com.pdfsecuredrive.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pdfsecuredrive.app.drive.DriveUploader
import com.pdfsecuredrive.app.model.PdfRecord
import com.pdfsecuredrive.app.notification.AppNotificationManager
import com.pdfsecuredrive.app.pdf.PdfCoverExtractor
import com.pdfsecuredrive.app.pdf.PdfTitleGenerator
import com.pdfsecuredrive.app.security.PdfScanner
import com.pdfsecuredrive.app.storage.HistoryStore
import com.pdfsecuredrive.app.storage.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class DriveUploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val KEY_FILE_PATH  = "fp"
        const val KEY_NOTIF_ID   = "nid"
        const val KEY_COVER_PATH = "cp"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val path    = inputData.getString(KEY_FILE_PATH) ?: return@withContext Result.failure()
        val notifId = inputData.getInt(KEY_NOTIF_ID, System.currentTimeMillis().toInt())
        val file    = File(path)

        if (!file.exists() || !file.canRead()) {
            AppNotificationManager.showUploadFailed(applicationContext, file.name, "File not accessible", notifId + 5000)
            return@withContext Result.failure()
        }
        if (!file.name.lowercase().endsWith(".pdf") || file.length() < 100) {
            AppNotificationManager.showUploadFailed(applicationContext, file.name, "File failed safety check", notifId + 5000)
            return@withContext Result.failure()
        }

        val account = SecurePreferences.getAccount(applicationContext) ?: run {
            AppNotificationManager.showUploadFailed(applicationContext, file.name, "Not signed in", notifId + 5000)
            return@withContext Result.failure()
        }

        val aiTitle  = PdfTitleGenerator.generate(file)
        val coverPath = inputData.getString(KEY_COVER_PATH)
            ?: PdfCoverExtractor.extract(file, applicationContext.cacheDir)?.absolutePath
        val fileHash = PdfScanner.computeHash(file)

        when (val r = DriveUploader(applicationContext).upload(file, account)) {
            is DriveUploader.Result.Success -> {
                // Save to history
                val record = PdfRecord(
                    id        = UUID.randomUUID().toString(),
                    fileName  = file.name,
                    aiTitle   = aiTitle,
                    driveLink = r.shareLink,
                    coverPath = coverPath,
                    filePath  = path,
                    fileSize  = file.length(),
                    fileHash  = fileHash,
                    scanDate  = System.currentTimeMillis(),
                    status    = "UPLOADED"
                )
                HistoryStore.add(applicationContext, record)

                AppNotificationManager.showUploadSuccess(
                    applicationContext, file.name, aiTitle, r.shareLink, coverPath, notifId + 5000
                )
                Result.success()
            }
            is DriveUploader.Result.Failure -> {
                AppNotificationManager.showUploadFailed(applicationContext, file.name, r.error, notifId + 5000)
                Result.retry()
            }
        }
    }
}
