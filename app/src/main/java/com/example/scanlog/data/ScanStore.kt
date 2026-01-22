package com.example.scanlog.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.time.LocalDate

// IMPORTANT:
// Use a UNIQUE name to avoid colliding with other files' Context.dataStore / Context.scanlogDataStore.
private val Context.scanStoreDataStore: DataStore<Preferences> by preferencesDataStore(name = "scanlog_store")

data class DayState(
    val counts: Map<String, Int> = emptyMap(),
    val lastCode: String? = null,
    val lastTsMs: Long? = null
)

class ScanStore(context: Context) {

    private val appContext = context.applicationContext
    private val ds = appContext.scanStoreDataStore

    private object Keys {
        val DAYS_JSON = stringPreferencesKey("days_json")

        // Store dup enabled as string "true"/"false" for backwards compatibility with your existing code
        val DUP_ENABLED = stringPreferencesKey("dup_guard_enabled")
        val DUP_WINDOW_MS = longPreferencesKey("dup_window_ms")
    }

    private val defaultDupEnabled = true
    private val defaultDupWindowMs = 2000L

    /* ---------------- helpers ---------------- */

    private fun todayKey(): String = LocalDate.now().toString()

    private fun parseRoot(json: String): JSONObject =
        if (json.isBlank()) JSONObject() else JSONObject(json)

    private fun readDay(root: JSONObject, day: String): DayState {
        if (!root.has(day)) return DayState()

        val d = root.getJSONObject(day)
        val countsObj = d.optJSONObject("counts") ?: JSONObject()

        val counts = buildMap<String, Int> {
            val it = countsObj.keys()
            while (it.hasNext()) {
                val k = it.next()
                put(k, countsObj.optInt(k, 0))
            }
        }

        val lastCode = if (d.has("lastCode") && !d.isNull("lastCode")) d.getString("lastCode") else null
        val lastTs = if (d.has("lastTsMs") && !d.isNull("lastTsMs")) d.getLong("lastTsMs") else null

        return DayState(counts = counts, lastCode = lastCode, lastTsMs = lastTs)
    }

    private fun writeDay(root: JSONObject, day: String, state: DayState) {
        val d = JSONObject()

        val countsObj = JSONObject()
        state.counts.forEach { (k, v) -> countsObj.put(k, v) }
        d.put("counts", countsObj)

        if (state.lastCode != null) d.put("lastCode", state.lastCode) else d.put("lastCode", JSONObject.NULL)
        if (state.lastTsMs != null) d.put("lastTsMs", state.lastTsMs) else d.put("lastTsMs", JSONObject.NULL)

        root.put(day, d)
    }

    private fun normalize(code: String): String = code.trim().uppercase()

    /* ---------------- flows ---------------- */

    val todayCounts: Flow<Map<String, Int>> =
        dayCounts(todayKey())

    fun dayCounts(day: String): Flow<Map<String, Int>> =
        ds.data.map { prefs ->
            val root = parseRoot(prefs[Keys.DAYS_JSON].orEmpty())
            readDay(root, day).counts
        }

    val days: Flow<List<String>> =
        ds.data.map { prefs ->
            val root = parseRoot(prefs[Keys.DAYS_JSON].orEmpty())
            val out = mutableListOf<String>()
            val it = root.keys()
            while (it.hasNext()) out.add(it.next())
            out.sortedDescending()
        }

    /* ---------------- duplicate settings ---------------- */

    val duplicateGuardEnabled: Flow<Boolean> =
        ds.data.map { prefs ->
            prefs[Keys.DUP_ENABLED]?.toBooleanStrictOrNull() ?: defaultDupEnabled
        }

    val duplicateWindowMs: Flow<Long> =
        ds.data.map { prefs ->
            prefs[Keys.DUP_WINDOW_MS] ?: defaultDupWindowMs
        }

    suspend fun setDuplicateGuardEnabled(enabled: Boolean) {
        ds.edit { it[Keys.DUP_ENABLED] = enabled.toString() }
    }

    suspend fun setDuplicateWindowMs(ms: Long) {
        ds.edit { it[Keys.DUP_WINDOW_MS] = ms }
    }

    /* ---------------- core actions ---------------- */

    /**
     * @return true if scan was recorded, false if ignored (duplicate within window)
     */
    suspend fun recordScan(codeRaw: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val code = normalize(codeRaw)
        if (code.isBlank()) return false

        var recorded = false

        ds.edit { prefs ->
            val root = parseRoot(prefs[Keys.DAYS_JSON].orEmpty())
            val day = todayKey()
            val current = readDay(root, day)

            val dupEnabled = prefs[Keys.DUP_ENABLED]?.toBooleanStrictOrNull() ?: defaultDupEnabled
            val dupWindow = prefs[Keys.DUP_WINDOW_MS] ?: defaultDupWindowMs

            val isDuplicate =
                dupEnabled &&
                        current.lastCode == code &&
                        current.lastTsMs != null &&
                        (nowMs - current.lastTsMs) < dupWindow

            if (!isDuplicate) {
                val newCounts = current.counts.toMutableMap()
                newCounts[code] = (newCounts[code] ?: 0) + 1

                val next = current.copy(
                    counts = newCounts,
                    lastCode = code,
                    lastTsMs = nowMs
                )

                writeDay(root, day, next)
                prefs[Keys.DAYS_JSON] = root.toString()
                recorded = true
            }
        }

        return recorded
    }

    suspend fun undoLast(nowMs: Long = System.currentTimeMillis()) {
        ds.edit { prefs ->
            val root = parseRoot(prefs[Keys.DAYS_JSON].orEmpty())
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
            prefs[Keys.DAYS_JSON] = root.toString()
        }
    }

    /* ---------------- day edit ---------------- */

    suspend fun incrementCode(day: String, codeRaw: String, delta: Int) {
        val code = normalize(codeRaw)
        if (code.isBlank()) return

        ds.edit { prefs ->
            val root = parseRoot(prefs[Keys.DAYS_JSON].orEmpty())
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
            prefs[Keys.DAYS_JSON] = root.toString()
        }
    }

    suspend fun deleteCode(day: String, codeRaw: String) {
        // set to zero by applying a large negative delta
        incrementCode(day, codeRaw, -1_000_000)
    }

    suspend fun deleteDay(day: String) {
        ds.edit { prefs ->
            val root = parseRoot(prefs[Keys.DAYS_JSON].orEmpty())
            if (root.has(day)) {
                root.remove(day)
                prefs[Keys.DAYS_JSON] = root.toString()
            }
        }
    }
}
