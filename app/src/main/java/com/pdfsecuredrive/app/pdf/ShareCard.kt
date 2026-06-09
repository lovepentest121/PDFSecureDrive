package com.pdfsecuredrive.app.pdf

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a shareable "subscriber card" image (cover + title + "[Only for subscriber]"
 * badge + Drive link) and saves it to the device gallery.
 *
 * The previous saveCoverToGallery used Environment.getExternalStoragePublicDirectory +
 * MediaScannerConnection only, which silently fails on Android 10+ (scoped storage) — that
 * is why "save to gallery" did nothing on modern devices. saveToGallery() below uses
 * MediaStore on API 29+ and falls back to the legacy path only on API <= 28.
 */
object ShareCard {

    private const val GALLERY_SUBDIR = "PDFSecureDrive"
    const val SUBSCRIBER_BADGE = "[Only for subscriber]"

    /** Build card, save to gallery. Returns true on success. */
    fun saveShareCard(context: Context, coverPath: String?, title: String, link: String?): Boolean {
        val card = buildShareCard(coverPath, title, link ?: "")
        val ok = saveToGallery(context, card, "pdfcard_${System.currentTimeMillis()}")
        card.recycle()
        return ok
    }

    /** Save a plain cover image (a File) to the gallery — replaces the broken legacy helper. */
    fun saveImageFile(context: Context, src: File): Boolean {
        if (!src.exists()) return false
        val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: return false
        val ok = saveToGallery(context, bmp, "pdfcover_${System.currentTimeMillis()}")
        bmp.recycle()
        return ok
    }

    // ---------------------------------------------------------------------------------------
    // Card rendering
    // ---------------------------------------------------------------------------------------

    fun buildShareCard(coverPath: String?, title: String, link: String): Bitmap {
        val cardWidth = 1080
        val pad = 56
        val gapAfterCover = 40
        val gapAfterTitle = 24
        val gapAfterBadge = 26
        val coverMaxHeight = 720
        val coverRadius = 28f
        val badgeRadius = 16f
        val badgePadH = 26
        val badgePadV = 13

        val cardBg = Color.parseColor("#0F1629")
        val placeholderBg = Color.parseColor("#1B2238")
        val placeholderFg = Color.parseColor("#5B6679")
        val titleColor = Color.parseColor("#F1F5F9")
        val badgeBg = Color.parseColor("#FFD166")
        val badgeText = Color.parseColor("#5A3D00")
        val urlColor = Color.parseColor("#60A5FA")

        val contentWidth = cardWidth - pad * 2

        val cover: Bitmap? = decodeCover(coverPath, contentWidth)

        val coverDest: Rect = if (cover != null) {
            val scale = contentWidth.toFloat() / cover.width.toFloat()
            val h = (cover.height * scale).toInt().coerceIn(1, coverMaxHeight)
            Rect(pad, pad, pad + contentWidth, pad + h)
        } else {
            Rect(pad, pad, pad + contentWidth, pad + 480)
        }

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = titleColor
            textSize = 54f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val titleLayout = buildStaticLayout(title.ifBlank { "Untitled PDF" }, titlePaint, contentWidth, 4)

        val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = badgeText
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val badgeFm = badgePaint.fontMetrics
        val badgeTextHeight = badgeFm.descent - badgeFm.ascent
        val badgeBoxWidth = badgePaint.measureText(SUBSCRIBER_BADGE) + badgePadH * 2
        val badgeBoxHeight = badgeTextHeight + badgePadV * 2

        val hasLink = link.isNotBlank()
        val urlPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = urlColor
            textSize = 34f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        val urlFm = urlPaint.fontMetrics
        val urlHeight = if (hasLink) (urlFm.descent - urlFm.ascent) else 0f
        val urlText = if (hasLink)
            TextUtils.ellipsize(link, urlPaint, contentWidth.toFloat(), TextUtils.TruncateAt.MIDDLE).toString()
        else ""

        val totalHeight = (
            coverDest.bottom + gapAfterCover +
            titleLayout.height + gapAfterTitle +
            badgeBoxHeight.toInt() + (if (hasLink) gapAfterBadge + urlHeight.toInt() else 0) + pad
        )

        val bitmap = Bitmap.createBitmap(cardWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(cardBg)

        val coverRectF = RectF(coverDest)
        if (cover != null) {
            val save = canvas.save()
            val clip = Path().apply { addRoundRect(coverRectF, coverRadius, coverRadius, Path.Direction.CW) }
            canvas.clipPath(clip)
            canvas.drawBitmap(cover, null, coverDest, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restoreToCount(save)
        } else {
            val ph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = placeholderBg }
            canvas.drawRoundRect(coverRectF, coverRadius, coverRadius, ph)
            val phText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = placeholderFg; textSize = 88f; textAlign = Paint.Align.CENTER
            }
            canvas.drawText("📄", coverRectF.centerX(), coverRectF.centerY() - (phText.descent() + phText.ascent()) / 2f, phText)
        }

        var cursorY = (coverDest.bottom + gapAfterCover).toFloat()
        canvas.save()
        canvas.translate(pad.toFloat(), cursorY)
        titleLayout.draw(canvas)
        canvas.restore()
        cursorY += titleLayout.height + gapAfterTitle

        val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = badgeBg }
        val badgeRect = RectF(pad.toFloat(), cursorY, pad + badgeBoxWidth, cursorY + badgeBoxHeight)
        canvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, badgeBgPaint)
        val badgeBaseline = badgeRect.centerY() - (badgeFm.ascent + badgeFm.descent) / 2f
        canvas.drawText(SUBSCRIBER_BADGE, badgeRect.left + badgePadH, badgeBaseline, badgePaint)
        cursorY += badgeBoxHeight + gapAfterBadge

        if (hasLink) {
            val urlBaseline = cursorY - urlFm.ascent
            canvas.drawText(urlText, pad.toFloat(), urlBaseline, urlPaint)
        }

        cover?.recycle()
        return bitmap
    }

    private fun decodeCover(coverPath: String?, reqWidth: Int): Bitmap? {
        if (coverPath.isNullOrBlank()) return null
        val file = File(coverPath)
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(coverPath, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > reqWidth * 2) sample *= 2
            BitmapFactory.decodeFile(coverPath, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) { null }
    }

    private fun buildStaticLayout(text: CharSequence, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.12f)
            .setIncludePad(false)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(maxLines)
            .build()

    // ---------------------------------------------------------------------------------------
    // Gallery save (MediaStore on API 29+, legacy File on API <= 28)
    // ---------------------------------------------------------------------------------------

    fun saveToGallery(context: Context, bitmap: Bitmap, displayName: String): Boolean {
        val name = if (displayName.endsWith(".png", true)) displayName else "$displayName.png"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            saveScoped(context, bitmap, name)
        else
            saveLegacy(context, bitmap, name)
    }

    private fun saveScoped(context: Context, bitmap: Bitmap, name: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + GALLERY_SUBDIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                    throw java.io.IOException("PNG compress failed")
            } ?: throw java.io.IOException("openOutputStream null")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    private fun saveLegacy(context: Context, bitmap: Bitmap, name: String): Boolean {
        return try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), GALLERY_SUBDIR)
                .also { it.mkdirs() }
            val dest = File(dir, name)
            FileOutputStream(dest).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                    throw java.io.IOException("PNG compress failed")
            }
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("image/png"), null)
            true
        } catch (_: Exception) { false }
    }
}
