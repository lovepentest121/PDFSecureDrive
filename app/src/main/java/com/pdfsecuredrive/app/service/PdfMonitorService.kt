package com.pdfsecuredrive.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import com.pdfsecuredrive.app.model.PdfRecord
import com.pdfsecuredrive.app.notification.AppNotificationManager
import com.pdfsecuredrive.app.pdf.PdfCoverExtractor
import com.pdfsecuredrive.app.security.PdfScanner
import com.pdfsecuredrive.app.security.RiskLevel
import com.pdfsecuredrive.app.security.SecurityEngine
import com.pdfsecuredrive.app.storage.HistoryStore
import com.pdfsecuredrive.app.storage.SecurePreferences
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
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
            enqueueUri(uriStr)
        }
        return START_STICKY
    }

    private fun registerObservers() {
        val handler = Handler(Looper.getMainLooper())

        // MediaStore observer
        contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) { uri?.let { queryMediaStore(it) } }
        }
        try {
            contentResolver.registerContentObserver(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, contentObserver!!
            )
        } catch (_: Exception) {}

        // Also observe all media
        try {
            contentResolver.registerContentObserver(
                MediaStore.Files.getContentUri("external"), true, contentObserver!!
            )
        } catch (_: Exception) {}

        // FileObserver on Downloads + common app download dirs
        val dirs = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File(Environment.getExternalStorageDirectory(), "LinkedIn"),
            File(Environment.getExternalStorageDirectory(), "WhatsApp/Media/WhatsApp Documents"),
            File(Environment.getExternalStorageDirectory(), "Telegram"),
        ).filter { it.exists() || it.mkdirs() }

        fileObserver = buildMultiDirObserver(dirs)
        fileObserver?.startWatching()
    }

    private fun buildMultiDirObserver(dirs: List<File>): FileObserver {
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dirs, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path?.lowercase()?.endsWith(".pdf") == true) {
                        dirs.forEach { d ->
                            val f = File(d, path)
                            if (f.exists()) enqueueUri(Uri.fromFile(f).toString())
                        }
                    }
                }
            }
        } else {
            // API 26-28: watch first dir only
            @Suppress("DEPRECATION")
            object : FileObserver(dirs.first().absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path?.lowercase()?.endsWith(".pdf") == true)
                        enqueueUri(Uri.fromFile(File(dirs.first(), path)).toString())
                }
            }
        }
    }

    private fun queryMediaStore(uri: Uri) {
        scope.launch {
            try {
                contentResolver.query(uri,
                    arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.MIME_TYPE),
                    null, null, null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val mime = runCatching {
                            cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
                        }.getOrNull()
                        val path = runCatching {
                            cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
                        }.getOrNull()
                        if (path != null && (mime == "application/pdf" || path.lowercase().endsWith(".pdf")))
                            enqueueUri(Uri.fromFile(File(path)).toString())
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun enqueueUri(uriStr: String) {
        val key = uriStr
        val now = System.currentTimeMillis()
        if (scanned[key]?.let { now - it < 5_000 } == true) return
        scanned[key] = now
        if (scanned.size > 200) scanned.entries.removeIf { now - it.value > 120_000 }

        scope.launch {
            val id = idCounter.getAndIncrement()
            val quickName = uriStr.substringAfterLast("/").substringAfterLast("%2F").ifBlank { "PDF" }

            // Show instant "Scanning…" notification — user sees it immediately
            AppNotificationManager.showScanning(applicationContext, quickName, id)

            try {
                delay(400) // brief wait for file write to flush

                val file = resolveToFile(uriStr) ?: run {
                    AppNotificationManager.dismiss(applicationContext, id); return@launch
                }
                if (!file.exists() || file.length() < 100) {
                    AppNotificationManager.dismiss(applicationContext, id); return@launch
                }

                val result = SecurityEngine.scanFile(applicationContext, file)
                // runCatching so an OutOfMemoryError while rendering a cover (an Error,
                // not an Exception — PdfCoverExtractor's own catch wouldn't catch it)
                // can never kill this coroutine and leave the notification stuck.
                val coverFile = if (result.isValidPdf)
                    runCatching { PdfCoverExtractor.extract(file, cacheDir) }.getOrNull() else null

                // Only a genuinely dangerous (CRITICAL) verdict is auto-deleted. Lower-risk
                // indicators — hyperlinks (/URI), encryption (/Encrypt), a "CVE-" string in
                // a security write-up, remote GoTo, etc. — are common in perfectly legit
                // PDFs, so we still surface the Upload prompt for them instead of silently
                // deleting the file (which is why the upload option "never" appeared).
                val isCritical = !result.isSafe && result.highestRisk == RiskLevel.CRITICAL

                if (isCritical) {
                    // Auto-delete the malicious file immediately
                    val originalPath = if (uriStr.startsWith("file://")) uriStr.removePrefix("file://") else file.absolutePath
                    val deleted = autoDeleteThreat(uriStr, file, originalPath)

                    HistoryStore.add(applicationContext, PdfRecord(
                        id = UUID.randomUUID().toString(),
                        fileName = file.name,
                        aiTitle = file.nameWithoutExtension,
                        driveLink = null,
                        coverPath = coverFile?.absolutePath,
                        filePath  = null,   // already deleted
                        fileSize = file.length(),
                        fileHash = PdfScanner.computeHash(file),
                        scanDate = System.currentTimeMillis(),
                        status = "THREAT",
                        threatCount = result.threatCount,
                        riskLevel = result.highestRisk.name
                    ))
                    AppNotificationManager.showThreat(applicationContext, result, id, deleted)
                } else {
                    // Safe, or flagged with only low/medium-risk indicators → offer upload.
                    // NOTE: we no longer delete the cached copy of content:// downloads here.
                    // It is the exact file the Upload action re-reads, so deleting it made
                    // tapping "Upload to Drive" silently fail. The OS clears app cache itself.
                    AppNotificationManager.showSafePrompt(applicationContext, result, file.absolutePath, coverFile?.absolutePath, id)
                }
            } catch (c: CancellationException) {
                // Service is shutting down — clear the in-flight notification, then let
                // cancellation propagate normally (don't swallow it).
                AppNotificationManager.dismiss(applicationContext, id)
                throw c
            } catch (t: Throwable) {
                // Never leave the ongoing "🔍 Scanning…" notification stuck — an unhandled
                // failure here was what made scanning appear to run forever.
                AppNotificationManager.dismiss(applicationContext, id)
            }
        }
    }

    // Delete malicious file — tries content URI first, then file path
    private fun autoDeleteThreat(uriStr: String, cacheFile: File, originalPath: String): Boolean {
        return try {
            var deleted = false
            // Try deleting via ContentResolver (works for MediaStore entries)
            if (uriStr.startsWith("content://")) {
                runCatching {
                    val rows = contentResolver.delete(Uri.parse(uriStr), null, null)
                    if (rows > 0) deleted = true
                }
            }
            // Also try deleting the real file path
            if (!deleted) {
                val real = File(originalPath)
                if (real.exists()) deleted = real.delete()
            }
            // Delete cache copy too
            if (cacheFile.absolutePath.startsWith(cacheDir.absolutePath)) cacheFile.delete()
            deleted
        } catch (_: Exception) { false }
    }

    // Resolve URI to a readable File — copies content:// URIs to cache
    private fun resolveToFile(uriStr: String): File? {
        return try {
            val uri = Uri.parse(uriStr)
            when {
                uriStr.startsWith("file://") -> {
                    File(uri.path ?: return null).takeIf { it.canRead() }
                }
                uriStr.startsWith("content://") -> {
                    copyContentToCache(uri)
                }
                else -> {
                    File(uriStr).takeIf { it.canRead() }
                }
            }
        } catch (_: Exception) { null }
    }

    private fun copyContentToCache(uri: Uri): File? {
        return try {
            val dest = File(cacheDir, "dl_${System.currentTimeMillis()}.pdf")
            contentResolver.openInputStream(uri)?.use { inp ->
                FileOutputStream(dest).use { out -> inp.copyTo(out) }
            }
            dest.takeIf { it.exists() && it.length() > 0 }
        } catch (_: Exception) { null }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        contentObserver?.let { contentResolver.unregisterContentObserver(it) }
        fileObserver?.stopWatching()
    }
}
