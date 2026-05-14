package com.pdfsecuredrive.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import com.pdfsecuredrive.app.notification.AppNotificationManager
import com.pdfsecuredrive.app.security.SecurityEngine
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PdfMonitorService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val idCounter = AtomicInteger(100)

    // Deduplication: path → last scan timestamp
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
        return START_STICKY // Auto-restart after kill
    }

    private fun registerObservers() {
        val handler = Handler(Looper.getMainLooper())

        // Observer 1: MediaStore Downloads URI (all DownloadManager downloads)
        contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                uri?.let { queryMediaStore(it) }
            }
        }
        try {
            contentResolver.registerContentObserver(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                true,
                contentObserver!!
            )
        } catch (_: SecurityException) { }

        // Observer 2: FileObserver on Downloads directory (direct-write apps)
        val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        fileObserver = buildFileObserver(dlDir)
        fileObserver?.startWatching()
    }

    private fun buildFileObserver(dir: File): FileObserver {
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path?.lowercase()?.endsWith(".pdf") == true)
                        enqueueFile("${dir.absolutePath}/$path")
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path?.lowercase()?.endsWith(".pdf") == true)
                        enqueueFile("${dir.absolutePath}/$path")
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
                        if (path != null &&
                            (mime == "application/pdf" || path.lowercase().endsWith(".pdf"))) {
                            enqueueFile(path)
                        }
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun enqueueFile(path: String) {
        val now = System.currentTimeMillis()
        // Skip if processed within last 5s (dedup for rapid duplicate events)
        if (scanned[path]?.let { now - it < 5_000 } == true) return
        scanned[path] = now

        // Purge old dedup entries to prevent memory growth
        if (scanned.size > 200) scanned.entries.removeIf { now - it.value > 120_000 }

        scope.launch {
            delay(1_500) // Let OS finish writing the file
            val file = File(path)
            if (!file.exists() || !file.canRead() || file.length() == 0L) return@launch

            // SECURITY: Full scan BEFORE any notification — file never opened/run until safe
            val result = SecurityEngine.scanFile(applicationContext, file)
            val id = idCounter.getAndIncrement()

            if (result.isSafe) {
                AppNotificationManager.showSafePrompt(applicationContext, result, path, id)
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
