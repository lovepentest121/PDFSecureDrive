package com.pdfsecuredrive.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pdfsecuredrive.app.security.SecurityEngine
import com.pdfsecuredrive.app.storage.SecurePreferences
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class PdfInterceptActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_intercept)

        val uri = intent?.data
        if (uri == null) { finish(); return }

        if (!SecurePreferences.isEnabled(this)) {
            // Not connected — pass through directly
            openWithOtherApp(uri); finish(); return
        }

        SecurityEngine.initialize(this)
        startScan(uri)
    }

    private fun startScan(uri: Uri) {
        val tvStatus = findViewById<TextView>(R.id.tv_intercept_status)
        val tvFile   = findViewById<TextView>(R.id.tv_intercept_file)
        val pb       = findViewById<ProgressBar>(R.id.pb_intercept)

        tvFile.text = uri.lastPathSegment ?: "document.pdf"
        tvStatus.text = "🔍 Scanning for threats…"
        pb.visibility = View.VISIBLE

        scope.launch {
            val file = withContext(Dispatchers.IO) { resolveUri(uri) }

            if (file == null || !file.exists() || file.length() < 50) {
                // Can't read — pass through
                openWithOtherApp(uri); finish(); return@launch
            }

            tvStatus.text = "🛡️ Analyzing content…"

            val result = withContext(Dispatchers.IO) {
                SecurityEngine.scanFile(this@PdfInterceptActivity, file)
            }

            pb.visibility = View.GONE

            if (result.isSafe) {
                tvStatus.text = "✅ Safe — opening…"
                // Small delay so user sees the result
                delay(600)
                openWithOtherApp(uri)
                finish()
            } else {
                tvStatus.text = "☠️ THREAT DETECTED"
                tvStatus.setTextColor(0xFFEF4444.toInt())

                // Delete the file
                try {
                    contentResolver.delete(uri, null, null)
                } catch (_: Exception) {}
                file.delete()

                val threats = result.threats.take(4).joinToString("\n") { "• [${it.riskLevel}] ${it.name}" }
                AlertDialog.Builder(this@PdfInterceptActivity)
                    .setTitle("☠️ Malicious PDF Deleted")
                    .setMessage(
                        "File: ${file.name}\n" +
                        "Risk: ${result.highestRisk}  |  Threats: ${result.threatCount}\n\n" +
                        threats +
                        "\n\n🗑️ File has been permanently deleted."
                    )
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun openWithOtherApp(uri: Uri) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Build chooser excluding ourselves
        val chooser = Intent.createChooser(viewIntent, "Open PDF with")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(chooser) } catch (_: Exception) {}
    }

    private fun resolveUri(uri: Uri): File? = try {
        when (uri.scheme) {
            "file"    -> uri.path?.let { File(it) }?.takeIf { it.canRead() }
            "content" -> {
                val dest = File(cacheDir, "intercept_${System.currentTimeMillis()}.pdf")
                contentResolver.openInputStream(uri)?.use { inp ->
                    FileOutputStream(dest).use { inp.copyTo(it) }
                }
                dest.takeIf { it.exists() && it.length() > 0 }
            }
            else -> null
        }
    } catch (_: Exception) { null }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
