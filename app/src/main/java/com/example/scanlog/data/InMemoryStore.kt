package com.example.scanlog.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ScanEvent(
    val codeRaw: String,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val dayKey: String
        get() = Instant.ofEpochMilli(timestampMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
}

object InMemoryStore {
    // All scans for all days while app process is alive
    private val _events = MutableStateFlow<List<ScanEvent>>(emptyList())
    val events: StateFlow<List<ScanEvent>> = _events.asStateFlow()

    fun add(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        _events.value = _events.value + ScanEvent(codeRaw = trimmed)
    }

    fun undoLast(): Boolean {
        val cur = _events.value
        if (cur.isEmpty()) return false
        _events.value = cur.dropLast(1)
        return true
    }

    fun countsForDay(dayKey: String): Map<String, Int> {
        return _events.value
            .asSequence()
            .filter { it.dayKey == dayKey }
            .groupingBy { normalize(it.codeRaw) }
            .eachCount()
    }

    fun distinctDaysDesc(): List<String> {
        return _events.value
            .map { it.dayKey }
            .distinct()
            .sortedDescending()
    }

    fun todayKey(): String = LocalDate.now().toString()

    private fun normalize(s: String): String = s.trim().uppercase()
}
