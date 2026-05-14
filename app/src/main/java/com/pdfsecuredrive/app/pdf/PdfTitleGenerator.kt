package com.pdfsecuredrive.app.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.concurrent.TimeoutException

object PdfTitleGenerator {

    fun generate(file: File): String {
        return try {
            PDDocument.load(file).use { doc ->
                // 1. PDF metadata title
                val meta = doc.documentInformation?.title?.trim()
                if (!meta.isNullOrBlank() && meta.length >= 4 && !meta.equals("untitled", ignoreCase = true))
                    return@use meta.take(70)

                // 2. First-page text
                val stripper = PDFTextStripper().apply { startPage = 1; endPage = 1 }
                val text = runCatching { stripper.getText(doc).trim() }.getOrDefault("")
                val title = titleFromText(text)
                if (title.isNotBlank()) return@use title

                // 3. Filename fallback
                cleanFileName(file.nameWithoutExtension)
            }
        } catch (_: Exception) { cleanFileName(file.nameWithoutExtension) }
    }

    private fun titleFromText(text: String): String {
        return text.lines()
            .map { it.trim() }
            .firstOrNull { it.length in 5..100 && it.any { c -> c.isLetter() } }
            ?.take(70) ?: ""
    }

    private fun cleanFileName(name: String): String =
        name.replace(Regex("[_\\-]+"), " ")
            .split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
            .take(70).ifBlank { "PDF Document" }
}
