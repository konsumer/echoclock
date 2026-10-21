# macOS — unlocking `cronos`

macOS **works** with this repo. The installer uses a small Python USB client
(`install/fbflash.py`) instead of fastboot for the amonet unlock.

Stock Android fastboot cannot send amonet's payload: the image is 114,294,812
bytes and the device reports a 114,294,784-byte maximum. The LK exploit needs
those extra 28 bytes. Results with stock tools:

| fastboot | Result |
|---|---|
| macOS platform-tools 37 | `Could not resparse file for partition: brick` |
| macOS platform-tools 33 / 28 | `FAILED (remote: 'the command you input is restricted on locked hw')` |
| Windows patched `bin/fastboot.exe` from the bundle | works |
| Linux patched `bin/fastboot` from the bundle | works |
| **`install/fbflash.py` (this repo, macOS)** | **works** |

`fbflash.py` speaks the fastboot USB protocol directly, sends the raw image
with a hexadecimal `download:` size (the part stock fastboot gets wrong), and
treats the device resetting as success. The device watchdog-resets into TWRP.

## Requirements

- Python 3
- libusb: `brew install libusb`
- pyusb: the installer creates `install/.venv` and installs it automatically
  on first use. To do it yourself:

  ```sh
  python3 -m venv install/.venv
  install/.venv/bin/pip install pyusb
  ```

You do **not** need a patched fastboot binary or a Linux VM.

## Normal unlock on macOS

1. Power-cycle the Echo and re-enter fastboot:

   - Unplug USB.
   - Hold the privacy/power button until the screen goes off (~10 s).
   - Connect the charger, hold **Volume Down + Volume Up + Mute** until
     `=> FASTBOOT mode...`.

2. Connect USB and confirm:

   ```sh
   fastboot devices
   ```

3. Run the flasher. On macOS it selects the pyusb client automatically:

   ```sh
   ./install/flash-lineageos.sh -s <serial>
   ```

   The plan shows `unlock : pyusb client (macOS)`.

4. The device shows `USB Transferring...`, then `USB Transmission OK` with the
   transfer time/rate. It may sit there for a few minutes before booting TWRP.
   The script waits up to 10 minutes.
5. Once TWRP is up, the script wipes, pushes, sha256-verifies, and installs
   LineageOS then GApps, then reboots with `twrp reboot system`.
6. First boot takes 5–10 minutes. Then enable Developer options + **USB
   debugging** and provision:

   ```sh
   ./install/provision.sh -s <serial>
   ```

If the script fails **after** TWRP is up (for example an interrupted push or
install), it is safe to redo the TWRP phase without touching the unlock:

```sh
./install/flash-lineageos.sh --skip-unlock -s <serial>
```

If the device stays in TWRP, reboot it manually:

```sh
adb shell twrp reboot system
```

## If you have a patched fastboot binary

You can still force the vendor `fastbrick.sh` path:

```sh
./install/flash-lineageos.sh --fastboot /path/to/patched/fastboot -s <serial>
```

It must be a native macOS binary:

```sh
file /path/to/patched/fastboot        # Mach-O, not ELF/PE
/path/to/patched/fastboot --version
```

The Linux ELF and Windows `.exe` from the amonet zip cannot run on macOS.
`--fastboot` is also accepted by `install/setup.sh`, or set
`AMONET_FASTBOOT=/path/to/fastboot`.

## Docker

Docker Desktop on macOS cannot pass the Echo's USB device into a Linux
container. It only exposes USB/IP virtual root hubs, and there is no macOS
USB/IP server. Do not rely on `--device=/dev/bus/usb` there.

Docker is usable only on a **Linux host**:

```sh
docker run --rm -it --privileged \
  -v /dev/bus/usb:/dev/bus/usb -v "$PWD:/repo" -w /repo \
  ubuntu:24.04

# inside the container:
apt update && apt install -y android-tools-adb android-tools-fastboot unzip curl
./install/flash-lineageos.sh -s <serial>
```

## Linux VM fallback

If the pyusb client cannot see the device (USB permissions, hub, VM), use a
Linux VM with USB passthrough (UTM, VMware Fusion, or Parallels) and run the
bundle's patched Linux fastboot there. Attach the MediaTek Android device
(`0bb4:0c01`) to the VM. After TWRP starts, detach it and finish from macOS:

```sh
./install/flash-lineageos.sh --skip-unlock -s <serial>
./install/provision.sh -s <serial>
```

## If the Echo is stuck on “USB Transferring…”

It is not bricked. LK is waiting for the rest of a fastboot download.

1. Unplug USB.
2. Hold the privacy/power button until the screen goes off (~10 s).
3. Re-enter fastboot: charger connected, hold
   **Volume Down + Volume Up + Mute** until `=> FASTBOOT mode...`.
4. Reconnect USB and continue.

Do not send random data to clear that screen.
