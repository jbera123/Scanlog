# scanlog — Android barcode + UHF RFID counting app

Internal tool for counting/verifying furniture-module inventory on a handheld
PDA. Jetpack Compose + Navigation, single-Activity, AndroidViewModel + StateFlow,
DataStore for persistence.

## ⚠️ Hardware-in-the-loop: you CANNOT fully test from a cloud/phone session

This app drives **physical hardware** that does not exist in any cloud sandbox:

- **UHF RFID module** (RF-M6001) over serial `/dev/ttyS3`, powered via GPIO
  sysfs. Driven by the GClient SDK (`app/libs/reader.jar`).
- **Barcode scanner** — two different PDA vendors (Xcheng broadcast + 条捷印 T02
  `android.bld.ScanManager` via reflection).
- **Hardware trigger key** — intercepted in `MainActivity.dispatchKeyEvent`.

A cloud/phone session can **edit code, build with Gradle, and push commits**, but
it can **NOT**:
- install the APK on the PDA,
- read RFID tags or fire the barcode laser,
- verify anything touching `RfidController`, `BldScanner`, trigger keycodes, or
  serial/GPIO behaviour.

**Workflow when working from phone/cloud:**
1. Make the code change, run `./gradlew assembleDebug` to confirm it COMPILES.
2. Push the commit. **Do not claim the change is verified — it only compiles.**
3. The user pulls on their PC, builds in Android Studio, installs on the PDA,
   and tests the hardware there.

Always run `./gradlew assembleDebug` before pushing. A green build is the bar for
a phone session; hardware behaviour is confirmed later on the user's PC.

## Key files

- `rfid/RfidController.kt` — GClient SDK wrapper (singleton). Power/serial
  lifecycle, inventory, range tiers, per-EPC throttle, health-check reconnect,
  `tagFlow` / `holdCount` / `connState` StateFlows.
- `rfid/PowerUtil.kt` — UHF power rail via GPIO sysfs.
- `util/BldScanner.kt` — reflection wrapper for the 条捷印 T02 laser
  (`setOPMode`, `openScanner`, `setContinuous`). Silent no-op on other PDAs.
- `util/ScannerConstants.kt` — both vendors' broadcast actions + extras.
- `util/CountExporter.kt` — daily CSV auto-export to Downloads.
- `data/ScanStore.kt` — DataStore-backed per-day counts, seenEpcs dedup,
  settings, exported-days tracking.
- `data/RfidBarcodeCatalog.kt` — EPC prefix → SKU lookup (longest-prefix match).
- `data/Models.kt` — `RfidRange` (WEAK/MEDIUM/STRONG), `ScanMode`.
- `ScanlogApp.kt` — Application: inits reader, laser mode, auto-export on launch.
- `MainActivity.kt` — trigger key interception (only consumes when RFID gated).
- `ui/screens/ScanScreen.kt` — counting screen (RFID rollup + barcode).
- `ui/screens/MatchScreen.kt` — RFID/barcode compare (RFID+Barcode mode only).

## Domain notes

- **EPC scheme:** 8 hex chars = 2-char category prefix (`AA`,`AB`,…`BC`) +
  6-digit serial. Catalog maps prefix → SKU. Rolled up by category in Scan tab.
- **Scan modes:** `BARCODE_ONLY` (barcode receiver active, RFID off) vs
  `RFID_AND_BARCODE` (Scan tab ignores barcode, pure RFID; Match tab compares).
- **Dedup:** each unique EPC counts once per day (per-day `seenEpcs` set, plus an
  in-memory fast-path cache in `ScanViewModel`).

## ⚠️ RFID reader — known-good config (DO NOT regress)

These were each hard-won by debugging on hardware. Re-introducing any of them
silently breaks RFID or crashes the app (verified, then fixed — see git log).

- **NO RSSI floor.** `RfidController.FIXED_RSSI_FLOOR = 0` (no filter), power
  `FIXED_POWER_DBM = 33`. A non-zero floor (e.g. -70) filters out tags at both
  the firmware (`MsgBaseSetTagLog`) and callback level → **reads nothing**. The
  working demo (`UHFReaderDemo` `ReadFragment6c`) sets no RSSI filter at all.
- **NO background thread may touch the serial.** A periodic health-check ping
  from an IO thread raced the main-thread inventory commands on `/dev/ttyS3` and
  corrupted the reader (~30s "works then dies"). There is no health check now;
  the reader is commanded only from the main thread (trigger) + startup.
- **Reader is opened lazily, only in `RFID_AND_BARCODE` mode** (AppNav's scanMode
  effect: `init()` on entering RFID mode, `release()` on leaving). Barcode-only
  mode never powers the reader — keeps the default app crash-proof.
- **Barcode receivers MUST be `RECEIVER_EXPORTED`** on Android 13+ (API 33). The
  scan broadcast comes from the PDA scanner service (a separate app);
  `RECEIVER_NOT_EXPORTED` silently drops it. See `ScanScreen`/`MatchScreen`.
- The reader module has **no physical buzzer** — audio is `SimpleBeep` only.
- en/zh SKU labels: `app/src/main/res/raw/barcode_map.csv` (synced from the
  user's `sorted_order_2.xlsx`).

## Build

`./gradlew assembleDebug` — output: `app/build/outputs/apk/debug/app-debug.apk`.
minSdk 26, targetSdk 34, compileSdk 34.
