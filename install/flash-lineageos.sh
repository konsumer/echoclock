#!/usr/bin/env bash
# EchoClock — unlock (amonet) + flash LineageOS 18.1 + GApps on an Echo Show 5
# (2nd gen, cronos). GUIDED and DESTRUCTIVE.
#
# This script never flashes preloader/lk/tee itself; the amonet bundle performs
# the bootloader change as part of its unlock, under its own prompts.
#
# Run ./install/download.sh first, and put the XDA-only amonet bundle at
# install/dl/<AMONET_FILE> (see install/config.env).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# shellcheck source=install/lib.sh
source "$HERE/lib.sh"
# shellcheck source=install/config.env
source "$HERE/config.env"

DRY_RUN=0
SKIP_UNLOCK=0
USE_SIDELOAD=0
ASSUME_YES=${ASSUME_YES:-0}

AMONET_ZIP="$HERE/dl/$AMONET_FILE"
ROM_ZIP="$HERE/dl/$ROM_FILE"
GAPPS_ZIP="$HERE/dl/$GAPPS_FILE"
SDCARD=/sdcard

TMP_DIRS=""
cleanup() {
  local d
  for d in $TMP_DIRS; do [[ -n "$d" ]] && rm -rf "$d"; done
}
trap cleanup EXIT

usage() {
  cat <<EOF
EchoClock flasher — unlock the bootloader, then flash LineageOS 18.1 + GApps via TWRP.

Usage: ./install/flash-lineageos.sh [options]

Options:
  -n, --dry-run     print the plan and exit; touch nothing
  -y, --yes         auto-confirm prompts (non-interactive; ASSUME_YES=1 also works)
  -s, --serial S    target exactly this adb serial (USB serial or host:port); required
                    when more than one device is attached
      --skip-unlock use when the device is already unlocked / already in TWRP
      --sideload    flash with 'adb sideload' instead of pushing zips to /sdcard
  -h, --help        show this help

Steps:
  1. sanity-check adb/fastboot and install/dl/$AMONET_FILE
  2. refusal-safe identity check: ro.product.device + ro.product.name == $CODENAME
     and ro.boot.hardware == mt8163
  3. unlock: extract the amonet bundle, swap any Linux-ELF fastboot/adb for the
     host ones (macOS), run fastbrick.sh interactively (loud anti-rollback warning,
     typed YES required)
  4. TWRP: 'twrp wipe system/cache/dalvik', 'twrp format data', then push the zips
     (or 'twrp sideload' + 'adb sideload'), verify sha256 ON DEVICE, 'twrp install'
     the ROM then GApps, 'twrp reboot'
  5. if the TWRP CLI is unavailable, prints the manual XDA fallback

Notes:
  * 'twrp format data' erases internal storage, so the zips are pushed AFTER the
    wipes (or streamed with adb sideload) — never lost mid-flash.
  * This script never flashes preloader/lk/tee.
  * First boot after flashing takes 5-10 minutes; then enable USB debugging and
    run ./install/provision.sh.

Environment overrides: ALLOW_UNKNOWN_DEVICE=1 skips the identity refusal (dangerous),
ASSUME_YES=1 acts like --yes.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    -n|--dry-run) DRY_RUN=1 ;;
    -y|--yes) ASSUME_YES=1 ;;
    -s|--serial) ec_set_serial "${2:-}"; shift ;;
    --serial=*) ec_set_serial "${1#--serial=}" ;;
    --skip-unlock) SKIP_UNLOCK=1 ;;
    --sideload) USE_SIDELOAD=1 ;;
    *) die "unknown argument: $1 (try --help)" ;;
  esac
  shift
done

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

run() { # execute a mutating command, or print it in --dry-run
  if [[ "$DRY_RUN" -eq 1 ]]; then
    info "[dry-run] $*"
  else
    "$@"
  fi
}

twrp_candidates() { adb devices | awk 'NR > 1 && ($2 == "device" || $2 == "recovery") { print $1 }'; }

twrp_available() { # SERIAL
  local s=$1
  adb -s "$s" shell 'command -v twrp >/dev/null 2>&1 || [ -x /sbin/twrp ]' >/dev/null 2>&1
}

wait_for_twrp() { # TIMEOUT_SECONDS -> serial on stdout
  local timeout=$1 waited=0 s
  while [[ "$waited" -lt "$timeout" ]]; do
    s=$(twrp_candidates | sed -n '1p')
    if [[ -n "$s" ]] && twrp_available "$s"; then
      printf '%s\n' "$s"
      return 0
    fi
    sleep 5
    waited=$((waited + 5))
  done
  return 1
}

wait_adb_state() { # SERIAL STATE TIMEOUT
  local s=$1 want=$2 timeout=$3 waited=0 st
  while [[ "$waited" -lt "$timeout" ]]; do
    st=$(adb -s "$s" get-state 2>/dev/null || true)
    [[ "$st" == "$want" ]] && return 0
    sleep 5
    waited=$((waited + 5))
  done
  return 1
}

twrp_cmd() { # SERIAL ARGS...
  local s=$1
  shift
  run adb -s "$s" shell twrp "$@"
}

device_sha256() { # SERIAL REMOTE_PATH -> hash on stdout ('' if no tool)
  local s=$1 f=$2
  adb -s "$s" shell "sha256sum '$f' 2>/dev/null || toybox sha256sum '$f' 2>/dev/null || busybox sha256sum '$f' 2>/dev/null" 2>/dev/null \
    | tr -d '\r' | awk '{print $1}'
}

verify_on_device() { # SERIAL REMOTE_PATH EXPECTED_SHA
  local s=$1 rp=$2 exp=${3:-} actual want
  info "verifying sha256 on device: $rp"
  actual=$(device_sha256 "$s" "$rp")
  if [[ -z "$actual" ]]; then
    warn "no sha256sum/toybox/busybox on device — skipping on-device verification (host copy is already verified)"
    return 0
  fi
  want=$(printf '%s' "$exp" | tr 'A-Z' 'a-z')
  if [[ -z "$want" ]]; then
    warn "no pinned checksum for $(basename "$rp"); on-device sha256: $actual"
  elif [[ "$actual" != "$want" ]]; then
    die "on-device sha256 mismatch for $rp
       expected: $want
       actual:   $actual
     refusing to flash this file"
  else
    ok "on-device sha256 verified: $rp"
  fi
  return 0
}

print_manual() {
  cat >&2 <<EOF

MANUAL FALLBACK (finish from the device's TWRP screen):
  1. Wipe > Format Data (type yes), then Wipe > Advanced Wipe > System, Cache,
     Dalvik > Swipe to wipe.
  2. Copy the zips back (Format Data erased internal storage):
       adb push "$ROM_ZIP" /sdcard/
       adb push "$GAPPS_ZIP" /sdcard/
  3. Install > $ROM_FILE (Add more zips > $GAPPS_FILE) > Swipe to confirm >
     Reboot System.
  Or stream them instead:
       adb shell twrp sideload && adb sideload "$ROM_ZIP"
       adb shell twrp sideload && adb sideload "$GAPPS_ZIP"

  Unlock bundle / full guide: $AMONET_PAGE
  Never flash preloader/lk/tee by hand.
EOF
}

# ---------------------------------------------------------------------------
# preflight
# ---------------------------------------------------------------------------
info "EchoClock flasher — repo: $ROOT"

if [[ "$DRY_RUN" -eq 1 ]]; then
  for t in adb fastboot unzip; do
    command -v "$t" >/dev/null 2>&1 || warn "$t not on PATH (dry-run: continuing)"
  done
else
  ensure_platform_tools
  need_cmd adb    "install platform-tools or set ANDROID_HOME"
  need_cmd fastboot "install platform-tools or set ANDROID_HOME"
  need_cmd unzip
fi

# artifacts
for pair in "ROM:$ROM_ZIP:${ROM_SHA256:-}" "GApps:$GAPPS_ZIP:${GAPPS_SHA256:-}"; do
  label=${pair%%:*}; rest=${pair#*:}; file=${rest%%:*}; sha=${rest#*:}
  if [[ -f "$file" ]]; then
    verify_sha256 "$file" "$sha"
  elif [[ "$DRY_RUN" -eq 1 ]]; then
    warn "$label zip missing: $file (run ./install/download.sh before flashing)"
  else
    die "$label zip missing: $file — run ./install/download.sh first"
  fi
done

# current device state
SERIAL=""
STAGE=""
CAND=""
if command -v adb >/dev/null 2>&1; then
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    CAND="$ANDROID_SERIAL"
  else
    CAND=$(twrp_candidates | sed -n '1p')
  fi
fi

if [[ -n "$CAND" ]] && twrp_available "$CAND"; then
  SERIAL="$CAND"
  STAGE=twrp
  ok "device $SERIAL is already in TWRP — unlock step will be skipped"
elif [[ "$SKIP_UNLOCK" -eq 1 ]]; then
  SERIAL="$CAND"
  STAGE=twrp
  info "--skip-unlock: expecting TWRP to be (or become) reachable"
else
  STAGE=unlock
fi

if [[ "$STAGE" == unlock ]]; then
  if [[ -f "$AMONET_ZIP" ]]; then
    ok "amonet bundle present: $AMONET_ZIP"
  elif [[ "$DRY_RUN" -eq 1 ]]; then
    warn "amonet bundle missing: $AMONET_ZIP (get it from $AMONET_PAGE)"
  else
    die "amonet bundle missing: $AMONET_ZIP
     It is only distributed via XDA (login required): $AMONET_PAGE
     Save it under that exact name and re-run."
  fi
  if [[ "$DRY_RUN" -eq 0 ]]; then
    SERIAL=$(adb_dev)
    assert_device_identity "$SERIAL"
  elif [[ -n "$CAND" ]]; then
    info "device $CAND present — identity would be checked (ro.product.device/name == $CODENAME, ro.boot.hardware == mt8163)"
  else
    warn "no adb device attached (plan only)"
  fi
fi

# ---------------------------------------------------------------------------
# plan + confirmation
# ---------------------------------------------------------------------------
cat >&2 <<EOF

$(printf '%sPLAN%s' "$C_BOLD" "$C_RESET")
  target      : $CODENAME (Echo Show 5 gen 2, MT8163)
  stage       : $([[ "$STAGE" == unlock ]] && echo "unlock with amonet, then TWRP" || echo "TWRP only")
  flash mode  : $([[ "$USE_SIDELOAD" -eq 1 ]] && echo "adb sideload" || echo "push + twrp install (on-device sha256 verified)")
  ROM         : $ROM_FILE
  GApps       : $GAPPS_FILE
  THIS ERASES THE DEVICE (data, system, internal storage) and CHANGES THE BOOTLOADER.
EOF

if [[ "$STAGE" == unlock ]]; then
  cat >&2 <<'EOF'

  ANTI-ROLLBACK / BRICK WARNING
  The amonet unlock patches the bootloader of this specific device. Flashing the
  wrong bootloader, unplugging during the process, or a bad bundle WILL BRICK the
  device. Never interrupt power or USB while fastbrick.sh runs.
EOF
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
  info "[dry-run] no changes made. Would run:"
  if [[ "$STAGE" == unlock ]]; then
    info "  unzip $AMONET_ZIP -> temp dir; substitute host fastboot/adb if bundle ships Linux ELF"
    info "  (cd <temp>/... && bash ./fastbrick.sh)   # interactive, typed YES"
    info "  wait for TWRP over adb"
  fi
  info "  adb -s <serial> shell twrp wipe system"
  info "  adb -s <serial> shell twrp wipe cache"
  info "  adb -s <serial> shell twrp wipe dalvik"
  info "  adb -s <serial> shell twrp format data"
  if [[ "$USE_SIDELOAD" -eq 1 ]]; then
    info "  adb -s <serial> shell twrp sideload; adb -s <serial> sideload $ROM_ZIP"
    info "  adb -s <serial> shell twrp sideload; adb -s <serial> sideload $GAPPS_ZIP"
  else
    info "  adb -s <serial> push $ROM_ZIP /sdcard/ ; verify sha256 on device ; twrp install /sdcard/$ROM_FILE"
    info "  adb -s <serial> push $GAPPS_ZIP /sdcard/ ; verify sha256 on device ; twrp install /sdcard/$GAPPS_FILE"
  fi
  info "  adb -s <serial> shell twrp reboot"
  exit 0
fi

confirm "Proceed with unlocking and flashing $SERIAL (or the attached device)?"

# ---------------------------------------------------------------------------
# stage 1: unlock with amonet
# ---------------------------------------------------------------------------
if [[ "$STAGE" == unlock ]]; then
  info "extracting $AMONET_FILE"
  TMP=$(mktemp -d "${TMPDIR:-/tmp}/echoclock-amonet.XXXXXX")
  TMP_DIRS="$TMP_DIRS $TMP"
  unzip -q -o "$AMONET_ZIP" -d "$TMP"
  FASTBRICK=$(find "$TMP" -maxdepth 3 -type f -name fastbrick.sh -print 2>/dev/null | sed -n '1p')
  [[ -n "$FASTBRICK" ]] || die "fastbrick.sh not found inside $AMONET_FILE — is this the right bundle?"

  # The bundle ships Linux binaries; substitute the host ones on macOS.
  for tool in fastboot adb; do
    f="$TMP/bin/$tool"
    [[ -f "$f" ]] || continue
    if [[ "$(uname -s)" == "Darwin" ]] && file "$f" 2>/dev/null | grep -qi 'ELF'; then
      host_tool=$(command -v "$tool" || true)
      [[ -n "$host_tool" ]] || die "bundle ships a Linux $tool and no host $tool was found on PATH"
      warn "bundle bin/$tool is a Linux ELF — substituting host $tool for macOS"
      cp -f "$host_tool" "$f"
      chmod +x "$f"
    fi
  done
  if [[ -d "$TMP/bin" ]]; then
    chmod +x "$TMP/bin"/* 2>/dev/null || true
  fi
  chmod +x "$FASTBRICK"

  info "running amonet fastbrick.sh interactively from $(dirname "$FASTBRICK")"
  ( cd "$(dirname "$FASTBRICK")" && bash ./fastbrick.sh )

  info "unlock finished — waiting up to 10 min for TWRP over adb"
  SERIAL=$(wait_for_twrp 600 || true)
  if [[ -z "$SERIAL" ]]; then
    warn "TWRP did not show up over adb"
    print_manual
    die "cannot continue automatically after unlock"
  fi
  ok "TWRP reachable at $SERIAL"
fi

# ---------------------------------------------------------------------------
# stage 2: TWRP wipe + install
# ---------------------------------------------------------------------------
[[ -n "$SERIAL" ]] || SERIAL=$(twrp_candidates | sed -n '1p')
if [[ -z "$SERIAL" ]]; then
  SERIAL=$(wait_for_twrp 120 || true)
fi
[[ -n "$SERIAL" ]] || { warn "no device in TWRP found over adb"; print_manual; die "cannot continue automatically"; }

if ! twrp_available "$SERIAL"; then
  warn "TWRP command-line interface not available on $SERIAL"
  print_manual
  die "cannot continue automatically"
fi
ok "using TWRP CLI on $SERIAL"

# Light identity guard for the --skip-unlock path (we are in recovery, where
# ro.product.name is not always populated).
HW=$(_ec_getprop "$SERIAL" ro.boot.hardware)
if [[ -n "$HW" && "$HW" != *mt8163* && "$HW" != *MT8163* ]]; then
  die "device $SERIAL reports ro.boot.hardware='$HW', not mt8163 — refusing"
fi

info "wiping system/cache/dalvik (data is formatted next; internal storage will be erased)"
twrp_cmd "$SERIAL" wipe system
twrp_cmd "$SERIAL" wipe cache
twrp_cmd "$SERIAL" wipe dalvik
twrp_cmd "$SERIAL" format data || twrp_cmd "$SERIAL" wipe data

flash_one() { # LOCAL EXPECTED LABEL -> 0 ok, 1 push/verify failed
  local lf=$1 exp=$2 label=$3 rp="$SDCARD/$(basename "$lf")"
  info "pushing $label to $rp"
  if ! adb -s "$SERIAL" push "$lf" "$rp"; then
    warn "adb push failed for $label"
    return 1
  fi
  verify_on_device "$SERIAL" "$rp" "$exp" || return 1
  info "installing $label via TWRP: $(basename "$lf")"
  twrp_cmd "$SERIAL" install "$rp"
  return 0
}

sideload_one() { # LOCAL LABEL
  local f=$1 label=$2
  info "sideloading $label: $(basename "$f")"
  twrp_cmd "$SERIAL" sideload
  wait_adb_state "$SERIAL" sideload 120 || die "device did not enter sideload mode"
  adb -s "$SERIAL" sideload "$f" || die "adb sideload failed for $label"
  wait_adb_state "$SERIAL" device 900 || wait_adb_state "$SERIAL" recovery 60 \
    || die "device did not return from sideload after $label"
  ok "$label sideloaded"
}

if [[ "$USE_SIDELOAD" -eq 1 ]]; then
  sideload_one "$ROM_ZIP" "LineageOS ROM"
  sideload_one "$GAPPS_ZIP" "GApps"
else
  if ! flash_one "$ROM_ZIP" "${ROM_SHA256:-}" "LineageOS ROM"; then
    warn "falling back to adb sideload for the ROM and GApps"
    sideload_one "$ROM_ZIP" "LineageOS ROM"
    sideload_one "$GAPPS_ZIP" "GApps"
  elif ! flash_one "$GAPPS_ZIP" "${GAPPS_SHA256:-}" "GApps"; then
    warn "falling back to adb sideload for the GApps"
    sideload_one "$GAPPS_ZIP" "GApps"
  fi
fi

info "rebooting to system"
twrp_cmd "$SERIAL" reboot

cat >&2 <<EOF

$(printf '%sDONE%s' "$C_GREEN" "$C_RESET")
  LineageOS 18.1 + GApps flashed. First boot takes 5-10 minutes.
  Then on the device: Settings > About > tap Build number 7x > Developer options >
  USB debugging, and run: ./install/provision.sh
EOF
