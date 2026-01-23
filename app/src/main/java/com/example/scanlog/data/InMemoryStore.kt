package com.example.scanlog.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * Simple in-memory store (no persistence).
 * Keep it compiling even if you are using ScanStore for real persistence.
 *
 * Uses ScanEvent from Models.kt (single shared model).
 */
class InMemoryStore {

    // day -> counts
    private val dayCountsState = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())

    // newest first
    private val recentEventsState = MutableStateFlow<List<ScanEvent>>(emptyList())

    // For undo
    private var lastScannedDay: String? = null
    private var lastScannedCode: String? = null

    private fun dayKey(): String = LocalDate.now().toString()

    fun dayCounts(day: String): Flow<Map<String, Int>> =
        dayCountsState.map { all -> all[day].orEmpty() }

    val todayCounts: Flow<Map<String, Int>> =
        dayCounts(dayKey())

    val days: Flow<List<String>> =
        dayCountsState.map { all -> all.keys.sortedDescending() }

    val recentEvents: Flow<List<ScanEvent>> = recentEventsState

    /**
     * Return true if recorded, false if ignored (blank).
     * (No duplicate guard here; ScanStore handles that.)
     */
    fun recordScan(codeRaw: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val code = normalize(codeRaw)
        if (code.isBlank()) return false

        val day = dayKey()

        val all = dayCountsState.value.toMutableMap()
        val counts = all[day].orEmpty().toMutableMap()

        val nextQty = (counts[code] ?: 0) + 1
        counts[code] = nextQty
        all[day] = counts
        dayCountsState.value = all

        val nextEvents = buildList {
            add(ScanEvent(code = code, countAfter = nextQty, tsMs = nowMs))
            addAll(recentEventsState.value)
        }.take(200)
        recentEventsState.value = nextEvents

        lastScannedDay = day
        lastScannedCode = code
        return true
    }

    fun undoLast(nowMs: Long = System.currentTimeMillis()): Boolean {
        val day = lastScannedDay ?: return false
        val code = lastScannedCode ?: return false

        val all = dayCountsState.value.toMutableMap()
        val counts = all[day]?.toMutableMap() ?: return false

        val curQty = counts[code] ?: return false
        if (curQty <= 0) return false

        val nextQty = curQty - 1
        if (nextQty == 0) counts.remove(code) else counts[code] = nextQty

        all[day] = counts
        dayCountsState.value = all

        lastScannedDay = null
        lastScannedCode = null
        return true
    }

    companion object {
        fun normalize(s: String): String = s.trim().uppercase()
    }
}
