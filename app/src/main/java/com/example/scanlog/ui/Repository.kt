package com.example.scanlog.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

object Repository {

    private val scansByDay =
        MutableStateFlow<Map<String, MutableMap<String, Int>>>(emptyMap())

    fun countsForDay(day: String): Flow<Map<String, Int>> =
        scansByDay.map { it[day]?.toMap() ?: emptyMap() }

    fun days(): Flow<List<String>> =
        scansByDay.map { it.keys.sortedDescending() }

    suspend fun addScan(day: String, codeRaw: String) {
        val current = scansByDay.value.toMutableMap()
        val dayMap = current.getOrPut(day) { mutableMapOf() }
        dayMap[codeRaw] = (dayMap[codeRaw] ?: 0) + 1
        scansByDay.value = current
    }

    suspend fun undoLastScan(day: String) {
        val current = scansByDay.value.toMutableMap()
        val dayMap = current[day] ?: return
        val lastKey = dayMap.keys.lastOrNull() ?: return

        val newValue = (dayMap[lastKey] ?: 1) - 1
        if (newValue <= 0) {
            dayMap.remove(lastKey)
        } else {
            dayMap[lastKey] = newValue
        }
        scansByDay.value = current
    }
}
