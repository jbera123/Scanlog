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
 * If a code is not present exactly, trailing affixes ("-A", "-1", …) are
 * stripped and matched against similarly-stripped CSV codes so label
 * variants still get a translation. Tallies stay per exact scanned code.
 * If nothing matches, displayText(code) returns code only.
 */
class BarcodeCatalog(context: Context) {

    private val appContext = context.applicationContext

    // Lazy-load once per instance
    private val map: Map<String, String> by lazy { loadCsv() }

    /**
     * Base-code index: CSV code with trailing affixes stripped -> zh.
     * Lets label variants (with/without "-A", "-1", …) resolve to the same
     * Chinese name. Counts are NEVER keyed on this — ScanStore tallies the
     * exact scanned code — this only affects the displayed translation.
     */
    private val baseMap: Map<String, String> by lazy {
        val out = LinkedHashMap<String, String>()
        for ((code, zh) in map) {
            for (b in baseCandidates(code)) out.putIfAbsent(b, zh)
        }
        out
    }

    fun chineseNameFor(codeRaw: String): String? {
        val code = normalize(codeRaw)
        if (code.isEmpty()) return null
        map[code]?.let { return it }
        // Fallback: strip affixes from the scanned code, progressively, and
        // match against the same-stripped CSV codes.
        for (b in baseCandidates(code)) {
            baseMap[b]?.let { return it }
        }
        return null
    }

    /**
     * Progressively strip trailing affix tokens: a single letter (revision,
     * e.g. "-A") or a single digit (variant, e.g. "-1"). Returns candidates
     * from most to least specific, starting with the code itself.
     * "OSEAT4-CR-1-A" -> ["OSEAT4-CR-1-A", "OSEAT4-CR-1", "OSEAT4-CR"]
     */
    private fun baseCandidates(code: String): List<String> {
        val out = ArrayList<String>(3)
        var cur = code
        out.add(cur)
        while (true) {
            val idx = cur.lastIndexOf('-')
            if (idx <= 0) break
            val tail = cur.substring(idx + 1)
            val isAffix = tail.length == 1 && (tail[0].isLetter() || tail[0].isDigit())
            if (!isAffix) break
            cur = cur.substring(0, idx)
            out.add(cur)
        }
        return out
    }

    fun displayText(codeRaw: String): String {
        val code = normalize(codeRaw)
        if (code.isEmpty()) return ""
        val zh = chineseNameFor(code) ?: return code
        // Keep code exactly as-is (normalized the same way everywhere); append Chinese for UI
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
                        val code = normalize(cols[0])
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

    private fun normalize(s: String): String =
        s.trim().uppercase()
}
