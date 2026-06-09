package com.pdfsecuredrive.app.worker

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pdfsecuredrive.app.model.VideoRecord
import com.pdfsecuredrive.app.notification.AppNotificationManager
import com.pdfsecuredrive.app.storage.VideoHistoryStore
import com.pdfsecuredrive.app.video.VideoExtractor
import com.pdfsecuredrive.app.video.VideoLinkDetector
import com.pdfsecuredrive.app.video.VideoTitleFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Resolves a social video link to a direct stream and enqueues a DownloadManager download.
 * Triggered by the "Download" action on the video-detected notification (one tap).
 * Runs off the main thread so the network resolution can't block the UI.
 */
class VideoDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object { const val KEY_URL = "url" }

    private val downloadUa =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112 Mobile Safari/537.36"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val raw = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val url = VideoLinkDetector.extractUrl(raw) ?: raw

        val info   = VideoTitleFetcher.fetch(url)
        val stream = VideoExtractor.extractStream(info)

        if (stream == null) {
            AppNotificationManager.showVideoResult(
                applicationContext, false,
                "Couldn't get a direct ${info.platform.label} stream (login-gated or private). " +
                "Open the post and use its in-app save."
            )
            return@withContext Result.failure()
        }

        try {
            val safeTitle = info.title.replace(Regex("[^a-zA-Z0-9 ]"), "").trim().take(40)
            val fileName  = "${safeTitle.ifBlank { info.platform.label }}_${System.currentTimeMillis()}.mp4"

            val req = DownloadManager.Request(Uri.parse(stream.directUrl)).apply {
                setTitle(info.title.ifBlank { "${info.platform.label} video" })
                setDescription("Downloading ${info.platform.label} video…")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                // Download into our app-private Movies dir; DownloadCompleteReceiver then
                // publishes it into the gallery's Video collection (MediaStore) so it actually
                // shows up in the Gallery app — DownloadManager's own public-dir files land in
                // the Downloads collection and gallery apps never index them.
                setDestinationInExternalFilesDir(applicationContext, Environment.DIRECTORY_MOVIES, fileName)
                addRequestHeader("User-Agent", downloadUa)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            (applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)

            VideoHistoryStore.add(applicationContext, VideoRecord(
                id            = UUID.randomUUID().toString(),
                title         = info.title,
                url           = info.url,
                platform      = info.platform.label,
                platformEmoji = info.platform.emoji,
                localPath     = "${Environment.DIRECTORY_MOVIES}/PDFSecureDrive/$fileName",
                thumbnailUrl  = info.thumbnailUrl,
                downloadDate  = System.currentTimeMillis(),
                status        = "DOWNLOADED"
            ))

            AppNotificationManager.showVideoResult(
                applicationContext, true,
                "${info.platform.emoji} ${info.title.take(60)} — downloading… it'll appear in your Gallery when done."
            )
            Result.success()
        } catch (e: Exception) {
            AppNotificationManager.showVideoResult(applicationContext, false, "Download failed: ${e.message}")
            Result.failure()
        }
    }
}
