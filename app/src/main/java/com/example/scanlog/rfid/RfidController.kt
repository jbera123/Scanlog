package com.example.scanlog.rfid

import android.util.Log
import com.example.scanlog.data.RfidRange
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
 * - init() powers up the UHF rail and opens /dev/ttyS3 (tries 460800 then 115200).
 * - startInventory()/stopInventory() drive continuous EPC reads.
 * - setRange() controls both hardware power (dBm via MsgBaseSetPower) and an
 *   in-app RSSI floor applied in the tag callback.
 * - tagFlow emits each accepted EPC string (uppercase, trimmed).
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

    // Current RSSI floor (dBm). 0 == no filter.
    @Volatile private var rssiFloorDbm: Int = -60

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
            val ok = runCatching { client.openAndroidSerial(path, 10) }
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
                // Apply saved range defaults (MEDIUM by default — VM will call setRange later).
                applyPower(15)
                applyTagLog(rssiTv = -60, repeatedMs = 0)
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

    /** Map RfidRange -> (power dBm, RSSI floor dBm). Tune on bench. */
    fun setRange(range: RfidRange) {
        val (dbm, rssiFloor) = when (range) {
            RfidRange.WEAK   -> 20 to -75   // tag close, clean reads only
            RfidRange.MEDIUM -> 27 to -78   // verify mid — reach blocked / farther tags
            RfidRange.STRONG -> 33 to 0     // counting day — max power, no floor
        }
        rssiFloorDbm = rssiFloor
        if (!opened.get()) return

        val wasRunning = running.get()
        if (wasRunning) stopInventory()
        applyPower(dbm)
        // Hardware-level RSSI filter (0 = disabled).
        applyTagLog(rssiTv = rssiFloor, repeatedMs = 0)
        if (wasRunning) startInventory()
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
