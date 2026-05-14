package com.pdfsecuredrive.app.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

object PdfCoverExtractor {

    private const val COVER_WIDTH  = 480
    private const val JPEG_QUALITY = 82

    fun extract(pdfFile: File, cacheDir: File): File? {
        return try {
            val outDir = File(cacheDir, "covers").also { it.mkdirs() }
            val outFile = File(outDir, "${pdfFile.nameWithoutExtension}_cover.jpg")
            if (outFile.exists() && outFile.length() > 0) return outFile

            val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            if (renderer.pageCount == 0) { renderer.close(); fd.close(); return null }

            val page = renderer.openPage(0)
            val scale = COVER_WIDTH.toFloat() / page.width.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(COVER_WIDTH, (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close(); renderer.close(); fd.close()

            FileOutputStream(outFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            bmp.recycle()
            outFile
        } catch (_: Exception) { null }
    }
}
