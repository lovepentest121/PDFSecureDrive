package com.pdfsecuredrive.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import com.pdfsecuredrive.app.receiver.NotificationActionReceiver
import com.pdfsecuredrive.app.security.RiskLevel
import com.pdfsecuredrive.app.security.ScanResult
import com.pdfsecuredrive.app.security.VtStatus
import java.io.File

object AppNotificationManager {

    const val CH_MONITOR = "ch_monitor"
    const val CH_SCAN    = "ch_scan"
    const val CH_UPLOAD  = "ch_upload"

    const val ACTION_UPLOAD     = "com.pdfsecuredrive.UPLOAD"
    const val ACTION_CANCEL     = "com.pdfsecuredrive.CANCEL"
    const val ACTION_COPY_LINK  = "com.pdfsecuredrive.COPY_LINK"
    const val ACTION_SAVE_COVER = "com.pdfsecuredrive.SAVE_COVER"

    const val EXTRA_FILE_PATH   = "fp"
    const val EXTRA_NOTIF_ID    = "nid"
    const val EXTRA_SHARE_LINK  = "sl"
    const val EXTRA_COVER_PATH  = "cp"

    fun createChannels(context: Context) {
        val nm = nm(context)
        nm.createNotificationChannel(
            NotificationChannel(CH_MONITOR, "PDF Monitor", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Background PDF detection" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SCAN, "Scan Results", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Security scan results" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_UPLOAD, "Upload Status", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Drive upload results" }
        )
    }

    fun buildForegroundNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CH_MONITOR)
            .setContentTitle("PDF Shield Active")
            .setContentText("Monitoring all downloads for threats")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun showScanning(context: Context, fileName: String, id: Int) {
        nm(context).notify(id,
            NotificationCompat.Builder(context, CH_SCAN)
                .setContentTitle("🔍 Scanning: ${fileName.take(40)}")
                .setContentText("Checking for threats…")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setProgress(0, 0, true)   // indeterminate progress
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setAutoCancel(false)
                .build()
        )
    }

    fun showSafePrompt(context: Context, result: ScanResult, filePath: String, coverPath: String?, id: Int) {
        val uploadPi = broadcast(context, id, ACTION_UPLOAD) {
            putExtra(EXTRA_FILE_PATH, filePath)
            putExtra(EXTRA_NOTIF_ID, id)
            putExtra(EXTRA_COVER_PATH, coverPath)
        }
        val cancelPi = broadcast(context, id + 1000, ACTION_CANCEL) {
            putExtra(EXTRA_NOTIF_ID, id)
        }

        val vtLine = when (result.vtStatus) {
            VtStatus.CLEAN          -> "🛡️ VirusTotal: CLEAN\n"
            VtStatus.ERROR          -> "⚠️ VirusTotal: Unreachable\n"
            VtStatus.NOT_CONFIGURED -> ""
            else                    -> ""
        }
        val body = buildString {
            append("📄 ${result.fileName}\n")
            append("📦 ${fmtSize(result.fileSize)}   ⏱ ${result.scanDurationMs}ms\n")
            append("🔑 ${result.fileHash.take(16)}…\n")
            append(vtLine)
            append("✅ No threats detected — safe to upload")
        }

        val cover = loadCoverBitmap(coverPath)

        val builder = NotificationCompat.Builder(context, CH_SCAN)
            .setContentTitle("✅ Safe PDF: ${result.fileName.take(40)}")
            .setContentText("Tap Upload to save to Google Drive")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setColor(0xFF00C853.toInt())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .addAction(android.R.drawable.ic_menu_upload, "Upload to Drive", uploadPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)

        if (cover != null) builder.setLargeIcon(cover)

        nm(context).notify(id, builder.build())
    }

    fun showThreat(context: Context, result: ScanResult, id: Int, deleted: Boolean = false) {
        val color = when (result.highestRisk) {
            RiskLevel.CRITICAL -> 0xFFFF1744.toInt()
            RiskLevel.HIGH     -> 0xFFFF6D00.toInt()
            RiskLevel.MEDIUM   -> 0xFFFFD600.toInt()
            else               -> 0xFF888888.toInt()
        }
        val riskEmoji = when (result.highestRisk) {
            RiskLevel.CRITICAL -> "☠️"
            RiskLevel.HIGH     -> "🔴"
            RiskLevel.MEDIUM   -> "🟡"
            else               -> "🟠"
        }
        val detail  = result.threats.take(5).joinToString("\n") { "  • [${it.riskLevel}] ${it.name}" }
        val more    = if (result.threatCount > 5) "\n  + ${result.threatCount - 5} more threats" else ""
        val delLine = if (deleted) "\n🗑️ File auto-deleted from device" else "\n⚠️ File still on device — delete manually"

        nm(context).notify(id,
            NotificationCompat.Builder(context, CH_SCAN)
                .setContentTitle("$riskEmoji MALICIOUS FILE BLOCKED")
                .setContentText("${result.fileName.take(35)} — ${result.threatCount} threat(s) — ${result.highestRisk}")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setColor(color)
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "📄 ${result.fileName}\n" +
                    "⚠️  Risk: ${result.highestRisk}   |   Threats: ${result.threatCount}\n" +
                    "🔑 ${result.fileHash.take(16)}…\n\n" +
                    detail + more +
                    delLine
                ))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .build()
        )
    }

    fun showUploadSuccess(context: Context, fileName: String, aiTitle: String, link: String, coverPath: String?, id: Int) {
        val copyPi = broadcast(context, id + 2000, ACTION_COPY_LINK) {
            putExtra(EXTRA_SHARE_LINK, link)
            putExtra(EXTRA_NOTIF_ID, id)
        }
        val saveCoverPi = broadcast(context, id + 3000, ACTION_SAVE_COVER) {
            putExtra(EXTRA_COVER_PATH, coverPath)
            putExtra(EXTRA_NOTIF_ID, id)
        }

        val cover = loadCoverBitmap(coverPath)

        val displayTitle = if (aiTitle.isNotBlank() && aiTitle != fileName) aiTitle else fileName

        val builder = NotificationCompat.Builder(context, CH_UPLOAD)
            .setContentTitle("☁️ Uploaded: $displayTitle".take(60))
            .setContentText("Tap 'Copy Link' to share from Google Drive")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setColor(0xFF1F6FEB.toInt())
            .addAction(android.R.drawable.ic_menu_share, "Copy Link", copyPi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        if (cover != null) {
            // BigPictureStyle shows cover prominently
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(cover)
                    .setBigContentTitle("☁️ $displayTitle".take(55))
                    .setSummaryText("🔗 Uploaded to Google Drive  •  Tap Copy Link")
            )
            builder.setLargeIcon(cover)
            if (coverPath != null) {
                builder.addAction(android.R.drawable.ic_menu_save, "Save Cover", saveCoverPi)
            }
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(
                "✅ $displayTitle\n\n🔗 $link\n\nTap 'Copy Link' to share."
            ))
        }

        nm(context).notify(id, builder.build())
    }

    fun showUploadFailed(context: Context, fileName: String, error: String, id: Int) {
        nm(context).notify(id,
            NotificationCompat.Builder(context, CH_UPLOAD)
                .setContentTitle("❌ Upload Failed: ${fileName.take(40)}")
                .setContentText(error)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
        )
    }

    fun dismiss(context: Context, id: Int) = nm(context).cancel(id)

    private fun loadCoverBitmap(path: String?): Bitmap? {
        if (path == null) return null
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(path, opts)
        } catch (_: Exception) { null }
    }

    private fun nm(ctx: Context) =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun broadcast(context: Context, requestCode: Int, action: String, block: Intent.() -> Unit): PendingIntent {
        val i = Intent(context, NotificationActionReceiver::class.java).apply { this.action = action; block() }
        return PendingIntent.getBroadcast(context, requestCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun fmtSize(b: Long) = when {
        b < 1024          -> "${b}B"
        b < 1024 * 1024   -> "${b / 1024}KB"
        else              -> "${b / (1024 * 1024)}MB"
    }
}
