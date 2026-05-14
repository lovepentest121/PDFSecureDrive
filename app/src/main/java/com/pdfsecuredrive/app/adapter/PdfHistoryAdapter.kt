package com.pdfsecuredrive.app.adapter

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.pdfsecuredrive.app.PdfDetailActivity
import com.pdfsecuredrive.app.R
import com.pdfsecuredrive.app.model.PdfRecord
import com.pdfsecuredrive.app.storage.HistoryStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PdfHistoryAdapter(
    private val records: MutableList<PdfRecord>,
    private val onChanged: () -> Unit
) : RecyclerView.Adapter<PdfHistoryAdapter.VH>() {

    private val dateFmt = SimpleDateFormat("dd MMM yy", Locale.getDefault())
    private var fullList: List<PdfRecord> = records.toList()

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val ivCover        : ImageView    = v.findViewById(R.id.iv_cover)
        val llFallback     : LinearLayout = v.findViewById(R.id.ll_cover_fallback)
        val tvAiTitle      : TextView     = v.findViewById(R.id.tv_ai_title)
        val tvFilename     : TextView     = v.findViewById(R.id.tv_filename)
        val tvDate         : TextView     = v.findViewById(R.id.tv_date)
        val tvSyncLabel    : TextView     = v.findViewById(R.id.tv_sync_label)
        val tvStatusBadge  : TextView     = v.findViewById(R.id.tv_status_badge)
        val btnCopyTitle   : ImageButton  = v.findViewById(R.id.btn_copy_title)
        val btnCopy        : ImageButton  = v.findViewById(R.id.btn_copy)
        val btnOpen        : ImageButton  = v.findViewById(R.id.btn_open)
        val btnDelete      : ImageButton  = v.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_record, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val rec = records[position]
        val ctx = h.itemView.context

        // Cover thumbnail
        val cover = rec.coverPath?.let { File(it) }
        if (cover != null && cover.exists()) {
            val bmp = BitmapFactory.decodeFile(cover.absolutePath)
            if (bmp != null) {
                h.ivCover.setImageBitmap(bmp)
                h.ivCover.visibility    = View.VISIBLE
                h.llFallback.visibility = View.GONE
            } else {
                h.ivCover.visibility    = View.GONE
                h.llFallback.visibility = View.VISIBLE
            }
        } else {
            h.ivCover.visibility    = View.GONE
            h.llFallback.visibility = View.VISIBLE
        }

        // Titles
        h.tvAiTitle.text  = rec.aiTitle.ifBlank { rec.fileName }
        h.tvFilename.text = rec.fileName
        h.tvDate.text     = dateFmt.format(Date(rec.scanDate))

        // Status
        when (rec.status) {
            "UPLOADED" -> {
                h.tvStatusBadge.text = "SECURED"
                h.tvStatusBadge.setTextColor(0xFF10B981.toInt())
                h.tvStatusBadge.setBackgroundResource(R.drawable.badge_uploaded)
                h.tvSyncLabel.text = "DRIVE SYNC"
                h.tvSyncLabel.setTextColor(0xFF3B82F6.toInt())
            }
            "THREAT" -> {
                h.tvStatusBadge.text = "THREAT"
                h.tvStatusBadge.setTextColor(0xFFEF4444.toInt())
                h.tvStatusBadge.setBackgroundResource(R.drawable.badge_threat)
                h.tvSyncLabel.text = "BLOCKED"
                h.tvSyncLabel.setTextColor(0xFFEF4444.toInt())
            }
            else -> {
                h.tvStatusBadge.text = "SAFE"
                h.tvStatusBadge.setTextColor(0xFF3B82F6.toInt())
                h.tvStatusBadge.setBackgroundResource(R.drawable.chip_bg)
                h.tvSyncLabel.text = "LOCAL"
                h.tvSyncLabel.setTextColor(0xFF3B5B8E.toInt())
            }
        }

        // Copy AI title to clipboard
        h.btnCopyTitle.setOnClickListener {
            val title = rec.aiTitle.ifBlank { rec.fileName }
            val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("title", title))
            Toast.makeText(ctx, "Title copied!", Toast.LENGTH_SHORT).show()
        }

        // Copy Drive link to clipboard
        h.btnCopy.setOnClickListener {
            val link = rec.driveLink
            if (link.isNullOrBlank()) {
                Toast.makeText(ctx, "No Drive link yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("drive_link", link))
            Toast.makeText(ctx, "Drive link copied!", Toast.LENGTH_SHORT).show()
        }

        // Open in browser
        h.btnOpen.setOnClickListener {
            val link = rec.driveLink
            if (link.isNullOrBlank()) {
                Toast.makeText(ctx, "Not uploaded to Drive yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        // Delete — offer to delete from history only OR from phone too
        h.btnDelete.setOnClickListener {
            val hasFile = rec.filePath?.let { File(it).exists() } == true
            if (hasFile) {
                AlertDialog.Builder(ctx)
                    .setTitle("Delete PDF")
                    .setMessage("\"${rec.aiTitle.ifBlank { rec.fileName }}\"\n\nDelete from history only, or also from your phone?")
                    .setPositiveButton("Phone + History") { _, _ ->
                        rec.filePath?.let { File(it).delete() }
                        removeRecord(ctx, rec, h.adapterPosition)
                    }
                    .setNeutralButton("History only") { _, _ ->
                        removeRecord(ctx, rec, h.adapterPosition)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                AlertDialog.Builder(ctx)
                    .setTitle("Remove from history?")
                    .setMessage("\"${rec.aiTitle.ifBlank { rec.fileName }}\"")
                    .setPositiveButton("Remove") { _, _ ->
                        removeRecord(ctx, rec, h.adapterPosition)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        // Tap → detail screen
        h.itemView.setOnClickListener {
            val i = Intent(ctx, PdfDetailActivity::class.java).apply {
                putExtra(PdfDetailActivity.EXTRA_RECORD_ID, rec.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
        }
    }

    private fun removeRecord(ctx: Context, rec: PdfRecord, pos: Int) {
        HistoryStore.delete(ctx, rec.id)
        if (pos < records.size) {
            records.removeAt(pos)
            notifyItemRemoved(pos)
        }
        onChanged()
    }

    override fun getItemCount() = records.size

    fun updateRecords(newList: List<PdfRecord>) {
        fullList = newList.toList()
        records.clear()
        records.addAll(newList)
        notifyDataSetChanged()
    }

    fun getFullList(): List<PdfRecord> = fullList

    fun filter(query: String) {
        records.clear()
        if (query.isBlank()) {
            records.addAll(fullList)
        } else {
            val q = query.lowercase()
            records.addAll(fullList.filter {
                it.fileName.lowercase().contains(q) ||
                it.aiTitle.lowercase().contains(q) ||
                it.status.lowercase().contains(q)
            })
        }
        notifyDataSetChanged()
    }
}
