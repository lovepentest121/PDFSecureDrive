package com.pdfsecuredrive.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.pdfsecuredrive.app.security.RootDetector
import com.pdfsecuredrive.app.service.PdfMonitorService
import com.pdfsecuredrive.app.storage.SecurePreferences

class MainActivity : AppCompatActivity() {

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val account = GoogleSignIn
                .getSignedInAccountFromIntent(result.data)
                .getResult(Exception::class.java)

            account?.email?.let { email ->
                SecurePreferences.saveAccount(this, email)
                SecurePreferences.setEnabled(this, true)
                startMonitor()
                render(email)
            }
        } catch (_: Exception) {
            render(null)
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Silently handled — service still works */ }

    private val storageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Silently handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screenshots — protects Google account info on screen
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_main)

        // Warn on rooted devices
        if (RootDetector.isRooted(this)) {
            findViewById<TextView>(R.id.tv_root_warning).visibility = View.VISIBLE
        }

        requestRuntimePermissions()

        // Restore state if already signed in
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null &&
            account.grantedScopes.any { it.scopeUri == DriveScopes.DRIVE_FILE }) {
            render(account.email)
            if (SecurePreferences.isEnabled(this)) startMonitor()
        } else {
            render(null)
        }

        findViewById<Button>(R.id.btn_sign_in).setOnClickListener { startSignIn() }
        findViewById<Button>(R.id.btn_sign_out).setOnClickListener { signOut() }
    }

    private fun requestRuntimePermissions() {
        // POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // READ_EXTERNAL_STORAGE on Android 9-12
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            storageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun startSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE)) // Least-privilege Drive scope
            .build()
        signInLauncher.launch(GoogleSignIn.getClient(this, gso).signInIntent)
    }

    private fun signOut() {
        GoogleSignIn.getClient(this, GoogleSignInOptions.DEFAULT_SIGN_IN)
            .signOut()
            .addOnCompleteListener {
                stopService(Intent(this, PdfMonitorService::class.java))
                SecurePreferences.clearAll(this)
                render(null)
            }
    }

    private fun startMonitor() {
        startForegroundService(Intent(this, PdfMonitorService::class.java))
    }

    private fun render(email: String?) {
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val btnIn    = findViewById<Button>(R.id.btn_sign_in)
        val btnOut   = findViewById<Button>(R.id.btn_sign_out)

        if (email != null) {
            // Mask email — show prefix + domain only
            val masked = "${email.take(3)}***${email.dropWhile { it != '@' }}"
            tvStatus.text = "✅ Monitoring active\n\nAccount: $masked\n\nAny PDF downloaded anywhere on this device will be scanned automatically."
            btnIn.visibility  = View.GONE
            btnOut.visibility = View.VISIBLE
        } else {
            tvStatus.text = getString(R.string.status_idle)
            btnIn.visibility  = View.VISIBLE
            btnOut.visibility = View.GONE
        }
    }
}
