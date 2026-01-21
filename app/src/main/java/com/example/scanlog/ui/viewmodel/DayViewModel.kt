package com.example.scanlog.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.scanlog.data.ScanStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class DayViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ScanStore(app.applicationContext)

    fun counts(day: String): Flow<Map<String, Int>> =
        store.dayCounts(day)

    // +/- with delta
    fun increment(day: String, code: String, delta: Int) {
        if (delta == 0) return
        viewModelScope.launch {
            store.incrementCode(day, code, delta)
        }
    }

    // Convenience helpers
    fun increment(day: String, code: String) = increment(day, code, +1)

    fun decrement(day: String, code: String) = increment(day, code, -1)

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
