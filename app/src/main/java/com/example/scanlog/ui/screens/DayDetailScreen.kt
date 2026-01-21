package com.example.scanlog.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scanlog.R
import com.example.scanlog.data.BarcodeCatalog
import com.example.scanlog.ui.viewmodel.DayViewModel

@Composable
fun DayDetailScreen(
    day: String,
    onBack: () -> Unit,
    vm: DayViewModel = viewModel()
) {
    val appContext = LocalContext.current.applicationContext
    val catalog = remember(appContext) { BarcodeCatalog(appContext) }

    val counts by vm.counts(day).collectAsState(initial = emptyMap())
    var confirmDeleteCode by remember { mutableStateOf<String?>(null) }

    val sorted = remember(counts) {
        counts.toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    }
    val total = remember(sorted) { sorted.sumOf { it.second } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                // Day string stays raw (ISO date)
                Text(day, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = stringResource(R.string.total, total),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }

        Spacer(Modifier.height(12.dp))

        if (sorted.isEmpty()) {
            Text(
                text = stringResource(R.string.no_scans_for_day),
                style = MaterialTheme.typography.bodyLarge
            )
        } else {
            LazyColumn {
                items(
                    items = sorted,
                    key = { (code, _) -> code }
                ) { (code, count) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left side: code + chinese name (if exists)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = catalog.displayText(code),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(R.string.count_label, count),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        // Right side: actions (no Icons -> no "Remove" error)
                        var menuExpanded by remember(code) { mutableStateOf(false) }

                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("+1") },
                                onClick = {
                                    menuExpanded = false
                                    vm.increment(day, code, +1)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("-1") },
                                enabled = count > 0,
                                onClick = {
                                    menuExpanded = false
                                    vm.increment(day, code, -1)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                onClick = {
                                    menuExpanded = false
                                    confirmDeleteCode = code
                                }
                            )
                        }

                    }
                    HorizontalDivider()
                }
            }
        }
    }

    val codeToDelete = confirmDeleteCode
    if (codeToDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteCode = null },
            title = { Text(stringResource(R.string.delete_code_title)) },
            text = {
                // Keep raw code/day in message; localized shell text
                Text(stringResource(R.string.delete_code_text, codeToDelete, day))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteCode(day, codeToDelete)
                        confirmDeleteCode = null
                    }
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteCode = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
