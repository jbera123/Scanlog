package com.example.scanlog.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.scanlog.data.ScanStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    // IMPORTANT: use ScanStore so settings and scan logic read/write the same place
    private val store = ScanStore(app.applicationContext)

    val dupEnabled: StateFlow<Boolean> =
        store.duplicateGuardEnabled.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = true
        )

    val dupWindowMs: StateFlow<Long> =
        store.duplicateWindowMs.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = 2000L
        )

    fun setDupEnabled(v: Boolean) {
        viewModelScope.launch {
            store.setDuplicateGuardEnabled(v)
        }
    }

    fun setDupWindowSeconds(seconds: Long) {
        viewModelScope.launch {
            store.setDuplicateWindowMs(seconds * 1000L)
        }
    }
}
