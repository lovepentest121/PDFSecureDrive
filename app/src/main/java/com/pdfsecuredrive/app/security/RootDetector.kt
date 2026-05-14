package com.pdfsecuredrive.app.security

import android.content.Context
import java.io.File

object RootDetector {

    private val SU_PATHS = arrayOf(
        "/sbin/su", "/system/bin/su", "/system/xbin/su",
        "/data/local/xbin/su", "/data/local/bin/su",
        "/system/sd/xbin/su", "/su/bin/su", "/data/local/su",
        "/system/bin/failsafe/su"
    )

    private val ROOT_PACKAGES = arrayOf(
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.noshufou.android.su",
        "com.kingroot.kinguser",
        "com.kingo.root",
        "com.smedialink.oneclickroot",
        "com.zhiqupk.root.global",
        "com.alephzain.framaroot"
    )

    fun isRooted(context: Context): Boolean =
        checkSuBinaries() || checkRootPackages(context) || checkTestKeys()

    private fun checkSuBinaries() = SU_PATHS.any { File(it).exists() }

    private fun checkRootPackages(context: Context): Boolean {
        val pm = context.packageManager
        return ROOT_PACKAGES.any { pkg ->
            try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
        }
    }

    private fun checkTestKeys(): Boolean {
        val tags = android.os.Build.TAGS ?: return false
        return tags.contains("test-keys")
    }
}
