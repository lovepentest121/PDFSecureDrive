package com.pdfsecuredrive.app.drive

import android.content.Context
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.Permission
import java.io.File

class DriveUploader(private val context: Context) {

    sealed class Result {
        data class Success(val shareLink: String, val fileId: String) : Result()
        data class Failure(val error: String) : Result()
    }

    fun upload(file: File, accountName: String): Result {
        return try {
            // Token lifecycle handled by Google Play Services — we never store tokens
            val credential = GoogleAccountCredential
                .usingOAuth2(context, listOf(DriveScopes.DRIVE_FILE))
                .apply { selectedAccountName = accountName }

            val drive = Drive.Builder(
                AndroidHttp.newCompatibleTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("PDFSecureDrive").build()

            val meta = com.google.api.services.drive.model.File().apply {
                name = sanitizeFileName(file.name)
                mimeType = "application/pdf"
            }

            val uploaded = drive.files()
                .create(meta, FileContent("application/pdf", file))
                .setFields("id,webViewLink")
                .execute()

            // Minimal permission: anyone with link, read-only (not edit/comment)
            drive.permissions()
                .create(uploaded.id, Permission().apply {
                    type = "anyone"
                    role = "reader"
                })
                .execute()

            Result.Success(uploaded.webViewLink, uploaded.id)
        } catch (e: Exception) {
            Result.Failure(e.message ?: "Upload failed")
        }
    }

    // Sanitize filename before upload — strip any traversal characters
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .take(200)
            .ifBlank { "document.pdf" }
    }
}
