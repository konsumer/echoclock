# SETUP — install EchoClock

The **step-by-step, phase-by-phase guide is [`install/README.md`](../install/README.md)**.
Start there whether your Echo is stock, already unlocked, already running LineageOS, or
being upgraded.

This page is the short contract: what the target is, which host can do what, and the warnings
that must not get lost. Read [`docs/GOTCHAS.md`](GOTCHAS.md) before touching hardware.

> **Parts:** **1 · SETUP.md (this page)** — [2 · USAGE.md](USAGE.md) — 3 · the APK (GitHub
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

## Host support

- **Bootloader unlock:** Windows or Linux only. amonet's XDA instructions do not support
  macOS, and macOS's USB stack can wedge `fastbrick` / `fastboot getvar`. On macOS, use a
  Linux VM with USB passthrough, a Raspberry Pi, or a Windows PC.
- **After the unlock:** macOS is fine for TWRP/`adb`, building the APK, and provisioning.
  `brew install android-platform-tools coreutils`.

The unlock bundle (`amonet-cronos-v2.0.1.zip`) is distributed only on the
[XDA thread](https://xdaforums.com/t/unlock-root-twrp-unbrick-amazon-echo-show-5-2nd-gen-2021-cronos.4772596/)
(login required). Put it at `install/dl/amonet-cronos-v2.0.1.zip`.

Stock devices must be on **Fire OS 6.5.7.0 or newer**; amonet checks the build.

> ### ⚠️ Read this first
>
> - **Anti-rollback is a permanent hardware fuse.** Never flash a `preloader`, `lk` or `tee`
>   image older than what is already installed — that is an **unrecoverable brick**. Before
>   unlocking a stock unit, confirm **Fire OS 6.5.7.0 or newer** in Settings. `fastbrick`
>   prints the detected LK build and selected payload; stop if it reports “Device mismatch”.
> - The device normally has **no BROM USBDL access**, so many bricks can never be undone.
> - Unlocking **wipes the device** (all data). `install/flash-lineageos.sh` also wipes system,
>   cache, dalvik and data when it flashes.
> - The exploit intentionally corrupts a partition to force BROM entry. The screen going
>   **black or garbled is normal** during `fastbrick` — **do not unplug** or interrupt it after
>   the 10-second grace period.
> - `boot` images have **no** anti-rollback, so those are always safe to reflash/restore.
> - The scripts never flash `preloader`, `lk`, or `tee` themselves.

## Where the steps are

| Phase | Guide |
|---|---|
| Factory-reset Echo on Fire OS | [Phase A](../install/README.md#phase-a--factory-echo-show-5-stock-fire-os) |
| Already unlocked / TWRP reachable | [Phase B](../install/README.md#phase-b--already-unlocked--twrp-reachable) |
| LineageOS running, EchoClock missing | [Phase C](../install/README.md#phase-c--lineageos-running-echoclock-not-installed) |
| Upgrading the app, config, faces, or ROM | [Phase D](../install/README.md#phase-d--upgrades) |
| Reset / re-provision | [Phase E](../install/README.md#phase-e--reset-or-re-provision-an-existing-device) |
| Stuck device or script refusal | [Troubleshooting](../install/README.md#troubleshooting) |

After installation, the clock's day-to-day behavior is in [`USAGE.md`](USAGE.md); face
programming is in [`CUSTOMIZE.md`](CUSTOMIZE.md).
