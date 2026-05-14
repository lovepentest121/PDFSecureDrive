package com.pdfsecuredrive.app

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
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

    private var pulseAnimator: AnimatorSet? = null

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
            }
        } catch (e: ApiException) {
            val msg = when (e.statusCode) {
                10   -> "SHA-1 mismatch — open Settings, copy SHA-1, update Google Cloud Console"
                12500, 12501 -> "Sign-in cancelled"
                7    -> "No internet connection"
                else -> "Sign-in failed (code ${e.statusCode})"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
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

        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null && account.grantedScopes.any { it.scopeUri == DriveScopes.DRIVE_FILE }) {
            renderConnected(account.email)
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
        listOf(
            R.id.fl_shield to 0L,
            R.id.ll_status_label to 80L,
            R.id.card_main to 160L,
            R.id.ll_chips to 240L
        ).forEach { (id, delay) ->
            val v = findViewById<View>(id)
            v.translationY = 60f; v.alpha = 0f
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(v, "translationY", 60f, 0f),
                    ObjectAnimator.ofFloat(v, "alpha", 0f, 1f)
                )
                duration = 450; startDelay = delay
                interpolator = DecelerateInterpolator(1.4f)
                start()
            }
        }
    }

    private fun startPulse() {
        val pulse = findViewById<View>(R.id.v_pulse)
        pulseAnimator?.cancel()
        pulseAnimator = AnimatorSet().apply {
            val scale = ObjectAnimator.ofPropertyValuesHolder(pulse,
                PropertyValuesHolder.ofFloat("scaleX", 0.8f, 1.6f),
                PropertyValuesHolder.ofFloat("scaleY", 0.8f, 1.6f),
                PropertyValuesHolder.ofFloat("alpha",  0.5f, 0f)
            ).apply {
                duration = 1800
                repeatCount = ValueAnimator.INFINITE
                interpolator = DecelerateInterpolator()
            }
            play(scale)
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        findViewById<View>(R.id.v_pulse).alpha = 0f
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
            }
    }

    private fun startMonitor() {
        startForegroundService(Intent(this, PdfMonitorService::class.java))
    }

    private fun renderConnected(email: String?) {
        val masked = email?.let { "${it.take(3)}***${it.dropWhile { c -> c != '@' }}" } ?: "Connected"
        setCardBackground(active = true)
        startPulse()
        findViewById<View>(R.id.v_status_dot).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_status_label).apply {
            text = "MONITORING ACTIVE"
            setTextColor(0xFF00E676.toInt())
        }
        findViewById<TextView>(R.id.tv_shield).text = "✅"
        findViewById<TextView>(R.id.tv_status).apply {
            text = "$masked\n\nAll PDF downloads are being scanned\nautomatically in the background"
            setTextColor(0xFFB0BBCC.toInt())
        }
        findViewById<Button>(R.id.btn_sign_in).visibility  = View.GONE
        findViewById<Button>(R.id.btn_sign_out).visibility = View.VISIBLE
    }

    private fun renderIdle() {
        setCardBackground(active = false)
        stopPulse()
        findViewById<View>(R.id.v_status_dot).visibility = View.INVISIBLE
        findViewById<TextView>(R.id.tv_status_label).apply {
            text = "INACTIVE"
            setTextColor(0xFF6B7A99.toInt())
        }
        findViewById<TextView>(R.id.tv_shield).text = "🛡️"
        findViewById<TextView>(R.id.tv_status).apply {
            text = "Connect Google Drive to start\nautomatically scanning all PDF downloads"
            setTextColor(0xFFB0BBCC.toInt())
        }
        findViewById<Button>(R.id.btn_sign_in).visibility  = View.VISIBLE
        findViewById<Button>(R.id.btn_sign_out).visibility = View.GONE
    }

    private fun setCardBackground(active: Boolean) {
        val res = if (active) R.drawable.card_glow_active else R.drawable.card_glow_idle
        findViewById<LinearLayout>(R.id.ll_card_inner).background =
            ContextCompat.getDrawable(this, res)
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseAnimator?.cancel()
    }
}
