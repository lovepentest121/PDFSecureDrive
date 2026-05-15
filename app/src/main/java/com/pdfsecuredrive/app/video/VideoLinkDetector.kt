package com.pdfsecuredrive.app.video

import java.util.regex.Pattern

object VideoLinkDetector {

    private val urlPattern = Pattern.compile(
        "https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+"
    )

    // Platform URL signatures
    private val platformPatterns = listOf(
        Platform.YOUTUBE  to listOf("youtube.com/watch", "youtu.be/", "youtube.com/shorts/", "youtube.com/live/"),
        Platform.INSTAGRAM to listOf("instagram.com/reel", "instagram.com/p/", "instagram.com/tv/"),
        Platform.TWITTER  to listOf("twitter.com/", "x.com/", "t.co/"),
        Platform.FACEBOOK to listOf("facebook.com/", "fb.watch/", "fb.com/"),
        Platform.LINKEDIN to listOf("linkedin.com/posts/", "linkedin.com/feed/", "lnkd.in/"),
        Platform.TIKTOK   to listOf("tiktok.com/", "vm.tiktok.com/"),
    )

    /** Extract first URL from arbitrary text/clipboard */
    fun extractUrl(text: String): String? {
        val m = urlPattern.matcher(text)
        return if (m.find()) m.group().trimEnd('.', ',', ')', ']') else null
    }

    /** Detect which platform a URL belongs to */
    fun platform(url: String): Platform {
        val lower = url.lowercase()
        platformPatterns.forEach { (platform, patterns) ->
            if (patterns.any { lower.contains(it) }) return platform
        }
        return Platform.OTHER
    }

    /** Returns true if this URL looks like a social media video link */
    fun isVideoLink(url: String): Boolean {
        val lower = url.lowercase()
        return platformPatterns.any { (_, patterns) -> patterns.any { lower.contains(it) } }
    }

    fun isVideoText(text: String): Boolean {
        val url = extractUrl(text) ?: return false
        return isVideoLink(url)
    }
}
