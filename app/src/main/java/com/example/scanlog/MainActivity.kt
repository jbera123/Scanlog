package com.example.scanlog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.example.scanlog.ui.theme.ScanlogTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScanlogTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppNav()
                }
            }
        }
    }
}
