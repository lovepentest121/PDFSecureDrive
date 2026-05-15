package com.pdfsecuredrive.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pdfsecuredrive.app.adapter.VideoHistoryAdapter
import com.pdfsecuredrive.app.model.VideoRecord
import com.pdfsecuredrive.app.storage.VideoHistoryStore
import com.pdfsecuredrive.app.video.VideoLinkDetector

class VideoActivity : AppCompatActivity() {

    private lateinit var adapter: VideoHistoryAdapter
    private val records = mutableListOf<VideoRecord>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_video)

        setupTopBar()
        setupDownloaderCard()
        setupRecyclerView()
        refreshHistory()
    }

    override fun onResume() {
        super.onResume()
        refreshHistory()
        checkClipboard()
    }

    private fun setupTopBar() {
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
    }

    private fun setupDownloaderCard() {
        val etUrl    = findViewById<EditText>(R.id.et_video_url)
        val btnPaste = findViewById<Button>(R.id.btn_paste_url)
        val btnDl    = findViewById<Button>(R.id.btn_video_download)

        btnPaste.setOnClickListener {
            val cm   = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (text.isNotBlank()) {
                etUrl.setText(text)
                etUrl.setSelection(text.length)
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        btnDl.setOnClickListener {
            val raw = etUrl.text?.toString()?.trim() ?: ""
            val url = if (raw.isBlank()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
            } else raw

            val extracted = VideoLinkDetector.extractUrl(url)
            if (extracted == null) {
                Toast.makeText(this, "Please paste a valid video link", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            openVideoHandler(extracted)
            etUrl.setText("")
        }

        etUrl.setOnEditorActionListener { _, _, _ -> btnDl.performClick(); true }
    }

    private fun openVideoHandler(url: String) {
        startActivity(Intent(this, ShareHandlerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data   = Uri.parse(url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun checkClipboard() {
        try {
            val cm   = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: return
            val url  = VideoLinkDetector.extractUrl(text) ?: return
            if (!VideoLinkDetector.isVideoLink(url)) return
            val et = findViewById<EditText>(R.id.et_video_url)
            if (et.text.isNullOrBlank()) {
                et.setText(url)
                val p = VideoLinkDetector.platform(url)
                Toast.makeText(this, "${p.emoji} ${p.label} link detected!", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {}
    }

    private fun setupRecyclerView() {
        adapter = VideoHistoryAdapter(records) { refreshHistory() }
        val rv  = findViewById<RecyclerView>(R.id.rv_videos)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        val et = findViewById<EditText>(R.id.et_search)
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun refreshHistory() {
        val all = VideoHistoryStore.getAll(this)
        adapter.updateRecords(all)

        val empty = all.isEmpty()
        findViewById<View>(R.id.ll_empty).visibility    = if (empty) View.VISIBLE else View.GONE
        findViewById<RecyclerView>(R.id.rv_videos).visibility = if (empty) View.GONE else View.VISIBLE
        runCatching {
            findViewById<View>(R.id.ll_search).visibility = if (empty) View.GONE else View.VISIBLE
        }

        val count = VideoHistoryStore.totalDownloads(this)
        findViewById<TextView>(R.id.tv_download_count).text = "$count"
        findViewById<TextView>(R.id.tv_saved_badge).text    = "$count SAVED"
    }

}

