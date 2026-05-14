package com.pdfsecuredrive.app.security

// Ordered SAFE(0) → CRITICAL(4) so maxByOrNull picks the worst threat
enum class RiskLevel { SAFE, LOW, MEDIUM, HIGH, CRITICAL }

enum class VtStatus {
    NOT_CONFIGURED, // No API key set
    CLEAN,          // VirusTotal says safe
    MALICIOUS,      // VirusTotal detected threats
    ERROR           // VT unreachable/timeout
}

data class Threat(
    val name: String,
    val description: String,
    val riskLevel: RiskLevel,
    val vector: String
)

data class ScanResult(
    val isSafe: Boolean,
    val threats: List<Threat>,
    val fileName: String,
    val fileSize: Long,
    val scanDurationMs: Long,
    val fileHash: String,
    val vtStatus: VtStatus = VtStatus.NOT_CONFIGURED,
    val isValidPdf: Boolean = true
) {
    val highestRisk: RiskLevel
        get() = threats.maxByOrNull { it.riskLevel.ordinal }?.riskLevel ?: RiskLevel.SAFE
    val threatCount: Int get() = threats.size
}
