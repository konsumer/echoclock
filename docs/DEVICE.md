# Device & firmware facts (verified)

Everything here was confirmed against primary sources (repos, XDA threads, release
metadata, and artifact inspection) — not copied from a guide without checking.

## Echo Show 5 (2nd gen, 2021)

| Property | Value |
|---|---|
| Codename | `cronos` (product `AEOCN`) |
| Model | C76N8S / H23K37 family; 2021 release |
| SoC | MediaTek **MT8163** (quad Cortex-A53, armv8-a, Mali-450) |
| Stock OS | Fire OS 7.1 (Android 7.1 base) |
| Screen | 5.5", **960x480** landscape |
| RAM | 1 GB |
| USB | micro-USB (data-capable cable required) |
| Buttons | Volume Up/Down, Mute. **Mute = POWER** after unlock; Mute wakes the screen |
| Partitions | pre-Treble: no `vbmeta`/`dtbo`/`vendor`/`nvram`. `boot` = `/dev/block/mmcblk0p9`; `/dev/block/by-name/` does **not** exist |
| Boot modes (post-unlock) | Vol-Down + power = hacked fastboot; Vol-Up + power = TWRP; Mute+Vol-Down + power = USBDL (unbrick) |

### Userspace is 32-bit

The device tree (`android_device_amazon_mt8163-common/BoardConfigCommon.mk`) sets:

```
TARGET_ARCH := arm            # 32-bit userspace
TARGET_ARCH_VARIANT := armv8-a
TARGET_CPU_VARIANT := cortex-a53
TARGET_KERNEL_ARCH := arm64   # 64-bit kernel, 32-bit userspace
TARGET_BOARD_PLATFORM := mt8163
```

So: the ROM, **GApps**, and any native code we ship must be **ARM (32-bit, `armeabi-v7a`)**,
not ARM64. This is a classic trap — the SoC is 64-bit capable.

## LineageOS port

Unofficial **LineageOS 18.1 (Android 11)** for `cronos`, by bengris32 / R0rt1z2 / FieryFlames,
distributed at **https://github.com/amazon-oss/releases**. Latest at time of writing is
`lineage-18.1-cronos-v0.4` → `lineage-18.1-20260904-UNOFFICIAL-cronos.zip`
(sha256 `4c355998061a454792128d4b730932b47ed05a3d2a6d2628599218f44cc84678`).

- Works: touch, WiFi (2.4GHz safest), Bluetooth (incl. A2DP **sink**), speaker,
  brightness, always-on display, **camera** (once GApps is present — the AOSP
  `com.android.camera2` app is installed and functional; launch it from the app drawer).
- Limited: SELinux **permissive**; **deep sleep intentionally disabled**;
  battery always reports **100%**.
- Verified installed on-device: `com.android.camera2` (camera), `com.android.deskclock` (system
  Clock/alarms; `AlarmManager.getNextAlarmClock()` works), `com.android.providers.calendar`
  (calendar events available once an account is signed in). No root.
- GApps: **MindTheGapps 11.0.0 arm** (LineageOS-team-recommended, lean; essential on 1 GB RAM).
  Flashed right after the ROM, before first boot is fine; the installer flashes ROM then GApps.

## Bootloader unlock — amonet

`amonet` (R0rt1z2, https://github.com/R0rt1z2/amonet, branch `mt8163-cronos`) is a MediaTek
BROM/Preloader exploit. The distributable bundle is hosted **only on the XDA thread** (login
gated), so it cannot be auto-downloaded — place it in `install/dl/`:

- Thread: https://xdaforums.com/t/unlock-root-twrp-unbrick-amazon-echo-show-5-2nd-gen-2021-cronos.4772596/
- File: `amonet-cronos-v2.0.1.zip` (current release at time of writing)

The bundle ships cross-platform `fastbrick.sh` / `.bat` / `.ps1`, `backup.*`, `restore.*`,
`boot-fastboot.*`, `boot-recovery.*`, and `bin/` (payload blobs + firmware images). It also
installs **TWRP**.

### Safety

- **Anti-rollback is a permanent fuse.** Never flash a `preloader` / `lk` / `tee` older than
  what is installed — that is an unrecoverable brick. `fastbrick` version-matches the firmware
  image automatically; confirm it prints "Fire OS 6.5.7.0 or newer" on an updated unit.
- The device **lacks BROM USBDL** access in the normal case, so bricks can be permanent.
- The exploit intentionally corrupts a partition to force BROM ("fastbrick") — the screen
  going black/garbled is normal. Do not unplug.
- `boot` images have **no** anti-rollback → safe to reflash/restore.

## Sources

- ROM: https://github.com/amazon-oss/releases (tag `lineage-18.1-cronos-v0.4`)
- Device tree: https://github.com/amazon-oss/android_device_amazon_cronos and
  `android_device_amazon_mt8163-common`
- Unlock+TWRP: XDA thread 4772596 (cronos)
- ROM thread: XDA thread 4772598 (cronos)
- GApps: https://github.com/MindTheGapps/11.0.0-arm
- Prior-art playbooks: `dallanwagz/echo-show-jailbreak`, `revjmoney/oHce-checkmate-3ch0`
