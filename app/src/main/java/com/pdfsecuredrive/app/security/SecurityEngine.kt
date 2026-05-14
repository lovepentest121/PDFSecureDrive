package com.pdfsecuredrive.app.security

import android.content.Context
import java.io.File

object SecurityEngine {

    fun initialize(context: Context) {
        PdfScanner.initialize(context)
    }

    fun scanFile(context: Context, file: File): ScanResult {
        val start = System.currentTimeMillis()
        val allThreats = mutableListOf<Threat>()

        // Stage 1: Filename deep inspection
        val fnResult = FilenameValidator.validate(file.name)
        allThreats.addAll(fnResult.threats)

        // Stage 2: PDF static content analysis (magic bytes, patterns, structure)
        val scanOutput = PdfScanner.scan(file)
        allThreats.addAll(scanOutput.threats)

        // Deduplicate across both stages
        val unique = allThreats.distinctBy { it.name }

        return ScanResult(
            isSafe = unique.isEmpty() && scanOutput.isValidPdf,
            threats = unique.sortedByDescending { it.riskLevel.ordinal },
            fileName = file.name,
            fileSize = file.length(),
            scanDurationMs = System.currentTimeMillis() - start,
            fileHash = scanOutput.fileHash
        )
    }
}
