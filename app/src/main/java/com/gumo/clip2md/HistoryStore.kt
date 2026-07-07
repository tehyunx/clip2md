package com.gumo.clip2md

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(val id: Long, val timestamp: Long, val title: String)

/**
 * Simple file-based history of past conversions. Each entry is stored as
 * two files under filesDir/history/<id>.md and <id>.html, with a single
 * index.json listing {id, timestamp, title} for the list screen.
 */
object HistoryStore {

    private const val MAX_ENTRIES = 200

    private fun dir(context: Context): File =
        File(context.filesDir, "history").apply { mkdirs() }

    private fun indexFile(context: Context): File = File(dir(context), "index.json")

    private fun readIndex(context: Context): JSONArray {
        val f = indexFile(context)
        if (!f.exists()) return JSONArray()
        return try {
            JSONArray(f.readText())
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun writeIndex(context: Context, arr: JSONArray) {
        indexFile(context).writeText(arr.toString())
    }

    fun newId(): Long = System.currentTimeMillis()

    /** Per-entry folder for downloaded images, referenced by absolute file:// links. */
    fun imagesDir(context: Context, id: Long): File =
        File(dir(context), "${id}_images").apply { mkdirs() }

    fun save(context: Context, markdown: String, rawHtml: String?): Long =
        save(context, newId(), markdown, rawHtml)

    fun save(context: Context, id: Long, markdown: String, rawHtml: String?): Long {
        if (markdown.isBlank()) return -1
        File(dir(context), "$id.md").writeText(markdown)
        if (rawHtml != null) {
            File(dir(context), "$id.html").writeText(rawHtml)
        }

        val title = markdown.trim().lineSequence().firstOrNull { it.isNotBlank() }
            ?.take(60) ?: "(제목 없음)"

        val arr = readIndex(context)
        // Replace any existing entry with the same id (re-saves shouldn't duplicate).
        val withoutDup = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getLong("id") != id) withoutDup.put(o)
        }
        val entry = JSONObject()
        entry.put("id", id)
        entry.put("timestamp", id)
        entry.put("title", title)
        withoutDup.put(entry)

        // Trim oldest entries beyond MAX_ENTRIES
        while (withoutDup.length() > MAX_ENTRIES) {
            val removed = withoutDup.getJSONObject(0)
            File(dir(context), "${removed.getLong("id")}.md").delete()
            File(dir(context), "${removed.getLong("id")}.html").delete()
            File(dir(context), "${removed.getLong("id")}_images").deleteRecursively()
            val trimmed = JSONArray()
            for (i in 1 until withoutDup.length()) trimmed.put(withoutDup.getJSONObject(i))
            writeIndex(context, trimmed)
            return id
        }
        writeIndex(context, withoutDup)
        return id
    }

    fun list(context: Context): List<HistoryEntry> {
        val arr = readIndex(context)
        val result = ArrayList<HistoryEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            result.add(HistoryEntry(o.getLong("id"), o.getLong("timestamp"), o.getString("title")))
        }
        return result.sortedByDescending { it.timestamp }
    }

    fun loadMarkdown(context: Context, id: Long): String? {
        val f = File(dir(context), "$id.md")
        return if (f.exists()) f.readText() else null
    }

    fun loadRawHtml(context: Context, id: Long): String? {
        val f = File(dir(context), "$id.html")
        return if (f.exists()) f.readText() else null
    }

    fun delete(context: Context, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        val arr = readIndex(context)
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getLong("id") in idSet) {
                File(dir(context), "${o.getLong("id")}.md").delete()
                File(dir(context), "${o.getLong("id")}.html").delete()
                File(dir(context), "${o.getLong("id")}_images").deleteRecursively()
            } else {
                kept.put(o)
            }
        }
        writeIndex(context, kept)
    }
}
