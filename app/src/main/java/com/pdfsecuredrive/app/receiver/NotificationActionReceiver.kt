package com.pdfsecuredrive.app.receiver

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.pdfsecuredrive.app.notification.AppNotificationManager
import com.pdfsecuredrive.app.worker.DriveUploadWorker

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppNotificationManager.EXTRA_NOTIF_ID, -1)

        when (intent.action) {
            AppNotificationManager.ACTION_UPLOAD -> {
                val path = intent.getStringExtra(AppNotificationManager.EXTRA_FILE_PATH) ?: return
                AppNotificationManager.dismiss(context, id)
                // Start async upload via WorkManager — survives app kill
                WorkManager.getInstance(context).enqueue(
                    OneTimeWorkRequestBuilder<DriveUploadWorker>()
                        .setInputData(
                            Data.Builder()
                                .putString(DriveUploadWorker.KEY_FILE_PATH, path)
                                .putInt(DriveUploadWorker.KEY_NOTIF_ID, id)
                                .build()
                        )
                        .build()
                )
            }

            AppNotificationManager.ACTION_CANCEL -> {
                AppNotificationManager.dismiss(context, id)
                // File stays local — no upload, no deletion
            }

            AppNotificationManager.ACTION_COPY_LINK -> {
                val link = intent.getStringExtra(AppNotificationManager.EXTRA_SHARE_LINK) ?: return
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                // Generic label — prevents clipboard history leaking the file name
                cb.setPrimaryClip(ClipData.newPlainText("link", link))
                Toast.makeText(context, "Drive link copied to clipboard", Toast.LENGTH_SHORT).show()
                AppNotificationManager.dismiss(context, id)
            }
        }
    }
}
