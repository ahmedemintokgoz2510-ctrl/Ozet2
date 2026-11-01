package com.ozet.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale

data class HistoryItem(
    val id: String,
    val name: String,
    val type: String,
    val sizeBytes: Long,
    val dateMillis: Long
)

class HistoryStore(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("ozet_history", Context.MODE_PRIVATE)
    private val dir = File(ctx.filesDir, "history").apply { mkdirs() }

    companion object {
        private const val MAX_ITEMS = 30
        private const val MAX_CHARS = 1_500_000
    }

    fun load(): List<HistoryItem> {
        val raw = prefs.getString("items", null) ?: return emptyList()
        val out = ArrayList<HistoryItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    HistoryItem(
                        o.getString("id"), o.getString("name"), o.getString("type"),
                        o.getLong("size"), o.getLong("date")
                    )
                )
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun save(list: List<HistoryItem>) {
        val arr = JSONArray()
        for (h in list) {
            val o = JSONObject()
            o.put("id", h.id)
            o.put("name", h.name)
            o.put("type", h.type)
            o.put("size", h.sizeBytes)
            o.put("date", h.dateMillis)
            arr.put(o)
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    fun add(name: String, type: String, size: Long, text: String): List<HistoryItem> {
        val current = load().toMutableList()
        val dup = current.filter { it.name == name && it.type == type && it.sizeBytes == size }
        for (d in dup) File(dir, d.id + ".txt").delete()
        current.removeAll(dup)
        val now = System.currentTimeMillis()
        val id = now.toString()
        try {
            File(dir, "$id.txt").writeText(if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text)
        } catch (e: Exception) {
            return load()
        }
        current.add(0, HistoryItem(id, name, type, size, now))
        while (current.size > MAX_ITEMS) {
            val old = current.removeAt(current.size - 1)
            File(dir, old.id + ".txt").delete()
        }
        save(current)
        return current
    }

    fun readText(id: String): String? {
        val f = File(dir, "$id.txt")
        return if (f.exists()) f.readText() else null
    }

    fun remove(id: String): List<HistoryItem> {
        File(dir, "$id.txt").delete()
        val rest = load().filter { it.id != id }
        save(rest)
        return rest
    }

    fun clear(): List<HistoryItem> {
        dir.listFiles()?.forEach { it.delete() }
        save(emptyList())
        return emptyList()
    }
}

fun typeOf(name: String): String {
    val n = name.lowercase()
    return when {
        n.endsWith(".pdf") -> "PDF"
        n.endsWith(".docx") || n.endsWith(".doc") -> "DOCX"
        n.endsWith(".xlsx") || n.endsWith(".xls") -> "EXCEL"
        n.endsWith(".csv") -> "CSV"
        n.endsWith(".pptx") || n.endsWith(".ppt") -> "PPTX"
        n.endsWith(".html") || n.endsWith(".htm") -> "HTML"
        n.endsWith(".rtf") -> "RTF"
        n.endsWith(".md") || n.endsWith(".markdown") -> "MD"
        n.endsWith(".json") -> "JSON"
        else -> "TXT"
    }
}

fun sizeOf(context: Context, uri: Uri): Long {
    var size = 0L
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0)
        }
    } catch (e: Exception) {
    }
    return size
}

fun formatSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
}

private val MONTHS = arrayOf("Oca", "Şub", "Mar", "Nis", "May", "Haz", "Tem", "Ağu", "Eyl", "Eki", "Kas", "Ara")

fun formatDate(ms: Long): String {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    return "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
}
