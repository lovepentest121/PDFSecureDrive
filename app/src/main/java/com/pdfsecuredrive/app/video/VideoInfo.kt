package com.pdfsecuredrive.app.video

data class VideoInfo(
    val url: String,
    val title: String,
    val platform: Platform,
    val thumbnailUrl: String? = null,
    val directVideoUrl: String? = null
)

enum class Platform(val label: String, val emoji: String) {
    YOUTUBE("YouTube", "▶️"),
    INSTAGRAM("Instagram", "📸"),
    TWITTER("Twitter/X", "🐦"),
    FACEBOOK("Facebook", "👥"),
    LINKEDIN("LinkedIn", "💼"),
    TIKTOK("TikTok", "🎵"),
    OTHER("Video", "🎬")
}
