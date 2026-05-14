package com.pdfsecuredrive.app

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.pdfsecuredrive.app.security.RootDetector
import com.pdfsecuredrive.app.service.PdfMonitorService
import com.pdfsecuredrive.app.storage.SecurePreferences

class MainActivity : AppCompatActivity() {

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK && result.data == null) {
            Toast.makeText(this, "Sign-in cancelled", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        try {
            val account = GoogleSignIn
                .getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)

            account?.email?.let { email ->
                SecurePreferences.saveAccount(this, email)
                SecurePreferences.setEnabled(this, true)
                startMonitor()
                renderConnected(email)
                animateCard(true)
            }
        } catch (e: ApiException) {
            val msg = when (e.statusCode) {
                10   -> "Setup error: SHA-1 not registered.\nOpen Settings → copy SHA-1 → add to Google Cloud Console"
                12500, 12501 -> "Sign-in cancelled"
                7    -> "Network error — check connection"
                else -> "Sign-in failed (code ${e.statusCode})"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Sign-in error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val storageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)

        if (RootDetector.isRooted(this)) {
            findViewById<TextView>(R.id.tv_root_warning).visibility = View.VISIBLE
        }

        requestRuntimePermissions()

        // Restore state
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null && account.grantedScopes.any { it.scopeUri == DriveScopes.DRIVE_FILE }) {
            renderConnected(account.email)
            animateCard(true)
            if (SecurePreferences.isEnabled(this)) startMonitor()
        } else {
            renderIdle()
        }

        playEntranceAnimation()

        findViewById<Button>(R.id.btn_sign_in).setOnClickListener { startSignIn() }
        findViewById<Button>(R.id.btn_sign_out).setOnClickListener { signOut() }
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun playEntranceAnimation() {
        val card = findViewById<View>(R.id.card_status)
        card.translationY = 120f
        card.alpha = 0f
        val slide = ObjectAnimator.ofFloat(card, "translationY", 120f, 0f)
        val fade  = ObjectAnimator.ofFloat(card, "alpha", 0f, 1f)
        AnimatorSet().apply {
            playTogether(slide, fade)
            duration = 500
            interpolator = DecelerateInterpolator(1.5f)
            startDelay = 80
            start()
        }
    }

    private fun animateCard(active: Boolean) {
        val card = findViewById<View>(R.id.card_status)
        val targetAlpha = if (active) 1f else 0.85f
        ObjectAnimator.ofFloat(card, "translationZ",
            card.translationZ, if (active) 24f else 8f
        ).apply { duration = 300; start() }
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            storageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun startSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
        signInLauncher.launch(GoogleSignIn.getClient(this, gso).signInIntent)
    }

    private fun signOut() {
        GoogleSignIn.getClient(this, GoogleSignInOptions.DEFAULT_SIGN_IN)
            .signOut().addOnCompleteListener {
                stopService(Intent(this, PdfMonitorService::class.java))
                SecurePreferences.clearAll(this)
                renderIdle()
                animateCard(false)
            }
    }

    private fun startMonitor() {
        startForegroundService(Intent(this, PdfMonitorService::class.java))
    }

    private fun renderConnected(email: String?) {
        val masked = email?.let { "${it.take(3)}***${it.dropWhile { c -> c != '@' }}" } ?: "Connected"
        findViewById<TextView>(R.id.tv_shield_icon).text = "✅"
        findViewById<TextView>(R.id.tv_status).text =
            "Monitoring active\n\n$masked\n\nAll PDF downloads scanned automatically"
        findViewById<Button>(R.id.btn_sign_in).visibility  = View.GONE
        findViewById<Button>(R.id.btn_sign_out).visibility = View.VISIBLE
    }

    private fun renderIdle() {
        findViewById<TextView>(R.id.tv_shield_icon).text = "🛡️"
        findViewById<TextView>(R.id.tv_status).text = "Connect your Google account\nto start monitoring PDF downloads"
        findViewById<Button>(R.id.btn_sign_in).visibility  = View.VISIBLE
        findViewById<Button>(R.id.btn_sign_out).visibility = View.GONE
    }
}
