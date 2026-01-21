package com.example.scanlog.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.time.LocalDate

private val Context.dataStore by preferencesDataStore(name = "scanlog_store")

data class DayState(
    val counts: Map<String, Int> = emptyMap(),
    val lastCode: String? = null,
    val lastTsMs: Long? = null
)

class ScanStore(private val context: Context) {

    private object Keys {
        val JSON = stringPreferencesKey("days_json")
        val DUP_GUARD = stringPreferencesKey("dup_guard_enabled") // "true"/"false"
        val DUP_WINDOW_MS = longPreferencesKey("dup_window_ms")
    }

    // Defaults
    private val defaultDupGuardEnabled = true
    private val defaultDupWindowMs = 2000L

    private fun todayKey(): String = LocalDate.now().toString()

    private fun parseAll(json: String): JSONObject =
        if (json.isBlank()) JSONObject() else JSONObject(json)

    private fun dayObject(root: JSONObject, day: String): JSONObject =
        if (root.has(day)) root.getJSONObject(day) else JSONObject()

    private fun readDay(root: JSONObject, day: String): DayState {
        val d = dayObject(root, day)

        val countsObj = if (d.has("counts")) d.getJSONObject("counts") else JSONObject()
        val counts = buildMap<String, Int> {
            val keys = countsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                put(k, countsObj.optInt(k, 0))
            }
        }

        val lastCode: String? =
            if (d.has("lastCode") && !d.isNull("lastCode")) d.getString("lastCode") else null

        val lastTs: Long? =
            if (d.has("lastTsMs") && !d.isNull("lastTsMs")) d.getLong("lastTsMs") else null

        return DayState(counts = counts, lastCode = lastCode, lastTsMs = lastTs)
    }

    private fun writeDay(root: JSONObject, day: String, state: DayState) {
        val d = JSONObject()

        val countsObj = JSONObject()
        state.counts.forEach { (code, qty) -> countsObj.put(code, qty) }
        d.put("counts", countsObj)

        state.lastCode?.let { d.put("lastCode", it) }
        state.lastTsMs?.let { d.put("lastTsMs", it) }

        root.put(day, d)
    }

    val todayCounts: Flow<Map<String, Int>> =
        dayCounts(todayKey())

    fun dayCounts(day: String): Flow<Map<String, Int>> =
        context.dataStore.data.map { prefs ->
            val root = parseAll(prefs[Keys.JSON].orEmpty())
            readDay(root, day).counts
        }

    val days: Flow<List<String>> =
        context.dataStore.data.map { prefs ->
            val root = parseAll(prefs[Keys.JSON].orEmpty())
            val list = mutableListOf<String>()
            val it = root.keys()
            while (it.hasNext()) list.add(it.next())
            list.sortedDescending()
        }

    val duplicateGuardEnabled: Flow<Boolean> =
        context.dataStore.data.map { prefs ->
            (prefs[Keys.DUP_GUARD]?.toBooleanStrictOrNull()) ?: defaultDupGuardEnabled
        }

    val duplicateWindowMs: Flow<Long> =
        context.dataStore.data.map { prefs ->
            prefs[Keys.DUP_WINDOW_MS] ?: defaultDupWindowMs
        }

    suspend fun setDuplicateGuardEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DUP_GUARD] = enabled.toString() }
    }

    suspend fun setDuplicateWindowMs(ms: Long) {
        context.dataStore.edit { it[Keys.DUP_WINDOW_MS] = ms }
    }

    suspend fun recordScan(codeRaw: String, nowMs: Long = System.currentTimeMillis()) {
        val code = normalize(codeRaw)
        if (code.isBlank()) return

        context.dataStore.edit { prefs ->
            val root = parseAll(prefs[Keys.JSON].orEmpty())
            val day = todayKey()
            val current = readDay(root, day)

            val dupEnabled =
                (prefs[Keys.DUP_GUARD]?.toBooleanStrictOrNull()) ?: defaultDupGuardEnabled
            val dupWindow =
                prefs[Keys.DUP_WINDOW_MS] ?: defaultDupWindowMs

            val isDupe =
                dupEnabled &&
                        current.lastCode != null &&
                        current.lastTsMs != null &&
                        current.lastCode == code &&
                        (nowMs - current.lastTsMs) < dupWindow

            if (!isDupe) {
                val newCounts = current.counts.toMutableMap()
                newCounts[code] = (newCounts[code] ?: 0) + 1

                val next = current.copy(
                    counts = newCounts,
                    lastCode = code,
                    lastTsMs = nowMs
                )

                writeDay(root, day, next)
                prefs[Keys.JSON] = root.toString()
            }
        }
    }

    suspend fun undoLast(nowMs: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs ->
            val root = parseAll(prefs[Keys.JSON].orEmpty())
            val day = todayKey()
            val current = readDay(root, day)
            val code = current.lastCode ?: return@edit

            val qty = current.counts[code] ?: 0
            if (qty <= 0) return@edit

            val newCounts = current.counts.toMutableMap()
            if (qty == 1) newCounts.remove(code) else newCounts[code] = qty - 1

            val next = current.copy(
                counts = newCounts,
                lastCode = null,
                lastTsMs = nowMs
            )

            writeDay(root, day, next)
            prefs[Keys.JSON] = root.toString()
        }
    }
// --- NEW: edit/delete support (per-day) ---

    suspend fun deleteDay(day: String) {
        context.dataStore.edit { prefs ->
            val root = parseAll(prefs[Keys.JSON].orEmpty())
            if (root.has(day)) {
                root.remove(day)
                prefs[Keys.JSON] = root.toString()
            }
        }
    }

    suspend fun setCodeCount(day: String, codeRaw: String, qty: Int) {
        val code = normalize(codeRaw)
        if (code.isBlank()) return

        val newQty = qty.coerceAtLeast(0)

        context.dataStore.edit { prefs ->
            val root = parseAll(prefs[Keys.JSON].orEmpty())
            val current = readDay(root, day)

            val newCounts = current.counts.toMutableMap()
            if (newQty == 0) newCounts.remove(code) else newCounts[code] = newQty

            // If we removed the lastCode, clear it so Undo doesn't act on a removed code.
            val next = current.copy(
                counts = newCounts,
                lastCode = if (current.lastCode == code && newQty == 0) null else current.lastCode
            )

            writeDay(root, day, next)
            prefs[Keys.JSON] = root.toString()
        }
    }

    suspend fun incrementCode(day: String, codeRaw: String, delta: Int) {
        val code = normalize(codeRaw)
        if (code.isBlank()) return

        context.dataStore.edit { prefs ->
            val root = parseAll(prefs[Keys.JSON].orEmpty())
            val current = readDay(root, day)

            val oldQty = current.counts[code] ?: 0
            val newQty = (oldQty + delta).coerceAtLeast(0)

            val newCounts = current.counts.toMutableMap()
            if (newQty == 0) newCounts.remove(code) else newCounts[code] = newQty

            val next = current.copy(
                counts = newCounts,
                lastCode = if (current.lastCode == code && newQty == 0) null else current.lastCode
            )

            writeDay(root, day, next)
            prefs[Keys.JSON] = root.toString()
        }
    }

    suspend fun deleteCode(day: String, codeRaw: String) {
        setCodeCount(day, codeRaw, 0)
    }
    companion object {
        fun normalize(s: String): String =
            s.trim().uppercase()
    }
}
