package com.example.scanlog.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.scanlog.data.ScanStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ScanStore(app.applicationContext)

    val days: StateFlow<List<String>> =
        store.days.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}
