package com.example.scanlog.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val zone: ZoneId = ZoneId.systemDefault()

fun dayKeyLocal(epochMs: Long): String {
    val dt = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    return dt.toString() // YYYY-MM-DD
}

fun timeHms(epochMs: Long): String {
    val t = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()
    return t.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
}

fun isoLocal(epochMs: Long): String {
    return Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDateTime().toString()
}
