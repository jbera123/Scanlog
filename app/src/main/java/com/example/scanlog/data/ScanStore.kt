package com.example.scanlog.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.scanlog.data.RfidRange
import com.example.scanlog.data.ScanMode
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

private val Context.dataStore by preferencesDataStore(name = "scanlog_store")

data class DayState(
    val counts: Map<String, Int> = emptyMap(),
    val lastCode: String? = null,
    val lastTsMs: Long? = null,
    // Unique RFID EPCs already counted today. Used to prevent double-counting
    // the same physical tag under its rolled-up category label.
    val seenEpcs: Set<String> = emptySet()
)



class ScanStore(private val context: Context) {

    private object Keys {
        val JSON = stringPreferencesKey("days_json")

        // Dup settings stored here (since your app currently does it this way)
        val DUP_GUARD = stringPreferencesKey("dup_guard_enabled") // "true"/"false"
        val DUP_WINDOW_MS = longPreferencesKey("dup_window_ms")
        val RFID_RANGE = stringPreferencesKey("rfid_range")
        val SCAN_MODE = stringPreferencesKey("scan_mode")

        // Testing aid: when "true", the per-day unique-EPC dedup is bypassed so the
        // same tag can be counted repeatedly without deleting the day.
        val ALLOW_REPEATED = stringPreferencesKey("allow_repeated_scans")

        // NEW: recent scan events for Scan tab
        val RECENT_EVENTS_JSON = stringPreferencesKey("recent_events_json")

        // Comma-separated list of days that have been auto-exported to CSV.
        val EXPORTED_DAYS = stringPreferencesKey("exported_days")
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

        val seen: Set<String> = if (d.has("seenEpcs")) {
            val arr = d.getJSONArray("seenEpcs")
            buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
        } else emptySet()

        return DayState(counts = counts, lastCode = lastCode, lastTsMs = lastTs, seenEpcs = seen)
    }

    private fun writeDay(root: JSONObject, day: String, state: DayState) {
        val d = JSONObject()

        val countsObj = JSONObject()
        state.counts.forEach { (code, qty) -> countsObj.put(code, qty) }
        d.put("counts", countsObj)

        state.lastCode?.let { d.put("lastCode", it) }
        state.lastTsMs?.let { d.put("lastTsMs", it) }

        if (state.seenEpcs.isNotEmpty()) {
            val arr = JSONArray()
            state.seenEpcs.forEach { arr.put(it) }
            d.put("seenEpcs", arr)
        }

        root.put(day, d)
    }

    // --- Existing flows (used by Counts/History/Day detail) ---

    val todayCounts: Flow<Map<String, Int>> = dayCounts(todayKey())

    /**
     * Today's already-counted EPC set. Lets the ViewModel build an in-memory
     * dedup cache so re-emits of an already-counted tag short-circuit before
     * the DataStore round-trip.
     */
    val todaySeenEpcs: Flow<Set<String>> =
        context.dataStore.data.map { prefs ->
            val root = parseAll(prefs[Keys.JSON].orEmpty())
            readDay(root, todayKey()).seenEpcs
        }

    fun dayCounts(day: String): Flow<Map<String, Int>> =
        context.dataStore.data.map { prefs ->
            val root = parseAll(prefs[Keys.JSON].orEmpty())
            readDay(root, day).counts
        }

    /** Snapshot of full days_json — used by the auto-exporter at app start. */
    suspend fun snapshotAllDays(): Map<String, Map<String, Int>> {
        val prefs = context.dataStore.data.first()
        val root = parseAll(prefs[Keys.JSON].orEmpty())
        val out = LinkedHashMap<String, Map<String, Int>>()
        val it = root.keys()
        while (it.hasNext()) {
            val day = it.next()
            out[day] = readDay(root, day).counts
        }
        return out
    }

    /**
     * Drops the seenEpcs sets of every day EXCEPT today. Past days only need their
     * final counts — their EPC lists exist purely for same-day dedup and become
     * dead weight after midnight, yet every scan re-parses + rewrites the whole
     * days_json blob. Pruning keeps per-scan cost flat over weeks of use.
     * Called once at app start (ScanlogApp). Counts are untouched.
     */
    suspend fun pruneOldSeenEpcs() {
        context.dataStore.edit { prefs ->
            val root = parseAll(prefs[Keys.JSON].orEmpty())
            val today = todayKey()
            var changed = false
            val it = root.keys()
            while (it.hasNext()) {
                val day = it.next()
                if (day == today) continue
                val d = root.getJSONObject(day)
                if (d.has("seenEpcs")) {
                    d.remove("seenEpcs")
                    changed = true
                }
            }
            if (changed) prefs[Keys.JSON] = root.toString()
        }
    }

    /** Days already auto-exported. */
    suspend fun getExportedDays(): Set<String> {
        val prefs = context.dataStore.data.first()
        return prefs[Keys.EXPORTED_DAYS]
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
    }

    /** Mark a day as exported so we don't write it again on next launch. */
    suspend fun markExported(day: String) {
        context.dataStore.edit { prefs ->
            val existing = prefs[Keys.EXPORTED_DAYS]
                ?.split(',')
                ?.filter { it.isNotBlank() }
                ?.toMutableSet()
                ?: mutableSetOf()
            if (existing.add(day)) {
                prefs[Keys.EXPORTED_DAYS] = existing.joinToString(",")
            }
        }
    }

    val days: Flow<List<String>> =
        context.dataStore.data.map { prefs ->
            val root = parseAll(prefs[Keys.JSON].orEmpty())
            val list = mutableListOf<String>()
            val it = root.keys()
            while (it.hasNext()) list.add(it.next())
            list.sortedDescending()
        }

    // --- Settings (dup guard) stored in ScanStore datastore ---

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

    val rfidRange: Flow<RfidRange> =
        context.dataStore.data.map { prefs ->
            RfidRange.fromKey(prefs[Keys.RFID_RANGE].orEmpty())
        }

    suspend fun setRfidRange(range: RfidRange) {
        context.dataStore.edit { it[Keys.RFID_RANGE] = range.name }
    }

    val scanMode: Flow<ScanMode> =
        context.dataStore.data.map { prefs ->
            ScanMode.fromKey(prefs[Keys.SCAN_MODE].orEmpty())
        }

    suspend fun setScanMode(mode: ScanMode) {
        context.dataStore.edit { it[Keys.SCAN_MODE] = mode.name }
    }

    val allowRepeatedScans: Flow<Boolean> =
        context.dataStore.data.map { prefs ->
            prefs[Keys.ALLOW_REPEATED]?.toBooleanStrictOrNull() ?: false
        }

    suspend fun setAllowRepeatedScans(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ALLOW_REPEATED] = enabled.toString() }
    }

    // --- NEW: Recent events for Scan tab ---

    val recentEvents: Flow<List<ScanEvent>> =
        context.dataStore.data.map { prefs ->
            parseRecentEvents(prefs[Keys.RECENT_EVENTS_JSON].orEmpty())
        }

    private fun parseRecentEvents(json: String): List<ScanEvent> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<ScanEvent>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val code = o.optString("code", "")
                val countAfter = o.optInt("countAfter", 0)
                val tsMs = o.optLong("tsMs", 0L)
                if (code.isNotBlank() && tsMs > 0L) {
                    out.add(ScanEvent(code = code, countAfter = countAfter, tsMs = tsMs))
                }
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun writeRecentEvents(events: List<ScanEvent>): String {
        val arr = JSONArray()
        events.forEach { e ->
            val o = JSONObject()
            o.put("code", e.code)
            o.put("countAfter", e.countAfter)
            o.put("tsMs", e.tsMs)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun pushRecentEvent(
        prefsJson: String,
        event: ScanEvent,
        maxSize: Int = 10
    ): String {
        val existing = parseRecentEvents(prefsJson).toMutableList()
        // Insert newest first
        existing.add(0, event)
        // Trim
        if (existing.size > maxSize) {
            while (existing.size > maxSize) existing.removeAt(existing.lastIndex)
        }
        return writeRecentEvents(existing)
    }

    // --- Core scan + dup logic (kept) + NEW: append recent event ---

    suspend fun recordScan(codeRaw: String, nowMs: Long = System.currentTimeMillis()): Int? {
        val code = normalize(codeRaw)
        if (code.isBlank()) return null

        var recordedCountAfter: Int? = null

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

            if (isDupe) {
                recordedCountAfter = null
                return@edit
            }

            val oldQty = current.counts[code] ?: 0
            val newQty = oldQty + 1

            val newCounts = current.counts.toMutableMap()
            newCounts[code] = newQty

            val next = current.copy(
                counts = newCounts,
                lastCode = code,
                lastTsMs = nowMs
            )

            writeDay(root, day, next)
            prefs[Keys.JSON] = root.toString()

            recordedCountAfter = newQty
        }

        return recordedCountAfter
    }


    /**
     * Records a scanned RFID tag. Each unique EPC is counted at most ONCE per day
     * under the rolled-up category code (e.g. "SEAT4-1-A"). If the EPC was already
     * seen today, this is a no-op and returns null.
     */
    suspend fun recordRfidTag(
        epcRaw: String,
        rollupCodeRaw: String,
        nowMs: Long = System.currentTimeMillis(),
        allowRepeat: Boolean = false
    ): Int? {
        val epc = normalize(epcRaw)
        val rollup = normalize(rollupCodeRaw)
        if (epc.isBlank() || rollup.isBlank()) return null

        var recordedCountAfter: Int? = null

        context.dataStore.edit { prefs ->
            val root = parseAll(prefs[Keys.JSON].orEmpty())
            val day = todayKey()
            val current = readDay(root, day)

            // Per-tag dedup: same physical EPC only counts once per day. When
            // allowRepeat is set (testing toggle) we skip this so the same tag can
            // be counted again and again without deleting the day.
            if (!allowRepeat && epc in current.seenEpcs) {
                recordedCountAfter = null
                return@edit
            }

            val oldQty = current.counts[rollup] ?: 0
            val newQty = oldQty + 1

            val newCounts = current.counts.toMutableMap()
            newCounts[rollup] = newQty

            val next = current.copy(
                counts = newCounts,
                lastCode = rollup,
                lastTsMs = nowMs,
                seenEpcs = current.seenEpcs + epc
            )

            writeDay(root, day, next)
            prefs[Keys.JSON] = root.toString()

            recordedCountAfter = newQty
        }

        return recordedCountAfter
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

            // Optional: We are NOT editing recentEvents on undo (keeps it simple).
            // If you want undo to remove the latest event entry, we can add that later.
        }
    }

    // --- Existing edit/delete support (keep as-is) ---

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
        fun normalize(s: String): String = s.trim().uppercase()
    }
}
