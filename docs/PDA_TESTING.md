# Testing on the PDA without a person on the trigger

Verified on the BLD T02 PDA over adb: production build, no root. Hardware behaviour
still has to be confirmed on the device, see `CLAUDE.md`.

## Side-by-side test packages

Give a throwaway build another package id and label so it installs next to the real
app, with its own data and no signature clash. Do not commit these two edits:

- `app/build.gradle.kts`: `applicationId = "com.example.scanlog.fix"`
- `app/src/main/AndroidManifest.xml`: `android:label="SL2 fix"`

The FileProvider authority uses `${applicationId}`, so two package ids coexist.
Only one app can own the UHF reader at a time. Press Home before opening another
one: leaving an app releases the reader, switching through Recents can power it off
under the new app.

## Pre-seed settings (skip the Settings screen)

The settings live in `files/datastore/scanlog_store.preferences_pb`. This writes
RFID + Barcode mode and the Strong range:

```python
def s(b): return bytes([len(b)]) + b
def entry(k, v):
    val = b'\x2a' + s(v.encode())
    return b'\x0a' + s(b'\x0a' + s(k.encode()) + b'\x12' + s(val))
open('seed.pb', 'wb').write(entry('rfid_range', 'STRONG') + entry('scan_mode', 'RFID_AND_BARCODE'))
```

```sh
adb shell am force-stop <pkg>
adb shell run-as <pkg> sh -c 'mkdir -p files/datastore && cat > files/datastore/scanlog_store.preferences_pb' < seed.pb
```

Writing the same file with only these two keys also resets the day's counts.

## Simulate the gun trigger

A virtual keyboard named `gpio_keys` with vendor 0x16 and product 0x1 gets the same
key layout as the real gun. Usage 0x36 arrives as keyCode 619, scanCode 51, with
key auto-repeat for the whole hold.

```python
import json
desc = [0x05,0x01,0x09,0x06,0xA1,0x01,0x05,0x07,0x19,0xE0,0x29,0xE7,0x15,0x00,0x25,0x01,
        0x75,0x01,0x95,0x08,0x81,0x02,0x95,0x01,0x75,0x08,0x81,0x03,0x95,0x05,0x75,0x01,
        0x05,0x08,0x19,0x01,0x29,0x05,0x91,0x02,0x95,0x01,0x75,0x03,0x91,0x03,0x95,0x06,
        0x75,0x08,0x15,0x00,0x25,0x65,0x05,0x07,0x19,0x00,0x29,0x65,0x81,0x00,0xC0]
hold_ms = 3000
ev = [{"id":1,"command":"register","name":"gpio_keys","vid":22,"pid":1,"bus":"usb","descriptor":desc},
      {"id":1,"command":"delay","duration":800},
      {"id":1,"command":"report","report":[0,0,0x36,0,0,0,0,0]},
      {"id":1,"command":"delay","duration":hold_ms},
      {"id":1,"command":"report","report":[0]*8},
      {"id":1,"command":"delay","duration":800}]
open('gun.json', 'w').write("\n".join(json.dumps(e) for e in ev) + "\n")
```

```sh
adb push gun.json /data/local/tmp/gun.json
adb shell input keyevent KEYCODE_WAKEUP; adb shell input keyevent 82   # the screen relocks within a minute
adb shell hid /data/local/tmp/gun.json
```

- The file must be a sequence of JSON objects, not an array.
- `adb shell input keycombination -t 3000 59 619` is a cruder hold (deviceId -1).
- `sendevent` is blocked by SELinux, and `adb root` is refused.

## Other facts

- `Log.d` is hidden on this PDA. Use `Log.i` for diagnostics.
- `/proc/interrupts`, line "handle key": counts real trigger edges, two per press.
- To inspect the module, use a throwaway activity that mirrors `RfidController.init()`
  and sends the GClient `Get` messages (`MsgBaseGetPower`, `MsgBaseGetBaseband`,
  `MsgBaseGetFreqRange`, `MsgBaseGetTagLog`, `MsgBaseGetAutoDormancy`), then runs a
  timed `MsgBaseInventoryEpc` and counts reads per EPC.
