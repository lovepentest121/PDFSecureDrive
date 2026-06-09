package com.pdfsecuredrive.app.receiver

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pdfsecuredrive.app.notification.AppNotificationManager
import com.pdfsecuredrive.app.service.PdfMonitorService
import com.pdfsecuredrive.app.storage.SecurePreferences
import java.io.File

class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Strict action check — prevents spoofed broadcasts
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

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
            val localPath = local?.let { u -> runCatching { Uri.parse(u).path }.getOrNull() }
            val ourMovies = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.absolutePath

            // Our video downloads land in the app-private Movies dir — publish them into the
            // gallery's Video collection so they actually appear in the Gallery app.
            if (localPath != null && ourMovies != null && localPath.startsWith(ourMovies) &&
                (mime?.startsWith("video/") == true || localPath.lowercase().endsWith(".mp4"))) {
                publishVideoToGallery(context, File(localPath))
                return
            }

            // PDF auto-scan (only while monitoring is enabled)
            if (SecurePreferences.isEnabled(context) &&
                (mime == "application/pdf" || local?.lowercase()?.endsWith(".pdf") == true)) {
                context.startForegroundService(
                    Intent(context, PdfMonitorService::class.java).apply {
                        action = PdfMonitorService.ACTION_SCAN_FILE
                        putExtra(PdfMonitorService.EXTRA_FILE_URI, local)
                    }
                )
            }
        }
    }

    /** Move the downloaded mp4 from our private dir into the gallery (MediaStore.Video on 29+). */
    private fun publishVideoToGallery(context: Context, src: File) {
        if (!src.exists()) return
        val name = src.name
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PDFSecureDrive")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = resolver.insert(collection, values) ?: return
                try {
                    resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { inp -> inp.copyTo(out) } }
                        ?: run { resolver.delete(uri, null, null); return }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    src.delete()
                    AppNotificationManager.showVideoResult(context, true, "Saved to Gallery → Movies/PDFSecureDrive: $name")
                } catch (_: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                }
            } else {
                // API <= 28: copy to public Movies + media scan
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "PDFSecureDrive")
                    .also { d -> d.mkdirs() }
                val dest = File(dir, name)
                src.inputStream().use { inp -> dest.outputStream().use { out -> inp.copyTo(out) } }
                src.delete()
                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null)
                AppNotificationManager.showVideoResult(context, true, "Saved to Gallery: $name")
            }
        } catch (_: Exception) {}
    }
}
