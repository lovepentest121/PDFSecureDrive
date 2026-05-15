package com.pdfsecuredrive.app.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object VideoTitleFetcher {

    suspend fun fetch(url: String): VideoInfo = withContext(Dispatchers.IO) {
        val platform = VideoLinkDetector.platform(url)
        if (platform == Platform.YOUTUBE) {
            val oembed = fetchYouTubeOEmbed(url)
            if (oembed != null) return@withContext oembed
        }
        val info = fetchOpenGraph(url, platform)
        info ?: VideoInfo(url = url, title = generateFallbackTitle(platform), platform = platform)
    }

    private fun fetchYouTubeOEmbed(url: String): VideoInfo? {
        return try {
            val oembedUrl = "https://www.youtube.com/oembed?url=${encode(url)}&format=json"
            val json = httpGet(oembedUrl, timeout = 5000) ?: return null
            val title = extractJson(json, "title") ?: return null
            val thumb = extractJson(json, "thumbnail_url")
            VideoInfo(url = url, title = title.trim(), platform = Platform.YOUTUBE, thumbnailUrl = thumb)
        } catch (_: Exception) { null }
    }

    private fun fetchOpenGraph(url: String, platform: Platform): VideoInfo? {
        return try {
            val html = httpGet(url, timeout = 7000, maxBytes = 32768) ?: return null
            val title = extractMeta(html, "og:title")
                ?: extractMeta(html, "twitter:title")
                ?: extractTag(html, "<title>", "</title>")
                ?: return null
            val thumb = extractMeta(html, "og:image") ?: extractMeta(html, "twitter:image")
            VideoInfo(url = url, title = cleanTitle(title, platform), platform = platform, thumbnailUrl = thumb)
        } catch (_: Exception) { null }
    }

    private fun httpGet(url: String, timeout: Int = 5000, maxBytes: Int = 65536): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 Chrome/112.0.0.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.readBytes().take(maxBytes).toByteArray()
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun extractMeta(html: String, property: String): String? {
        val patterns = listOf(
            Regex("""<meta[^>]+(?:property|name)=["']$property["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']$property["']""", RegexOption.IGNORE_CASE),
        )
        patterns.forEach { r ->
            val m = r.find(html)
            if (m != null) return decodeHtml(m.groupValues[1].trim())
        }
        return null
    }

    private fun extractTag(html: String, open: String, close: String): String? {
        val start = html.indexOf(open, ignoreCase = true).takeIf { it >= 0 } ?: return null
        val end   = html.indexOf(close, start + open.length, ignoreCase = true).takeIf { it >= 0 } ?: return null
        return html.substring(start + open.length, end).trim().takeIf { it.isNotBlank() }
    }

    private fun extractJson(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)

    private fun cleanTitle(raw: String, platform: Platform): String =
        raw.replace(" - YouTube", "").replace(" | LinkedIn", "")
            .replace(" on Instagram", "").replace(" on Twitter", "")
            .replace(" • Instagram", "").trim().take(80)
            .ifBlank { generateFallbackTitle(platform) }

    private fun generateFallbackTitle(platform: Platform) = "${platform.emoji} ${platform.label} Video"

    private fun decodeHtml(s: String) = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    private fun encode(url: String) = java.net.URLEncoder.encode(url, "UTF-8")
}
