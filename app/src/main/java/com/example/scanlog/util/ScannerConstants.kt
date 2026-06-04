package com.example.scanlog.util

object ScannerConstants {
    // Barcode scanner broadcast (Xcheng PDA — original target).
    const val ACTION_DECODE_XCHENG = "com.xcheng.scanner.action.BARCODE_DECODING_BROADCAST"
    const val EXTRA_DECODE_XCHENG = "EXTRA_BARCODE_DECODING_DATA"

    // Barcode scanner broadcast (条捷印 T02 / BLD ScanManager).
    // Note: vendor's extra key is misspelled "barocode" — keep it as-is.
    const val ACTION_DECODE_BLD = "scan.rcv.message"
    const val EXTRA_DECODE_BLD_BYTES = "barocode"
    const val EXTRA_DECODE_BLD_LEN = "length"

    /** All broadcast actions we listen on. */
    val ALL_DECODE_ACTIONS = listOf(ACTION_DECODE_XCHENG, ACTION_DECODE_BLD)

    // Back-compat alias for old call sites.
    @Deprecated("Use ALL_DECODE_ACTIONS / vendor-specific constants.")
    const val ACTION_DECODE = ACTION_DECODE_XCHENG
    @Deprecated("Use ALL_DECODE_ACTIONS / vendor-specific constants.")
    const val EXTRA_DECODE_DATA = EXTRA_DECODE_XCHENG

    // RFID is handled via the GClient SDK (see com.example.scanlog.rfid.RfidController),
    // not broadcasts. Range is set by MsgBaseSetPower + RSSI floor, not an extra.
}
