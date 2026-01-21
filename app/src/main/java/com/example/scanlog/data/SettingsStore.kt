package com.example.scanlog.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private val DUP_GUARD_ENABLED = booleanPreferencesKey("dup_guard_enabled")
    private val DUP_WINDOW_MS = longPreferencesKey("dup_window_ms")

    val duplicateGuardEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[DUP_GUARD_ENABLED] ?: true }

    val duplicateWindowMs: Flow<Long> =
        context.dataStore.data.map { it[DUP_WINDOW_MS] ?: 2000L }

    suspend fun setDuplicateGuardEnabled(enabled: Boolean) {
        context.dataStore.edit { it[DUP_GUARD_ENABLED] = enabled }
    }

    suspend fun setDuplicateWindowMs(ms: Long) {
        context.dataStore.edit { it[DUP_WINDOW_MS] = ms }
    }
}
