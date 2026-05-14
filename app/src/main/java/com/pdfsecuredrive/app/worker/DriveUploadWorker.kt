package com.pdfsecuredrive.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pdfsecuredrive.app.drive.DriveUploader
import com.pdfsecuredrive.app.notification.AppNotificationManager
import com.pdfsecuredrive.app.storage.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DriveUploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val KEY_FILE_PATH = "fp"
        const val KEY_NOTIF_ID  = "nid"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val path   = inputData.getString(KEY_FILE_PATH)
            ?: return@withContext Result.failure()
        val notifId = inputData.getInt(KEY_NOTIF_ID, System.currentTimeMillis().toInt())
        val file   = File(path)

        if (!file.exists() || !file.canRead()) {
            AppNotificationManager.showUploadFailed(
                applicationContext, file.name, "File not accessible", notifId + 5000
            )
            return@withContext Result.failure()
        }

        val account = SecurePreferences.getAccount(applicationContext) ?: run {
            AppNotificationManager.showUploadFailed(
                applicationContext, file.name, "Not signed in to Google", notifId + 5000
            )
            return@withContext Result.failure()
        }

        when (val r = DriveUploader(applicationContext).upload(file, account)) {
            is DriveUploader.Result.Success -> {
                AppNotificationManager.showUploadSuccess(
                    applicationContext, file.name, r.shareLink, notifId + 5000
                )
                Result.success()
            }
            is DriveUploader.Result.Failure -> {
                AppNotificationManager.showUploadFailed(
                    applicationContext, file.name, r.error, notifId + 5000
                )
                // Retry on network errors (WorkManager handles backoff)
                Result.retry()
            }
        }
    }
}
