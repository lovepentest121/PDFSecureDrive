package com.pdfsecuredrive.app

import android.app.DownloadManager
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
import com.pdfsecuredrive.app.video.Platform
import com.pdfsecuredrive.app.video.VideoInfo
import com.pdfsecuredrive.app.video.VideoLinkDetector
import com.pdfsecuredrive.app.video.VideoTitleFetcher
import kotlinx.coroutines.*

class ShareHandlerActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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

        if (url == null) {
            Toast.makeText(this, "No video link found", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        val platform = VideoLinkDetector.platform(url)
        setupUI(url, platform)
        fetchAndDisplay(url)
    }

    private fun setupUI(url: String, platform: Platform) {
        findViewById<TextView>(R.id.tv_platform).text = "${platform.emoji} ${platform.label}"
        findViewById<TextView>(R.id.tv_video_url).text = url.take(60) + if (url.length > 60) "…" else ""
        findViewById<TextView>(R.id.tv_video_title).text = "Fetching title…"
        findViewById<ProgressBar>(R.id.pb_fetch).visibility = View.VISIBLE

        findViewById<Button>(R.id.btn_download).isEnabled = false
        findViewById<Button>(R.id.btn_open_browser).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        findViewById<TextView>(R.id.btn_close).setOnClickListener { finish() }
    }

    private fun fetchAndDisplay(url: String) {
        scope.launch {
            val info = withContext(Dispatchers.IO) {
                VideoTitleFetcher.fetch(url)
            }

            findViewById<ProgressBar>(R.id.pb_fetch).visibility = View.GONE
            displayVideoInfo(info)
        }
    }

    private fun displayVideoInfo(info: VideoInfo) {
        val tvTitle    = findViewById<TextView>(R.id.tv_video_title)
        val tvPlatform = findViewById<TextView>(R.id.tv_platform)
        val btnDl      = findViewById<Button>(R.id.btn_download)
        val btnBrowser = findViewById<Button>(R.id.btn_open_browser)

        tvTitle.text    = info.title
        tvPlatform.text = "${info.platform.emoji} ${info.platform.label}"

        when (info.platform) {
            Platform.YOUTUBE -> {
                // YouTube: open in YouTube app or browser (yt-dlp not bundled)
                btnDl.text = "▶️ Open in YouTube"
                btnDl.isEnabled = true
                btnDl.setOnClickListener {
                    openInApp(info.url, "com.google.android.youtube") ?: run {
                        openBrowser(info.url)
                    }
                }
                btnBrowser.text = "🌐 Open in Browser"
            }
            Platform.TIKTOK -> {
                btnDl.text = "▶️ Open in TikTok"
                btnDl.isEnabled = true
                btnDl.setOnClickListener { openBrowser(info.url) }
            }
            Platform.INSTAGRAM -> {
                btnDl.text = "▶️ Open in Instagram"
                btnDl.isEnabled = true
                btnDl.setOnClickListener {
                    openInApp(info.url, "com.instagram.android") ?: openBrowser(info.url)
                }
            }
            Platform.TWITTER -> {
                btnDl.text = "▶️ Open in Twitter/X"
                btnDl.isEnabled = true
                btnDl.setOnClickListener {
                    openInApp(info.url, "com.twitter.android")
                        ?: openInApp(info.url, "com.x.android")
                        ?: openBrowser(info.url)
                }
            }
            Platform.LINKEDIN -> {
                btnDl.text = "▶️ Open in LinkedIn"
                btnDl.isEnabled = true
                btnDl.setOnClickListener {
                    openInApp(info.url, "com.linkedin.android") ?: openBrowser(info.url)
                }
            }
            else -> {
                // Try direct download if URL ends with video extension
                if (info.url.contains(Regex("\\.(mp4|webm|mkv|mov)(\\?|$)", RegexOption.IGNORE_CASE))) {
                    btnDl.text = "⬇️ Download Video"
                    btnDl.isEnabled = true
                    btnDl.setOnClickListener { startDirectDownload(info) }
                } else {
                    btnDl.text = "🌐 Open in Browser"
                    btnDl.isEnabled = true
                    btnDl.setOnClickListener { openBrowser(info.url) }
                }
            }
        }
        btnBrowser.setOnClickListener { openBrowser(info.url) }
    }

    private fun startDirectDownload(info: VideoInfo) {
        try {
            val fileName = "${info.title.replace(Regex("[^a-zA-Z0-9 ]"), "").take(40)}.mp4"
            val req = DownloadManager.Request(Uri.parse(info.url)).apply {
                setTitle(info.title)
                setDescription("Downloading from ${info.platform.label}")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, fileName)
                addRequestHeader("User-Agent",
                    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/112")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            Toast.makeText(this, "⬇️ Download started!", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openInApp(url: String, packageName: String): Unit? = try {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    } catch (_: Exception) { null }

    private fun openBrowser(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
