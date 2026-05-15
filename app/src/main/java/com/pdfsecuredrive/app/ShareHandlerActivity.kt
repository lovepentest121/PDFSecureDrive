package com.pdfsecuredrive.app

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pdfsecuredrive.app.model.VideoRecord
import com.pdfsecuredrive.app.storage.VideoHistoryStore
import com.pdfsecuredrive.app.video.*
import kotlinx.coroutines.*
import java.util.UUID

class ShareHandlerActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentInfo: VideoInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_share_handler)

        val sharedText = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }

        val url = sharedText?.let { VideoLinkDetector.extractUrl(it) }
            ?: intent?.dataString?.let { VideoLinkDetector.extractUrl(it) }

        if (url == null) {
            Toast.makeText(this, "No video link found", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        setupStaticUI(url)
        fetchInfo(url)
    }

    private fun setupStaticUI(url: String) {
        val platform = VideoLinkDetector.platform(url)
        findViewById<TextView>(R.id.tv_platform).text = "${platform.emoji} ${platform.label}"
        findViewById<TextView>(R.id.tv_video_url).text = url.take(55) + if (url.length > 55) "…" else ""
        findViewById<TextView>(R.id.tv_video_title).text = "Fetching title…"
        findViewById<ProgressBar>(R.id.pb_fetch).visibility = View.VISIBLE
        findViewById<Button>(R.id.btn_download).isEnabled = false
        findViewById<Button>(R.id.btn_copy_title).visibility = View.GONE
        findViewById<TextView>(R.id.btn_close).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_open_browser).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun fetchInfo(url: String) {
        scope.launch {
            val info = withContext(Dispatchers.IO) { VideoTitleFetcher.fetch(url) }
            currentInfo = info
            displayInfo(info)
        }
    }

    private fun displayInfo(info: VideoInfo) {
        findViewById<ProgressBar>(R.id.pb_fetch).visibility = View.GONE
        val tvTitle = findViewById<TextView>(R.id.tv_video_title)
        val tvPlatform = findViewById<TextView>(R.id.tv_platform)
        val btnDl = findViewById<Button>(R.id.btn_download)
        val btnCopyTitle = findViewById<Button>(R.id.btn_copy_title)

        tvTitle.text = info.title
        tvPlatform.text = "${info.platform.emoji} ${info.platform.label}"

        // Copy title button
        btnCopyTitle.visibility = View.VISIBLE
        btnCopyTitle.setOnClickListener {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("title", info.title))
            Toast.makeText(this, "Title copied!", Toast.LENGTH_SHORT).show()
        }

        // Configure download button based on platform
        when (info.platform) {
            Platform.YOUTUBE -> {
                btnDl.text = "⬇️  Download Video (MP4)"
                btnDl.isEnabled = true
                btnDl.setOnClickListener { startYouTubeDownload(info) }
            }
            else -> {
                // For other platforms, try direct download or open in app
                btnDl.text = "⬇️  Download / Open"
                btnDl.isEnabled = true
                btnDl.setOnClickListener { handleOtherPlatform(info) }
            }
        }

        findViewById<Button>(R.id.btn_open_browser).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun startYouTubeDownload(info: VideoInfo) {
        val btnDl = findViewById<Button>(R.id.btn_download)
        val pb    = findViewById<ProgressBar>(R.id.pb_fetch)

        btnDl.isEnabled = false
        btnDl.text = "🔍 Finding stream…"
        pb.visibility = View.VISIBLE

        scope.launch {
            val stream = withContext(Dispatchers.IO) { VideoExtractor.extractStream(info) }
            pb.visibility = View.GONE

            if (stream != null) {
                btnDl.text = "⬇️ Starting download…"
                startDownload(info, stream.directUrl)
            } else {
                // Fallback: open in YouTube app or browser
                btnDl.isEnabled = true
                btnDl.text = "▶️ Open in YouTube"
                Toast.makeText(this@ShareHandlerActivity,
                    "Direct stream not available — opening YouTube", Toast.LENGTH_LONG).show()
                openInAppOrBrowser(info.url, "com.google.android.youtube")
            }
        }
    }

    private fun handleOtherPlatform(info: VideoInfo) {
        // Try to detect direct mp4 in URL, else open in native app
        if (info.url.contains(Regex("\\.(mp4|webm|mov)(\\?|$)", RegexOption.IGNORE_CASE))) {
            startDownload(info, info.url)
        } else {
            val pkg = when (info.platform) {
                Platform.INSTAGRAM -> "com.instagram.android"
                Platform.TWITTER   -> "com.twitter.android"
                Platform.LINKEDIN  -> "com.linkedin.android"
                Platform.TIKTOK    -> "com.zhiliaoapp.musically"
                Platform.FACEBOOK  -> "com.facebook.katana"
                else               -> null
            }
            openInAppOrBrowser(info.url, pkg)
            Toast.makeText(this,
                "Opening in ${info.platform.label} — use in-app save/download option",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun startDownload(info: VideoInfo, downloadUrl: String) {
        try {
            val safeTitle = info.title.replace(Regex("[^a-zA-Z0-9 ]"), "").trim().take(40)
            val fileName  = "${safeTitle.ifBlank { info.platform.label }}_${System.currentTimeMillis()}.mp4"

            val req = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle(info.title)
                setDescription("Downloading ${info.platform.label} video…")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "PDFSecureDrive/$fileName")
                addRequestHeader("User-Agent",
                    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/112 Mobile Safari/537.36")
                addRequestHeader("Referer", "https://www.youtube.com/")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)

            // Save to video history
            VideoHistoryStore.add(this, VideoRecord(
                id           = UUID.randomUUID().toString(),
                title        = info.title,
                url          = info.url,
                platform     = info.platform.label,
                platformEmoji = info.platform.emoji,
                localPath    = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)}/PDFSecureDrive/$fileName",
                thumbnailUrl  = info.thumbnailUrl,
                downloadDate = System.currentTimeMillis(),
                status       = "DOWNLOADED"
            ))

            Toast.makeText(this, "⬇️ Download started! Check notification bar.", Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            findViewById<Button>(R.id.btn_download).apply { isEnabled = true; text = "⬇️ Retry" }
        }
    }

    private fun openInAppOrBrowser(url: String, packageName: String?) {
        if (packageName != null) {
            try {
                packageManager.getLaunchIntentForPackage(packageName) ?: throw Exception()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                return
            } catch (_: Exception) {}
        }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}
