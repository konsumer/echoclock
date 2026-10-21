#!/usr/bin/env bash
# EchoClock — one-shot, per-device setup.
#
#   ./install/setup.sh --serial <serial>            # download + (flash) + provision
#   ./install/setup.sh --serial <serial> --stage provision
#
# It runs, in order:
#   1. install/download.sh        fetch ROM/GApps (+ build the APK if needed)
#   2. install/flash-lineageos.sh unlock + flash LineageOS + GApps   (skipped if the
#                                 device already reports LineageOS >= API 30)
#   3. install/provision.sh       kiosk settings, install EchoClock, push app data,
#                                 set it as HOME, grant permissions
#
# Every step is idempotent: re-running against the same device is safe.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# shellcheck source=install/lib.sh
source "$HERE/lib.sh"
# shellcheck source=install/config.env
source "$HERE/config.env"

DRY_RUN=0
STAGE=all                 # all | download | flash | provision
SKIP_FLASH=0
FORCE_FLASH=0
NO_DOWNLOAD=0
ASSUME_YES=${ASSUME_YES:-0}
FASTBOOT_OVERRIDE="${AMONET_FASTBOOT:-}"

usage() {
  cat <<EOF
EchoClock setup — prepare one Echo Show 5 (2nd gen, cronos) end to end.

Usage: ./install/setup.sh [options]

Options:
  -s, --serial S    target exactly this adb serial (USB serial, or host:port for
                    network adb). Strongly recommended; required when more than one
                    device is attached.
  -y, --yes         auto-confirm prompts (forwarded to the flasher)
  -n, --dry-run     plan only; touch nothing on the device (downloads still run)
      --stage STAGE  all (default) | download | flash | provision
      --skip-flash   never unlock/flash (device already runs LineageOS)
      --force-flash  flash even if the device already looks like LineageOS
      --no-download  skip the download step
      --fastboot P   force a patched amonet fastboot for the unlock (advanced)
  -h, --help        show this help

Examples:
  ./install/setup.sh -s 0123456789ABCDEF                 # full setup over USB
  ./install/setup.sh -s 192.168.1.42:5555 --stage provision
  ./install/setup.sh -s ABC123 --skip-flash -y

Notes:
  * The amonet unlock bundle is not redistributable — put it at
    install/dl/$AMONET_FILE first (see install/config.env for the XDA page).
  * After a flash, provisioning waits up to 10 minutes for LineageOS to boot
    and adb to appear. Enable USB debugging when the device reaches the home
    screen, or run --stage provision afterwards.
  * Never flashes preloader/lk/tee.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    -s|--serial) ec_set_serial "${2:-}"; shift ;;
    --serial=*) ec_set_serial "${1#--serial=}" ;;
    -y|--yes) ASSUME_YES=1 ;;
    -n|--dry-run) DRY_RUN=1 ;;
    --stage) [[ -n "${2:-}" ]] || die "--stage requires a value"; STAGE=$2; shift ;;
    --stage=*) STAGE=${1#--stage=} ;;
    --skip-flash) SKIP_FLASH=1 ;;
    --force-flash) FORCE_FLASH=1 ;;
    --no-download) NO_DOWNLOAD=1 ;;
    --fastboot) FASTBOOT_OVERRIDE="${2:-}"; shift ;;
    --fastboot=*) FASTBOOT_OVERRIDE="${1#--fastboot=}" ;;
    *) die "unknown argument: $1 (try --help)" ;;
  esac
  shift
done

case "$STAGE" in
  all|download|flash|provision) ;;
  *) die "invalid --stage '$STAGE' (all|download|flash|provision)" ;;
esac

export ASSUME_YES
[[ -n "$FASTBOOT_OVERRIDE" ]] && export AMONET_FASTBOOT="$FASTBOOT_OVERRIDE"

stage_on() { # NAME -> is this stage requested?
  [[ "$STAGE" == "all" || "$STAGE" == "$1" ]]
}

info "EchoClock setup — device: ${ANDROID_SERIAL:-<auto>}  stage: $STAGE  dry-run: $DRY_RUN"

# ---------------------------------------------------------------------------
# 1. download artifacts (+ build APK)
# ---------------------------------------------------------------------------
if [[ "$NO_DOWNLOAD" -eq 1 ]]; then
  info "== step 1/3: download skipped (--no-download) =="
elif stage_on download; then
  info "== step 1/3: download ROM/GApps + build APK =="
  "$HERE/download.sh"
else
  info "== step 1/3: download skipped (stage=$STAGE) =="
fi

# ---------------------------------------------------------------------------
# 2. flash (unless the device already runs LineageOS)
# ---------------------------------------------------------------------------
NEED_FLASH=0
if stage_on flash && [[ "$SKIP_FLASH" -eq 0 ]]; then
  if [[ "$FORCE_FLASH" -eq 1 ]]; then
    NEED_FLASH=1
  else
    if [[ -n "${ANDROID_SERIAL:-}" ]]; then
      detect_serial=$ANDROID_SERIAL
    else
      detect_serial=$(adb_devices | sed -n '1p')
    fi
    if [[ -z "$detect_serial" ]]; then
      info "no adb device detected yet — the flasher will guide the unlock/flash step"
      NEED_FLASH=1
    else
      d_sdk=$(_ec_getprop "$detect_serial" ro.build.version.sdk || true)
      d_lin=$(_ec_getprop "$detect_serial" ro.lineage.version || true)
      if [[ -n "$d_lin" && "$d_sdk" =~ ^[0-9]+$ && "$d_sdk" -ge 30 ]]; then
        info "== step 2/3: $detect_serial already runs LineageOS ($d_lin) — skipping flash =="
        info "            (use --force-flash to flash anyway, or --stage flash)"
      else
        NEED_FLASH=1
      fi
    fi
  fi
else
  info "== step 2/3: flash skipped =="
fi

if [[ "$NEED_FLASH" -eq 1 ]]; then
  info "== step 2/3: unlock + flash LineageOS + GApps =="
  if [[ "$DRY_RUN" -eq 1 && "$ASSUME_YES" -eq 1 ]]; then
    "$HERE/flash-lineageos.sh" -n -y
  elif [[ "$DRY_RUN" -eq 1 ]]; then
    "$HERE/flash-lineageos.sh" -n
  elif [[ "$ASSUME_YES" -eq 1 ]]; then
    "$HERE/flash-lineageos.sh" -y
  else
    "$HERE/flash-lineageos.sh"
  fi
fi

# ---------------------------------------------------------------------------
# 3. provision
# ---------------------------------------------------------------------------
if stage_on provision; then
  info "== step 3/3: provision (kiosk settings, install EchoClock, set HOME) =="
  if [[ "$DRY_RUN" -eq 1 ]]; then
    "$HERE/provision.sh" -n
  else
    if [[ "$NEED_FLASH" -eq 1 ]]; then
      waited=0
      info "waiting up to 10 min for LineageOS boot + adb (enable USB debugging)"
      while ! adb_devices | grep -q .; do
        if [[ "$waited" -ge 600 ]]; then
          warn "no adb device after 10 minutes — provisioning skipped"
          warn "enable USB debugging, then run: ./install/setup.sh -s <serial> --stage provision"
          ok "setup complete for ${ANDROID_SERIAL:-the attached device} (flash only)"
          exit 0
        fi
        sleep 5
        waited=$((waited + 5))
      done
    fi
    "$HERE/provision.sh"
  fi
else
  info "== step 3/3: provision skipped (stage=$STAGE) =="
fi

ok "setup complete for ${ANDROID_SERIAL:-the attached device}"
