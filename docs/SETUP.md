# SETUP — from a blank Echo Show 5 to EchoClock

This is the manual, irreversible part: **unlock the bootloader, replace Fire OS with
LineageOS 18.1, and put EchoClock on it**. Budget about an hour of hands-on time plus a
5–10 minute first boot. Do this only on hardware you own, and read
[`docs/GOTCHAS.md`](GOTCHAS.md) before you start — it lists the traps that brick devices.

> **Parts:** **1 · SETUP.md (this file)** — [2 · USAGE.md](USAGE.md) — 3 · the APK (GitHub
> Releases) — [4 · CUSTOMIZE.md](CUSTOMIZE.md)

---

## Target

| | |
|---|---|
| Device | Amazon Echo Show 5 **2nd gen (2021)** — model `AEOCN`, codename **`cronos`** |
| Not this device | 1st gen Show 5 (`checkers`), Show 8 (`crown`) — different unlock packages |
| OS | LineageOS **18.1** (Android 11, API 30), unofficial port for `cronos` |
| Userspace | **32-bit** (`armeabi-v7a`) — the SoC is 64-bit but the ROM is not |
| Screen | 960×480 landscape, 1 GB RAM, wall-powered |

Versions, URLs and checksums are pinned in one place: [`install/config.env`](../install/config.env).

## Requirements

- An Echo Show 5 **2nd gen** (`cronos`). Confirm before anything: a wrong model = bootloop.
- A **macOS or Linux host** with `adb` + `fastboot` (Android platform-tools):
  - macOS: `brew install android-platform-tools coreutils`
  - Linux: your distro's `android-tools-adb`/`android-tools-fastboot`, or Google's
    platform-tools — plus working USB permissions (udev).
- A **data-capable micro-USB cable** and the bundled **AC charger** (do not run the flash on
  battery).
- **~2 GB free disk** (ROM ~485 MB, GApps ~176 MB, APK).
- The **amonet unlock bundle**: `amonet-cronos-v2.0.1.zip`. It is distributed **only on the
  XDA thread, which requires an XDA login**, so it cannot be auto-downloaded — grab it yourself:
  <https://xdaforums.com/t/unlock-root-twrp-unbrick-amazon-echo-show-5-2nd-gen-2021-cronos.4772596/>
  and drop it in `install/dl/amonet-cronos-v2.0.1.zip`.
- Your unit should be on **Fire OS 6.5.7.0 or newer** (the amonet tool checks this).

> ### ⚠️ Read this first
>
> - **Anti-rollback is a permanent hardware fuse.** Never flash a `preloader`, `lk` or `tee`
>   image older than what is already installed — that is an **unrecoverable brick**. The amonet
>   `fastbrick` step version-matches the firmware image automatically; confirm it prints
>   “Fire OS 6.5.7.0 or newer” before continuing.
> - The device normally has **no BROM USBDL access**, so many bricks can never be undone.
> - Unlocking **wipes the device** (all data).
> - The exploit intentionally corrupts a partition to force BROM entry. The screen going
>   **black or garbled is normal** during `fastbrick` — **do not unplug** until it tells you it
>   is done.
> - `boot` images have **no** anti-rollback, so those are always safe to reflash/restore.

---

## Step 1 — Unlock (amonet) and get TWRP

### Option A: the guided helper (recommended)

Clone this repo, put the amonet zip where `install/config.env` expects it, then:

```sh
./install/download.sh          # ROM, GApps (+ tries to build the APK)
./install/flash-lineageos.sh   # guided: identity check → unlock → TWRP → ROM + GApps
```

`download.sh` takes `--force` to re-download files that are already present and checksum-clean.

`flash-lineageos.sh` verifies `ro.product.device`/`ro.product.name` is `cronos` before touching
anything, refuses to guess which device you mean when several are attached (`-s/--serial`), and
never flashes `preloader`/`lk`/`tee` itself. Useful flags: `-n/--dry-run`, `-y/--yes`,
`--skip-unlock` (already unlocked / already in TWRP), `--sideload`.

For a whole batch of devices there is [`install/setup.sh`](../install/setup.sh), which runs
download → flash → provision per device and skips flashing a device that already runs
LineageOS:

```sh
./install/setup.sh -s <serial>              # everything, one device
./install/setup.sh -s <serial> --stage provision
```

`setup.sh` flags: `-s/--serial`, `--stage all|download|flash|provision`, `--skip-flash`,
`--force-flash`, `--no-download`, `-y/--yes`, `-n/--dry-run`.

### Option B: manual amonet

1. **macOS only:** the bundled `bin/fastboot` inside the amonet zip is a **Linux ELF**.
   Replace it with your host `fastboot`, and make GNU `timeout` visible:
   ```sh
   export PATH="$(brew --prefix coreutils)/libexec/gnubin:$PATH"
   ```
2. Unzip `amonet-cronos-v2.0.1.zip` and run its `fastbrick.sh` (Linux/macOS) or `fastbrick.bat`
   (Windows) as the script instructs. This is **interactive**: read the warnings and type the
   confirmation it asks for. It installs TWRP and unlocks the bootloader.
3. If the exploit stalls on macOS's USB stack, try another cable/port — or, far more reliably,
   a **Linux host (a Raspberry Pi is plenty)**.
4. When it finishes, the boot modes are:

   | Buttons at power-on | Mode |
   |---|---|
   | Vol-Down + power | hacked fastboot |
   | Vol-Up + power | **TWRP recovery** |
   | Mute + Vol-Down + power | USBDL (unbrick; not normally available) |

> Note: after unlocking, the **privacy button acts as the power button** and it is what
> wakes the screen (not volume).

---

## Step 2 — Flash LineageOS 18.1 + MindTheGapps

Files (pinned in [`install/config.env`](../install/config.env)):

- ROM: `lineage-18.1-20260904-UNOFFICIAL-cronos.zip` (release tag `lineage-18.1-cronos-v0.4`)
- GApps: `MindTheGapps-11.0.0-arm-20230922_081034.zip` — **must be the ARM (32-bit) build**,
  matching the ROM's `armeabi-v7a` userspace. An arm64 package will not work.

Boot **TWRP** (Vol-Up + power), then follow the ROM thread's exact order:

1. **Format Data**
2. **Advanced Wipe** → Data, System, Cache
3. Now push the zips (step 1 erased internal storage, so push *after* the wipe):
   ```sh
   adb push install/dl/lineage-18.1-20260904-UNOFFICIAL-cronos.zip /sdcard/
   adb push install/dl/MindTheGapps-11.0.0-arm-20230922_081034.zip /sdcard/
   ```
   (or stream them with **`adb sideload`** from TWRP — nothing is stored up front)
4. **Install** the ROM zip, then **Install** the GApps zip.
5. **Format Data again**, then **Reboot → System**.

`install/flash-lineageos.sh` automates the unlock, wipe and flash (ROM then GApps, with an
**on-device sha256 check** before installing), following the same wipe-before-push rule, and
can use `--sideload` for the push-after-wipe problem. Don't improvise a different wipe order —
the sequence above is what the ROM thread says boots reliably.

**First boot takes 5–10 minutes.** Don't panic and pull power.

---

## Step 3 — First boot, Developer options, identity check

1. Let LineageOS finish booting, then unlock the screen.
2. **Developer options:** Settings → About tablet → tap **Build number** 7 times.
3. Developer options → enable **USB debugging**, then plug in and accept the host-key prompt:
   ```sh
   adb devices        # must list your device as "device"
   ```
4. Verify this is really a 32-bit `cronos` before installing anything:

   ```sh
   adb shell getprop ro.product.device        # cronos
   adb shell getprop ro.product.cpu.abilist   # armeabi-v7a,armeabi
   adb shell getprop ro.product.cpu.abilist64 # (empty)
   adb shell getprop ro.build.version.sdk     # 30
   ```

   If `ro.product.device` is `checkers` — stop, wrong device. If the ABI list shows `arm64-v8a`
   or `abilist64` is non-empty — wrong ROM. `install/flash-lineageos.sh` only flashes a device
   whose identity is `cronos`, and `install/provision.sh` refuses to proceed below API 30.

---

## Step 4 — Install the EchoClock APK

Pick one:

- **Download:** grab `EchoClock.apk` from [GitHub Releases](https://github.com/konsumer/echoclock/releases/latest)
  (built by CI on tags `v*`), then `adb install -r EchoClock.apk`.
- **Build it yourself** (needs JDK 17 + Android SDK; the Gradle wrapper is committed and pinned
  — no system Gradle):
  ```sh
  bash app/build.sh          # → app/out/EchoClock.apk
  adb install -r app/out/EchoClock.apk
  ```

An error like `INSTALL_FAILED_NO_MATCHING_ABIS` means you have an arm64 build: EchoClock ships
`armeabi-v7a` only, because the device has no 64-bit userspace.

---

## Step 5 — Provision (optional helper)

[`install/provision.sh`](../install/provision.sh) finishes the job over adb and is idempotent
(re-runnable). With the device booted and USB debugging on:

```sh
./install/provision.sh -s <serial>      # -n/--dry-run to preview
```

It does, in order:

- **setup wizard skipped** and **kiosk display settings**: `locksettings set-disabled true`,
  screensaver/doze off, `screen_off_timeout 1800000`, `stay_on_while_plugged_in 7`, night mode;
- **installs** EchoClock (and any `BUNDLED_APKS`);
- **pushes app data**: `config/config.json` and any extra `faces/*` into `/sdcard/EchoClock/`;
- **sets the launcher** to EchoClock: `cmd package set-home-activity org.echoclock/.HomeActivity`;
- **grants** the `MANAGE_EXTERNAL_STORAGE` appop (so the app can read `/sdcard/EchoClock`) and
  **`READ_CALENDAR`** (so the clock can show upcoming events), and adds the app to the
  **doze/deviceidle whitelist** so it is not frozen;
- disables a handful of unused LineageOS apps.

Minimal manual equivalent if you don't want the script:

```sh
adb install -r EchoClock.apk
adb shell mkdir -p /sdcard/EchoClock/faces
adb push config/config.json /sdcard/EchoClock/
adb push faces/. /sdcard/EchoClock/faces/       # optional extra faces (skip if you have none)
adb shell cmd package set-home-activity org.echoclock/.HomeActivity
adb shell appops set org.echoclock MANAGE_EXTERNAL_STORAGE allow
adb shell pm grant org.echoclock android.permission.READ_CALENDAR
adb shell dumpsys deviceidle whitelist +org.echoclock
```

---

## Verify it works

- [ ] EchoClock is the home screen, and the **Home key returns to the clock**.
- [ ] **Swipe left/right** changes clock faces; **long-press** opens per-face settings and
      changes stick.
- [ ] **Tap the clock** opens the app drawer; **Home** closes it. A face's own controls (e.g.
      Pomodoro's timer, Focus/Break buttons) still work when tapped.
- [ ] The **camera** works: app drawer → **Camera** (needs GApps, which Step 2 installed).
- [ ] With WiFi on and an account signed in, the **agenda** face shows calendar events, the
      next alarm and the weather; WiFi off, the clock still works and weather just goes quiet.
- [ ] `adb shell logcat -s EchoClock` shows no errors; the clock keeps ticking with WiFi off,
      and nothing is sent anywhere.

If a step misbehaves, check [`docs/GOTCHAS.md`](GOTCHAS.md) first; the known quirks
(battery always 100%, deep sleep disabled) are documented there and in
[USAGE.md](USAGE.md).
