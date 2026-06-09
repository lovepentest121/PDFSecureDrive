package com.pdfsecuredrive.app.receiver

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.pdfsecuredrive.app.notification.AppNotificationManager
import com.pdfsecuredrive.app.pdf.ShareCard
import com.pdfsecuredrive.app.worker.DriveUploadWorker
import com.pdfsecuredrive.app.worker.VideoDownloadWorker
import java.io.File

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppNotificationManager.EXTRA_NOTIF_ID, -1)

        when (intent.action) {
            AppNotificationManager.ACTION_UPLOAD -> {
                val path = intent.getStringExtra(AppNotificationManager.EXTRA_FILE_PATH) ?: return
                if (!isPathSafe(context, path)) return
                val coverPath = intent.getStringExtra(AppNotificationManager.EXTRA_COVER_PATH)
                AppNotificationManager.dismiss(context, id)
                WorkManager.getInstance(context).enqueue(
                    OneTimeWorkRequestBuilder<DriveUploadWorker>()
                        .setInputData(
                            Data.Builder()
                                .putString(DriveUploadWorker.KEY_FILE_PATH, path)
                                .putInt(DriveUploadWorker.KEY_NOTIF_ID, id)
                                .putString(DriveUploadWorker.KEY_COVER_PATH, coverPath)
                                .build()
                        ).build()
                )
            }

            AppNotificationManager.ACTION_CANCEL -> {
                AppNotificationManager.dismiss(context, id)
            }

            AppNotificationManager.ACTION_COPY_LINK -> {
                val link = intent.getStringExtra(AppNotificationManager.EXTRA_SHARE_LINK) ?: return
                if (!link.startsWith("https://drive.google.com/")) return
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("link", link))
                Toast.makeText(context, "Drive link copied", Toast.LENGTH_SHORT).show()
                AppNotificationManager.dismiss(context, id)
            }

            AppNotificationManager.ACTION_SAVE_COVER -> {
                val coverPath = intent.getStringExtra(AppNotificationManager.EXTRA_COVER_PATH) ?: return
                val src = File(coverPath)
                if (!src.exists()) {
                    Toast.makeText(context, "Cover not available", Toast.LENGTH_SHORT).show()
                    return
                }
                val ok = ShareCard.saveImageFile(context, src)
                Toast.makeText(context, if (ok) "Cover saved to Gallery" else "Failed to save cover", Toast.LENGTH_SHORT).show()
                AppNotificationManager.dismiss(context, id)
            }

            // MASTER SAVE: one tap → render+save subscriber card to gallery + copy title & link
            AppNotificationManager.ACTION_MASTER_SAVE -> {
                val link  = intent.getStringExtra(AppNotificationManager.EXTRA_SHARE_LINK)
                val cover = intent.getStringExtra(AppNotificationManager.EXTRA_COVER_PATH)
                val title = intent.getStringExtra(AppNotificationManager.EXTRA_TITLE) ?: "PDF"

                val saved = ShareCard.saveShareCard(context, cover, title, link)
                val clip = buildString {
                    append(title).append("\n").append(ShareCard.SUBSCRIBER_BADGE)
                    if (!link.isNullOrBlank()) append("\n").append(link)
                }
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("pdf_card", clip))

                val msg = if (saved) "✓ Card saved to Gallery · title & link copied"
                          else "Card save failed · title & link copied"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                AppNotificationManager.dismiss(context, id)
            }

            // VIDEO: one tap → resolve direct stream + download (runs in a worker, off the main thread)
            AppNotificationManager.ACTION_VIDEO_DOWNLOAD -> {
                val url = intent.getStringExtra(AppNotificationManager.EXTRA_VIDEO_URL) ?: return
                AppNotificationManager.showVideoDownloading(context, "")
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "video_dl_${url.hashCode()}",
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<VideoDownloadWorker>()
                        .setInputData(Data.Builder().putString(VideoDownloadWorker.KEY_URL, url).build())
                        .build()
                )
            }

            AppNotificationManager.ACTION_VIDEO_CANCEL -> {
                AppNotificationManager.dismiss(context, id)
            }
        }
    }

    private fun isPathSafe(context: Context, path: String): Boolean {
        if (path.contains("..")) return false
        val canonical = File(path).canonicalPath
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalPath
        val extRoot   = Environment.getExternalStorageDirectory().canonicalPath
        // content:// downloads are scanned from a copy in our own cache dir, and that
        // copy is what the Upload action re-reads — so it must be an allowed source too.
        val cache     = context.cacheDir.canonicalPath
        return canonical.startsWith(downloads) || canonical.startsWith(extRoot) || canonical.startsWith(cache)
    }
}
