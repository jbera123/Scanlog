package com.example.scanlog.data

import android.content.Context
import com.example.scanlog.R
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads a barcode -> Chinese name mapping from res/raw/barcode_map.csv
 *
 * CSV format expectation (header optional):
 *   code,zh
 *   1234567890,苹果
 *
 * If a code is not present, displayText(code) returns code only.
 */
class BarcodeCatalog(context: Context) {

    private val appContext = context.applicationContext

    // Lazy-load once per instance
    private val map: Map<String, String> by lazy { loadCsv() }

    fun chineseNameFor(codeRaw: String): String? {
        val code = codeRaw.trim()
        if (code.isEmpty()) return null
        return map[code]
    }

    fun displayText(codeRaw: String): String {
        val code = codeRaw.trim()
        if (code.isEmpty()) return ""
        val zh = chineseNameFor(code) ?: return code
        // Keep code exactly as-is; append Chinese for UI
        return "$code  $zh"
    }

    private fun loadCsv(): Map<String, String> {
        val result = LinkedHashMap<String, String>()

        val res = appContext.resources
        val input = res.openRawResource(R.raw.barcode_map)

        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            reader.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEachIndexed { index, line ->
                    val cols = parseCsvLine(line)
                    if (cols.isEmpty()) return@forEachIndexed

                    // Skip header if present
                    if (index == 0 && cols.size >= 2) {
                        val c0 = cols[0].trim().lowercase()
                        val c1 = cols[1].trim().lowercase()
                        if (c0 == "code" && (c1 == "zh" || c1.contains("chinese"))) {
                            return@forEachIndexed
                        }
                    }

                    if (cols.size >= 2) {
                        val code = cols[0].trim()
                        val zh = cols[1].trim()
                        if (code.isNotEmpty() && zh.isNotEmpty()) {
                            result[code] = zh
                        }
                    }
                }
        }

        return result
    }

    // Minimal CSV parser supporting quoted fields with commas inside quotes.
    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when (ch) {
                '"' -> {
                    // Handle escaped quotes ""
                    val nextIsQuote = (i + 1 < line.length && line[i + 1] == '"')
                    if (inQuotes && nextIsQuote) {
                        sb.append('"')
                        i++ // skip the escaped quote
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ',' -> {
                    if (inQuotes) sb.append(ch)
                    else {
                        out.add(sb.toString())
                        sb.setLength(0)
                    }
                }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
