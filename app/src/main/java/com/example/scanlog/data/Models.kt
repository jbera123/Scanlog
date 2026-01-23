package com.example.scanlog.data

data class ScanEvent(
    val code: String,
    val countAfter: Int,
    val tsMs: Long
)
