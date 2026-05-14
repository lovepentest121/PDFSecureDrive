package com.pdfsecuredrive.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import com.pdfsecuredrive.app.notification.AppNotificationManager
import com.pdfsecuredrive.app.pdf.PdfCoverExtractor
import com.pdfsecuredrive.app.security.SecurityEngine
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PdfMonitorService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val idCounter = AtomicInteger(100)
    private val scanned = ConcurrentHashMap<String, Long>()
    private var contentObserver: ContentObserver? = null
    private var fileObserver: FileObserver? = null

    companion object {
        const val ACTION_SCAN_FILE = "com.pdfsecuredrive.SCAN_FILE"
        const val EXTRA_FILE_URI   = "furi"
    }

    override fun onCreate() {
        super.onCreate()
        AppNotificationManager.createChannels(this)
        SecurityEngine.initialize(this)
        startForeground(1, AppNotificationManager.buildForegroundNotification(this))
        registerObservers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SCAN_FILE) {
            val uriStr = intent.getStringExtra(EXTRA_FILE_URI) ?: return START_STICKY
            Uri.parse(uriStr).path?.let { enqueueFile(it) }
        }
        return START_STICKY
    }

    private fun registerObservers() {
        val handler = Handler(Looper.getMainLooper())
        contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) { uri?.let { queryMediaStore(it) } }
        }
        try {
            contentResolver.registerContentObserver(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, contentObserver!!
            )
        } catch (_: SecurityException) { }

        val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        fileObserver = buildFileObserver(dlDir)
        fileObserver?.startWatching()
    }

    private fun buildFileObserver(dir: File): FileObserver {
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path?.lowercase()?.endsWith(".pdf") == true) enqueueFile("${dir.absolutePath}/$path")
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path?.lowercase()?.endsWith(".pdf") == true) enqueueFile("${dir.absolutePath}/$path")
                }
            }
        }
    }

    private fun queryMediaStore(uri: Uri) {
        scope.launch {
            try {
                contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Downloads.DATA, MediaStore.Downloads.MIME_TYPE),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE))
                        val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATA))
                        if (path != null && (mime == "application/pdf" || path.lowercase().endsWith(".pdf")))
                            enqueueFile(path)
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun enqueueFile(path: String) {
        val now = System.currentTimeMillis()
        if (scanned[path]?.let { now - it < 5_000 } == true) return
        scanned[path] = now
        if (scanned.size > 200) scanned.entries.removeIf { now - it.value > 120_000 }

        scope.launch {
            delay(1_500)
            val file = File(path)
            if (!file.exists() || !file.canRead() || file.length() == 0L) return@launch

            val result = SecurityEngine.scanFile(applicationContext, file)
            val id = idCounter.getAndIncrement()

            // Extract cover page (safe: read-only PdfRenderer, no execution)
            val coverFile = if (result.isValidPdf) {
                PdfCoverExtractor.extract(file, cacheDir)
            } else null

            if (result.isSafe) {
                AppNotificationManager.showSafePrompt(applicationContext, result, path, coverFile?.absolutePath, id)
            } else {
                AppNotificationManager.showThreat(applicationContext, result, id)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        contentObserver?.let { contentResolver.unregisterContentObserver(it) }
        fileObserver?.stopWatching()
    }
}
