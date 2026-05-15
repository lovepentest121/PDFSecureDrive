package com.pdfsecuredrive.app.adapter

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import com.pdfsecuredrive.app.R
import com.pdfsecuredrive.app.model.VideoRecord
import com.pdfsecuredrive.app.storage.VideoHistoryStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoHistoryAdapter(
    private val records: MutableList<VideoRecord>,
    private val onChanged: () -> Unit
) : RecyclerView.Adapter<VideoHistoryAdapter.VH>() {

    private val dateFmt = SimpleDateFormat("dd MMM yy", Locale.getDefault())
    private var fullList: List<VideoRecord> = records.toList()

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val ivThumb            : ImageView   = v.findViewById(R.id.iv_thumb)
        val llFallback         : LinearLayout = v.findViewById(R.id.ll_thumb_fallback)
        val tvEmojiLarge       : TextView    = v.findViewById(R.id.tv_platform_emoji_large)
        val tvPlatformSmall    : TextView    = v.findViewById(R.id.tv_platform_label_small)
        val tvTitle            : TextView    = v.findViewById(R.id.tv_video_title)
        val tvPlatformEmoji    : TextView    = v.findViewById(R.id.tv_platform_emoji)
        val tvPlatformName     : TextView    = v.findViewById(R.id.tv_platform_name)
        val tvDate             : TextView    = v.findViewById(R.id.tv_date)
        val tvStatusBadge      : TextView    = v.findViewById(R.id.tv_status_badge)
        val btnCopyTitle       : ImageButton = v.findViewById(R.id.btn_copy_title)
        val btnOpen            : ImageButton = v.findViewById(R.id.btn_open)
        val btnDelete          : ImageButton = v.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video_record, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val rec = records[position]
        val ctx = h.itemView.context

        // Thumbnail — try local file first, then fallback to emoji
        val localFile = rec.localPath?.let { File(it) }
        if (localFile != null && localFile.exists()) {
            // We don't decode video frames — show platform emoji in thumbnail area
            h.ivThumb.visibility    = View.GONE
            h.llFallback.visibility = View.VISIBLE
        } else {
            h.ivThumb.visibility    = View.GONE
            h.llFallback.visibility = View.VISIBLE
        }
        h.tvEmojiLarge.text    = rec.platformEmoji
        h.tvPlatformSmall.text = rec.platform.uppercase()

        // Title
        h.tvTitle.text = rec.title.ifBlank { rec.platform }

        // Platform chip
        h.tvPlatformEmoji.text = rec.platformEmoji
        h.tvPlatformName.text  = rec.platform

        // Date
        h.tvDate.text = dateFmt.format(Date(rec.downloadDate))

        // Status badge
        when (rec.status) {
            "DOWNLOADED" -> {
                h.tvStatusBadge.text = "SAVED"
                h.tvStatusBadge.setTextColor(0xFFa78bfa.toInt())
                h.tvStatusBadge.setBackgroundResource(R.drawable.badge_downloaded)
            }
            "FAILED" -> {
                h.tvStatusBadge.text = "FAILED"
                h.tvStatusBadge.setTextColor(0xFFEF4444.toInt())
                h.tvStatusBadge.setBackgroundResource(R.drawable.badge_threat)
            }
            else -> {
                h.tvStatusBadge.text = "PENDING"
                h.tvStatusBadge.setTextColor(0xFF64748B.toInt())
                h.tvStatusBadge.setBackgroundResource(R.drawable.chip_bg)
            }
        }

        // Copy title
        h.btnCopyTitle.setOnClickListener {
            val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("title", rec.title))
            Toast.makeText(ctx, "Title copied!", Toast.LENGTH_SHORT).show()
        }

        // Open original URL
        h.btnOpen.setOnClickListener {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(rec.url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        // Delete
        h.btnDelete.setOnClickListener {
            val hasFile = rec.localPath?.let { File(it).exists() } == true
            if (hasFile) {
                AlertDialog.Builder(ctx)
                    .setTitle("Delete Video")
                    .setMessage("\"${rec.title.ifBlank { rec.platform }}\"\n\nDelete from history only, or also from your phone?")
                    .setPositiveButton("Phone + History") { _, _ ->
                        rec.localPath?.let { File(it).delete() }
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
                    .setMessage("\"${rec.title.ifBlank { rec.platform }}\"")
                    .setPositiveButton("Remove") { _, _ ->
                        removeRecord(ctx, rec, h.adapterPosition)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun removeRecord(ctx: Context, rec: VideoRecord, pos: Int) {
        VideoHistoryStore.delete(ctx, rec.id)
        if (pos < records.size) {
            records.removeAt(pos)
            notifyItemRemoved(pos)
        }
        onChanged()
    }

    override fun getItemCount() = records.size

    fun updateRecords(newList: List<VideoRecord>) {
        fullList = newList.toList()
        records.clear()
        records.addAll(newList)
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        records.clear()
        if (query.isBlank()) {
            records.addAll(fullList)
        } else {
            val q = query.lowercase()
            records.addAll(fullList.filter {
                it.title.lowercase().contains(q) ||
                it.platform.lowercase().contains(q) ||
                it.status.lowercase().contains(q)
            })
        }
        notifyDataSetChanged()
    }
}
