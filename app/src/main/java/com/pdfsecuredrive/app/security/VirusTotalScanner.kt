package com.pdfsecuredrive.app.security

import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object VirusTotalScanner {

    private const val VT_BASE = "https://www.virustotal.com/api/v3"
    private const val POLL_INTERVAL_MS = 15_000L
    private const val MAX_POLLS = 12 // 3 minutes max wait

    sealed class VtResult {
        data class Clean(val engines: Int, val detections: Int) : VtResult()
        data class Malicious(
            val detections: Int,
            val totalEngines: Int,
            val threats: List<Threat>
        ) : VtResult()
        data class Error(val message: String) : VtResult()
        object NoApiKey : VtResult()
    }

    fun scan(file: File, apiKey: String, fileHash: String): VtResult {
        if (apiKey.isBlank()) return VtResult.NoApiKey

        // Step 1: Check by hash first (no file upload needed, saves quota)
        val hashResult = checkByHash(fileHash, apiKey)
        if (hashResult != null) return hashResult

        // Step 2: Hash not in VT database — upload file for scan
        val analysisId = uploadFile(file, apiKey) ?: return VtResult.Error("Upload to VirusTotal failed")

        // Step 3: Poll for results
        return pollAnalysis(analysisId, apiKey)
    }

    private fun checkByHash(sha256: String, apiKey: String): VtResult? {
        return try {
            val conn = openGet("$VT_BASE/files/$sha256", apiKey)
            if (conn.responseCode == 404) return null // Not in database
            if (conn.responseCode != 200) return null

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            parseFileReport(json)
        } catch (e: Exception) {
            null // Treat as not found, proceed to upload
        }
    }

    private fun uploadFile(file: File, apiKey: String): String? {
        return try {
            val boundary = "----VTBoundary${System.currentTimeMillis()}"
            val url = URL("$VT_BASE/files")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("x-apikey", apiKey)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 60_000
            }

            conn.outputStream.use { out ->
                // Write multipart boundary + file
                val prefix = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\nContent-Type: application/pdf\r\n\r\n"
                out.write(prefix.toByteArray())
                FileInputStream(file).use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }

            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            json.getJSONObject("data").getString("id")
        } catch (e: Exception) {
            null
        }
    }

    private fun pollAnalysis(analysisId: String, apiKey: String): VtResult {
        repeat(MAX_POLLS) {
            Thread.sleep(POLL_INTERVAL_MS)
            try {
                val conn = openGet("$VT_BASE/analyses/$analysisId", apiKey)
                if (conn.responseCode != 200) return@repeat

                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val attrs = json.getJSONObject("data").getJSONObject("attributes")
                val status = attrs.optString("status", "queued")

                if (status == "completed") {
                    return parseAnalysisReport(attrs)
                }
            } catch (_: Exception) { }
        }
        return VtResult.Error("VirusTotal scan timed out")
    }

    private fun parseFileReport(json: JSONObject): VtResult {
        val attrs = json.getJSONObject("data").getJSONObject("attributes")
        return parseStats(attrs.optJSONObject("last_analysis_stats"), attrs.optJSONObject("last_analysis_results"))
    }

    private fun parseAnalysisReport(attrs: JSONObject): VtResult {
        return parseStats(attrs.optJSONObject("stats"), attrs.optJSONObject("results"))
    }

    private fun parseStats(stats: JSONObject?, results: JSONObject?): VtResult {
        val malicious  = stats?.optInt("malicious", 0) ?: 0
        val suspicious = stats?.optInt("suspicious", 0) ?: 0
        val harmless   = stats?.optInt("harmless", 0) ?: 0
        val undetected = stats?.optInt("undetected", 0) ?: 0
        val total = malicious + suspicious + harmless + undetected
        val detections = malicious + suspicious

        if (detections == 0) return VtResult.Clean(total, 0)

        // Build threat list from engines that flagged it
        val threats = mutableListOf<Threat>()
        results?.keys()?.forEach { engine ->
            val engineResult = results.optJSONObject(engine)
            val category = engineResult?.optString("category", "") ?: ""
            if (category == "malicious" || category == "suspicious") {
                val vtName = engineResult?.optString("result", "Unknown") ?: "Unknown"
                val risk = if (category == "malicious") RiskLevel.CRITICAL else RiskLevel.HIGH
                threats.add(Threat(
                    name = "VT: $engine",
                    description = "$engine flagged as: $vtName",
                    riskLevel = risk,
                    vector = "VirusTotal"
                ))
            }
        }

        return VtResult.Malicious(detections, total, threats)
    }

    private fun openGet(urlStr: String, apiKey: String): HttpURLConnection {
        return (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            // API key sent only over HTTPS (enforced by network_security_config)
            setRequestProperty("x-apikey", apiKey)
            connectTimeout = 15_000
            readTimeout = 15_000
        }
    }
}
