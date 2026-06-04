package com.example.scanlog.data

import android.content.Context
import com.example.scanlog.R
import java.io.BufferedReader
import java.io.InputStreamReader

data class RfidEntry(val barcode: String, val label: String)

class RfidBarcodeCatalog(context: Context) {

    private val appContext = context.applicationContext

    private val map: Map<String, RfidEntry> by lazy { loadCsv() }

    /**
     * Looks up a scanned EPC against the CSV. Keys in the CSV are treated as
     * PREFIXES — a scanned tag matches an entry if it starts with that entry's
     * key. If multiple prefixes match, the longest (most specific) wins.
     *
     * This lets you put just the first N unique hex chars of a tag in the CSV
     * (e.g. "E2003" or "DEAD1") instead of the full 24-char EPC.
     */
    fun lookup(rfidTagRaw: String): RfidEntry? {
        val tag = normalize(rfidTagRaw)
        if (tag.isEmpty()) return null
        // Exact match first (fast path).
        map[tag]?.let { return it }
        // Fall back to longest prefix match.
        return map.entries
            .filter { tag.startsWith(it.key) }
            .maxByOrNull { it.key.length }
            ?.value
    }

    private fun loadCsv(): Map<String, RfidEntry> {
        val result = LinkedHashMap<String, RfidEntry>()

        val input = appContext.resources.openRawResource(R.raw.rfid_barcode_map)
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            reader.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEachIndexed { index, line ->
                    val cols = parseCsvLine(line)
                    if (cols.size < 3) return@forEachIndexed

                    // Skip header row
                    if (index == 0 && cols[0].trim().lowercase() == "rfid") return@forEachIndexed

                    val rfid = normalize(cols[0])
                    val barcode = normalize(cols[1])
                    val label = cols[2].trim()
                    if (rfid.isNotEmpty() && barcode.isNotEmpty()) {
                        result[rfid] = RfidEntry(barcode = barcode, label = label)
                    }
                }
        }

        return result
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when (ch) {
                '"' -> {
                    val nextIsQuote = (i + 1 < line.length && line[i + 1] == '"')
                    if (inQuotes && nextIsQuote) {
                        sb.append('"')
                        i++
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

    private fun normalize(s: String): String = s.trim().uppercase()
}
