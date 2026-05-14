package com.pdfsecuredrive.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.*
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
import com.pdfsecuredrive.app.worker.PdfScanWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: PdfHistoryAdapter
    private val records = mutableListOf<PdfRecord>()

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK && result.data == null) return@registerForActivityResult
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
            account?.email?.let { email ->
                SecurePreferences.saveAccount(this, email)
                SecurePreferences.setEnabled(this, true)
                startMonitor()
                renderConnected()
            }
        } catch (e: ApiException) {
            Toast.makeText(this, when (e.statusCode) {
                10   -> "SHA-1 mismatch — open Settings for correct SHA-1"
                12500, 12501 -> "Cancelled"
                else -> "Sign-in failed (${e.statusCode})"
            }, Toast.LENGTH_LONG).show()
        }
    }

    private val notifPermLauncher   = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val storageLauncher     = registerForActivityResult(ActivityResultContracts.RequestPermission()) { checkPermissionBanner() }
    private val allFilesLauncher    = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { checkPermissionBanner() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)

        if (RootDetector.isRooted(this)) {
            findViewById<TextView>(R.id.tv_root_warning).visibility = View.VISIBLE
        }

        requestRuntimePermissions()
        checkPermissionBanner()
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
        findViewById<Button>(R.id.btn_grant_permission).setOnClickListener { requestAllFilesPermission() }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionBanner()
        refreshHistory()
    }

    private fun setupRecyclerView() {
        adapter = PdfHistoryAdapter(records) { refreshHistory() }
        val rv = findViewById<RecyclerView>(R.id.rv_history)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        // Wire up search
        val et = findViewById<EditText>(R.id.et_search)
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun refreshHistory() {
        val all = HistoryStore.getAll(this)
        adapter.updateRecords(all)   // updates both list + fullList for search

        val empty = all.isEmpty()
        findViewById<View>(R.id.ll_empty).visibility           = if (empty) View.VISIBLE else View.GONE
        findViewById<RecyclerView>(R.id.rv_history).visibility = if (empty) View.GONE  else View.VISIBLE
        findViewById<View>(R.id.ll_search).visibility          = if (empty) View.GONE  else View.VISIBLE

        val uploads = HistoryStore.totalUploads(this)
        val scans   = HistoryStore.totalScans(this)
        findViewById<TextView>(R.id.tv_intercepts).text     = if (scans > 999) "${scans/1000}.${(scans%1000)/100}k" else "$scans"
        findViewById<TextView>(R.id.tv_secured).text        = "$uploads"
        findViewById<TextView>(R.id.tv_secured_badge).text  = "$uploads SECURED"
    }

    private fun checkPermissionBanner() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()
        findViewById<LinearLayout>(R.id.ll_permission_banner).visibility =
            if (needsPermission) View.VISIBLE else View.GONE
    }

    private fun requestAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                allFilesLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                allFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun schedulePeriodicScan() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "pdf_periodic_scan",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PdfScanWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                .build()
        )
    }

    private fun renderConnected() {
        val email = GoogleSignIn.getLastSignedInAccount(this)?.email
            ?: SecurePreferences.getAccount(this) ?: "Connected"
        val masked = "${email.take(3)}***${email.dropWhile { it != '@' }}"

        findViewById<TextView>(R.id.tv_interceptor).apply { text = "ACTIVE"; setTextColor(0xFF10B981.toInt()) }
        findViewById<View>(R.id.v_dot).visibility = View.VISIBLE
        findViewById<Button>(R.id.btn_sign_in).visibility     = View.GONE
        findViewById<ImageButton>(R.id.btn_logout).visibility = View.VISIBLE

        val empty = HistoryStore.getAll(this).isEmpty()
        if (empty) {
            findViewById<TextView>(R.id.tv_empty_title).text = "Monitoring active"
            findViewById<TextView>(R.id.tv_empty_sub).text = "$masked\n\nDownload any PDF to see it here"
            findViewById<Button>(R.id.btn_sign_in_empty).visibility = View.GONE
        }

        loadDriveUsage()
        schedulePeriodicScan()
        refreshHistory()
    }

    private fun renderIdle() {
        findViewById<TextView>(R.id.tv_interceptor).apply { text = "OFFLINE"; setTextColor(0xFF64748B.toInt()) }
        findViewById<View>(R.id.v_dot).visibility = View.INVISIBLE
        findViewById<Button>(R.id.btn_sign_in).visibility     = View.VISIBLE
        findViewById<ImageButton>(R.id.btn_logout).visibility = View.GONE
        findViewById<TextView>(R.id.tv_drive_used).text = "—"
        findViewById<TextView>(R.id.tv_drive_quota).text = "Connect to see usage"
        findViewById<ProgressBar>(R.id.pb_drive).progress = 0
        val empty = HistoryStore.getAll(this).isEmpty()
        if (empty) findViewById<Button>(R.id.btn_sign_in_empty).visibility = View.VISIBLE
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
                    com.google.api.client.json.gson.GsonFactory.getDefaultInstance(), credential
                ).setApplicationName("PDFSecureDrive").build()
                val quota = drive.about().get().setFields("storageQuota").execute().storageQuota
                val used  = quota.usage ?: 0L
                val total = quota.limit ?: (15L * 1024 * 1024 * 1024)
                withContext(Dispatchers.Main) {
                    val usedGb = used.toFloat() / (1024 * 1024 * 1024)
                    val pct    = ((used.toFloat() / total) * 100).toInt().coerceIn(0, 100)
                    findViewById<TextView>(R.id.tv_drive_used).text   = "%.1f".format(usedGb)
                    findViewById<TextView>(R.id.tv_drive_quota).text  = "OF %.0f GB ALLOTTED".format(total.toFloat()/(1024*1024*1024))
                    findViewById<ProgressBar>(R.id.pb_drive).progress = pct
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { findViewById<TextView>(R.id.tv_drive_quota).text = "Usage unavailable" }
            }
        }
    }

    private fun startSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail().requestScopes(Scope(DriveScopes.DRIVE_FILE)).build()
        signInLauncher.launch(GoogleSignIn.getClient(this, gso).signInIntent)
    }

    private fun signOut() {
        GoogleSignIn.getClient(this, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut().addOnCompleteListener {
            stopService(Intent(this, PdfMonitorService::class.java))
            WorkManager.getInstance(this).cancelUniqueWork("pdf_periodic_scan")
            SecurePreferences.clearAll(this)
            renderIdle()
        }
    }

    private fun startMonitor() = startForegroundService(Intent(this, PdfMonitorService::class.java))

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            storageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
