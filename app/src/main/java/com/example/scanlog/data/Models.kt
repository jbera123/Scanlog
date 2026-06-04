package com.example.scanlog.data

data class ScanEvent(
    val code: String,
    val countAfter: Int,
    val tsMs: Long
)

enum class RfidRange(val label: String) {
    WEAK("Weak"),
    MEDIUM("Medium"),
    STRONG("Strong");

    companion object {
        fun fromKey(key: String): RfidRange = when (key) {
            "WEAK" -> WEAK
            "MEDIUM" -> MEDIUM
            "STRONG" -> STRONG
            // Migrate legacy values from earlier builds.
            "LONG" -> STRONG
            "SHORT" -> WEAK
            else -> MEDIUM
        }
    }
}

enum class ScanMode(val label: String) {
    BARCODE_ONLY("Barcode only"),
    RFID_AND_BARCODE("RFID + Barcode");

    companion object {
        fun fromKey(key: String): ScanMode =
            entries.firstOrNull { it.name == key } ?: BARCODE_ONLY
    }
}
