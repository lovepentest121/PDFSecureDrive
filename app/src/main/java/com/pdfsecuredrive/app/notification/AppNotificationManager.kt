package com.pdfsecuredrive.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.pdfsecuredrive.app.receiver.NotificationActionReceiver
import com.pdfsecuredrive.app.security.RiskLevel
import com.pdfsecuredrive.app.security.ScanResult

object AppNotificationManager {

    const val CH_MONITOR = "ch_monitor"
    const val CH_SCAN    = "ch_scan"
    const val CH_UPLOAD  = "ch_upload"

    const val ACTION_UPLOAD    = "com.pdfsecuredrive.UPLOAD"
    const val ACTION_CANCEL    = "com.pdfsecuredrive.CANCEL"
    const val ACTION_COPY_LINK = "com.pdfsecuredrive.COPY_LINK"

    const val EXTRA_FILE_PATH  = "fp"
    const val EXTRA_NOTIF_ID   = "nid"
    const val EXTRA_SHARE_LINK = "sl"

    fun createChannels(context: Context) {
        val nm = nm(context)
        nm.createNotificationChannel(
            NotificationChannel(CH_MONITOR, "PDF Monitor Service", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Background PDF detection service" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SCAN, "PDF Scan Results", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Security scan results" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_UPLOAD, "Upload Status", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Drive upload results" }
        )
    }

    fun buildForegroundNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CH_MONITOR)
            .setContentTitle("PDF Security Monitor Active")
            .setContentText("Scanning all PDF downloads in background")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun showSafePrompt(context: Context, result: ScanResult, filePath: String, id: Int) {
        val uploadPi = broadcast(context, id, ACTION_UPLOAD) {
            putExtra(EXTRA_FILE_PATH, filePath)
            putExtra(EXTRA_NOTIF_ID, id)
        }
        val cancelPi = broadcast(context, id + 1000, ACTION_CANCEL) {
            putExtra(EXTRA_NOTIF_ID, id)
        }

        val vtLine = when (result.vtStatus) {
            com.pdfsecuredrive.app.security.VtStatus.CLEAN          -> "🛡️ VirusTotal: CLEAN\n"
            com.pdfsecuredrive.app.security.VtStatus.ERROR          -> "⚠️ VirusTotal: Unreachable\n"
            com.pdfsecuredrive.app.security.VtStatus.NOT_CONFIGURED -> "ℹ️ VirusTotal: No API key\n"
            else -> ""
        }
        val body = buildString {
            append("📄 ${result.fileName}\n")
            append("📦 Size: ${fmtSize(result.fileSize)}\n")
            append("🔑 SHA-256: ${result.fileHash.take(16)}…\n")
            append("⏱ Scan: ${result.scanDurationMs}ms\n")
            append(vtLine)
            append("✅ Status: SAFE — No threats found\n\n")
            append("Upload to Google Drive?")
        }

        nm(context).notify(id,
            NotificationCompat.Builder(context, CH_SCAN)
                .setContentTitle("✅ Safe PDF Detected")
                .setContentText("${result.fileName} — Tap to upload to Drive")
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setColor(0xFF00C853.toInt())
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .addAction(android.R.drawable.ic_menu_upload, "Upload to Drive", uploadPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(false)
                .build()
        )
    }

    fun showThreat(context: Context, result: ScanResult, id: Int) {
        val color = when (result.highestRisk) {
            RiskLevel.CRITICAL -> 0xFFFF1744.toInt()
            RiskLevel.HIGH     -> 0xFFFF6D00.toInt()
            RiskLevel.MEDIUM   -> 0xFFFFD600.toInt()
            else               -> 0xFF888888.toInt()
        }
        val detail = result.threats.take(5).joinToString("\n") {
            "• [${it.riskLevel}] ${it.name}: ${it.description}"
        }
        val more = if (result.threatCount > 5) "\n+ ${result.threatCount - 5} more threat(s)" else ""

        nm(context).notify(id,
            NotificationCompat.Builder(context, CH_SCAN)
                .setContentTitle("⛔ THREAT BLOCKED: ${result.fileName}")
                .setContentText("${result.threatCount} threat(s) | Risk: ${result.highestRisk}")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setColor(color)
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "File: ${result.fileName}\n" +
                    "Risk Level: ${result.highestRisk}\n" +
                    "Threats: ${result.threatCount}\n\n" +
                    detail + more + "\n\n" +
                    "❌ Upload blocked. File not opened."
                ))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .build()
        )
    }

    fun showUploadSuccess(context: Context, fileName: String, link: String, id: Int) {
        val copyPi = broadcast(context, id + 2000, ACTION_COPY_LINK) {
            putExtra(EXTRA_SHARE_LINK, link)
            putExtra(EXTRA_NOTIF_ID, id)
        }
        nm(context).notify(id,
            NotificationCompat.Builder(context, CH_UPLOAD)
                .setContentTitle("☁️ Uploaded: $fileName")
                .setContentText("Tap 'Copy Link' to get Drive share link")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setColor(0xFF1F6FEB.toInt())
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "✅ $fileName\n\n🔗 $link\n\nTap 'Copy Link' to copy to clipboard."
                ))
                .addAction(android.R.drawable.ic_menu_share, "Copy Link", copyPi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
        )
    }

    fun showUploadFailed(context: Context, fileName: String, error: String, id: Int) {
        nm(context).notify(id,
            NotificationCompat.Builder(context, CH_UPLOAD)
                .setContentTitle("❌ Upload Failed: $fileName")
                .setContentText(error)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
        )
    }

    fun dismiss(context: Context, id: Int) = nm(context).cancel(id)

    private fun nm(ctx: Context) =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun broadcast(
        context: Context,
        requestCode: Int,
        action: String,
        block: Intent.() -> Unit
    ): PendingIntent {
        val i = Intent(context, NotificationActionReceiver::class.java)
            .apply { this.action = action; block() }
        return PendingIntent.getBroadcast(
            context, requestCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun fmtSize(b: Long) = when {
        b < 1024 -> "${b}B"
        b < 1024 * 1024 -> "${b / 1024}KB"
        else -> "${b / (1024 * 1024)}MB"
    }
}
