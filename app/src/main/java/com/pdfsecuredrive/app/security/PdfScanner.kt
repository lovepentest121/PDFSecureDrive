package com.pdfsecuredrive.app.security

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object PdfScanner {

    private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) // %PDF-

    // Static threat patterns — file is NEVER executed, only byte-scanned
    private data class PatternThreat(val pattern: String, val threat: Threat)

    private val DANGEROUS_PATTERNS = listOf(
        PatternThreat("/JavaScript",   Threat("Embedded JavaScript",     "/JavaScript action found",               RiskLevel.CRITICAL, "Auto-Exec")),
        PatternThreat("/JS ",          Threat("JavaScript Reference",    "/JS reference found",                    RiskLevel.CRITICAL, "Auto-Exec")),
        PatternThreat("/JS\n",         Threat("JavaScript Reference",    "/JS reference found",                    RiskLevel.CRITICAL, "Auto-Exec")),
        PatternThreat("/Launch",       Threat("Launch Action",           "/Launch can execute external programs",  RiskLevel.CRITICAL, "Auto-Exec")),
        PatternThreat("/OpenAction",   Threat("Auto-Open Action",        "Action executes on PDF open",            RiskLevel.CRITICAL, "Auto-Exec")),
        PatternThreat("/AA ",          Threat("Additional Actions",      "/AA triggers on page events",            RiskLevel.HIGH,     "Auto-Exec")),
        PatternThreat("/EmbeddedFile", Threat("Embedded File",           "PDF contains embedded file attachment",  RiskLevel.HIGH,     "Exfiltration")),
        PatternThreat("/ImportData",   Threat("Data Import",             "/ImportData loads external data",        RiskLevel.HIGH,     "Exfiltration")),
        PatternThreat("/SubmitForm",   Threat("Form Submission",         "PDF submits data to remote server",      RiskLevel.MEDIUM,   "Exfiltration")),
        PatternThreat("/RichMedia",    Threat("Rich Media Embed",        "Embedded Flash/video content",           RiskLevel.MEDIUM,   "Scripting")),
        PatternThreat("/XFA",          Threat("XFA Form",                "XFA forms can execute logic",            RiskLevel.MEDIUM,   "Scripting")),
        PatternThreat("cmd.exe",       Threat("Windows Shell Ref",       "cmd.exe reference in PDF",               RiskLevel.CRITICAL, "Auto-Exec")),
        PatternThreat("/bin/sh",       Threat("Unix Shell Ref",          "/bin/sh reference in PDF",               RiskLevel.CRITICAL, "Auto-Exec")),
        PatternThreat("powershell",    Threat("PowerShell Reference",    "PowerShell reference found",             RiskLevel.HIGH,     "Auto-Exec")),
        PatternThreat("WScript",       Threat("Windows Script Host",     "WScript reference found",                RiskLevel.HIGH,     "Auto-Exec")),
        PatternThreat("eval(",         Threat("Eval Call",               "eval() function call detected",          RiskLevel.HIGH,     "Scripting")),
        PatternThreat("app.alert",     Threat("PDF Alert Function",      "app.alert() Acrobat JS detected",        RiskLevel.CRITICAL, "Scripting")),
        PatternThreat("this.exportDataObject", Threat("Data Exfiltration","exportDataObject can leak files",       RiskLevel.CRITICAL, "Exfiltration")),
        PatternThreat("/URI ",          Threat("URI Action",              "External URI action in PDF",             RiskLevel.MEDIUM,   "Exfiltration")),
        PatternThreat("/GoToR",         Threat("Remote GoTo",             "Opens external PDF/file",                RiskLevel.MEDIUM,   "Exfiltration")),
        PatternThreat("/Sound",         Threat("Sound Embed",             "Embedded sound object detected",         RiskLevel.LOW,      "Scripting")),
        PatternThreat("/Movie",         Threat("Movie Embed",             "Embedded movie/video object",            RiskLevel.MEDIUM,   "Scripting")),
        PatternThreat("getURL",         Threat("URL Fetch",               "getURL() Acrobat JS call",               RiskLevel.HIGH,     "Exfiltration")),
        PatternThreat("app.launchURL",  Threat("Launch URL",              "app.launchURL() opens browser",          RiskLevel.HIGH,     "Exfiltration")),
        PatternThreat("util.printf",    Threat("Format String",           "util.printf() pattern detected",         RiskLevel.HIGH,     "Scripting")),
        PatternThreat("/Encrypt",       Threat("Encrypted Streams",       "Encrypted payload streams found",        RiskLevel.MEDIUM,   "Obfuscation")),
        PatternThreat("unescape(",      Threat("String Obfuscation",      "unescape() used for obfuscation",        RiskLevel.HIGH,     "Scripting")),
        PatternThreat("String.fromCharCode", Threat("Char Code Obfuscation","Char code encoding detected",         RiskLevel.HIGH,     "Scripting")),
        PatternThreat("%u0",            Threat("Unicode Obfuscation",     "Unicode escape obfuscation",             RiskLevel.MEDIUM,   "Obfuscation")),
        PatternThreat("CVE-",           Threat("CVE Reference",           "Known CVE reference in document",        RiskLevel.HIGH,     "Exploit")),
        PatternThreat("mshta",          Threat("MSHTA Reference",         "mshta.exe execution reference",          RiskLevel.CRITICAL, "Auto-Exec")),
        PatternThreat("certutil",       Threat("Certutil Reference",      "certutil LOLBin reference",              RiskLevel.CRITICAL, "Auto-Exec")),
        PatternThreat("regsvr32",       Threat("Regsvr32 Reference",      "regsvr32 LOLBin reference",              RiskLevel.CRITICAL, "Auto-Exec")),
        PatternThreat("wmic",           Threat("WMIC Reference",          "WMI command reference",                  RiskLevel.HIGH,     "Auto-Exec")),
        PatternThreat("bitsadmin",      Threat("BITSAdmin Reference",     "bitsadmin downloader reference",         RiskLevel.CRITICAL, "Auto-Exec")),
    )

    private val SUSPICIOUS_URI_SCHEMES = listOf(
        "javascript:", "data:text/html", "file://", "smb://", "\\\\\\\\", "ftp://"
    )

    fun initialize(context: Context) {
        PDFBoxResourceLoader.init(context)
    }

    data class ScanOutput(
        val threats: List<Threat>,
        val fileHash: String,
        val isValidPdf: Boolean
    )

    fun scan(file: File): ScanOutput {
        val threats = mutableListOf<Threat>()

        // Step 1: Magic bytes check — reject non-PDF immediately
        if (!checkMagicBytes(file)) {
            return ScanOutput(
                listOf(Threat("Invalid PDF Header", "Missing %PDF- magic bytes — format spoofing or corrupt file", RiskLevel.CRITICAL, "Format Spoofing")),
                computeHash(file),
                false
            )
        }

        // Step 2: File size sanity
        threats.addAll(checkFileSize(file))

        // Step 3: Raw byte pattern scan — zero execution
        threats.addAll(scanRawPatterns(file))

        // Step 4: PDF structure via PDFBox — parse only, no JS engine
        try {
            threats.addAll(analyzePdfStructure(file))
        } catch (e: Exception) {
            threats.add(
                Threat("Unparseable PDF", "Cannot parse — obfuscation or corruption suspected", RiskLevel.HIGH, "Structural")
            )
        }

        // Deduplicate by name
        val unique = threats.distinctBy { it.name }
        return ScanOutput(unique, computeHash(file), true)
    }

    private fun checkMagicBytes(file: File): Boolean {
        if (file.length() < 5) return false
        val buf = ByteArray(5)
        FileInputStream(file).use { it.read(buf) }
        return buf.contentEquals(PDF_MAGIC)
    }

    private fun checkFileSize(file: File): List<Threat> {
        val threats = mutableListOf<Threat>()
        val size = file.length()
        if (size < 100) threats.add(
            Threat("Suspiciously Small", "${size} bytes — too small to be a real PDF", RiskLevel.HIGH, "Structural")
        )
        if (size > 200L * 1024 * 1024) threats.add(
            Threat("Oversized File", "${size / (1024 * 1024)}MB — abnormally large", RiskLevel.MEDIUM, "Structural")
        )
        return threats
    }

    private fun scanRawPatterns(file: File): List<Threat> {
        val threats = mutableListOf<Threat>()
        val maxScan = 15L * 1024 * 1024 // 15MB max scan window

        val content = try {
            FileInputStream(file).use { stream ->
                val limit = minOf(file.length(), maxScan).toInt()
                val buf = ByteArray(limit)
                stream.read(buf)
                String(buf, Charsets.ISO_8859_1)
            }
        } catch (e: Exception) { return threats }

        for (pt in DANGEROUS_PATTERNS) {
            if (content.contains(pt.pattern)) threats.add(pt.threat)
        }

        for (scheme in SUSPICIOUS_URI_SCHEMES) {
            if (content.contains(scheme, ignoreCase = true)) {
                threats.add(Threat("Suspicious URI Scheme", "Contains URI: $scheme", RiskLevel.HIGH, "Exfiltration"))
                break
            }
        }
        return threats
    }

    private fun analyzePdfStructure(file: File): List<Threat> {
        val threats = mutableListOf<Threat>()
        // PDFBox parse-only — does NOT run JavaScript
        PDDocument.load(file).use { doc ->
            val catalog = doc.documentCatalog

            if (catalog.openAction != null) threats.add(
                Threat("OpenAction Present", "PDF has an action that fires on open", RiskLevel.CRITICAL, "Auto-Exec")
            )

            catalog.names?.embeddedFiles?.names
                ?.takeIf { it.isNotEmpty() }
                ?.let { threats.add(Threat("Embedded Files", "${it.size} embedded file(s) found", RiskLevel.HIGH, "Exfiltration")) }

            // Suspicious size-to-page ratio
            val pages = doc.numberOfPages
            if (pages > 0 && file.length() / pages < 50) threats.add(
                Threat("Anomalous Structure", "Unusual size/page ratio — obfuscation suspected", RiskLevel.MEDIUM, "Structural")
            )
        }
        return threats
    }

    fun computeHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream ->
            val buf = ByteArray(8192)
            var n: Int
            while (stream.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
