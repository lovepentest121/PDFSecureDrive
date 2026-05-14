package com.pdfsecuredrive.app.security

// Ordered SAFE(0) → CRITICAL(4) so maxByOrNull picks the worst threat
enum class RiskLevel { SAFE, LOW, MEDIUM, HIGH, CRITICAL }

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
    val fileHash: String
) {
    val highestRisk: RiskLevel
        get() = threats.maxByOrNull { it.riskLevel.ordinal }?.riskLevel ?: RiskLevel.SAFE
    val threatCount: Int get() = threats.size
}
