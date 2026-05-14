package com.pdfsecuredrive.app

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pdfsecuredrive.app.security.RootDetector
import com.pdfsecuredrive.app.storage.SecurePreferences
import java.security.MessageDigest

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_settings)

        if (RootDetector.isRooted(this)) {
            findViewById<TextView>(R.id.tv_root_warning).visibility = View.VISIBLE
        }

        // Close button
        findViewById<ImageButton>(R.id.btn_close).setOnClickListener { finish() }

        // VT key
        val etVt = findViewById<EditText>(R.id.et_vt_key)
        val existing = SecurePreferences.getVtApiKey(this)
        if (existing.isNotBlank()) {
            etVt.hint = "Key saved: ${existing.take(4)}••••"
        }
        findViewById<Button>(R.id.btn_save_vt).setOnClickListener {
            val key = etVt.text.toString().trim()
            if (key.length < 32) {
                Toast.makeText(this, "Invalid — key must be at least 32 chars", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            SecurePreferences.saveVtApiKey(this, key)
            etVt.setText("")
            etVt.hint = "Key saved: ${key.take(4)}••••"
            Toast.makeText(this, "VirusTotal key saved", Toast.LENGTH_SHORT).show()
        }

        // Show app SHA-1
        try {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            val sig = info.signatures?.firstOrNull()
            if (sig != null) {
                val bytes = MessageDigest.getInstance("SHA-1").digest(sig.toByteArray())
                val sha1 = bytes.joinToString(":") { "%02X".format(it) }
                findViewById<TextView>(R.id.tv_sha1).text = "App SHA-1:\n$sha1"
            }
        } catch (_: Exception) {
            findViewById<TextView>(R.id.tv_sha1).text = "SHA-1: unavailable"
        }
    }
}
