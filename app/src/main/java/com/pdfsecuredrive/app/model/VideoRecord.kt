package com.pdfsecuredrive.app.model

data class VideoRecord(
    val id: String,
    val title: String,
    val url: String,
    val platform: String,
    val platformEmoji: String,
    val localPath: String?,
    val thumbnailUrl: String?,
    val fileSize: Long = 0L,
    val downloadDate: Long,
    val status: String   // "DOWNLOADED", "FAILED", "PENDING"
)
