#!/usr/bin/env bash
# EchoClock — provision a freshly flashed Echo Show 5 (2nd gen, cronos) that has
# booted LineageOS 18.1 with USB debugging enabled.
#
# Idempotent: safe to re-run on the same device.
#
# Steps: verify codename > skip setup wizard > kiosk display settings > install
# EchoClock (+ $BUNDLED_APKS) > push config/faces > set home activity >
# grants + deviceidle whitelist > disable LineageOS bloat.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# shellcheck source=install/lib.sh
source "$HERE/lib.sh"
# shellcheck source=install/config.env
source "$HERE/config.env"

DRY_RUN=0

APK="$ROOT/app/out/$APK_NAME"
CONFIG_JSON="$ROOT/config/config.json"
FACES_DIR="$ROOT/faces"
APKS_DIR="$ROOT/apps"
DEVICE_APP_DIR="${DEVICE_APP_DIR:-/sdcard/EchoClock}"
APP_PKG="${LAUNCHER_COMPONENT%%/*}"

# packages that are safe to disable for a kiosk (guarded by "is it installed?")
BLOAT_PKGS="
com.android.contacts
com.android.calculator2
org.lineageos.calculator
org.lineageos.recorder
org.lineageos.eleven
com.etar
com.android.calendar
"

usage() {
  cat <<EOF
EchoClock provisioner — kiosk setup for a device already running LineageOS 18.1.

Usage: ./install/provision.sh [options]

Options:
  -n, --dry-run   print the actions without changing the device
  -s, --serial S  target exactly this adb serial (USB serial or host:port); required
                  when more than one device is attached
  -h, --help      show this help

Requires: device booted, USB debugging on, exactly one adb device ($CODENAME).

Does (idempotently):
  * skips the setup wizard      settings put global device_provisioned 1;
                                settings put secure user_setup_complete 1
  * kiosk display               locksettings set-disabled true; screensaver/doze
                                off; screen_off_timeout 1800000;
                                stay_on_while_plugged_in 7; 'cmd uimode night yes'
  * installs                    app/out/$APK_NAME ${BUNDLED_APKS:+(and: $BUNDLED_APKS)}
  * pushes                      config/config.json -> $DEVICE_APP_DIR/config.json
                                faces/<id>/  -> $DEVICE_APP_DIR/faces/
  * wires the launcher          cmd package set-home-activity $LAUNCHER_COMPONENT
  * grants                      READ_CALENDAR; appops MANAGE_EXTERNAL_STORAGE allow;
                                deviceidle whitelist +$APP_PKG
  * disables bloat              contacts, calculator, recorder, eleven, etar
                                (pm disable-user; system partitions untouched)

Environment: ALLOW_UNKNOWN_DEVICE=1 skips the cronos identity refusal (dangerous).
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    -n|--dry-run) DRY_RUN=1 ;;
    -s|--serial) ec_set_serial "${2:-}"; shift ;;
    --serial=*) ec_set_serial "${1#--serial=}" ;;
    *) die "unknown argument: $1 (try --help)" ;;
  esac
  shift
done

run() { # execute a mutating command, or print it in --dry-run
  if [[ "$DRY_RUN" -eq 1 ]]; then
    info "[dry-run] $*"
  else
    "$@"
  fi
}

# ---------------------------------------------------------------------------
# device checks
# ---------------------------------------------------------------------------
info "EchoClock provisioning — repo: $ROOT"
ensure_platform_tools
need_cmd adb "install platform-tools or set ANDROID_HOME"

SERIAL=$(adb_dev)
assert_device_identity "$SERIAL"

SDK=$(_ec_getprop "$SERIAL" ro.build.version.sdk)
REL=$(_ec_getprop "$SERIAL" ro.build.version.release)
LINEAGE=$(_ec_getprop "$SERIAL" ro.lineage.version)
info "device: Android ${REL:-?} (API ${SDK:-?})${LINEAGE:+ — $LINEAGE}"
if [[ -n "$SDK" && "$SDK" -lt 30 ]]; then
  die "this device is API $SDK; EchoClock needs Android 11 (API 30) or newer"
fi

if [[ "$DRY_RUN" -eq 0 ]]; then
  waited=0
  while [[ "$(_ec_getprop "$SERIAL" sys.boot_completed)" != "1" ]]; do
    if [[ "$waited" -ge 180 ]]; then
      die "device did not finish booting (sys.boot_completed != 1) — unlock the screen and retry"
    fi
    sleep 5
    waited=$((waited + 5))
  done
  ok "device booted"
fi

# ---------------------------------------------------------------------------
# 1. setup wizard + kiosk display settings
# ---------------------------------------------------------------------------
info "skipping setup wizard and applying kiosk display settings"
adb_shell() { run adb -s "$SERIAL" shell "$@"; }

adb_shell settings put global device_provisioned 1
adb_shell settings put secure user_setup_complete 1
adb_shell locksettings set-disabled true
adb_shell settings put secure screensaver_enabled 0
adb_shell settings put secure doze_enabled 0
adb_shell settings put system screen_off_timeout 1800000
adb_shell settings put global stay_on_while_plugged_in 7
adb_shell cmd uimode night yes

# ---------------------------------------------------------------------------
# 2. install APKs
# ---------------------------------------------------------------------------
install_apk() { # APK_PATH LABEL
  local apk=$1 label=$2 out
  [[ -f "$apk" ]] || die "APK not found: $apk"
  if [[ "$DRY_RUN" -eq 1 ]]; then
    info "[dry-run] adb -s $SERIAL install -r $apk   # $label"
    return 0
  fi
  info "installing $label: $(basename "$apk")"
  out=$(adb -s "$SERIAL" install -r "$apk" 2>&1) || {
    printf '%s\n' "$out" | sed 's/^/       /' >&2
    die "adb install failed for $(basename "$apk")"
  }
  printf '%s\n' "$out" | tr -d '\r' | sed 's/^/       /' >&2
  printf '%s\n' "$out" | grep -q Success || die "adb install did not report Success for $(basename "$apk")"
  ok "installed $label"
}

install_apk "$APK" "EchoClock"

for b in ${BUNDLED_APKS:-}; do
  if [[ -f "$APKS_DIR/$b" ]]; then
    install_apk "$APKS_DIR/$b" "bundled app $b"
  else
    warn "BUNDLED_APKS lists $b but $APKS_DIR/$b does not exist — skipping"
  fi
done

# ---------------------------------------------------------------------------
# 3. push app data: config + faces
# ---------------------------------------------------------------------------
[[ -f "$CONFIG_JSON" ]] || die "missing repo file: $CONFIG_JSON"

info "pushing app data to $DEVICE_APP_DIR"
adb_shell mkdir -p "$DEVICE_APP_DIR"
run adb -s "$SERIAL" push "$CONFIG_JSON" "$DEVICE_APP_DIR/config.json"

if [[ -d "$FACES_DIR" ]]; then
  adb_shell mkdir -p "$DEVICE_APP_DIR/faces"
  FACE_DIRS=""
  for d in "$FACES_DIR"/*/; do
    [[ -d "$d" ]] || continue
    if [[ -f "$d/index.html" ]]; then
      FACE_DIRS="$FACE_DIRS $d"
    else
      warn "skipping face without index.html: $d"
    fi
  done
  if [[ -n "$FACE_DIRS" ]]; then
    for d in $FACE_DIRS; do
      run adb -s "$SERIAL" push "$d" "$DEVICE_APP_DIR/faces/"
    done
  else
    warn "no user faces found in $FACES_DIR (bundled faces ship inside the APK)"
  fi
fi

# ---------------------------------------------------------------------------
# 4. launcher + permissions
# ---------------------------------------------------------------------------
info "wiring EchoClock as HOME activity"
adb_shell cmd package set-home-activity "$LAUNCHER_COMPONENT"

info "granting permissions"
adb_shell pm grant "$APP_PKG" android.permission.READ_CALENDAR
adb_shell appops set "$APP_PKG" MANAGE_EXTERNAL_STORAGE allow
adb_shell dumpsys deviceidle whitelist "+$APP_PKG"

# ---------------------------------------------------------------------------
# 5. debloat (disable only; system partitions preserved)
# ---------------------------------------------------------------------------
pkg_installed() { # PKG
  adb -s "$SERIAL" shell pm list packages "$1" 2>/dev/null | tr -d '\r' | grep -qx "package:$1"
}
pkg_enabled() { # PKG
  adb -s "$SERIAL" shell pm list packages -e "$1" 2>/dev/null | tr -d '\r' | grep -qx "package:$1"
}

info "disabling optional LineageOS apps"
for p in $BLOAT_PKGS; do
  if ! pkg_installed "$p"; then
    info "  not installed, skipping: $p"
  elif ! pkg_enabled "$p"; then
    ok "  already disabled: $p"
  elif [[ "$DRY_RUN" -eq 1 ]]; then
    info "  [dry-run] adb -s $SERIAL shell pm disable-user --user 0 $p"
  else
    out=$(adb -s "$SERIAL" shell pm disable-user --user 0 "$p" 2>&1) || true
    if printf '%s' "$out" | grep -q "new state: disabled"; then
      ok "  disabled: $p"
    else
      printf '%s\n' "$out" | tr -d '\r' | sed 's/^/       /' >&2
      warn "  could not disable $p (continuing)"
    fi
  fi
done

ok "provisioning complete — press Home to show EchoClock"
info "logs / debugging: adb -s $SERIAL logcat -s EchoClock"
