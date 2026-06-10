package com.example.scanlog.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scanlog.R
import com.example.scanlog.data.RfidRange
import com.example.scanlog.data.ScanMode
import com.example.scanlog.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val enabled by vm.dupEnabled.collectAsState()
    val windowMs by vm.dupWindowMs.collectAsState()
    val scanMode by vm.scanMode.collectAsState()
    val rfidRange by vm.rfidRange.collectAsState()
    val allowRepeated by vm.allowRepeatedScans.collectAsState()

    var secondsText by remember(windowMs) { mutableStateOf((windowMs / 1000L).toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_scan_mode),
                    style = MaterialTheme.typography.bodyLarge
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScanMode.entries.forEach { mode ->
                        FilterChip(
                            selected = scanMode == mode,
                            onClick = { vm.setScanMode(mode) },
                            label = { Text(mode.label) }
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.settings_scan_mode_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.settings_allow_repeated),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = allowRepeated,
                        onCheckedChange = { vm.setAllowRepeatedScans(it) }
                    )
                }
                Text(
                    text = stringResource(R.string.settings_allow_repeated_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.settings_dup_guard),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { vm.setDupEnabled(it) }
                    )
                }

                OutlinedTextField(
                    value = secondsText,
                    onValueChange = { secondsText = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.settings_dup_window_seconds)) },
                    supportingText = { Text(stringResource(R.string.settings_dup_window_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Button(
                    onClick = {
                        val sec = secondsText.toLongOrNull() ?: 2L
                        vm.setDupWindowSeconds(sec.coerceIn(0L, 10L))
                    }
                ) {
                    Text(stringResource(R.string.settings_apply))
                }
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_rfid_range),
                    style = MaterialTheme.typography.bodyLarge
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RfidRange.entries.forEach { range ->
                        FilterChip(
                            selected = rfidRange == range,
                            onClick = { vm.setRfidRange(range) },
                            label = { Text(range.label) }
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.settings_rfid_range_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
