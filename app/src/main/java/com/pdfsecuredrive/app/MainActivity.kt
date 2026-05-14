package com.pdfsecuredrive.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.pdfsecuredrive.app.adapter.PdfHistoryAdapter
import com.pdfsecuredrive.app.model.PdfRecord
import com.pdfsecuredrive.app.security.RootDetector
import com.pdfsecuredrive.app.service.PdfMonitorService
import com.pdfsecuredrive.app.storage.HistoryStore
import com.pdfsecuredrive.app.storage.SecurePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: PdfHistoryAdapter
    private val records = mutableListOf<PdfRecord>()

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK && result.data == null) { return@registerForActivityResult }
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            account?.email?.let { email ->
                SecurePreferences.saveAccount(this, email)
                SecurePreferences.setEnabled(this, true)
                startMonitor()
                renderConnected()
            }
        } catch (e: ApiException) {
            val msg = when (e.statusCode) {
                10   -> "SHA-1 mismatch — open Settings, copy SHA-1, update Cloud Console"
                12500, 12501 -> "Sign-in cancelled"
                7    -> "No internet"
                else -> "Sign-in failed (${e.statusCode})"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val storageLauncher   = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)

        if (RootDetector.isRooted(this)) {
            findViewById<TextView>(R.id.tv_root_warning).visibility = View.VISIBLE
        }

        requestRuntimePermissions()
        setupRecyclerView()

        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null && account.grantedScopes.any { it.scopeUri == DriveScopes.DRIVE_FILE }) {
            renderConnected()
            if (SecurePreferences.isEnabled(this)) startMonitor()
        } else {
            renderIdle()
        }

        findViewById<Button>(R.id.btn_sign_in).setOnClickListener { startSignIn() }
        findViewById<Button>(R.id.btn_sign_in_empty).setOnClickListener { startSignIn() }
        findViewById<ImageButton>(R.id.btn_logout).setOnClickListener { signOut() }
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHistory()
    }

    private fun setupRecyclerView() {
        adapter = PdfHistoryAdapter(records) { refreshHistory() }
        val rv = findViewById<RecyclerView>(R.id.rv_history)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
    }

    private fun refreshHistory() {
        val all = HistoryStore.getAll(this)
        records.clear()
        records.addAll(all)
        adapter.notifyDataSetChanged()

        val empty = all.isEmpty()
        findViewById<View>(R.id.ll_empty).visibility    = if (empty) View.VISIBLE else View.GONE
        findViewById<RecyclerView>(R.id.rv_history).visibility = if (empty) View.GONE else View.VISIBLE

        val uploads = HistoryStore.totalUploads(this)
        val scans   = HistoryStore.totalScans(this)

        findViewById<TextView>(R.id.tv_intercepts).text = if (scans > 999) "${scans/1000}.${(scans%1000)/100}k" else "$scans"
        findViewById<TextView>(R.id.tv_secured).text    = "$uploads"
        findViewById<TextView>(R.id.tv_secured_badge).text = "$uploads SECURED"
    }

    private fun renderConnected() {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        val email = account?.email ?: SecurePreferences.getAccount(this) ?: "Connected"
        val masked = "${email.take(3)}***${email.dropWhile { it != '@' }}"

        // Top bar
        findViewById<TextView>(R.id.tv_interceptor).apply {
            text = "INTERCEPTOR: ACTIVE"
            setTextColor(0xFF10B981.toInt())
        }
        findViewById<View>(R.id.v_interceptor_dot).setBackgroundResource(R.drawable.dot_green)
        findViewById<Button>(R.id.btn_sign_in).visibility    = View.GONE
        findViewById<ImageButton>(R.id.btn_logout).visibility = View.VISIBLE

        // Empty state
        val empty = HistoryStore.getAll(this).isEmpty()
        if (empty) {
            findViewById<TextView>(R.id.tv_empty_title).text = "Monitoring active"
            findViewById<TextView>(R.id.tv_empty_sub).text = "$masked\n\nDownload any PDF to see it here"
            findViewById<Button>(R.id.btn_sign_in_empty).visibility = View.GONE
        }

        // Drive usage
        loadDriveUsage()
        refreshHistory()
    }

    private fun renderIdle() {
        findViewById<TextView>(R.id.tv_interceptor).apply {
            text = "INTERCEPTOR: OFF"
            setTextColor(0xFF64748B.toInt())
        }
        findViewById<Button>(R.id.btn_sign_in).visibility    = View.VISIBLE
        findViewById<ImageButton>(R.id.btn_logout).visibility = View.GONE
        findViewById<TextView>(R.id.tv_drive_used).text = "—"
        findViewById<TextView>(R.id.tv_drive_quota).text = "CONNECT TO SEE USAGE"
        findViewById<ProgressBar>(R.id.pb_drive).progress = 0

        val empty = HistoryStore.getAll(this).isEmpty()
        if (empty) {
            findViewById<Button>(R.id.btn_sign_in_empty).visibility = View.VISIBLE
        }
        refreshHistory()
    }

    private fun loadDriveUsage() {
        val account = SecurePreferences.getAccount(this) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val credential = com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
                    .usingOAuth2(this@MainActivity, listOf(DriveScopes.DRIVE_FILE))
                    .apply { selectedAccountName = account }
                val drive = com.google.api.services.drive.Drive.Builder(
                    com.google.api.client.http.javanet.NetHttpTransport(),
                    com.google.api.client.json.gson.GsonFactory.getDefaultInstance(),
                    credential
                ).setApplicationName("PDFSecureDrive").build()

                val about = drive.about().get().setFields("storageQuota").execute()
                val quota = about.storageQuota
                val used  = quota.usage ?: 0L
                val total = quota.limit ?: (15L * 1024 * 1024 * 1024)

                withContext(Dispatchers.Main) {
                    val usedGb  = used.toFloat() / (1024 * 1024 * 1024)
                    val totalGb = total.toFloat() / (1024 * 1024 * 1024)
                    val pct     = ((used.toFloat() / total) * 100).toInt().coerceIn(0, 100)
                    findViewById<TextView>(R.id.tv_drive_used).text  = "%.1f".format(usedGb)
                    findViewById<TextView>(R.id.tv_drive_quota).text = "USED OF %.0f GB ALLOTTED".format(totalGb)
                    findViewById<ProgressBar>(R.id.pb_drive).progress = pct
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    findViewById<TextView>(R.id.tv_drive_quota).text = "USAGE UNAVAILABLE"
                }
            }
        }
    }

    private fun startSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail().requestScopes(Scope(DriveScopes.DRIVE_FILE)).build()
        signInLauncher.launch(GoogleSignIn.getClient(this, gso).signInIntent)
    }

    private fun signOut() {
        GoogleSignIn.getClient(this, GoogleSignInOptions.DEFAULT_SIGN_IN)
            .signOut().addOnCompleteListener {
                stopService(Intent(this, PdfMonitorService::class.java))
                SecurePreferences.clearAll(this)
                renderIdle()
            }
    }

    private fun startMonitor() {
        startForegroundService(Intent(this, PdfMonitorService::class.java))
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            storageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
