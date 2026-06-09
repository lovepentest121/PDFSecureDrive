package com.pdfsecuredrive.app.security

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object VirusTotalScanner {

    private const val VT_BASE = "https://www.virustotal.com/api/v3"

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

    /**
     * Hash-lookup ONLY — a single fast request.
     *
     * We deliberately no longer upload the file and poll for a fresh analysis here.
     * That poll loop slept up to 3 minutes (Thread.sleep × MAX_POLLS) *inside* the
     * scan path that gates the Upload prompt, so the "🔍 Scanning…" notification
     * appeared to hang forever and the Upload action never showed.
     *
     * Cloud verification is now advisory: known-bad hashes are still caught
     * instantly; unknown files return ERROR and the (fast, offline) local scan
     * verdict stands on its own.
     */
    fun scan(file: File, apiKey: String, fileHash: String): VtResult {
        if (apiKey.isBlank()) return VtResult.NoApiKey
        return checkByHash(fileHash, apiKey) ?: VtResult.Error("Not in VirusTotal database")
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

    private fun parseFileReport(json: JSONObject): VtResult {
        val attrs = json.getJSONObject("data").getJSONObject("attributes")
        return parseStats(attrs.optJSONObject("last_analysis_stats"), attrs.optJSONObject("last_analysis_results"))
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
            // Tight timeout — this runs inline in the scan path, so it must never
            // be able to stall the Upload prompt for long.
            connectTimeout = 8_000
            readTimeout = 8_000
        }
    }
}
