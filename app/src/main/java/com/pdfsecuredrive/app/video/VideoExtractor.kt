package com.pdfsecuredrive.app.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class StreamInfo(val directUrl: String, val quality: String, val mimeType: String)

object VideoExtractor {

    // Public Piped API mirrors. The official kavin.rocks instance is frequently
    // rate-limited / IP-blocked by YouTube, so we try several before giving up.
    // (Refreshed June 2026 — see github.com/TeamPiped/Piped/wiki/Instances for the
    // live list; these die and reappear constantly.)
    private val PIPED_INSTANCES = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.yt",
        "https://pipedapi.leptons.xyz",
        "https://pipedapi.reallyaweso.me",
        "https://api.piped.private.coffee",
        "https://pipedapi.ducks.party",
        "https://pipedapi.drgns.space"
    )

    // Invidious fallback mirrors (also subject to YouTube's IP-level blocking).
    private val INVIDIOUS_INSTANCES = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://inv.thepixora.com",
        "https://yewtu.be"
    )

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    /**
     * Try to get a direct downloadable .mp4 stream URL.
     *
     * Reliability (cookie-free, 2026): X/Twitter (syndication endpoint) and LinkedIn
     * (public-post HTML) are reliable; YouTube depends on Piped/Invidious mirrors;
     * Instagram is best-effort only (Meta strips og:video & rotates its private API,
     * so it frequently returns null without a logged-in session).
     */
    suspend fun extractStream(info: VideoInfo): StreamInfo? = withContext(Dispatchers.IO) {
        when (info.platform) {
            Platform.YOUTUBE   -> extractYouTube(info.url)
            Platform.TWITTER   -> extractTwitter(info.url) ?: ogVideo(info.url)
            Platform.LINKEDIN  -> extractLinkedIn(info.url) ?: ogVideo(info.url)
            Platform.INSTAGRAM -> extractInstagram(info.url) ?: ogVideo(info.url)
            else               -> ogVideo(info.url)   // Facebook / TikTok / other: best-effort og:video
        }
    }

    // ---- X / Twitter: syndication tweet-result endpoint (reliable, no auth) ----------------
    private fun extractTwitter(url: String): StreamInfo? {
        val id = Regex("status/(\\d+)").find(url)?.groupValues?.get(1) ?: return null
        val token = twitterToken()
        val json = httpGet(
            "https://cdn.syndication.twimg.com/tweet-result?id=$id&token=$token&lang=en",
            ua = "Googlebot"
        ) ?: return null
        return try {
            val media = JSONObject(json).optJSONArray("mediaDetails") ?: return null
            var bestUrl: String? = null
            var bestBitrate = -1
            for (i in 0 until media.length()) {
                val vinfo = media.optJSONObject(i)?.optJSONObject("video_info") ?: continue
                val variants = vinfo.optJSONArray("variants") ?: continue
                for (j in 0 until variants.length()) {
                    val v = variants.getJSONObject(j)
                    if (v.optString("content_type") == "video/mp4") {  // skip HLS .m3u8
                        val br = v.optInt("bitrate", 0)
                        if (br > bestBitrate) { bestBitrate = br; bestUrl = v.optString("url") }
                    }
                }
            }
            bestUrl?.takeIf { it.isNotBlank() }?.let {
                StreamInfo(it, if (bestBitrate > 0) "${bestBitrate / 1000}k" else "video", "video/mp4")
            }
        } catch (_: Exception) { null }
    }

    private fun twitterToken(): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        return buildString { repeat(11) { append(chars[kotlin.random.Random.nextInt(chars.length)]) } }
    }

    // ---- LinkedIn: parse <video data-sources> JSON from public post HTML --------------------
    private fun extractLinkedIn(url: String): StreamInfo? {
        // LinkedIn auth-walls datacenter IPs (HTTP 999); a Googlebot UA usually gets the full
        // public markup. The <video> tag sits deep in a heavy page, so read a LARGE window
        // (the old 400 KB cap truncated before the video element — a key reason this failed).
        val html = httpGet(url, ua = "Googlebot", maxBytes = 5_000_000)
            ?: httpGet(url, ua = DESKTOP_UA, maxBytes = 5_000_000)
            ?: return null

        // data-sources may be double- OR single-quoted, JSON HTML-entity-encoded
        val rawAttr = Regex("data-sources=(?:\"([^\"]+)\"|'([^']+)')").find(html)
            ?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
        if (!rawAttr.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(decodeHtmlEntities(rawAttr))
                var best: String? = null
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    val type = s.optString("type")
                    val src  = s.optString("src")
                    if (src.isNotBlank() && (type == "video/mp4" || src.contains(".mp4"))) best = src
                }
                best
            }.getOrNull()?.let { return StreamInfo(it, "video", "video/mp4") }
        }
        // JSON-LD / embedded progressive contentUrl
        Regex("\"contentUrl\":\"(https:[^\"]+\\.mp4[^\"]*)\"").find(html)?.groupValues?.get(1)
            ?.let { return StreamInfo(decodeJsonString(it), "video", "video/mp4") }
        return ogVideoFromHtml(html)?.let { StreamInfo(it, "video", "video/mp4") }
    }

    // ---- Instagram: HTML embedded video_url / og:video (best-effort, often fails) -----------
    private fun extractInstagram(url: String): StreamInfo? {
        val html = httpGet(url, ua = DESKTOP_UA, maxBytes = 5_000_000) ?: return null
        Regex("\"video_url\":\"(https:[^\"]+\\.mp4[^\"]*)\"").find(html)?.groupValues?.get(1)
            ?.let { return StreamInfo(decodeJsonString(it), "video", "video/mp4") }
        Regex("\"contentUrl\":\"(https:[^\"]+\\.mp4[^\"]*)\"").find(html)?.groupValues?.get(1)
            ?.let { return StreamInfo(decodeJsonString(it), "video", "video/mp4") }
        ogVideoFromHtml(html)?.let { return StreamInfo(it, "video", "video/mp4") }
        return null
    }

    // ---- Generic og:video meta fallback -----------------------------------------------------
    private fun ogVideo(url: String): StreamInfo? {
        val html = httpGet(url, ua = DESKTOP_UA, maxBytes = 2_000_000) ?: return null
        return ogVideoFromHtml(html)?.let { StreamInfo(it, "video", "video/mp4") }
    }

    private fun ogVideoFromHtml(html: String): String? {
        for (prop in listOf("og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream")) {
            val patterns = listOf(
                Regex("<meta[^>]+property=\"$prop\"[^>]+content=\"([^\"]+)\"", RegexOption.IGNORE_CASE),
                Regex("<meta[^>]+content=\"([^\"]+)\"[^>]+property=\"$prop\"", RegexOption.IGNORE_CASE)
            )
            for (r in patterns) {
                val m = r.find(html)?.groupValues?.get(1)
                if (m != null && (m.contains(".mp4"))) return decodeHtmlEntities(m)
            }
        }
        return null
    }

    private fun decodeHtmlEntities(s: String): String = s
        .replace("&quot;", "\"").replace("&#34;", "\"")
        .replace("&amp;", "&").replace("&#38;", "&")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'")

    private fun decodeJsonString(s: String): String = s
        .replace("\\u0026", "&").replace("\\/", "/").replace("\\\"", "\"")

    private fun extractYouTube(url: String): StreamInfo? {
        val videoId = extractYouTubeId(url) ?: return null
        return tryPipedApi(videoId) ?: tryInvidiousApi(videoId)
    }

    private fun tryPipedApi(videoId: String): StreamInfo? {
        for (base in PIPED_INSTANCES) {
            val found = runCatching {
                val json    = httpGet("$base/streams/$videoId") ?: return@runCatching null
                val obj     = JSONObject(json)
                val streams = obj.optJSONArray("videoStreams") ?: return@runCatching null

                var best: StreamInfo? = null
                for (i in 0 until streams.length()) {
                    val s = streams.getJSONObject(i)
                    val mime      = s.optString("mimeType", "")
                    val quality   = s.optString("quality", "")
                    val streamUrl = s.optString("url", "")
                    if (streamUrl.isBlank()) continue
                    // We need a *progressive* stream (audio + video in one file) so the
                    // download is directly playable. Piped marks adaptive video-only
                    // streams with videoOnly=true — skip those.
                    val videoOnly = s.optBoolean("videoOnly", false)
                    if (mime.contains("video/mp4") && !videoOnly) {
                        val q   = quality.replace("p", "").toIntOrNull() ?: 0
                        val cur = best?.quality?.replace("p", "")?.toIntOrNull() ?: 0
                        if (q > cur) best = StreamInfo(streamUrl, quality, mime)
                    }
                }
                best
            }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    private fun tryInvidiousApi(videoId: String): StreamInfo? {
        for (base in INVIDIOUS_INSTANCES) {
            val found = runCatching {
                val json    = httpGet("$base/api/v1/videos/$videoId?fields=formatStreams") ?: return@runCatching null
                val obj     = JSONObject(json)
                val streams = obj.optJSONArray("formatStreams") ?: return@runCatching null
                var match: StreamInfo? = null
                for (i in 0 until streams.length()) {
                    val s    = streams.getJSONObject(i)
                    val mime = s.optString("type", "")
                    val url  = s.optString("url", "")
                    if (url.isNotBlank() && mime.contains("mp4")) {
                        match = StreamInfo(url, s.optString("quality", "720p"), mime)
                        break
                    }
                }
                match
            }.getOrNull()
            if (found != null) return found
        }
        return null
    }

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

    private fun httpGet(url: String, ua: String = "Mozilla/5.0", maxBytes: Int = 2_000_000): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", ua)
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = true
        if (conn.responseCode !in 200..299) null
        else conn.inputStream.bufferedReader().use { reader ->
            val sb = StringBuilder()
            val buf = CharArray(16384)
            var n = reader.read(buf)
            while (n >= 0 && sb.length < maxBytes) { sb.append(buf, 0, n); n = reader.read(buf) }
            sb.toString()
        }
    } catch (_: Exception) { null }
}
