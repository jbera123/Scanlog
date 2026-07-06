# scanlog — macOS setup (migration from Windows)

Everything needed to build is in the repo (SDK jars + native `.so` under
`app/libs/`). No Windows-specific paths are committed. Steps on the Mac:

## 1. Clone

```sh
git clone https://github.com/jbera123/Scanlog.git
cd Scanlog
```

## 2. Open in Android Studio

- **File → Open** the `Scanlog` folder.
- On first sync Android Studio generates `local.properties` with the Mac SDK
  path automatically. Do **not** copy the Windows `local.properties` — it's
  gitignored for this reason.
- Install the SDK it prompts for: compileSdk/targetSdk **34**, minSdk **26**,
  JDK **17**.

## 3. Build

```sh
./gradlew assembleDebug
```

`gradlew` is committed executable with LF line endings, so it runs as-is. Output:
`app/build/outputs/apk/debug/app-debug.apk`, and a timestamped copy at the repo
root (`scanlog-v<ver>-debug-<ts>.apk`).

## Notes

- **Hardware still can't be tested off the PDA** — see `CLAUDE.md`. Mac can edit,
  build, push; RFID/barcode/trigger verify on the device.
- **Known-good reader config** and the phase history live in `CLAUDE.md` and
  `docs/ROADMAP.md` — read before touching `RfidController`.
- Push/PR workflow unchanged (GitHub). `gh` CLI works the same on Mac.
