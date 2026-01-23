package com.example.scanlog.ui.screens

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import java.io.OutputStream

@Composable
fun DayDetailScreen(
    day: String,
    onBack: () -> Unit,
    vm: DayViewModel = viewModel()
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val catalog = remember(appContext) { BarcodeCatalog(appContext) }

    val sorted by vm.sortedCounts(day).collectAsState(initial = emptyList())
    val total by vm.total(day).collectAsState(initial = 0)

    var confirmDeleteCode by remember { mutableStateOf<String?>(null) }

    var pageMenuExpanded by remember { mutableStateOf(false) }
    val sortMode by vm.sort.collectAsState()

    var confirmDeleteDay by remember { mutableStateOf(false) }

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
                Text(day, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = stringResource(R.string.total, total),
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Row {
                IconButton(onClick = { pageMenuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                }

                DropdownMenu(
                    expanded = pageMenuExpanded,
                    onDismissRequest = { pageMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sort_most)) },
                        onClick = {
                            pageMenuExpanded = false
                            vm.setSort(DayViewModel.DaySort.MOST)
                        },
                        enabled = sortMode != DayViewModel.DaySort.MOST
                    )

                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sort_alpha)) },
                        onClick = {
                            pageMenuExpanded = false
                            vm.setSort(DayViewModel.DaySort.ALPHA)
                        },
                        enabled = sortMode != DayViewModel.DaySort.ALPHA
                    )

                    HorizontalDivider()

                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_csv)) },
                        enabled = sorted.isNotEmpty(),
                        onClick = {
                            pageMenuExpanded = false
                            exportDayCsvToDownloads(
                                context = context,
                                day = day,
                                rows = sorted
                            )
                        }
                    )

                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete_day)) },
                        onClick = {
                            pageMenuExpanded = false
                            confirmDeleteDay = true
                        }
                    )
                }

                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            }
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

                        var rowMenuExpanded by remember(code) { mutableStateOf(false) }

                        IconButton(onClick = { rowMenuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }

                        DropdownMenu(
                            expanded = rowMenuExpanded,
                            onDismissRequest = { rowMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("+1") },
                                onClick = {
                                    rowMenuExpanded = false
                                    vm.increment(day, code, +1)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("-1") },
                                enabled = count > 0,
                                onClick = {
                                    rowMenuExpanded = false
                                    vm.increment(day, code, -1)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                onClick = {
                                    rowMenuExpanded = false
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
            text = { Text(stringResource(R.string.delete_code_text, codeToDelete, day)) },
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

    if (confirmDeleteDay) {
        AlertDialog(
            onDismissRequest = { confirmDeleteDay = false },
            title = { Text(stringResource(R.string.delete_day_title)) },
            text = { Text(stringResource(R.string.delete_day_text, day)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteDay(day)
                        confirmDeleteDay = false
                        onBack()
                    }
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteDay = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

fun exportDayCsvToDownloads(
    context: Context,
    day: String,
    rows: List<Pair<String, Int>>
) {
    val fileName = "ScanLog_$day.csv"

    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "text/csv")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }

    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return

    resolver.openOutputStream(uri)?.use { out ->
        writeCsv(out, rows)
    }
}

private fun writeCsv(out: OutputStream, rows: List<Pair<String, Int>>) {
    out.write("Code,Count\n".toByteArray())
    rows.forEach { (code, count) ->
        out.write("$code,$count\n".toByteArray())
    }
}
