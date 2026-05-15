package com.pdfsecuredrive.app.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class StreamInfo(val directUrl: String, val quality: String, val mimeType: String)

object VideoExtractor {

    /** Try to get a direct downloadable stream URL */
    suspend fun extractStream(info: VideoInfo): StreamInfo? = withContext(Dispatchers.IO) {
        when (info.platform) {
            Platform.YOUTUBE -> extractYouTube(info.url)
            else -> null   // other platforms need session cookies
        }
    }

    private fun extractYouTube(url: String): StreamInfo? {
        val videoId = extractYouTubeId(url) ?: return null
        return tryPipedApi(videoId) ?: tryInvidiousApi(videoId)
    }

    private fun tryPipedApi(videoId: String): StreamInfo? = try {
        val json = httpGet("https://pipedapi.kavin.rocks/streams/$videoId") ?: return null
        val obj  = JSONObject(json)
        val streams = obj.optJSONArray("videoStreams") ?: return null

        // Pick best quality mp4
        var best: StreamInfo? = null
        for (i in 0 until streams.length()) {
            val s = streams.getJSONObject(i)
            val mime    = s.optString("mimeType", "")
            val quality = s.optString("quality", "")
            val streamUrl = s.optString("url", "")
            if (streamUrl.isBlank()) continue
            if (mime.contains("video/mp4") && !mime.contains("audio")) {
                val q = quality.replace("p", "").toIntOrNull() ?: 0
                val cur = best?.quality?.replace("p","")?.toIntOrNull() ?: 0
                if (q > cur) best = StreamInfo(streamUrl, quality, mime)
            }
        }
        best
    } catch (_: Exception) { null }

    private fun tryInvidiousApi(videoId: String): StreamInfo? = try {
        val instances = listOf("invidious.io", "yewtu.be", "vid.puffyan.us")
        for (inst in instances) {
            val json = httpGet("https://$inst/api/v1/videos/$videoId?fields=formatStreams") ?: continue
            val obj  = JSONObject(json)
            val streams = obj.optJSONArray("formatStreams") ?: continue
            for (i in 0 until streams.length()) {
                val s = streams.getJSONObject(i)
                val mime = s.optString("type", "")
                val url  = s.optString("url", "")
                if (url.isNotBlank() && mime.contains("mp4")) {
                    return StreamInfo(url, s.optString("quality", "720p"), mime)
                }
            }
        }
        null
    } catch (_: Exception) { null }

    private fun extractYouTubeId(url: String): String? {
        val patterns = listOf(
            Regex("(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/shorts/)([a-zA-Z0-9_-]{11})"),
            Regex("youtube\\.com/live/([a-zA-Z0-9_-]{11})")
        )
        patterns.forEach { r ->
            val m = r.find(url)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    private fun httpGet(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = true
        if (conn.responseCode !in 200..299) null
        else conn.inputStream.bufferedReader().readText()
    } catch (_: Exception) { null }
}
