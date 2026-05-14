package com.pdfsecuredrive.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pdfsecuredrive.app.model.PdfRecord
import com.pdfsecuredrive.app.notification.AppNotificationManager
import com.pdfsecuredrive.app.storage.HistoryStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PdfDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RECORD_ID = "rid"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_pdf_detail)

        val id = intent.getStringExtra(EXTRA_RECORD_ID) ?: run { finish(); return }
        val record = HistoryStore.getAll(this).firstOrNull { it.id == id } ?: run { finish(); return }

        bind(record)
        findViewById<ImageButton>(R.id.btn_close).setOnClickListener { finish() }
    }

    private fun bind(rec: PdfRecord) {
        val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

        // Header
        findViewById<TextView>(R.id.tv_header).text = rec.fileName

        // Cover
        val coverFile = rec.coverPath?.let { File(it) }
        if (coverFile != null && coverFile.exists()) {
            val bmp = BitmapFactory.decodeFile(coverFile.absolutePath)
            if (bmp != null) {
                findViewById<ImageView>(R.id.iv_cover).setImageBitmap(bmp)
                if (!rec.coverPath.isNullOrBlank()) {
                    findViewById<Button>(R.id.btn_save_cover).visibility = View.VISIBLE
                }
            } else {
                showNoCover()
            }
        } else {
            showNoCover()
        }

        // Status badge
        val tvStatus = findViewById<TextView>(R.id.tv_detail_status)
        when (rec.status) {
            "UPLOADED" -> { tvStatus.text = "✓ UPLOADED"; tvStatus.setTextColor(0xFF10B981.toInt()); tvStatus.setBackgroundResource(R.drawable.badge_uploaded) }
            "THREAT"   -> { tvStatus.text = "⚠ THREAT";  tvStatus.setTextColor(0xFFEF4444.toInt()); tvStatus.setBackgroundResource(R.drawable.badge_threat) }
            else       -> { tvStatus.text = "✓ SAFE";    tvStatus.setTextColor(0xFF3B82F6.toInt()); tvStatus.setBackgroundResource(R.drawable.chip_bg) }
        }

        // AI Title
        val titleStr = rec.aiTitle.ifBlank { rec.fileName }
        findViewById<TextView>(R.id.tv_ai_title).text = titleStr

        // Details
        findViewById<TextView>(R.id.tv_size).text = AppNotificationManager.fmtSize(rec.fileSize)
        findViewById<TextView>(R.id.tv_detail_date).text = dateFmt.format(Date(rec.scanDate))
        findViewById<TextView>(R.id.tv_hash).text = "${rec.fileHash.take(16)}…"

        // Drive buttons
        val hasLink = !rec.driveLink.isNullOrBlank()
        val btnOpen = findViewById<Button>(R.id.btn_open_drive)
        val btnCopy = findViewById<Button>(R.id.btn_copy_link)

        if (!hasLink) {
            btnOpen.isEnabled = false; btnOpen.alpha = 0.4f
            btnCopy.isEnabled = false; btnCopy.alpha = 0.4f
        }

        btnOpen.setOnClickListener {
            if (hasLink) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rec.driveLink)))
        }
        btnCopy.setOnClickListener {
            if (hasLink) {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("link", rec.driveLink))
                Toast.makeText(this, "Drive link copied", Toast.LENGTH_SHORT).show()
            }
        }

        // Save cover
        findViewById<Button>(R.id.btn_save_cover).setOnClickListener {
            saveCoverToGallery(rec.coverPath)
        }
    }

    private fun showNoCover() {
        findViewById<ImageView>(R.id.iv_cover).visibility = View.GONE
        findViewById<TextView>(R.id.tv_no_cover).visibility = View.VISIBLE
    }

    private fun saveCoverToGallery(coverPath: String?) {
        if (coverPath == null) return
        try {
            val src = File(coverPath); if (!src.exists()) return
            val dest = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
                "PDFCovers"
            ).also { it.mkdirs() }
            FileInputStream(src).use { inp -> FileOutputStream(File(dest, src.name)).use { out -> inp.copyTo(out) } }
            android.media.MediaScannerConnection.scanFile(this, arrayOf(File(dest, src.name).absolutePath), arrayOf("image/jpeg"), null)
            Toast.makeText(this, "Cover saved to Pictures/PDFCovers", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Could not save cover", Toast.LENGTH_SHORT).show()
        }
    }
}
