#!/usr/bin/env python3
"""Minimal fastboot client for amonet's oversized 'brick' payload.

Stock fastboot refuses an image larger than the device's max-download-size
(114294812 > 114294784). This speaks the fastboot USB protocol directly and
sends the raw image in one download, which is what the amonet LK exploit
expects. The device may watchdog-reset after the transfer; that is success.

Requires: pyusb + libusb. The installer creates a venv and installs pyusb on
first use if it is missing.

Usage:
    fbflash.py IMAGE PARTITION [--read-timeout SECONDS]
"""
import sys
import time

import usb.core
import usb.util

CHUNK = 1 << 20  # 1 MiB
USB_TIMEOUT_MS = 30000


def find_fastboot(serial=None):
    for dev in usb.core.find(find_all=True):
        try:
            if serial and dev.serial_number != serial:
                continue
            for cfg in dev:
                for intf in cfg:
                    if (intf.bInterfaceClass == 0xFF
                            and intf.bInterfaceSubClass == 0x42
                            and intf.bInterfaceProtocol == 0x03):
                        return dev
        except usb.core.USBError:
            pass
    return None


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    image, part = sys.argv[1], sys.argv[2]
    read_timeout = 120
    if "--read-timeout" in sys.argv:
        read_timeout = int(sys.argv[sys.argv.index("--read-timeout") + 1])
    serial = None
    if "--serial" in sys.argv:
        serial = sys.argv[sys.argv.index("--serial") + 1]

    with open(image, "rb") as fh:
        data = fh.read()
    size = len(data)
    print(f"image={image} size={size} (0x{size:x}) partition={part}", flush=True)

    dev = find_fastboot(serial)
    if dev is None:
        sys.exit("no fastboot device found (is it in '=> FASTBOOT mode...'?)")
    print(f"device {dev.idVendor:04x}:{dev.idProduct:04x}", flush=True)

    dev.set_configuration()
    intf = dev.get_active_configuration()[(0, 0)]
    usb.util.claim_interface(dev, 0)
    ep_out = usb.util.find_descriptor(
        intf, custom_match=lambda e: usb.util.endpoint_direction(e.bEndpointAddress)
        == usb.util.ENDPOINT_OUT)
    ep_in = usb.util.find_descriptor(
        intf, custom_match=lambda e: usb.util.endpoint_direction(e.bEndpointAddress)
        == usb.util.ENDPOINT_IN)

    def send(payload):
        ep_out.write(payload, timeout=USB_TIMEOUT_MS)

    def read_resp(seconds):
        buf = b""
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                buf += bytes(ep_in.read(4096, timeout=1000))
            except usb.core.USBTimeoutError:
                continue
            except usb.core.USBError as exc:
                print(f"USB read ended: {exc}", flush=True)
                break
            if b"OKAY" in buf or b"FAIL" in buf:
                break
        return buf

    # Pre-flight: LK must answer cleanly before we start a 114 MB transfer.
    send(b"getvar:max-download-size")
    resp = read_resp(5)
    print(f"preflight getvar: {resp!r}", flush=True)
    if not resp.startswith(b"OKAY"):
        sys.exit("device is not answering getvar; reset it and re-enter fastboot")

    # Fastboot sizes are hexadecimal. Hex encoding is what makes the oversized
    # download work; decimal 'download:114294812' is misparsed by LK.
    cmd = b"download:%08x" % size
    print(f"sending {cmd.decode()} ...", flush=True)
    send(cmd)
    resp = read_resp(10)
    print(f"download resp: {resp!r}", flush=True)
    if not resp.startswith(b"DATA"):
        sys.exit(f"device refused download: {resp!r}")
    allowed = int(resp[4:12], 16)
    if size > allowed:
        sys.exit(f"device only allows {allowed} bytes (image is {size})")

    print("transmitting payload ...", flush=True)
    sent = 0
    t0 = time.time()
    while sent < size:
        try:
            sent += ep_out.write(data[sent:sent + CHUNK], timeout=USB_TIMEOUT_MS)
        except usb.core.USBError as exc:
            if sent >= size - CHUNK:
                print(f"device reset at end of payload (expected): {exc}", flush=True)
                break
            sys.exit(f"USB write failed after {sent}/{size} bytes: {exc}")
        if sent % (16 << 20) < CHUNK:
            print(f"  {sent}/{size} ({100 * sent // size}%)", flush=True)
    print(f"sent {sent} bytes in {time.time() - t0:.1f}s", flush=True)

    resp = read_resp(60)
    print(f"download complete resp: {resp!r}", flush=True)
    if b"FAIL" in resp:
        sys.exit(f"download failed: {resp!r}")

    print(f"sending flash:{part} ...", flush=True)
    try:
        send(b"flash:%s" % part.encode())
    except usb.core.USBError as exc:
        print(f"device reset while sending flash: {exc}", flush=True)
        return 0
    resp = read_resp(read_timeout)
    if resp:
        print(f"flash resp: {resp!r}", flush=True)
    else:
        print("flash resp: (none — device reset, expected on success)", flush=True)
    usb.util.release_interface(dev, 0)
    usb.util.dispose_resources(dev)
    print("done", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
