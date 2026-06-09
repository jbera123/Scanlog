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
- **Range tiers:** WEAK 20dBm/-75, MEDIUM 27dBm/-78, STRONG 33dBm/no-floor.
- **Dedup:** each unique EPC counts once per day (per-day `seenEpcs` set, plus an
  in-memory fast-path cache in `ScanViewModel`).
- en/zh SKU labels: `app/src/main/res/raw/barcode_map.csv` (synced from the
  user's `sorted_order_2.xlsx`).

## Decisions & rationale (why things are the way they are)

These are choices made in working sessions that aren't obvious from the code:

- **Range tiers were retuned after a real counting day.** A tight RSSI floor
  missed ~10% of tags even on the strongest setting. Final values:
  - WEAK 20 dBm / RSSI −75 — close-only verify, clean reads.
  - MEDIUM 27 dBm / RSSI −78 — verify day, reaches blocked / farther tags.
  - STRONG 33 dBm / no floor (0) — counting day, max range.
  An old SHORT tier (5–12 dBm) was removed as unusable. If lag returns on
  STRONG, raise the per-EPC throttle window rather than re-adding an RSSI floor.
- **Per-EPC throttle = 1000 ms** (`RfidController.THROTTLE_MS_DEFAULT`). It only
  suppresses *re-emits of the same EPC* within the window; new tags are never
  delayed. Daily dedup already guarantees one count per tag, so the throttle is
  purely a performance guard — a higher value is safe and only reduces lag.
- **The reader module has NO physical buzzer.** `MsgAppSetBeepOnOff` returns
  rtCode 0 but is silent on this unit — the OEM didn't wire a buzzer. All audio
  feedback goes through `SimpleBeep` (`res/raw/beep.mp3`) on the Android side.
  Don't re-add SDK-beep code expecting sound.
- **Barcode handling, option B:** in `RFID_AND_BARCODE` mode the Scan tab does
  not register the barcode receiver at all, so a stray trigger can't inflate
  counts. Barcodes only matter on the Match tab in that mode.
- **Two barcode PDAs, one APK.** Xcheng (`com.xcheng.scanner...` broadcast,
  String extra) and 条捷印 T02 (`scan.rcv.message`, `byte[] "barocode"` —
  vendor's misspelling, keep it — + `int "length"`). Receivers register both
  actions; `BldScanner` reflection no-ops on non-T02 hardware.
- **Trigger key is only consumed when RFID is active** (`isGateOpen()` /
  `triggerOwned`). Otherwise the keypress must fall through to the system
  barcode scanner service or the laser never fires.

## TODO / open items

- [ ] **PR #3 (`claude/seat-scan-misidentification-mt5gqb`) awaits PDA testing**
  before merge. Builds clean on PC. Test: sleep-recovery, mode-switch trigger,
  hold-to-scan, Counts-tab silence, compare single-fire. See PR body checklist.
- [ ] **Hold-to-scan 700 ms keep-alive window** (`HOLD_KEEPALIVE_MS`) is
  hardware-dependent — relies on the trigger key auto-repeating ACTION_DOWN.
  May need tuning if hold only reads a brief burst (check Logcat for repeated
  `trigger DOWN` lines).

## Print-batch deliverables

`docs/rfid_print_batch/` holds the EPC encoding files sent to the tag printer:
9 per-SKU CSVs (`epc_hex,label`) + a Chinese `README.txt` with the full EPC
spec (96-bit, Bank 01, start word 02, PC 0x3000, right-pad 8-hex to 24).
Regenerate from those quantities if a reprint is needed. The printed `label`
column (ZB, KB, L-FS, …) differs from the internal SKU — see the README.

## Build

`./gradlew assembleDebug` — output: `app/build/outputs/apk/debug/app-debug.apk`.
minSdk 26, targetSdk 34, compileSdk 34.
