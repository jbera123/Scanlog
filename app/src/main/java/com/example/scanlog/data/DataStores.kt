package com.example.scanlog.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

// Single shared DataStore instance for the whole app (same file name).
val Context.scanlogDataStore by preferencesDataStore(name = "scanlog_store")
