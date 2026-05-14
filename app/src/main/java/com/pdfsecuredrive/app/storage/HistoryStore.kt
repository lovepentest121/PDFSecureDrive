package com.pdfsecuredrive.app.storage

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pdfsecuredrive.app.model.PdfRecord
import java.io.File

object HistoryStore {

    private const val FILE_NAME = "pdf_history.json"
    private val gson = Gson()

    fun add(context: Context, record: PdfRecord) {
        val list = getAll(context).toMutableList()
        list.removeIf { it.id == record.id }
        list.add(0, record)
        if (list.size > 200) list.subList(200, list.size).clear()
        save(context, list)
    }

    fun getAll(context: Context): List<PdfRecord> {
        return try {
            val file = historyFile(context)
            if (!file.exists()) return emptyList()
            val json = file.readText()
            val type = object : TypeToken<List<PdfRecord>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    fun delete(context: Context, id: String) {
        val list = getAll(context).toMutableList()
        list.removeIf { it.id == id }
        save(context, list)
    }

    fun totalScans(context: Context) = getAll(context).size
    fun totalUploads(context: Context) = getAll(context).count { it.status == "UPLOADED" }
    fun totalThreats(context: Context) = getAll(context).count { it.status == "THREAT" }

    private fun save(context: Context, list: List<PdfRecord>) {
        historyFile(context).writeText(gson.toJson(list))
    }

    private fun historyFile(context: Context) = File(context.filesDir, FILE_NAME)
}
