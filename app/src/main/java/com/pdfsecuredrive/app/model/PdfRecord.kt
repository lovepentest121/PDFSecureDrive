package com.pdfsecuredrive.app.model

data class PdfRecord(
    val id: String,
    val fileName: String,
    val aiTitle: String,
    val driveLink: String?,
    val coverPath: String?,
    val filePath: String? = null,   // original file path on device (for delete)
    val fileSize: Long,
    val fileHash: String,
    val scanDate: Long,
    val status: String,       // "UPLOADED", "THREAT", "SAFE_LOCAL"
    val threatCount: Int = 0,
    val riskLevel: String = "SAFE"
)
