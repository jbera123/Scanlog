package com.example.scanlog.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.scanlog.data.ScanStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DayViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ScanStore(app.applicationContext)

    enum class DaySort {
        MOST,   // count desc, then code asc
        ALPHA   // code asc
    }

    private val _sort = MutableStateFlow(DaySort.MOST)
    val sort: StateFlow<DaySort> = _sort.asStateFlow()

    fun setSort(v: DaySort) {
        _sort.value = v
    }

    fun counts(day: String): Flow<Map<String, Int>> =
        store.dayCounts(day)

    // NEW: pre-sorted list that the UI can render directly
    fun sortedCounts(day: String): Flow<List<Pair<String, Int>>> {
        return combine(store.dayCounts(day), sort) { counts, mode ->
            val list = counts.toList()
            when (mode) {
                DaySort.MOST ->
                    list.sortedWith(
                        compareByDescending<Pair<String, Int>> { it.second }
                            .thenBy { it.first }
                    )
                DaySort.ALPHA ->
                    list.sortedBy { it.first }
            }
        }
            // Avoid extra recompositions if list is identical
            .distinctUntilChanged()
    }

    fun total(day: String): Flow<Int> =
        store.dayCounts(day).map { m -> m.values.sum() }.distinctUntilChanged()

    // +/- with delta
    fun increment(day: String, code: String, delta: Int) {
        if (delta == 0) return
        viewModelScope.launch {
            store.incrementCode(day, code, delta)
        }
    }

    fun deleteCode(day: String, code: String) {
        viewModelScope.launch {
            store.deleteCode(day, code)
        }
    }

    fun deleteDay(day: String) {
        viewModelScope.launch {
            store.deleteDay(day)
        }
    }
}
