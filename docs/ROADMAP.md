# scanlog — feature re-implementation roadmap

After a long hardware-debugging saga we stripped the app to a stable baseline
(barcode-first, RFID opt-in, no background serial threads, no RSSI filtering).
This roadmap brings features back **one phase at a time, each its own PR + a
version-labeled APK**, so any step can be tested in isolation and rolled back.

## Guiding rules (the lessons that cost us the most)

1. **No background thread may command the serial.** A periodic health-check ping
   from an IO thread raced main-thread inventory and corrupted the reader
   (~30s "works then dies"). Lifecycle work must be one-shot, never a loop.
2. **No tag-filtering RSSI floor.** `FIXED_RSSI_FLOOR` must stay 0. A non-zero
   floor filtered out every tag → "reads nothing." Tune range via **power only**.
3. **`RECEIVER_EXPORTED`** for barcode receivers on Android 13+.
4. **One change → build → install → hardware-test → next.**

## Part A — known bugs / gaps in the baseline

- **#1 No recovery after device sleep / background in RFID mode** (High). Reader
  is opened only via a scanMode effect; after the PDA sleeps the serial/power can
  die while `opened` stays true → trigger silently does nothing until cold start.
  → fixed in **Phase 1**.
- **#2 Reader stays powered when backgrounded in RFID mode** (Med). Battery +
  stale state. → fixed in **Phase 1**.
- **#3 Rapid mode-toggle does close+reopen** (Low-Med). Fragile on this serial HW;
  user-initiated/slow. Mitigated by reconcile-only-on-change in Phase 1.
- **#4 Dead code** (`RfidRange`, dormant `ScanStore` prefs) (Cosmetic). Re-used by
  Phase 3 / cleaned as features return.

## Phases

| Phase | Scope | Risk | APK label |
|---|---|---|---|
| 0 | Baseline soak — no code; full counting session on current build | – | (current) |
| 1 | Safe sleep/background recovery (demo's lifecycle pattern). Fixes #1, #2 | Low | `v1.1-sleep` |
| 2 | Re-add per-EPC throttle (in-memory flood control) | Low | `v1.2-throttle` |
| 3 | Re-add range tiers + Settings toggle — **power-only, floor stays 0** | Med | `v1.3-range` |
| 4 | UX: "+N" hold badge, repeated-scans testing toggle (not in this batch) | Low | – |
| 5 | Compare-mode single-fire laser (optional, not in this batch) | Low | – |

**Explicitly NOT returning:** the periodic health-check/auto-reconnect (Phase 1
covers recovery safely instead) and the hold keep-alive watchdog (press/release
works; revisit only if the trigger stops firing ACTION_UP).

## Rollback

Each phase is one squash-free PR merged to `main`. To roll back: `git revert`
the phase's merge commit, or re-install the previous phase's labeled APK from the
project root (`E:\App_dev\scanlog_2\scanlog-v<label>-debug-<ts>.apk`).
