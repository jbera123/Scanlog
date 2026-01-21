package com.example.scanlog.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scanlog.R
import com.example.scanlog.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val enabled by vm.dupEnabled.collectAsState()
    val windowMs by vm.dupWindowMs.collectAsState()

    var secondsText by remember(windowMs) { mutableStateOf((windowMs / 1000L).toString()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.settings_dup_guard), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = enabled, onCheckedChange = { vm.setDupEnabled(it) })
                }

                OutlinedTextField(
                    value = secondsText,
                    onValueChange = { secondsText = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.settings_dup_window_seconds)) },
                    supportingText = { Text(stringResource(R.string.settings_dup_window_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Button(onClick = {
                    val sec = secondsText.toLongOrNull() ?: 2L
                    vm.setDupWindowSeconds(sec.coerceIn(0L, 10L))
                }) {
                    Text(stringResource(R.string.settings_apply))
                }
            }
        }
    }
}
