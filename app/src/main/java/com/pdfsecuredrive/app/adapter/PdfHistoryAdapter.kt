package com.pdfsecuredrive.app.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.pdfsecuredrive.app.PdfDetailActivity
import com.pdfsecuredrive.app.R
import com.pdfsecuredrive.app.model.PdfRecord
import com.pdfsecuredrive.app.storage.HistoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PdfHistoryAdapter(
    private val records: MutableList<PdfRecord>,
    private val onChanged: () -> Unit
) : RecyclerView.Adapter<PdfHistoryAdapter.VH>() {

    private val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvFilename    : TextView    = v.findViewById(R.id.tv_filename)
        val tvDate        : TextView    = v.findViewById(R.id.tv_date)
        val tvSyncLabel   : TextView    = v.findViewById(R.id.tv_sync_label)
        val tvStatusBadge : TextView    = v.findViewById(R.id.tv_status_badge)
        val btnCopy       : ImageButton = v.findViewById(R.id.btn_copy)
        val btnOpen       : ImageButton = v.findViewById(R.id.btn_open)
        val btnDelete     : ImageButton = v.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_record, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val rec = records[position]
        val ctx = h.itemView.context

        h.tvFilename.text = rec.fileName
        h.tvDate.text     = dateFmt.format(Date(rec.scanDate))

        when (rec.status) {
            "UPLOADED" -> {
                h.tvStatusBadge.text = "UPLOADED"
                h.tvStatusBadge.setTextColor(0xFF10B981.toInt())
                h.tvStatusBadge.setBackgroundResource(R.drawable.badge_uploaded)
                h.tvSyncLabel.text = "DRIVE SYNC"
            }
            "THREAT" -> {
                h.tvStatusBadge.text = "THREAT"
                h.tvStatusBadge.setTextColor(0xFFEF4444.toInt())
                h.tvStatusBadge.setBackgroundResource(R.drawable.badge_threat)
                h.tvSyncLabel.text = "BLOCKED"
            }
            else -> {
                h.tvStatusBadge.text = "SAFE"
                h.tvStatusBadge.setTextColor(0xFF3B82F6.toInt())
                h.tvStatusBadge.setBackgroundResource(R.drawable.chip_bg)
                h.tvSyncLabel.text = "LOCAL"
            }
        }

        // Copy link
        h.btnCopy.setOnClickListener {
            val link = rec.driveLink
            if (link.isNullOrBlank()) { Toast.makeText(ctx, "No Drive link", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("link", link))
            Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show()
        }

        // Open in browser
        h.btnOpen.setOnClickListener {
            val link = rec.driveLink
            if (link.isNullOrBlank()) { Toast.makeText(ctx, "No Drive link", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        // Delete from history
        h.btnDelete.setOnClickListener {
            HistoryStore.delete(ctx, rec.id)
            records.removeAt(h.adapterPosition)
            notifyItemRemoved(h.adapterPosition)
            onChanged()
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

    override fun getItemCount() = records.size

    fun updateRecords(newList: List<PdfRecord>) {
        records.clear()
        records.addAll(newList)
        notifyDataSetChanged()
    }
}
