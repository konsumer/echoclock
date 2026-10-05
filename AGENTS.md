# AGENTS.md — guide for AI coding agents

You are working in **EchoClock**: an Android app + shell tooling that turns an
**Amazon Echo Show 5 (2nd gen, codename `cronos`)** into a private, offline-first desk clock
launcher with scriptable HTML/JS faces, an app drawer, calendar events, alarms and weather.
It is private by design: no account, no cloud, nothing sent anywhere.

`docs/DESIGN.md` is the **binding contract**. When code and this file disagree with it, follow
`DESIGN.md` (and update docs). Read `docs/DESIGN.md` §2 (app), §7 (faces/settings/external
trigger) and §8 (calendar/alarms/weather) before changing behaviour.

## Device constraints (do not fight them)

- **`cronos`**, **LineageOS 18.1 / Android 11 / API 30**.
- **32-bit userspace only: `armeabi-v7a`.** The SoC is 64-bit-capable but the ROM has no 64-bit
  userspace (`ro.product.cpu.abilist64` is empty). Any native code must ship `armeabi-v7a`;
  an arm64 build fails with `INSTALL_FAILED_NO_MATCHING_ABIS`. The app itself has no native code.
- **1 GB RAM** — keep dependencies and allocations small. No Compose, no AndroidX unless needed.
- **960×480** landscape screen.
- **Offline-first.** The clock works with no network. Weather is the one optional online
  feature (open-meteo.com) and must degrade silently when offline.
- **No root** used by the app.

## Repo map

```
app/                 the Android app — Kotlin (Gradle, wrapper-pinned) + bundled faces
  build.sh           build entry point -> out/EchoClock.apk
  src/org/echoclock/ HomeActivity, EcBridge, Actions, FaceStore, Config, SettingsSheet,
                     AppDrawer, AlarmData, CalendarData, Weather, Util
  assets/faces/<id>/ bundled clock faces (index.html + face.json)
config/              default config.json pushed to the device
faces/               optional staging dir for custom faces (provision.sh pushes it when present)
install/             config.env (pinned versions) + download/flash/provision/setup scripts
docs/DESIGN.md       THE contract (read this)
docs/DEVICE.md       hardware/firmware facts     docs/GOTCHAS.md  traps
docs/SETUP.md docs/USAGE.md docs/CUSTOMIZE.md + faces/README.md  user-facing docs
```

On-device data lives in **`/sdcard/EchoClock/`** (`config.json`, `faces/`). **User files
override bundled ones** with the same id.

## Build & test loop

```sh
bash app/build.sh                     # -> app/out/EchoClock.apk (signed release, no native libs)
adb install -r app/out/EchoClock.apk  # or: ./install/provision.sh -s <serial>
./install/provision.sh -n -s <serial> # dry-run preview
adb logcat -s EchoClock               # the app's log tag
```

Don't run the device as part of a code change unless asked; the owner tests on hardware.

**Never run `dumpsys media.audio_flinger` on the device** — the ported vendor audio HAL
null-derefs in `Device::debug()` and the speaker goes silent until reboot (see
`docs/GOTCHAS.md` → *Audio*). `dumpsys audio` / `dumpsys media.audio_policy` are fine.

**Trigger an action without touching the UI** (external action trigger, `DESIGN §7.3`). Prefix
`adb` with `-s <serial>` (or `export ANDROID_SERIAL=<serial>`) when more than one device is
attached — otherwise adb errors with `more than one device/emulator`; `adb devices -l` shows
which line is `device:cronos`:

```sh
adb shell am start -n org.echoclock/.HomeActivity --es action "face:pomodoro"
adb shell am start -n org.echoclock/.HomeActivity --es action "app:com.android.settings"
adb shell am start -n org.echoclock/.HomeActivity --es action "weather:refresh"
```

Action strings: `face:<id>`, `app:<package>`, `nextFace`, `prevFace`, `openDrawer`,
`weather:refresh`, `calendar:refresh`. That is the complete set.

## Interaction contract (DESIGN §8.1)

The ⚙/▤ overlay buttons are **gone**. On the clock:

- **short tap** → open the **app drawer**, *unless* the tap hit an interactive element inside
  the face (native calls `EC._hitInteractive(x,y)`; matcher covers `button`, `a`, `input`,
  `select`, `textarea`, `[data-ec]`);
- **long press** → open the **face settings sheet** (`EC.openSettings()`);
- **Home/Back** → return to the clock.

Faces with their own buttons keep working — add `data-ec` to any custom interactive element.

## Faces (the scripting surface)

A face is `faces/<id>/` with `index.html` + `face.json` (manifest: `id`, `name`, `author`,
`description`, optional `settings[]`). Rendered fullscreen in a WebView at 960×480 CSS
px. Faces **must run offline** and tolerate missing/extra config keys.

```js
window.ec = {
  config(cfg) {},   // this face's settings from config.json "faces.<id>"
  tick(state) {}    // once per second — state JSON, see DESIGN §2.4 + §8.2
};
```

Native side: `window.EC` — string-returning methods hand back JSON to parse, the boolean ones
return `true`/`false`.
Current set: `state()`, `faces()`, `settings()`, `setFace(id)`, `setSetting(k,v)`,
`nextFace()`, `prevFace()`, `openDrawer()`, `openSettings()`, `calendar()`, `nextAlarm()`,
`weather()`, `refresh()`.
See `docs/DESIGN.md` §2.4, §7.1, §8.4.

State includes `epoch, battery, charging, face, locale, brightness` and (all optional)
`nextAlarm, calendar, weather`.
**Guard for missing state** — no alarm set, no calendar account, offline weather.

To add a face: create `app/assets/faces/<id>/` (bundled) or push to
`/sdcard/EchoClock/faces/<id>/`, then swipe to it or trigger `face:<id>` externally.
Start from the worked example in [docs/CUSTOMIZE.md](docs/CUSTOMIZE.md) §1.

## Per-face settings

`face.json` may declare a `settings` array; the app builds the native sheet from it
(long-press). Types: `bool` (**checkbox**), `int`, `enum`, `color`, `text`, `list`. Values
persist to `config.json` `faces.<id>` and are pushed to the face via `window.ec.config(cfg)`.
Full contract: `DESIGN §7.1`.

## Conventions

- **Guard optional state.** Faces and native code must not assume an alarm/event/weather exists.
- **No network in faces**, ever — weather arrives via `state.weather` from native.
- **Keep it 32-bit** (`armeabi-v7a`) and memory-light; don't add native dependencies.
- **Never document the camera as non-functional.** The camera works once GApps is installed
  (`com.android.camera2`); it's reachable via the app drawer (see `docs/DEVICE.md`).
- Prefer editing existing files; keep the `EchoClock` logcat tag for new logs.
- Don't commit binary blobs; `install/config.env` is the single source of versions/URLs/sha256.

## Where to read more

- `docs/DESIGN.md` — §2 (app), §7 (faces/settings/external trigger), **§8 (calendar/alarms/weather, clean face, camera)**.
- [docs/CUSTOMIZE.md](docs/CUSTOMIZE.md) — faces, `window.ec`/`window.EC`, settings, weather config, external triggers.
- [docs/USAGE.md](docs/USAGE.md) — what the end user sees/does.
- `docs/GOTCHAS.md` — traps (ABI, storage, build).
