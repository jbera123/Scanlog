package com.example.scanlog.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.OutputStream

/**
 * Writes a day's counts as CSV to the public Downloads folder.
 *
 * On API 29+ we use MediaStore so the file shows up in the user's Files app
 * without needing WRITE_EXTERNAL_STORAGE. On API 26-28 we fall back to the
 * app-scoped external dir (no permission, but only visible via the system
 * Files app under Android/data/...).
 */
object CountExporter {

    private const val TAG = "CountExporter"

    /** Returns true if the file was written. */
    fun exportDay(context: Context, day: String, counts: Map<String, Int>): Boolean {
        if (counts.isEmpty()) return false
        val csv = buildCsv(counts)
        val name = "scanlog_${day}.csv"
        return try {
            if (Build.VERSION.SDK_INT >= 29) writeViaMediaStore(context, name, csv)
            else writeViaExternalDir(context, name, csv)
            Log.i(TAG, "exported $day → $name (${counts.size} rows)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "export failed for $day: ${t.message}")
            false
        }
    }

    private fun buildCsv(counts: Map<String, Int>): String {
        val sb = StringBuilder()
        sb.append("code,count\r\n")
        counts.entries.sortedBy { it.key }.forEach { (code, qty) ->
            sb.append(escape(code)).append(',').append(qty).append("\r\n")
        }
        return sb.toString()
    }

    private fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"${s.replace("\"", "\"\"")}\""
        else s

    private fun writeViaMediaStore(context: Context, name: String, csv: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/scanlog")
        }
        val uri: Uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert returned null")
        resolver.openOutputStream(uri).use { os: OutputStream? ->
            os ?: throw IllegalStateException("openOutputStream null")
            os.write(csv.toByteArray(Charsets.UTF_8))
        }
    }

    @Suppress("DEPRECATION")
    private fun writeViaExternalDir(context: Context, name: String, csv: String) {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "scanlog"
        )
        if (!dir.exists()) dir.mkdirs()
        File(dir, name).writeText(csv, Charsets.UTF_8)
    }
}
