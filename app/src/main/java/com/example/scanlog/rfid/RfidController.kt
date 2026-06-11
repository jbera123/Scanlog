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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    // Phase 3 — range tiers tune POWER only; the RSSI floor stays 0 for EVERY tier
    // (a non-zero floor silently filtered tags out — see ROADMAP guardrail #2).
    // Lower power = shorter read range (close-only) without dropping any tag that
    // does respond. Default STRONG (33 dBm) = the known-good counting config.
    private const val RSSI_FLOOR = 0
    @Volatile private var powerDbm: Int = 33

    private fun powerFor(range: RfidRange): Int = when (range) {
        RfidRange.WEAK   -> 18   // ~close-only verify
        RfidRange.MEDIUM -> 26   // mid range
        RfidRange.STRONG -> 33   // max range, counting
    }

    // Current RSSI floor (dBm). 0 == no filter (always 0 — see above).
    @Volatile private var rssiFloorDbm: Int = RSSI_FLOOR

    // Per-EPC throttle. The reader reports a tag held in the field many times/sec;
    // this drops repeats of the SAME epc within the window so a hold doesn't flood
    // the recording path. Purely in-memory (never touches the serial). Cleared on
    // triggerRelease.
    //
    // Kept SHORT (250ms) on purpose: a long window blocked INTENTIONAL re-scans of
    // the same tag — which both repeated-scans test mode and the compare workflow
    // rely on (rescanning one tag made it look "stuck for ~1s"). 250ms is below
    // human re-scan cadence yet still caps the in-field flood. Normal counting is
    // unaffected (the ScanViewModel seenEpcCache already drops dup reads).
    private const val THROTTLE_MS = 250L
    private val lastEmitMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val _tagFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 128
    )
    val tagFlow: SharedFlow<String> = _tagFlow.asSharedFlow()

    // Phase 4 — count of distinct EPCs emitted during the current trigger-hold.
    // Reset on press/release. UI shows it as a "+N" badge so the operator sees the
    // reader is alive during a long sweep. In-memory only.
    private val _holdCount = MutableStateFlow(0)
    val holdCount: StateFlow<Int> = _holdCount.asStateFlow()

    // --- Lifecycle (Phase 1): the reader should be OPEN iff RFID mode is active
    // AND the app is in the foreground. AppNav sets rfidActive; MainActivity sets
    // appForeground via onResume/onStop. After a device sleep the OS can kill the
    // serial/power while `opened` stays stale — backgrounding releases the reader
    // and foregrounding re-opens a FRESH one, recovering it (the demo's pattern).
    // reconcile runs one-shot on a coroutine (NEVER a polling loop — that was the
    // health-check race that corrupted the serial).
    @Volatile private var rfidActive = false
    @Volatile private var appForeground = true
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** AppNav: true while scan mode == RFID_AND_BARCODE. */
    fun setRfidActive(active: Boolean) {
        rfidActive = active
        lifecycleScope.launch { reconcile() }
    }

    /** MainActivity.onResume — app came to foreground. */
    fun onAppForeground() {
        appForeground = true
        lifecycleScope.launch { reconcile() }
    }

    /** MainActivity.onStop — app went to background. */
    fun onAppBackground() {
        appForeground = false
        lifecycleScope.launch { reconcile() }
    }

    @Synchronized
    private fun reconcile() {
        val shouldOpen = rfidActive && appForeground
        if (shouldOpen && !opened.get()) {
            init()
        } else if (!shouldOpen && opened.get()) {
            release()
        }
    }

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
                // Apply current range (power) + no RSSI filter.
                applyPower(powerDbm)
                applyTagLog(rssiTv = RSSI_FLOOR, repeatedMs = 0)
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

    /**
     * Set the read-range tier. Tunes transmit POWER only — the RSSI floor stays 0
     * so no tag that responds is ever filtered out. Safe to call before the reader
     * is open (caches the power; applied on next init()).
     *
     * @Synchronized: applyPower() writes the serial; without the monitor a range
     * change from Settings (main thread) could write concurrently with a
     * reconcile()/init()/release() on the lifecycle IO thread — the two-threads-on-
     * one-serial hazard that corrupted the reader before.
     */
    @Synchronized
    fun setRange(range: RfidRange) {
        powerDbm = powerFor(range)
        if (opened.get()) applyPower(powerDbm)
    }

    /** Called on hardware trigger key DOWN. Starts inventory only if gate is open. */
    fun triggerPress() {
        if (!gated.get()) return
        _holdCount.value = 0
        startInventory()
    }

    /** Called on hardware trigger key UP. Always stops inventory. */
    fun triggerRelease() {
        stopInventory()
        lastEmitMs.clear()
        _holdCount.value = 0
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

            // Throttle: drop a repeat of the same EPC seen within the window.
            val now = System.currentTimeMillis()
            val prev = lastEmitMs[epc]
            if (prev != null && now - prev < THROTTLE_MS) return@HandlerTagEpcLog
            lastEmitMs[epc] = now

            _tagFlow.tryEmit(epc)
            _holdCount.value = _holdCount.value + 1
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
