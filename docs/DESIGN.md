# EchoClock — design & interface contract

Shared contract for the parts of this repo. Every component MUST conform to the names,
paths and formats in this document so the pieces compose.

Target device: **Amazon Echo Show 5 (2nd gen, 2021), codename `cronos`**, running
**LineageOS 18.1 (Android 11, API 30)**, **32-bit userspace (`armeabi-v7a`)** even though
the MT8163 is 64-bit-capable. Screen is **960x480 landscape**, ~1 GB RAM.

---

## 1. Repository layout

```
echoclock/
  README.md                 landing page (what it is, the four parts)
  AGENTS.md                 guide for AI coding agents
  docs/
    SETUP.md                blank device -> LineageOS + GApps + EchoClock
    USAGE.md                living with a ready device
    CUSTOMIZE.md            faces, settings, external triggers (how-to)
    DESIGN.md               this file
    DEVICE.md               hardware/firmware facts + sources
    GOTCHAS.md              traps learned (from prior art + our testing)
  install/
    config.env              versions, URLs, sha256 (single source of truth)
    lib.sh                  shared bash helpers (colors, die, need, adb wrappers)
    download.sh             fetch ROM + GApps (+ build the APK when a JDK/SDK is present)
    setup.sh                one shot: download -> flash -> provision per device
    flash-lineageos.sh      unlock (amonet) + TWRP + ROM + GApps, guided
    provision.sh            post-first-boot: skip wizard, kiosk settings, install apps
  app/
    build.sh                wrapper -> ./gradlew assembleRelease -> out/EchoClock.apk
    settings.gradle.kts     Gradle + Kotlin build (wrapper pinned)
    build.gradle.kts
    gradle/libs.versions.toml  AGP/Kotlin/SDK pins
    gradle/wrapper/…  gradlew
    AndroidManifest.xml
    res/                    minimal resources (icon, app name, strings)
    src/org/echoclock/*.kt  Kotlin sources
    assets/faces/<id>/      bundled clock faces (HTML/CSS/JS)
  faces/                    optional staging dir for custom faces (faces/README.md; provision pushes it)
  config/                   default config.json pushed to the device
  apps/                     optional third-party APKs for BUNDLED_APKS (must be armeabi-v7a)
```

---

## 2. Android app — `org.echoclock`

Language: **Kotlin**. Build: **Gradle (Kotlin DSL)** with the AGP + Kotlin plugins, using the
Gradle **wrapper** (pinned version, no reliance on the host Gradle). `minSdk=30`, `targetSdk=30`,
`compileSdk=34`, `versionCode=1`, `versionName="0.1"`.
Use framework Android Views (no Jetpack Compose, no AndroidX
unless strictly needed) to keep the dependency graph small.

The app is **just a clock launcher**: it hosts the HTML/JS clock faces (swipeable, with a
per-face settings sheet), an app drawer, calendar events, alarms and weather. It is
**offline-first**: no account, no cloud, nothing sent anywhere.

### 2.1 Components

| Component | Type | Role |
|---|---|---|
| `org.echoclock.HomeActivity` | Activity | **HOME/launcher**. Hosts clock WebView + app drawer, gestures. |
| `org.echoclock.EcBridge` | JS interface | `window.EC` methods; pushes `state`/`config` to the face. |
| `org.echoclock.Actions` | static util | Parses/runs the external `action` intent extra (§7.3). |
| `org.echoclock.FaceStore` | static util | Enumerates/loads clock faces + per-face config. |
| `org.echoclock.SettingsSheet` | View | Native per-face settings sheet built from `face.json` schema. |
| `org.echoclock.AppDrawer` | View | Launchable-app grid overlay. |
| `org.echoclock.AlarmData` | static util | Next alarm via `AlarmManager.getNextAlarmClock()`. |
| `org.echoclock.CalendarData` | static util | Upcoming events from `CalendarContract.Instances`. |
| `org.echoclock.Weather` | static util | open-meteo fetch + on-disk cache. |
| `org.echoclock.Config` | static util | Paths, prefs, defaults. |
| `org.echoclock.Util` | static util | Logging and small helpers. |

`HomeActivity` intent filters (order matters for home button):

```xml
<activity android:name=".HomeActivity" android:exported="true"
          android:launchMode="singleTask"
          android:stateNotNeeded="true"
          android:excludeFromRecents="true"
          android:screenOrientation="landscape"
          android:configChanges="orientation|screenSize|keyboardHidden|uiMode|density|smallestScreenSize|screenLayout">
  <intent-filter>
    <action android:name="android.intent.action.MAIN"/>
    <category android:name="android.intent.category.HOME"/>
    <category android:name="android.intent.category.DEFAULT"/>
  </intent-filter>
  <intent-filter>
    <action android:name="android.intent.action.MAIN"/>
    <category android:name="android.intent.category.LAUNCHER"/>
  </intent-filter>
</activity>
```

### 2.2 Permissions

Declared in `app/AndroidManifest.xml` (and nothing else):

```
INTERNET, READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE, MANAGE_EXTERNAL_STORAGE,
QUERY_ALL_PACKAGES, READ_CALENDAR, SET_ALARM (com.android.alarm.permission.SET_ALARM)
```

`provision.sh` grants the `MANAGE_EXTERNAL_STORAGE` appop and `READ_CALENDAR` via adb, and adds
the app to the deviceidle whitelist.

### 2.3 On-device data layout (`/sdcard/EchoClock`)

```
/sdcard/EchoClock/
  config.json                 app settings (see §4)
  faces/<id>/index.html ...   user-installed faces (override bundled by same id)
```

Resolution order: **user dir first, then bundled `assets`.** If `/sdcard/EchoClock` is
missing, the app creates it and writes a default `config.json`; when it cannot write there
(`MANAGE_EXTERNAL_STORAGE` not granted yet) it falls back to the app-specific external dir.

### 2.4 Clock face interface (the "scriptable clocks")

A face is a directory with `index.html` (entry) + a `face.json` manifest. Faces are
rendered in a fullscreen `WebView` (960x480 CSS px, no zoom, dark background).

`face.json`:
```json
{ "id": "digital", "name": "Digital", "author": "echoclock",
  "description": "Big digital clock" }
```

Native injects a JS object `window.EC` (via `addJavascriptInterface`) and, **once per
second**, calls `window.ec.tick(stateJson)`; on page load, and again after a settings change,
it calls `window.ec.config(cfgJson)`.
The face MUST define `window.ec = { tick(state){}, config(cfg){} }` (both optional).

`state` JSON:
```json
{ "epoch": 1728100000123, "battery": 100, "charging": true,
  "face": "digital", "locale": "en-US", "brightness": 0.6 }
```
- `epoch` milliseconds since epoch.
- Additional optional fields (alarm, calendar, weather) are defined in §8.2.

`cfg` JSON: arbitrary per-face config from `config.json` `"faces": { "<id>": {...} }`.

Native JS methods (`window.EC`; JSON payloads come back as strings, boolean methods as
`true`/`false`):
| method | returns |
|---|---|
| `state()` | current state JSON (§2.4) |
| `faces()` | `[{"id","name","bundled":bool}]` |
| `settings()` | this face's settings schema + current values (JSON array) |
| `setFace(id)` | `true`/`false` (unknown id → `false`) |
| `setSetting(key,value)` | `true`/`false` (coerced to the schema type, persists to `config.json`) |
| `nextFace()` / `prevFace()` | `true` (`false` only when there are no faces) |
| `openDrawer()` | `true` (shows app drawer) |
| `openSettings()` | `true` (opens this face's settings sheet) |
| `calendar()` / `nextAlarm()` / `weather()` | the §8.2 JSON for each (`nextAlarm`/`weather` are the string `null` when unknown) |
| `refresh()` | `true` (re-request calendar + weather now, never blocks) |

Bundled faces (assets/faces/): `digital`, `flip`, `analog`, `lava`, `pomodoro`, `gallop`,
`world`, `binary`, `cute`, `agenda`. They must run with **no network** and degrade gracefully.

### 2.5 App drawer

Triggered by: `window.EC.openDrawer()`, or a short tap on the clock (see §8.1). Shows all
launchable apps (icons + labels, from `PackageManager`, `ACTION_MAIN`+`CATEGORY_LAUNCHER`),
scrollable grid. Tapping launches. It has a "Clock" / close affordance.
**`HomeActivity.onKeyDown(KEYCODE_HOME|KEYCODE_BACK)` returns to the clock.** The drawer is an
in-activity overlay, not a separate activity.

---

## 3. Build (`app/build.sh` → Gradle)

Gradle (Kotlin DSL), wrapper-pinned. Versions pinned in `app/gradle/libs.versions.toml`:

- Gradle **8.11.1** (wrapper), AGP **8.7.3**, Kotlin **2.0.21**, JDK **17**.
- `compileSdk=34`, `minSdk=30`, `targetSdk=30`, `namespace`/`applicationId` = `org.echoclock`.
- No NDK, no `abiFilters`, no `jniLibs`: the app is pure framework + Kotlin stdlib, so it
  carries no native code (any native code added later must be `armeabi-v7a`, §6).
- Flat source layout (unchanged paths), wired in `build.gradle.kts`:
  ```kotlin
  sourceSets["main"].apply {
      manifest.srcFile("AndroidManifest.xml")
      java.srcDirs("src")      // org/echoclock/*.kt
      res.srcDirs("res")
      assets.srcDirs("assets") // assets/faces/**
  }
  ```
- **No third-party dependencies.** Pure framework + Kotlin stdlib; the APK carries **no native
  libraries**. No AndroidX, no Compose.
- `signingConfigs`: use `app/keystore.jks` (`keytool`-generated by `build.sh` if absent, alias
  `echoclock`, store/key pass `android`); release variant uses it. Also fall back to the debug
  key if the keystore cannot be created.
- `app/build.sh`: `set -euo pipefail`, locate SDK (`ANDROID_HOME`/`ANDROID_SDK_ROOT`, else
  `/opt/homebrew/share/android-commandlinetools`, else `~/Library/Android/sdk`), write
  `app/local.properties` with `sdk.dir`, prefer JDK 17 (Homebrew `/opt/homebrew/opt/openjdk@17`
  exported as `JAVA_HOME`), create the keystore, run `./gradlew --no-daemon
  assembleRelease`, copy `app/build/outputs/apk/release/app-release.apk` → `app/out/EchoClock.apk`.
  Honour `--help` and an optional `--debug` (assembleDebug) flag; be `bash -n` clean.

Acceptance: `bash app/build.sh` builds a signed `app/out/EchoClock.apk` containing
`classes.dex`, `assets/faces/**` and the manifest with `HomeActivity` as HOME; the default
`config.json` comes from `Config.DEFAULT_CONFIG_JSON` (written on first run), not an asset.
There are no `lib/**` entries (the app has no native code).

---

## 4. `config.json`

```json
{
  "defaultFace": "digital",
  "brightness": 0.7,
  "weather":  { "enabled": true, "lat": 37.77, "lon": -122.42, "refreshMinutes": 30 },
  "calendar": { "enabled": true, "maxEvents": 4, "horizonHours": 48 },
  "faces": {
    "digital": { "format24h": false, "showSeconds": true, "showDate": true, "accent": "#4fd6ff" },
    "world": { "zones": ["America/Los_Angeles", "America/New_York", "Europe/London", "Asia/Tokyo"] },
    "pomodoro": { "focusMinutes": 25, "breakMinutes": 5 }
  }
}
```
These are the built-in defaults (`Config.DEFAULT_CONFIG_JSON`, mirrored in `config/config.json`).
Missing keys fall back to them; the file is re-read from disk on every state tick (1 Hz).

---

## 5. Install flow (scripts)

`install/config.env` is the single source of versions/URLs (ROM tag, GApps asset, amonet
version). `install/download.sh` fetches everything into `install/dl/` and verifies sha256
(warns if a checksum is unknown).

Flow:
1. `./install/download.sh` — everything including EchoClock.apk (build if missing).
2. `./install/flash-lineageos.sh` — guided:
   - sanity-check `adb`/`fastboot`, device codename `cronos`;
   - run amonet `fastbrick` (unlock) — interactive, warns loudly;
   - boot TWRP, `twrp wipe` + `twrp install` ROM, then GApps;
   - reboot.
3. First boot (5–10 min) → enable USB debugging.
4. `./install/provision.sh` — skip setup wizard, debloat, kiosk display settings,
   `adb install` EchoClock + bundled apps, push `config.json` + faces,
   `cmd package set-home-activity org.echoclock/.HomeActivity`, grant
   `MANAGE_EXTERNAL_STORAGE` + `READ_CALENDAR`, deviceidle-whitelist.

Each script: `set -euo pipefail`, `bash -n` clean, `--help`, and a `--dry-run` where
destructive.

---

## 6. Ground rules

- 32-bit userspace: any native code must ship `armeabi-v7a`. This is forced, not a preference —
  the shipped ROM has **no 64-bit userspace**: `ro.product.cpu.abilist=armeabi-v7a,armeabi`,
  `ro.product.cpu.abilist64=` (empty), `ro.zygote=zygote32`, and the device tree sets
  `TARGET_ARCH := arm` / `TARGET_CPU_ABI := armeabi-v7a` (arm64 kernel only). An `arm64-v8a`
  APK fails `INSTALL_FAILED_NO_MATCHING_ABIS`.
- Android 11 (API 30): no scoped-storage surprises if we use `MANAGE_EXTERNAL_STORAGE`
  (granted by adb) — keep using `/sdcard/EchoClock`.
- Offline-first: the clock works with no network. Weather
  is the one optional online feature (open-meteo.com) and must degrade silently.
- Idempotent scripts: safe to re-run on the same device.

---

## 7. v2 contracts

### 7.1 Face navigation & per-face settings

- **Swipe left/right** on the clock moves to the next/previous face in the ordered face list
  (user faces first, then bundled; alphabetical by `name`). Vertical swipe = no-op. Swipes are
  detected in `HomeActivity` and must not fight the WebView's own scrolling (faces are
  fullscreen and non-scrolling; consume horizontal drags only past a threshold).
- **Per-face settings sheet**: a long-press (or `EC.openSettings()`) opens a native settings panel
  built from the face's declared schema. Values persist to `config.json` under `faces.<id>` and
  are pushed to the face via `window.ec.config(cfg)`.
- `face.json` v2 adds an optional `settings` array:
  ```json
  { "id":"digital","name":"Digital","author":"echoclock","description":"…",
    "settings":[
      {"key":"format24h","label":"24-hour clock","type":"bool","default":true},
      {"key":"showSeconds","label":"Seconds","type":"bool","default":true},
      {"key":"accent","label":"Accent","type":"color","default":"#4fd6ff"},
      {"key":"dim","label":"Night dimming %","type":"int","min":0,"max":100,"default":0},
      {"key":"style","label":"Style","type":"enum","options":["thin","bold"],"default":"thin"}
    ] }
  ```
  `type` ∈ `bool|int|enum|color|text|list` (`list` is edited one-per-line in the sheet and
  stored as a JSON array of lines). `bool` renders as a **CheckBox**. Faces MUST tolerate
  missing/extra keys.
- New `window.EC` methods: `settings()` (JSON array of the current face's schema + values),
  `setSetting(key,value)`, `nextFace()`, `prevFace()`.
- Every bundled face should declare a `settings` array covering its options.

### 7.2 Distribution

- `app/` is released as a downloadable APK built by **GitHub Actions**
  (`.github/workflows/release.yml`): builds on tags `v*` and attaches `EchoClock.apk` to the
  GitHub Release; also builds on PRs/pushes as an artifact.
- End users who receive a device only ever need `docs/USAGE.md` + the APK.

### 7.3 External action trigger

`HomeActivity` accepts an intent extra `action` and runs it. Any activity start with the extra
works; no new permission. Used for automation (Tasker, Home Assistant, cron) and for testing:

```
adb shell am start -n org.echoclock/.HomeActivity --es action "face:pomodoro"
```

Supported values (and nothing else):

| value | effect |
|---|---|
| `face:<id>` | switch to clock face `<id>` |
| `app:<pkg>` | launch the package's launcher activity |
| `nextFace` / `prevFace` | move between faces |
| `openDrawer` | open the app drawer |
| `weather:refresh` | re-fetch the weather now |
| `calendar:refresh` | re-read calendar events now |

Values are trimmed and matched case-insensitively; anything else is logged and ignored.
`HomeActivity` handles the extra in `onCreate` and `onNewIntent`, so a running clock reacts
without restarting.

---

## 8. v3 contracts (calendar, alarms, weather, clean face)

### 8.1 Clean face (no overlay buttons)

Remove the ⚙ and ▤ overlay buttons. Instead, on the clock:
- **Short tap** (down→up < 300 ms, movement < slop) → **open the app drawer**, *unless* the tap
  landed on an interactive element inside the face (button/a/input/select/textarea/[data-ec]).
- **Long press** (≥ 600 ms, little movement) → **open the face settings sheet**.
- Both remain reachable from JS (`window.EC.openDrawer()`, `window.EC.openSettings()`).
- Hit-testing: native calls a JS helper `window._ecHit(xCss,yCss)` → boolean
  (`document.elementFromPoint` + closest matcher over `button,a,input,select,textarea,[data-ec]`),
  installed on every page load and also exposed as `EC._hitInteractive` when the bridge allows it.
  If it returns false → drawer. Faces with their own buttons keep working because their
  elements match the matcher; add `data-ec` to any custom interactive element.
- **A press that starts on an interactive element cancels the launcher's long-press.** This is
  what lets a face own presses/holds: e.g. the Pomodoro timer (`data-ec`) toggles start/pause on
  a tap (holding it also works), and native drops its pending settings long-press as soon as the
  DOWN hit-tests as interactive. Long-pressing *outside* any control still opens settings.

### 8.2 State additions

`state` JSON gains (all optional; faces must tolerate absence):

```json
{ "nextAlarm": { "epoch": 1728123000000, "label": "Alarm", "inMinutes": 480,
                 "dayOffset": 1, "weekday": "Tue", "time": "07:32" },
  "calendar":  [ { "title": "Standup", "begin": 1728100000000, "end": 1728101800000,
                   "allDay": false, "calendar": "Work", "inMinutes": 42 } ],
  "weather":   { "tempC": 18.4, "tempF": 65.1, "code": 3, "text": "Overcast",
                 "daily": [ {"date":"2026-10-06","minC":11,"maxC":20,"minF":51.8,"maxF":68,"code":61} ],
                 "stale": false, "updated": 1728100000 } }
```
- `nextAlarm` from `AlarmManager.getNextAlarmClock()` (null when none); `label` is the fixed
  string `"Alarm"` (Android exposes no alarm message). `dayOffset` (0 = today), `weekday`
  (short, device locale) and `time` ("HH:MM") are resolved in the device timezone.
- `calendar` = next `maxEvents` events from `CalendarContract.Instances`, chronological
  (needs READ_CALENDAR; empty without a signed-in account).
- `weather` from open-meteo.com (no API key). It always fetches Celsius and derives Fahrenheit,
  so **both** `tempC`/`tempF` (and `minC`/`maxC`/`minF`/`maxF`) are always present and the
  **face** picks its unit via its own `tempUnit` setting (default `F`) — the app has no global
  unit preference. `stale:true` when offline/cached. `code` may be null.

### 8.3 New config keys

```json
{ "weather": { "enabled": true, "lat": 37.77, "lon": -122.42, "refreshMinutes": 30 },
  "calendar": { "enabled": true, "maxEvents": 4, "horizonHours": 48 } }
```

### 8.4 New permissions & actions

- Permissions: `READ_CALENDAR`, `SET_ALARM` (`com.android.alarm.permission.SET_ALARM`, normal —
  granted at install; `provision.sh` grants READ_CALENDAR).
- External actions: `weather:refresh`, `calendar:refresh` (the complete list is §7.3).
- Alarms are set from the settings sheet (**Set alarm → Open Clock…**), which fires
  `AlarmClock.ACTION_SET_ALARM` with `EXTRA_SKIP_UI` when allowed, falling back to the
  pre-filled Clock UI and then a local time picker. There is no external `alarm:` action.
- `window.EC` gains `openSettings()`, `calendar()`, `nextAlarm()`, `weather()`, `refresh()`.

### 8.5 Faces

- `digital` may show next alarm / weather / next event, each behind a face setting.
- New bundled face `agenda`: upcoming events + next alarm + time.
- Weather is only rendered when data exists; never block the face on the network.

### 8.6 Camera

The camera **works** on the LineageOS port once GApps is present (the AOSP `com.android.camera2`
app is installed). Docs must not claim it is broken. USAGE points users to the app drawer →
Camera.
