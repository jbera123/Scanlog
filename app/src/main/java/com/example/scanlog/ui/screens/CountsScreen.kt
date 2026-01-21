package com.example.scanlog.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scanlog.R
import com.example.scanlog.data.BarcodeCatalog
import com.example.scanlog.ui.components.BarcodeRow
import com.example.scanlog.ui.viewmodel.ScanViewModel

@Composable
fun CountsScreen(
    vm: ScanViewModel = viewModel()
) {
    val appContext = LocalContext.current.applicationContext
    val catalog = remember(appContext) { BarcodeCatalog(appContext) }

    val counts by vm.todayCounts.collectAsState()
    var query by remember { mutableStateOf("") }

    val rows = remember(counts, query) {
        val q = query.trim()
        val list = counts.toList()
        val filtered =
            if (q.isEmpty()) list
            else list.filter { (code, _) -> code.contains(q, ignoreCase = true) }

        filtered.sortedWith(
            compareByDescending<Pair<String, Int>> { it.second }
                .thenBy { it.first }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.counts_today_title),
            style = MaterialTheme.typography.titleLarge
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.search_code_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Header row (labels)
        Card {
            BarcodeRow(
                leftText = stringResource(R.string.code_header),
                rightText = stringResource(R.string.count_header),
                modifier = Modifier.padding(16.dp)
            )
        }

        if (rows.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.no_data),
                style = MaterialTheme.typography.bodyLarge
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(
                    items = rows,
                    key = { (code, _) -> code }
                ) { (code, qty) ->
                    Card {
                        BarcodeRow(
                            leftText = catalog.displayText(code), // CODE + Chinese if mapped
                            rightText = qty.toString(),
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}
