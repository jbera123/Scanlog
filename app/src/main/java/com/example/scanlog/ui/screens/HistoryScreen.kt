package com.example.scanlog.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scanlog.R
import com.example.scanlog.ui.viewmodel.HistoryViewModel

@Composable
fun HistoryScreen(
    onOpenDay: (String) -> Unit,
    vm: HistoryViewModel = viewModel()
) {
    val days by vm.days.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.tab_history),
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(Modifier.height(12.dp))

        if (days.isEmpty()) {
            Text(
                text = stringResource(R.string.no_history_yet),
                style = MaterialTheme.typography.bodyLarge
            )
        } else {
            days.forEach { day ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable { onOpenDay(day) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Day stays raw (ISO date)
                        Text(day, style = MaterialTheme.typography.bodyLarge)

                        Text(
                            text = stringResource(R.string.history_open),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }
}
