package com.pdfsecuredrive.app.storage

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pdfsecuredrive.app.model.VideoRecord
import java.io.File

object VideoHistoryStore {
    private const val FILE_NAME = "video_history.json"
    private val gson = Gson()

    fun add(context: Context, record: VideoRecord) {
        val list = getAll(context).toMutableList()
        list.removeIf { it.id == record.id }
        list.add(0, record)
        if (list.size > 100) list.subList(100, list.size).clear()
        save(context, list)
    }

    fun getAll(context: Context): List<VideoRecord> {
        return try {
            val f = file(context)
            if (!f.exists()) emptyList()
            else {
                val type = object : TypeToken<List<VideoRecord>>() {}.type
                gson.fromJson<List<VideoRecord>>(f.readText(), type) ?: emptyList()
            }
        } catch (_: Exception) { emptyList() }
    }

    fun delete(context: Context, id: String) {
        val list = getAll(context).toMutableList()
        list.removeIf { it.id == id }
        save(context, list)
    }

    fun totalDownloads(context: Context) = getAll(context).count { it.status == "DOWNLOADED" }

    private fun save(context: Context, list: List<VideoRecord>) {
        file(context).writeText(gson.toJson(list))
    }
    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
