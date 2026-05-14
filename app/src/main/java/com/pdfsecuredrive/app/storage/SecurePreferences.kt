package com.pdfsecuredrive.app.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurePreferences {

    private const val PREFS_NAME = "app_secure_prefs"
    private const val KEY_ACCOUNT  = "a"
    private const val KEY_ENABLED  = "e"
    private const val KEY_VT_KEY   = "v"      // VirusTotal API key — AES-256 encrypted

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveAccount(context: Context, email: String) =
        prefs(context).edit().putString(KEY_ACCOUNT, email).apply()

    fun getAccount(context: Context): String? =
        prefs(context).getString(KEY_ACCOUNT, null)

    fun setEnabled(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun saveVtApiKey(context: Context, key: String) =
        prefs(context).edit().putString(KEY_VT_KEY, key).apply()

    fun getVtApiKey(context: Context): String =
        prefs(context).getString(KEY_VT_KEY, "") ?: ""

    fun clearAll(context: Context) =
        prefs(context).edit().clear().apply()
}
