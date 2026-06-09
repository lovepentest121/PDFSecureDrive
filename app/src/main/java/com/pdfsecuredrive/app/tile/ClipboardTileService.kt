package com.pdfsecuredrive.app.tile

import android.content.ClipboardManager
import android.content.Context
import android.service.quicksettings.TileService
import android.widget.Toast
import com.pdfsecuredrive.app.notification.AppNotificationManager
import com.pdfsecuredrive.app.video.VideoLinkDetector

/**
 * Quick Settings tile: after the user copies a video link in any app, they pull down the
 * shade and tap this tile. A TileService onClick runs with the QS panel focused, so it is
 * one of the few places (besides being the foreground app or default IME) where reading the
 * clipboard is permitted on Android 10+. We turn the copied link into a Download/Cancel
 * notification — the closest feasible thing to "copy → instant download prompt".
 */
class ClipboardTileService : TileService() {

    override fun onClick() {
        super.onClick()
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            val url = text?.let { VideoLinkDetector.extractUrl(it) }
            if (url == null || !VideoLinkDetector.isVideoLink(url)) {
                Toast.makeText(this, "Copy a video link first (YouTube/IG/X/LinkedIn)", Toast.LENGTH_SHORT).show()
                return
            }
            AppNotificationManager.createChannels(this)
            val p = VideoLinkDetector.platform(url)
            AppNotificationManager.showVideoPrompt(this, url, p.emoji, p.label, null)
            Toast.makeText(this, "${p.emoji} ${p.label} link → check your notifications", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }
}
