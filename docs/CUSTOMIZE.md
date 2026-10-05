# CUSTOMIZE — clock faces, settings & external triggers

EchoClock is customizable from the device's storage. Everything user-facing lives in
`/sdcard/EchoClock/`:

```
/sdcard/EchoClock/
  config.json       app settings, weather/calendar options, per-face settings
  faces/<id>/       clock faces (HTML/JS): index.html + face.json
```

**User files win over bundled ones** (same face `id` overrides the bundled face). Faces are
plain files: edit over adb and they load — no app rebuild, no reinstall:

```sh
adb pull  /sdcard/EchoClock/config.json
adb push  config.json /sdcard/EchoClock/config.json
```

> **With more than one device attached**, plain `adb` fails with
> `more than one device/emulator` — add `-s <serial>` to each command, or
> `export ANDROID_SERIAL=<serial>` once. Find the serial with `adb devices -l` (the line
> showing `device:cronos`). Every `adb …` example below assumes that is already set.

The full interface contract is [`docs/DESIGN.md`](DESIGN.md) §2.4, §7 and §8 — this page
is the practical how-to.

---

## 1. Clock faces

A face is a directory:

```
faces/<id>/
  face.json      manifest
  index.html     entry point (inline CSS/JS; no network)
```

Rendered fullscreen in a WebView at **960×480 CSS px**, no zoom, dark background, no scrolling.
Faces must be **offline-only** and tolerate missing/extra config keys.

### `face.json`

```json
{
  "id": "hello",
  "name": "Hello",
  "author": "you",
  "description": "Digital clock with a configurable greeting.",
  "settings": [
    { "key": "greeting",    "label": "Greeting",      "type": "text",  "default": "Hello" },
    { "key": "accent",      "label": "Accent colour", "type": "color", "default": "#4fd6ff" },
    { "key": "showSeconds", "label": "Seconds",       "type": "bool",  "default": true },
    { "key": "scale",       "label": "Size %",        "type": "int",   "min": 50, "max": 200, "default": 100 },
    { "key": "style",       "label": "Style",         "type": "enum",  "options": ["thin", "bold"], "default": "thin" },
    { "key": "quotes",      "label": "Quotes (one per line)", "type": "list", "default": "Tick.\nTock." }
  ]
}
```

`settings` is optional. When present, EchoClock builds a **native settings sheet** for the face
automatically (**long-press the clock**; there is no ⚙ button any more). Field types:

| `type` | Sheet control | Notes |
|---|---|---|
| `bool` | **checkbox** | JSON boolean |
| `int` | number/slider | optional `min`, `max` |
| `enum` | spinner | required `options` array |
| `color` | colour swatches | `#rrggbb` string |
| `text` | text field | string |
| `list` | multiline text | edited one per line; stored as a JSON array of lines |

Values persist to `config.json` under `"faces": { "<id>": { ... } }` and are handed back to the
face via `window.ec.config(cfg)`.

### The face-side API — `window.ec`

Define both callbacks; either may be omitted.

```js
window.ec = {
  config(cfg) { /* once on load (and after settings change): this face's settings */ },
  tick(state) { /* once per second: the clock/state snapshot */ }
};
```

`state` fields:

| field | meaning |
|---|---|
| `epoch` | milliseconds since epoch — render time from this, never from `Date.now()` drift |
| `battery`, `charging` | battery %, charging flag (on this device always 100 / charging) |
| `face` | current face id |
| `locale`, `brightness` | locale string, screen brightness 0–1 |
| `nextAlarm` | `{ epoch, label, inMinutes, dayOffset, weekday, time }` or `null` — the **next** system alarm (earliest of however many are set); `label` is always `"Alarm"` (Android exposes no alarm message) |
| `calendar` | array of `{ title, begin, end, allDay, calendar, inMinutes }`, chronological (may be empty). `title` can be empty — show it as "Untitled". |
| `weather` | `{ tempC, tempF, code, text, daily:[{date,minC,maxC,minF,maxF,code}], stale, updated }` or absent. **Both units are always supplied**; a face picks one from its own `tempUnit` setting (see below). `stale: true` means a cached value. |

All of the v3 fields are **optional**: faces MUST tolerate their absence (no alarm set, not
signed in to a calendar, offline weather). Never block rendering on them — see
[`DESIGN.md`](DESIGN.md) §8.2.

**Temperature units are a per-face setting.** A face that shows temperature should declare
`{ "key": "tempUnit", "label": "Temperature unit", "type": "enum", "options": ["F", "C"],
"default": "F" }` and render `tempF`/`tempC` accordingly — the app already provides both, so no
conversion is needed in JS. (`digital`, `cute` and `agenda` all do this.)

How a **later alarm** is presented is up to the face: the app supplies `dayOffset` (`0` = today,
`1` = tomorrow, …), `weekday` (short, device locale) and `time` ("HH:MM"), already resolved in the
device timezone — so a face can show "Alarm 7:32 AM Tue" (what `digital` does, behind its *Alarm
day* setting), show no day at all, or format `epoch` its own way. It is always the *next* alarm,
never a fixed first one.

For a face's single **"next event"**, don't just take `calendar[0]`: all-day entries start at
midnight and would always sort first. Prefer the earliest **timed** (`allDay !== true`) event, and
fall back to the earliest all-day one only when there is no timed event — that is what the
`digital` and `cute` faces do (both also render an empty title as `Untitled`).

### Native API — `window.EC`

Methods provided by the app, callable from a face. JSON payloads arrive as **strings** (parse
them); boolean methods return `true`/`false`.

| method | returns |
|---|---|
| `EC.state()` | current state JSON (§ above) |
| `EC.faces()` | `[{"id","name","bundled"}]` |
| `EC.settings()` | this face's settings schema **plus current values** (JSON array) |
| `EC.setFace(id)` | `true`/`false` (unknown id → `false`) |
| `EC.setSetting(key, value)` | persist one setting, coerced to its schema type (`false` for an unknown key/invalid value) |
| `EC.nextFace()` / `EC.prevFace()` | `true` (moves through the face list; `false` only if there are no faces) |
| `EC.openDrawer()` | `true` (shows the app drawer) |
| `EC.openSettings()` | `true` (opens this face's settings sheet, same as long-press) |
| `EC.calendar()` | upcoming events JSON (as in `state.calendar`) |
| `EC.nextAlarm()` | next system alarm JSON, or `null` |
| `EC.weather()` | current weather JSON (as in `state.weather`), or `null` |
| `EC.refresh()` | `true` (re-request calendar + weather now, never blocks) |

### Worked example

`faces/hello/face.json`:

```json
{
  "id": "hello",
  "name": "Hello",
  "author": "you",
  "description": "Digital clock with a configurable greeting, accent and size.",
  "settings": [
    { "key": "greeting", "label": "Greeting", "type": "text", "default": "Hello" },
    { "key": "accent", "label": "Accent colour", "type": "color", "default": "#4fd6ff" },
    { "key": "showSeconds", "label": "Seconds", "type": "bool", "default": true },
    { "key": "scale", "label": "Size %", "type": "int", "min": 50, "max": 200, "default": 100 }
  ]
}
```

`faces/hello/index.html`:

```html
<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <style>
    html, body { margin: 0; height: 100%; background: #000; overflow: hidden; }
    body { display: flex; flex-direction: column; align-items: center; justify-content: center;
           color: #fff; font-family: sans-serif; }
    #greeting { font-size: 30px; opacity: .8; }
    #clock { font-size: 96px; font-weight: 300; font-variant-numeric: tabular-nums; }
  </style>
</head>
<body>
  <div id="greeting"></div>
  <div id="clock"></div>
  <script>
    var cfg = { greeting: "Hello", accent: "#4fd6ff", showSeconds: true, scale: 100 };

    window.ec = {
      config: function (c) { Object.assign(cfg, c || {}); draw(); },
      tick: function (state) { draw(state.epoch); }
    };

    function draw(epoch) {
      document.getElementById("greeting").textContent = cfg.greeting;
      var clock = document.getElementById("clock");
      clock.style.color = cfg.accent;
      clock.style.fontSize = (96 * cfg.scale / 100) + "px";
      if (epoch !== undefined) {
        var opts = { hour: "2-digit", minute: "2-digit" };
        if (cfg.showSeconds) opts.second = "2-digit";
        clock.textContent = new Date(epoch).toLocaleTimeString(undefined, opts);
      }
    }
    draw();  // paint with defaults before the first tick
  </script>
</body>
</html>
```

Install it and swipe to it (user faces come first, then bundled, alphabetical by name):

```sh
adb push faces/hello /sdcard/EchoClock/faces/
```

The bundled **`gallop`** face is a worked example of a data-driven face: its horse is 12 traced
silhouettes (vector paths) cycled at 12 fps, from Eadweard Muybridge's *The Horse in Motion*
(1878 — public domain). Its `frames.js` is just `{ transform, frames: [d, …] }`, so you can drop
in your own traced frames the same way; without it the face falls back to a procedural horse.

`faces/` is just a staging directory in your checkout — create `faces/<id>/` if it isn't there
([faces/README.md](../faces/README.md)), or push straight to `/sdcard/EchoClock/faces/` from anywhere. Faces
load when they are selected, so switching away and back re-reads your edits — no reboot.
To customise a **bundled** face, copy its idea into a user directory with the same id (e.g.
`/sdcard/EchoClock/faces/digital/`); yours then overrides the built-in one. If a face shows a
blank screen, check `adb logcat -s EchoClock` for JavaScript errors.

---

## 2. Tap / long-press (the interaction contract)

There are **no overlay buttons** on the clock any more ([DESIGN §8.1](DESIGN.md)). Native
hit-tests the tap:

- **Short tap** (< 300 ms, still) → **open the app drawer**, *unless* the tap lands on an
  interactive element **inside the face**.
- **Long press** (≥ 600 ms) → **open the face settings sheet**. `EC.openSettings()` does the
  same from JS.
- **Home** always returns to the clock.

If your face has its own buttons/links, they keep working automatically when they are
`button`, `a`, `input`, `select`, `textarea`, or carry a `data-ec` attribute. **Add `data-ec`
to any custom interactive element** you want tap-through on, otherwise native swallows the tap
and opens the drawer:

```html
<div id="startBtn" data-ec onclick="start()">Start</div>
```

Faces are fullscreen and non-scrolling — consume horizontal drags only for your own UI; a
vertical/wheel gesture that isn't a swipe is ignored by native.

## 3. Weather, calendar & alarms

Configured in `config.json` (defaults shown); all optional.

```json
{
  "weather":  { "enabled": true, "lat": 37.77, "lon": -122.42, "refreshMinutes": 30 },
  "calendar": { "enabled": true, "maxEvents": 4, "horizonHours": 48 }
}
```

- **Weather** comes from [open-meteo.com](https://open-meteo.com) — free, **no API key**. Set
  `weather.lat` / `weather.lon` to your location. It always fetches Celsius and supplies both
  `tempC` and `tempF`, so each **face** chooses °F or °C in its own settings (default **°F**).
  It refreshes every `refreshMinutes` while WiFi is up and **silently falls back** to the last
  cached value (`weather.stale: true`) or shows nothing offline — never block rendering on it.
- **Calendar** reads the next `maxEvents` events within `horizonHours` from the device's signed-in
  account(s); empty if none. Needs `READ_CALENDAR` (provisioned).
- **Alarms** use the system Clock app (`com.android.deskclock`). `state.nextAlarm` is the next
  one, or `null`; set one from any face's settings sheet (**long-press the clock → Set alarm →
  Open Clock…**), which opens the Clock app's alarm UI (a simple time picker is the fallback).
- Expose any of these on a face via the state fields / `EC` methods above, usually behind a
  `settings` toggle (see the bundled **`agenda`** and **`digital`** faces for examples).

---

## Trigger an action from outside (adb / Tasker / another app)

Any of the triggerable actions can be fired externally by starting the launcher with an
`action` extra. This is handy for automation (Tasker, Home Assistant, cron) and for testing:

```sh
adb shell am start -n org.echoclock/.HomeActivity --es action "face:pomodoro"
adb shell am start -n org.echoclock/.HomeActivity --es action "app:com.android.settings"
adb shell am start -n org.echoclock/.HomeActivity --es action "nextFace"
adb shell am start -n org.echoclock/.HomeActivity --es action "openDrawer"
adb shell am start -n org.echoclock/.HomeActivity --es action "weather:refresh"
adb shell am start -n org.echoclock/.HomeActivity --es action "calendar:refresh"
```

Supported values (see [DESIGN §7.3](DESIGN.md)): `face:<id>`, `app:<pkg>`, `nextFace`,
`prevFace`, `openDrawer`, `weather:refresh`, `calendar:refresh`. Every invocation is logged under
the `EchoClock` logcat tag:

```sh
adb logcat -s EchoClock
```
