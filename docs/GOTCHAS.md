# Gotchas

Traps that cost real time. Some are from the community playbooks we build on, some we hit
ourselves. Read before flashing.

## Unlock / flashing

- **Wrong codename = bootloop.** `checkers` (Show 5 gen 1) and `cronos` (gen 2) need different
  amonet packages. Verify with `getprop ro.product.device` == `cronos` before anything.
- **Anti-rollback is permanent.** Never flash old `preloader`/`lk`/`tee`.
- **The bundled `bin/fastboot` in amonet is a Linux ELF.** On macOS, overwrite it with a real
  macOS `fastboot` (`brew install android-platform-tools`) before running `fastbrick.sh`.
  `fastbrick.sh` also needs GNU `timeout` (Homebrew coreutils) on macOS.
- **The exploit can stall on macOS's USB stack.** Try another cable/port; a Linux host (a Pi is
  plenty) usually works first try if macOS won't.
- **Format Data wipes internal storage**, so you cannot `adb push` the ROM zip and *then* format.
  Either push after the wipe and use `twrp install`, or use `adb sideload` from TWRP.
- **Don't improvise wipes.** The ROM thread's exact order (Format Data → Advanced Wipe
  Data/System/Cache → flash → Format Data again → reboot) is what boots reliably.
- **First boot takes 5–10 minutes.** Don't panic-pull power.

## LineageOS 18.1 on cronos

- **Battery always reads 100%** and **deep sleep is disabled** by design — it is a wall-powered
  appliance, not a bug.
- **The privacy button is the power button** after unlock, and it (not volume) wakes the screen.
- **SELinux is permissive.** Do not treat the device as a secure vault.
- **Camera works** once GApps is present — `com.android.camera2` is installed and usable from
  the app drawer. Do not document it as non-functional.
- **Calendar events need a signed-in account.** `READ_CALENDAR` is granted, but with no account
  in Settings → Accounts the agenda/calendar is simply empty — that is not a bug.
- **Weather is online-only but non-blocking.** open-meteo.com needs WiFi; offline the face shows
  the last cached value (`state.weather.stale: true`) or nothing. Never let a face wait on it.
- **1 GB RAM is tight when Google Play Services is installed.** GApps is needed for the camera
  and Google services, but if you never sign in and RAM pressure hurts, consider
  `pm disable-user`-ing `com.google.android.gms`.

## The app

- **32-bit userspace.** The ROM and GApps are `armeabi-v7a`; install the **ARM (32-bit)** GApps
  package. An arm64 package will not work. EchoClock itself has no native code.
- **`MANAGE_EXTERNAL_STORAGE` is granted via adb** in `provision.sh`; without it the app cannot
  read `/sdcard/EchoClock`. On a device you own this is fine.
- **Calendar needs a signed-in account** (see above) — the agenda is empty without one.
- **`GET_EVENT` buffering**: `getevent` full-buffers to a pipe, so shell "volume-button" monitors
  never see individual presses. Not used here, but explains why that trick fails on this device.

## Audio: never run `dumpsys media.audio_flinger` on this device

The ported vendor audio HAL (`/vendor/bin/hw/android.hardware.audio.service`, built from
`android.hardware.audio@2.0-impl.so` — the `amazon_wrapper` HAL) has a **null-pointer
dereference in `Device::debug()`**:

```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0  (null pointer dereference)
#01 .../android.hardware.audio@2.0-impl.so
    (Device::debug(hidl_handle const&, hidl_vec<hidl_string> const&)+46)
```

`dumpsys media.audio_flinger` invokes that `debug()` method, so **running it crashes the HAL**.
Reproduced twice on hardware: the HAL pid changed and a fresh tombstone appeared on each run.
init restarts the service, but the speaker output stays **silent until reboot** — Android still
reports everything healthy (music unmuted, volume 15/15, `OUT_SPEAKER`, master mute off,
`underruns=0 writeErrors=0`), which is why it looks like "audio-out just stopped working".

- **Do not run** `dumpsys media.audio_flinger` (and don't have tooling do it).
  `dumpsys audio` and `dumpsys media.audio_policy` are safe.
- **If audio goes silent:** `adb shell logcat -b crash -d | grep -i audio.service` — if you see
  that signature, **reboot**; more dumpsys will only crash it again.
- The HAL is crash-prone generally (several distinct pids in the crash buffer from one boot),
  so this is an upstream bug in the port worth reporting to the XDA thread with the stack above.

## Building

- **Use the Gradle wrapper.** `bash app/build.sh` runs `./gradlew --no-daemon assembleRelease`
  and copies the APK to `app/out/EchoClock.apk`; the wrapper and versions are pinned
  (Gradle 8.11.1 / AGP 8.7.3 / Kotlin 2.0.21, JDK 17). Don't hand-roll `aapt2`/`javac`/`d8`.
- **No AARs, no native libs.** The app is pure framework + Kotlin stdlib, so there is nothing to
  fetch from Maven Central at build time and the APK contains no `lib/**` entries. Don't add
  native dependencies casually — see the 32-bit constraint above.
- Keep native libs (if ever added) at `lib/armeabi-v7a/` inside the APK; AGP zipaligns and signs
  the release build for you (via `app/keystore.jks`, created by `build.sh` if absent).
- **Build errors** — see the app's own paths and SDK discovery in `app/build.sh`; it honours
  `--debug` and `--help`.
