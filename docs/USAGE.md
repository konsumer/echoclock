# USAGE — living with the EchoClock device

You have an Amazon Echo Show 5 that no longer runs Fire OS. It boots straight into
**EchoClock**: an always-on desk clock with swipeable faces, an app drawer, and optional
calendar/alarm/weather extras.

Everything happens **on the device**. There is no EchoClock account and no cloud — nothing is
sent anywhere. The clock works fine with WiFi off; only the optional calendar/weather extras
ever touch the network (see below).

This page is for whoever uses the device. If you're the one who builds or programs it, see
[CUSTOMIZE.md](CUSTOMIZE.md).

---

## The screen

- The **clock face fills the screen**. Faces are little apps — a digital clock, a flip clock,
  an analog dial, an **agenda** (calendar + next alarm), and more are included.
- **Swipe left or right across the clock** to move to the next / previous clock face.
- **Tap the clock** to open the **app drawer** — a scrollable grid of every app installed on
  the device (including **Camera**). Tap an app to launch it; the drawer's **Clock** button (or
  **Home**) closes it.
- **Long-press the clock** to open that face's **settings**: things like 24-hour time, seconds,
  accent colour, and which extras (next alarm, weather, calendar) to show. On/off options are
  **checkboxes**; press **Save** to apply — **Cancel** (or Back) discards.
- A face's **own controls keep working** — pressing one does its thing instead of opening the
  drawer or settings. On the **Pomodoro** face: **tap the big timer** to start/pause (holding it
  works too; the face says *"paused"* and dims while stopped), and use the **Focus / Break**
  buttons to switch mode.
  Tap or long-press the clock *outside* a face's controls to reach the drawer / settings.
- The **Home button returns to the clock**, from the drawer or any app.

There are no on-screen ▤/⚙ buttons any more — a **tap** opens the drawer and a **long-press**
opens settings.

## Calendar, alarms & weather

Three extras can appear on faces that support them (and on the built-in **agenda** face):

- **Calendar** — your next few upcoming events. This needs the device **signed in to a
  calendar account** (Google or a local one) in Settings → Accounts; if it isn't, the clock
  simply shows no events. Enabled by default; switch to the agenda face by swiping to it.
- **Alarms** — set one from the face settings (**long-press the clock → Set alarm → Open
  Clock…**, which hands off to the system Clock app), or in the Clock app directly. The clock
  shows the **next alarm** when one is set, and alarms ring through the system **Clock** app
  (`com.android.deskclock`), so snooze/dismiss are the normal Android ones.
- **Weather** — current temperature and conditions, from **open-meteo.com** (free, no API key,
  no sign-in), refreshed while WiFi is on. **Offline it just quietly shows the last cached
  value, or nothing** — the clock never blocks waiting for the network.

Which of these each face shows is a per-face setting (**long-press the clock**), including the
temperature unit (**°F or °C**, default **°F**). The weather latitude/longitude live in
`config.json` (see [CUSTOMIZE.md](CUSTOMIZE.md) → *Weather, calendar & alarms*).

## Troubleshooting

**“Where's the camera?”**
It works. Open the **app drawer** (tap the clock) and tap **Camera**. The camera needs GApps
installed (it is, if you followed [SETUP.md](SETUP.md)) and the `com.android.camera2` app.

**“The battery always shows 100%.”**
By design — the battery reporting on this modified ROM is not real. It's meant to live plugged
in with the charger it came with.

**“It never deep-sleeps / it's always warm / the screen is always on.”**
Intended. This is a wall-powered clock: deep sleep is disabled and the screen is configured to
stay on.

**“Does anything go to Amazon/Google?”**
No. There is no account and no cloud service; only the optional weather (and any calendar
account you sign in yourself) use the network.

**“It's stuck / I want to restart it.”**
Unplug it, wait a few seconds, plug it back in.

**“I want to add my own clock.”**
That's a programming task — see [CUSTOMIZE.md](CUSTOMIZE.md). Faces are plain files in the
device's `EchoClock` folder.

---

New here? The full story of how the device was built is in [SETUP.md](SETUP.md); the
two-page overview is [README.md](../README.md).

---

## Settings details

- **Face settings** are opened by **long-pressing the clock** and are applied with the sheet's
  **Save** button (**Cancel**/Back discards). Every on/off option is a **checkbox** (for
  example *24-hour clock*, *Seconds*, *Date*, *Next alarm*, *Weather*).
- **Set alarm → Open Clock…** hands off to the **system Clock app** so the alarm is a real
  DeskClock alarm: it rings normally, survives a reboot, and appears as *next alarm* on the
  clock face. (If no Clock app is present it falls back to a simple time picker.)
- The settings list scrolls — on faces with several options the Alarm/extra rows are below the
  fold, so swipe up inside the sheet.
