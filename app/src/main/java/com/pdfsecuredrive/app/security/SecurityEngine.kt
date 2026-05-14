package com.pdfsecuredrive.app.security

import android.content.Context
import com.pdfsecuredrive.app.storage.SecurePreferences
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

        // Stage 2: PDF static analysis — magic bytes, patterns, structure
        val scanOutput = PdfScanner.scan(file)
        allThreats.addAll(scanOutput.threats)

        // Stage 3: VirusTotal cloud scan (only if local scan passes + API key set)
        val vtApiKey = SecurePreferences.getVtApiKey(context)
        var vtStatus = VtStatus.NOT_CONFIGURED

        if (allThreats.isEmpty() && scanOutput.isValidPdf && vtApiKey.isNotBlank()) {
            when (val vtResult = VirusTotalScanner.scan(file, vtApiKey, scanOutput.fileHash)) {
                is VirusTotalScanner.VtResult.Clean -> {
                    vtStatus = VtStatus.CLEAN
                }
                is VirusTotalScanner.VtResult.Malicious -> {
                    vtStatus = VtStatus.MALICIOUS
                    allThreats.addAll(vtResult.threats)
                    // Add summary threat
                    allThreats.add(Threat(
                        name = "VirusTotal Detection",
                        description = "${vtResult.detections}/${vtResult.totalEngines} engines flagged this file",
                        riskLevel = RiskLevel.CRITICAL,
                        vector = "VirusTotal"
                    ))
                }
                is VirusTotalScanner.VtResult.Error -> {
                    vtStatus = VtStatus.ERROR
                }
                is VirusTotalScanner.VtResult.NoApiKey -> {
                    vtStatus = VtStatus.NOT_CONFIGURED
                }
            }
        }

        val unique = allThreats.distinctBy { it.name }

        return ScanResult(
            isSafe = unique.isEmpty() && scanOutput.isValidPdf,
            threats = unique.sortedByDescending { it.riskLevel.ordinal },
            fileName = file.name,
            fileSize = file.length(),
            scanDurationMs = System.currentTimeMillis() - start,
            fileHash = scanOutput.fileHash,
            vtStatus = vtStatus
        )
    }
}

