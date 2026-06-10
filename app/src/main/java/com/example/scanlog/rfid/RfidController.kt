package com.example.scanlog.rfid

import android.util.Log
import com.gg.reader.api.dal.GClient
import com.gg.reader.api.dal.HandlerTagEpcLog
import com.gg.reader.api.dal.HandlerTagEpcOver
import com.gg.reader.api.protocol.gx.EnumG
import com.gg.reader.api.protocol.gx.LogBaseEpcInfo
import com.gg.reader.api.protocol.gx.LogBaseEpcOver
import com.gg.reader.api.protocol.gx.MsgBaseInventoryEpc
import com.gg.reader.api.protocol.gx.MsgBaseSetPower
import com.gg.reader.api.protocol.gx.MsgBaseSetTagLog
import com.gg.reader.api.protocol.gx.MsgBaseStop
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Hashtable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton wrapper around the GClient SDK.
 *
 * - init() powers up the UHF rail and opens /dev/ttyS3 (tries 460800 then 115200),
 *   then applies a single fixed power + RSSI floor (no runtime range tuning).
 * - startInventory()/stopInventory() drive continuous EPC reads.
 * - tagFlow emits each accepted EPC string (uppercase, trimmed).
 *
 * Lifecycle: opened lazily by AppNav only in RFID_AND_BARCODE mode and released
 * when leaving it, so the default barcode-only app never powers on the reader.
 *
 * Thread-safety: all public functions are safe to call from the main thread.
 * SDK callbacks arrive on an internal SDK thread — we only do a fast emit +
 * filter, no UI work, no blocking calls.
 */
object RfidController {

    private const val TAG = "RfidController"
    private const val SERIAL_DEV = "/dev/ttyS3"

    private val client: GClient = GClient()
    private val opened = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    // Gate: if false, triggerPress() is a no-op and any active inventory is stopped.
    // Controlled by AppNav based on (scan mode == RFID_AND_BARCODE) AND (current tab in {scan, match}).
    private val gated = AtomicBoolean(false)

    // Single fixed reader config (range tiers removed). Power in dBm, RSSI floor in
    // dBm (0 == NO filter — read every tag, like the working demo). This matches the
    // counting-day "STRONG" config (max power, no floor). A non-zero floor here was
    // silently filtering out tags so nothing read.
    private const val FIXED_POWER_DBM = 33
    private const val FIXED_RSSI_FLOOR = 0

    // Current RSSI floor (dBm). 0 == no filter.
    @Volatile private var rssiFloorDbm: Int = FIXED_RSSI_FLOOR

    private val _tagFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 128
    )
    val tagFlow: SharedFlow<String> = _tagFlow.asSharedFlow()

    /**
     * Power up and open serial. Returns true if the reader handshook OK.
     * Safe to call more than once — subsequent calls no-op.
     */
    @Synchronized
    fun init(): Boolean {
        if (opened.get()) return true
        PowerUtil.power("1")

        val baudRates = intArrayOf(460800, 115200)
        for (baud in baudRates) {
            val path = "$SERIAL_DEV:$baud"
            val ok = runCatching { client.openAndroidSerial(path, 0) }
                .onFailure { Log.w(TAG, "openAndroidSerial($path) threw: ${it.message}") }
                .getOrDefault(false)
            if (!ok) {
                Log.w(TAG, "openAndroidSerial($path) returned false")
                continue
            }
            val stop = MsgBaseStop()
            client.sendSynMsg(stop)
            if (stop.rtCode.toInt() == 0) {
                Log.i(TAG, "reader opened at $baud")
                wireCallbacks()
                opened.set(true)
                // Single fixed config — no runtime range tuning.
                applyPower(FIXED_POWER_DBM)
                applyTagLog(rssiTv = FIXED_RSSI_FLOOR, repeatedMs = 0)
                return true
            } else {
                Log.w(TAG, "handshake failed at $baud rtCode=${stop.rtCode} msg=${stop.rtMsg}")
                runCatching { client.close() }
            }
        }
        Log.e(TAG, "failed to open reader at any baud rate")
        PowerUtil.power("0")
        return false
    }

    @Synchronized
    fun release() {
        if (!opened.get()) return
        stopInventory()
        runCatching { client.close() }
        PowerUtil.power("0")
        opened.set(false)
    }

    /**
     * Enable or disable the trigger. When disabled, triggerPress() is ignored and
     * any inventory in progress is stopped. Called by AppNav.
     */
    fun setGate(enabled: Boolean) {
        gated.set(enabled)
        if (!enabled) stopInventory()
    }

    /**
     * True when RFID is active (gate open). MainActivity uses this to decide
     * whether to consume the hardware trigger key. When false (barcode-only mode
     * or a non-RFID screen), the trigger must pass through to the system barcode
     * scanner service untouched.
     */
    fun isGateOpen(): Boolean = gated.get()

    /** Called on hardware trigger key DOWN. Starts inventory only if gate is open. */
    fun triggerPress() {
        if (!gated.get()) return
        startInventory()
    }

    /** Called on hardware trigger key UP. Always stops inventory. */
    fun triggerRelease() {
        stopInventory()
    }

    @Synchronized
    fun startInventory() {
        if (!opened.get()) {
            Log.w(TAG, "startInventory() skipped — reader not open")
            return
        }
        if (running.get()) return

        // Re-assert the tag callback right before each inventory, like the demo does
        // — cheap insurance against it ever being cleared.
        wireCallbacks()

        // Flush anything already in flight.
        client.sendUnsynMsg(MsgBaseStop())

        val msg = MsgBaseInventoryEpc().apply {
            antennaEnable = EnumG.AntennaNo_1
            inventoryMode = EnumG.InventoryMode_Inventory
        }
        client.sendSynMsg(msg)
        if (msg.rtCode.toInt() == 0) {
            running.set(true)
            Log.i(TAG, "inventory started")
        } else {
            Log.w(TAG, "inventory start failed rtCode=${msg.rtCode} msg=${msg.rtMsg}")
        }
    }

    @Synchronized
    fun stopInventory() {
        if (!opened.get() || !running.get()) return
        val stop = MsgBaseStop()
        client.sendSynMsg(stop)
        running.set(false)
        Log.i(TAG, "inventory stopped rtCode=${stop.rtCode}")
    }

    // --- internals ---

    private fun wireCallbacks() {
        client.onTagEpcLog = HandlerTagEpcLog { _: String?, info: LogBaseEpcInfo? ->
            info ?: return@HandlerTagEpcLog
            if (info.result != 0) return@HandlerTagEpcLog
            // getRssi() is a raw value; getRssidBm() is the signed dBm. Prefer dBm when nonzero.
            val dbm = info.rssidBm.takeIf { it != 0 } ?: info.rssi
            val floor = rssiFloorDbm
            if (floor != 0 && dbm < floor) return@HandlerTagEpcLog
            val epc = info.epc?.trim()?.uppercase() ?: return@HandlerTagEpcLog
            if (epc.isEmpty()) return@HandlerTagEpcLog

            _tagFlow.tryEmit(epc)
        }
        client.onTagEpcOver = HandlerTagEpcOver { _: String?, _: LogBaseEpcOver? -> /* no-op */ }
    }

    private fun applyPower(dbm: Int) {
        val clamped = dbm.coerceIn(0, 33)
        val msg = MsgBaseSetPower()
        val dic = Hashtable<Int, Int>()
        dic[1] = clamped
        msg.dicPower = dic
        client.sendSynMsg(msg)
        Log.i(TAG, "power=${clamped}dBm rtCode=${msg.rtCode}")
    }

    private fun applyTagLog(rssiTv: Int, repeatedMs: Int) {
        val msg = MsgBaseSetTagLog()
        // rssiTV: tags below this dBm are dropped by firmware (0 disables).
        msg.rssiTV = rssiTv
        msg.repeatedTime = repeatedMs
        client.sendSynMsg(msg)
        Log.i(TAG, "tagLog rssiTV=$rssiTv repeatedMs=$repeatedMs rtCode=${msg.rtCode}")
    }
}
