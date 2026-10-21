# Installing EchoClock — start from your phase

This is the command-oriented map. [`docs/SETUP.md`](../docs/SETUP.md) has the target,
host support, and brick warnings; [`docs/GOTCHAS.md`](../docs/GOTCHAS.md) lists the traps.
Read both before touching hardware.

All commands assume you are in the repo root:

```sh
git clone https://github.com/konsumer/echoclock
cd echoclock
```

## Pick your starting point

| Your device today | Start at |
|---|---|
| Factory-reset Echo Show 5 (2nd gen), still on Fire OS | [Phase A](#phase-a--factory-echo-show-5-stock-fire-os) |
| Already unlocked, or TWRP is reachable | [Phase B](#phase-b--already-unlocked--twrp-reachable) |
| Booted into LineageOS 18.1, EchoClock not installed | [Phase C](#phase-c--lineageos-running-echoclock-not-installed) |
| EchoClock installed; upgrading the app, config, faces, or ROM | [Phase D](#phase-d--upgrades) |
| EchoClock device needs a wipe or re-provision | [Phase E](#phase-e--reset-or-re-provision-an-existing-device) |
| Stuck, no adb, no fastboot, or a script refused to run | [Troubleshooting](#troubleshooting) |

## Before any phase

| Check | Detail |
|---|---|
| Model | Echo Show 5 **2nd gen (2021)**, model `AEOCN`, codename `cronos`. Not `checkers` (1st gen) or `crown` (Show 8). |
| Userspace | **32-bit (`armeabi-v7a`).** Use the ARM/32-bit ROM, GApps, and third-party APKs. |
| Fire OS (stock devices) | **6.5.7.0 or newer** — verify in Settings before unlocking. amonet prints the detected LK build/payload and aborts on a device mismatch. |
| Host for the unlock | **macOS, Linux, or Windows.** macOS uses the bundled pyusb client (`install/fbflash.py`) automatically; Linux/Windows use amonet's patched fastboot. macOS needs Python 3 + libusb (`brew install libusb`). |
| Host tools | `adb`, `fastboot`, `unzip`, `curl`; `shasum` (macOS) or `sha256sum` (Linux). |
| Disk | **~2 GB free** for the ROM (~485 MB), GApps (~176 MB), and APK. |
| Java/Android SDK | Only if you build the APK yourself: JDK 17 + Android SDK. `install/download.sh` builds it when both are present. |
| Unlock bundle | `install/dl/amonet-cronos-v2.0.1.zip`, manually downloaded from the [XDA thread](https://xdaforums.com/t/unlock-root-twrp-unbrick-amazon-echo-show-5-2nd-gen-2021-cronos.4772596/) (login required). |
| Power | Use the bundled AC charger. Do not unlock or flash on battery. |
| Cable | Data-capable micro-USB. Charge-only cables cause “no device” loops. |

macOS specifics — the patched `--fastboot` override, Linux VM USB passthrough, and
Docker limits — are in [`install/MACOS.md`](MACOS.md).

Serial numbers: `adb devices -l` for Android, `fastboot devices` for fastboot. Pass
`-s <serial>` to every script when more than one device is attached. With exactly one
device, the scripts auto-detect it.

> **Brick risk:** anti-rollback is a permanent hardware fuse. Never flash an older
> `preloader`, `lk`, or `tee`; the scripts never do. Once `fastbrick` starts, do not
> interrupt power or USB after its 10-second grace period. A bad interruption on
> `cronos` can be unrecoverable.

---

## Phase A — factory Echo Show 5 (stock Fire OS)

You have a reset Echo with Fire OS, no adb, no TWRP.

### A1. Get the artifacts

```sh
./install/download.sh
```

This fetches the LineageOS ROM and GApps into `install/dl/`, verifies the ROM checksum,
and tries to build `app/out/EchoClock.apk`. It also warns if the amonet bundle is missing.

If the bundle is missing, download `amonet-cronos-v2.0.1.zip` from the XDA thread and place
it at:

```
install/dl/amonet-cronos-v2.0.1.zip
```

If the APK was not built (no JDK/SDK), get `EchoClock.apk` from
[GitHub Releases](https://github.com/konsumer/echoclock/releases/latest) and save it as
`app/out/EchoClock.apk`.

### A2. Enter fastboot

1. Unplug USB.
2. Power the Echo off (hold the privacy/power button).
3. Connect the AC charger.
4. Hold **Volume Down + Volume Up + Mute** until the screen shows `=> FASTBOOT mode...`.
5. Connect the micro-USB cable to the Linux/Windows host.

You do **not** need adb, USB debugging, or a Fire OS developer option for the unlock;
amonet talks to fastboot directly.

Confirm the host sees it:

```sh
fastboot devices
```

The serial shown is the one to use below.

### A3. Unlock and flash

Preview first; this touches nothing:

```sh
./install/flash-lineageos.sh -n -s <serial>
```

Then run it:

```sh
./install/flash-lineageos.sh -s <serial>
```

What happens:

- verifies `fastboot getvar product` is `cronos`;
- Linux/Windows: runs amonet's `fastbrick.sh` interactively — read its warnings, type `YES`;
- macOS: sends the same `fastbrick.img` payload with the pyusb client (no `fastbrick` prompt);
- waits for the device to finish (up to 10 minutes; it can look black/garbled, show
  `USB Transmission OK`, and take a few minutes to boot TWRP);
- boots TWRP, wipes system/cache/dalvik, formats data;
- pushes and sha256-verifies the ROM + GApps, installs both, reboots with
  `twrp reboot system`.

This erases the device, including internal storage. If TWRP does not appear automatically,
boot it with **Volume Up only** while connecting power. The script prints manual TWRP
recovery instructions if its `twrp` CLI is unavailable.

> **macOS:** the flasher automatically uses `install/fbflash.py` (pyusb) for the unlock.
> It needs Python 3 and libusb (`brew install libusb`); first run creates
> `install/.venv` and installs pyusb. The plan prints `unlock : pyusb client (macOS)`.
> If you already have the **patched amonet fastboot** from a previous unlock, you can
> force the vendor path instead:
> ```sh
> ./install/flash-lineageos.sh --fastboot /path/to/patched/fastboot -s <serial>
> ```
> Stock/platform-tools fastboot will not work. See [`install/MACOS.md`](MACOS.md).

### A4. First boot and adb

Leave it alone for **5–10 minutes**. Do not pull power when it looks stuck.

Then:

1. Unlock the screen. After the unlock, the **privacy button acts as the power button** and
   wakes the screen — volume will not.
2. Settings → About tablet → tap **Build number** 7 times.
3. Developer options → enable **USB debugging**.
4. Connect USB and accept the host-key prompt.

```sh
adb devices        # must list the device as "device"
adb shell getprop ro.product.device          # cronos
adb shell getprop ro.build.version.sdk       # 30
adb shell getprop ro.product.cpu.abilist     # armeabi-v7a,armeabi
```

### A5. Provision

```sh
./install/provision.sh -s <serial>
```

This installs EchoClock, pushes `config/config.json` and any `faces/*` to
`/sdcard/EchoClock/`, applies kiosk display settings, grants permissions, whitelists it from
doze, and sets it as HOME.

If you already customized `/sdcard/EchoClock/config.json`, back it up first — provision
overwrites it with `config/config.json` from the repo.

### A6. Verify

- EchoClock is the home screen; **Home** returns to it.
- Swipe left/right changes faces; **long-press** opens face settings.
- **Tap** opens the app drawer; a face's own buttons still work.
- `adb shell logcat -s EchoClock` shows no repeating errors.
- Camera opens from the app drawer (requires GApps, installed in A3).

---

## Phase B — already unlocked / TWRP reachable

Use this when the bootloader is already unlocked but you want LineageOS + GApps installed
or reinstalled.

### B1. Boot TWRP

1. Unplug USB.
2. Power the Echo off.
3. Hold **Volume Up only** while connecting power.
4. Connect USB and confirm adb sees it:

```sh
adb devices
```

Some TWRP builds expose the serial as `recovery`; either state is fine.

### B2. Flash

Preview:

```sh
./install/flash-lineageos.sh --skip-unlock -n -s <serial>
```

Run:

```sh
./install/flash-lineageos.sh --skip-unlock -s <serial>
```

`--skip-unlock` means “amonet already ran; do not touch the bootloader.” The script still
wipes system/cache/dalvik, formats data, verifies the zips, flashes ROM then GApps, and
reboots.

**Destructive:** this erases internal storage. Copy anything you need off the device first:

```sh
adb pull /sdcard/EchoClock ./EchoClock-backup
```

### B3. Finish

First boot takes 5–10 minutes, then enable USB debugging and run Phase C3.

If TWRP has no `twrp` command-line interface, the script prints the manual TWRP steps
(Format Data → Advanced Wipe → install ROM → install GApps → Format Data → reboot).

---

## Phase C — LineageOS running, EchoClock not installed

You already have LineageOS 18.1 (API 30) booted. Do **not** flash anything.

### C1. Enable adb

Settings → About tablet → tap **Build number** 7 times → Developer options → **USB
debugging**. Connect USB and accept the RSA prompt.

```sh
adb devices                                  # "device", not "unauthorized"
adb shell getprop ro.product.device          # cronos
adb shell getprop ro.build.version.sdk       # 30
```

### C2. Get the APK

Either download the release APK from
[GitHub Releases](https://github.com/konsumer/echoclock/releases/latest) and save it as
`app/out/EchoClock.apk`, or build it:

```sh
bash app/build.sh
```

`./install/download.sh` also attempts the build when JDK 17 + Android SDK are available.

### C3. Provision

```sh
./install/provision.sh -s <serial>
```

Provision is idempotent: system settings are re-applied, `adb install -r` upgrades the
app, and `config.json`/`faces` are pushed. It does **not** wipe installed apps or user
data.

### C4. Verify

Same checks as Phase A6.

---

## Phase D — upgrades

### D1. Upgrade only the EchoClock app

The release APK is signed with the repo's committed key, so it installs over older
releases:

```sh
adb install -r app/out/EchoClock.apk
```

App data is preserved. `config.json` and faces live in `/sdcard/EchoClock/`, not in app
data, so they are untouched.

For a downgrade:

```sh
adb install -r -d app/out/EchoClock.apk
```

If Android reports a signature mismatch, the installed copy came from a different key:

```sh
adb uninstall org.echoclock
adb install app/out/EchoClock.apk
```

`/sdcard/EchoClock/` survives an uninstall.

### D2. Upgrade config or faces only

Push only what changed:

```sh
adb push config/config.json /sdcard/EchoClock/config.json
adb push faces/. /sdcard/EchoClock/faces/
adb shell am force-stop org.echoclock
adb shell am start -n org.echoclock/.HomeActivity
```

A face directory merges into `/sdcard/EchoClock/faces/<id>/`; files with the same name are
overwritten. User faces with the same id override bundled ones.

Do **not** run `provision.sh` just to push files if you have on-device edits: it overwrites
`/sdcard/EchoClock/config.json` with the repo's copy.

### D3. Upgrade LineageOS / GApps (destructive)

The pinned versions live in [`install/config.env`](config.env). To move to a newer ROM:

1. Update `ROM_TAG`, `ROM_FILE`, `ROM_URL`, `ROM_SHA256` (and GApps versions if the ROM
   thread says so).
2. Download and verify:

   ```sh
   ./install/download.sh --force
   ```

3. Back up anything on internal storage:

   ```sh
   adb pull /sdcard/EchoClock ./EchoClock-backup
   ```

4. Boot TWRP (**Volume Up only** while connecting power).
5. Flash with the unlock step skipped:

   ```sh
   ./install/flash-lineageos.sh --skip-unlock -s <serial>
   ```

6. Wait 5–10 minutes for first boot, enable USB debugging, then run Phase C3.

This wipes data, system, and internal storage. It is a clean install, not an in-place
upgrade. Re-provision afterwards because the APK and `/sdcard/EchoClock/` are gone.

### D4. `setup.sh` shortcut

`install/setup.sh` wraps download + flash + provision and skips flashing when the attached
device already reports LineageOS API ≥ 30:

```sh
./install/setup.sh -s <serial>                    # from LineageOS, no flash
./install/setup.sh -s <serial> --stage provision  # provision only
./install/setup.sh -s <serial> --force-flash      # reflash even if LineageOS is present
```

For a stock device, the `all` stage now waits up to 10 minutes after flashing for
LineageOS to boot and adb to appear. Enable **USB debugging** when the home screen
appears; if adb is not up after 10 minutes it prints the flash-only result and exits,
and you can finish later with:

```sh
./install/setup.sh -s <serial> --stage provision
```

---

## Phase E — reset or re-provision an existing device

### E1. Re-provision without wiping

Fix display settings, reinstall the APK, re-push config/faces, or restore EchoClock as
HOME:

```sh
./install/provision.sh -s <serial>
```

Idempotent and non-destructive, except that it overwrites `/sdcard/EchoClock/config.json`
with the repo copy. Back up first if you edited it on the device.

### E2. Factory-reset a stock Echo without Settings

If the Echo is still on Fire OS and you cannot reach Settings:

1. Leave it powered on and connected to the charger.
2. Hold **Mute (Microphones & Camera Disable/Enable) + Volume Down** for about 15 seconds,
   until the Amazon logo appears.
3. Release and follow the on-screen reset/setup prompts.

This erases the device. If you plan to sell or give it away, also deregister it in the Alexa
app. This is a stock-Fire-OS path only. On an unlocked `cronos`, **Mute + Volume Down at
power-on is USBDL**, not recovery — use TWRP instead (next section).

### E3. Factory-reset LineageOS without Settings

Boot TWRP (**Volume Up only** while connecting power), then Wipe → **Format Data**. Or use
Settings → System → Reset options → **Erase all data**. This erases `/sdcard/EchoClock/`.
Boot LineageOS, enable USB debugging, then run Phase C3.

### E4. Start completely clean

Flash LineageOS again with Phase B, then provision. The flasher's `format data` and wipes
remove the app, its config, and all internal storage.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `provision.sh` says “device is in fastboot, not a booted Android system” | Wrong phase. Unlock/flash first (Phase A/B), then boot LineageOS and enable USB debugging. |
| `fastbrick.sh` prints `Sending payload, this might take a few attempts...` and never advances | On Linux/Windows it is retrying failed `fastboot flash brick` attempts forever, usually because a stock host fastboot was used or replaced the bundle's patched binary. On macOS you should see `unlock : pyusb client (macOS)` instead, not this loop. |
| `FAILED (remote: 'the command you input is restricted on locked hw')` | A stock/platform-tools fastboot was used. The exploit needs amonet's patched `bin/fastboot`, or the macOS pyusb client. Do not replace the bundle binary with host fastboot. |
| macOS: `no fastboot device found`, `pyusb` import errors, or `USB write failed` | Install libusb (`brew install libusb`) and let the script create `install/.venv`. Power-cycle the Echo, re-enter `=> FASTBOOT mode...`, and retry. See [`install/MACOS.md`](MACOS.md). |
| `fastboot devices` lists the Echo, but `getvar product` times out | Wedged fastboot (often after an aborted download). Power-cycle the Echo, re-enter `=> FASTBOOT mode...`, and retry. On macOS the unlock uses pyusb, not host fastboot. |
| `no adb device in 'device' state` | Enable USB debugging, use a data cable, accept the RSA prompt. Check `adb devices`. |
| `device unauthorized` | Accept the “Allow USB debugging?” prompt on the Echo. Revoke and re-authorize if it never appears. |
| `device did not finish booting (sys.boot_completed != 1)` | Unlock the screen after first boot and retry provisioning. First boot can take 10 minutes. |
| `fastboot` cannot find the device | Re-enter `=> FASTBOOT mode...` with **Volume Down + Volume Up + Mute**; try another port/cable; on Windows install the Kindle Fire / Google USB driver. |
| TWRP does not appear after unlock | Power off, then hold **Volume Up only** while connecting power. If it still fails, use the manual fallback printed by the flasher and the XDA thread. |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | You are installing an arm64 APK. `cronos` is 32-bit; use an `armeabi-v7a` APK. EchoClock itself is fine. |
| Flash fails after TWRP is already up (push, sha256, or install error) | Re-run only the TWRP phase: `./install/flash-lineageos.sh --skip-unlock -s <serial>`. It re-wipes and reflashes; the unlock is untouched. |
| Device stays in TWRP after flashing | `adb shell twrp reboot system`. Bare `twrp reboot` has no target and may not reboot. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / signature mismatch | Installed app used a different signing key. `adb uninstall org.echoclock`, then install again. `/sdcard/EchoClock/` is preserved. |
| `adb install` fails with a blank error and the device disappears | USB dropped during the install (or LineageOS rebooted). Reconnect USB, wake the screen, confirm `adb devices`, then re-run `./install/provision.sh -s <serial>`. |
| `adb install` reports no space | 1 GB device. Remove unused `apps/*.apk` from the install set or uninstall unused packages. |
| Provisioning succeeds but the clock is not HOME | Re-run `./install/provision.sh -s <serial>`; confirm `cmd package set-home-activity org.echoclock/.HomeActivity` succeeded. |
| Screen is black/garbled during `fastbrick` | Expected during the exploit. Do not unplug, do not interrupt. Wait up to 5 minutes. |
| Speaker goes silent | Never run `dumpsys media.audio_flinger` on this device — it crashes the vendor HAL. Reboot. See [`docs/GOTCHAS.md`](../docs/GOTCHAS.md). |
| Device will not boot at all | Stop. Read the XDA thread before trying recovery. Never flash `preloader`, `lk`, or `tee`; `cronos` normally has no recoverable BROM USBDL. |

## What each script needs

| Script | Needs | Changes |
|---|---|---|
| `install/download.sh` | `curl`, `unzip`, checksum tool | fills `install/dl/`, may build `app/out/EchoClock.apk` |
| `install/flash-lineageos.sh` | adb + fastboot; amonet bundle; ROM/GApps zips | bootloader unlock (amonet), wipes, flashes ROM + GApps |
| `install/provision.sh` | booted Android + adb | installs APK, pushes config/faces, kiosk settings, HOME, permissions |
| `install/setup.sh` | same as above | runs download → flash → provision; use `--stage` to stop at a phase |

All scripts accept `-n`/`--dry-run`; `flash-lineageos.sh` and `setup.sh` accept `-y`/`--yes`
for non-interactive runs. Never pass `--yes` to the unlock while you are still reading the
warnings.
