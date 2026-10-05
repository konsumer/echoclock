# shellcheck shell=bash
# EchoClock installer — shared helpers. Source this file; never execute it directly.
#
# Compatible with bash 3.2 (macOS /bin/bash) and bash 4/5 (Linux).
# Callers are expected to have already done: set -euo pipefail

# ---------------------------------------------------------------------------
# coloured output (stderr). NO_COLOR / non-tty disables colour.
# ---------------------------------------------------------------------------
if [[ -t 2 && -z "${NO_COLOR:-}" && "${TERM:-dumb}" != "dumb" ]]; then
  C_RESET=$'\033[0m'
  C_RED=$'\033[31m'
  C_GREEN=$'\033[32m'
  C_YELLOW=$'\033[33m'
  C_BLUE=$'\033[34m'
  C_BOLD=$'\033[1m'
else
  C_RESET=''; C_RED=''; C_GREEN=''; C_YELLOW=''; C_BLUE=''; C_BOLD=''
fi

info() { printf '%s[info]%s %s\n'  "$C_BLUE"   "$C_RESET" "$*" >&2; }
ok()   { printf '%s[ ok ]%s %s\n'  "$C_GREEN"  "$C_RESET" "$*" >&2; }
warn() { printf '%s[warn]%s %s\n'  "$C_YELLOW" "$C_RESET" "$*" >&2; }
die()  { printf '%s[fail]%s %s\n'  "$C_RED"    "$C_RESET" "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# prerequisites
# ---------------------------------------------------------------------------

# need_cmd CMD [HINT]
need_cmd() {
  local cmd=$1 hint=${2:-}
  command -v "$cmd" >/dev/null 2>&1 || die "missing required command: $cmd${hint:+ — $hint}"
}

# Put platform-tools (adb/fastboot) on PATH when the Android SDK exists but the
# tools are not exported. Safe to call repeatedly.
ensure_platform_tools() {
  local dir
  for dir in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
             "$HOME/Library/Android/sdk" "/opt/homebrew/share/android-commandlinetools"; do
    if [[ -n "$dir" && -d "$dir/platform-tools" ]]; then
      case ":$PATH:" in
        *":$dir/platform-tools:"*) ;;
        *) PATH="$PATH:$dir/platform-tools" ;;
      esac
    fi
  done
  export PATH
}

# ---------------------------------------------------------------------------
# hashing / downloading
# ---------------------------------------------------------------------------

# sha256_of FILE -> lowercase hex on stdout
sha256_of() {
  local file=$1
  [[ -f "$file" ]] || die "sha256_of: not a file: $file"
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
  else
    die "no shasum or sha256sum on PATH"
  fi
}

# verify_sha256 FILE [EXPECTED]
# Expected empty -> warn and print the computed hash. Mismatch -> die.
verify_sha256() {
  local file=$1 expected=${2:-} actual want
  [[ -f "$file" ]] || die "verify_sha256: no such file: $file"
  actual=$(sha256_of "$file")
  want=$(printf '%s' "$expected" | tr 'A-Z' 'a-z')
  if [[ -z "$want" ]]; then
    warn "no expected sha256 for $(basename "$file") — computed: $actual"
  elif [[ "$actual" != "$want" ]]; then
    die "sha256 mismatch for $(basename "$file")
       expected: $want
       actual:   $actual"
  else
    ok "sha256 verified: $(basename "$file")"
  fi
  return 0
}

# dl URL DEST — curl with retry + resume; interrupted transfer is kept in DEST.part.
dl() {
  local url=$1 dest=$2 part="${2}.part"
  mkdir -p "$(dirname "$dest")"
  info "downloading $(basename "$dest")"
  info "  from $url"
  if ! curl -fL --retry 5 --retry-delay 2 --retry-connrefused --connect-timeout 30 \
            -C - -o "$part" "$url"; then
    warn "resume failed or unsupported — restarting download from scratch"
    rm -f "$part"
    curl -fL --retry 5 --retry-delay 2 --retry-connrefused --connect-timeout 30 \
         -o "$part" "$url" || die "download failed: $url"
  fi
  mv -f "$part" "$dest"
  ok "downloaded $(basename "$dest") ($(du -h "$dest" | awk '{print $1}'))"
}

# ---------------------------------------------------------------------------
# interaction
# ---------------------------------------------------------------------------

# confirm "MESSAGE" — require the literal answer YES.
# Set ASSUME_YES=1 (CLI --yes) to auto-confirm for scripted runs.
confirm() {
  local msg=${1:-Continue?} ans
  if [[ "${ASSUME_YES:-0}" == 1 ]]; then
    info "auto-confirmed (--yes / ASSUME_YES=1): $msg"
    return 0
  fi
  if [[ ! -t 0 ]]; then
    die "stdin is not a terminal — re-run with --yes to confirm non-interactively. Pending: $msg"
  fi
  printf '%s%s%s\n' "$C_YELLOW" "$msg" "$C_RESET" >&2
  printf 'Type YES to continue: ' >&2
  IFS= read -r ans || die "aborted (no input)"
  [[ "$ans" == "YES" ]] || die "aborted by user (expected YES, got '${ans}')"
  ok "confirmed"
}

# ---------------------------------------------------------------------------
# adb helpers
# ---------------------------------------------------------------------------

_ec_getprop() { # SERIAL PROP
  local serial=$1 prop=$2
  adb -s "$serial" shell getprop "$prop" 2>/dev/null \
    | tr -d '\r\n' \
    | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

# adb_devices — serials of devices in the normal 'device' state, one per line.
adb_devices() {
  adb devices | awk 'NR > 1 && $2 == "device" { print $1 }'
}

# ec_set_serial SERIAL — validate and export ANDROID_SERIAL so every later adb call
# targets exactly this device (needed when several are attached).
ec_set_serial() {
  [[ -n "${1:-}" ]] || die "--serial requires a value (e.g. --serial 192.168.1.5:5555 or a USB serial)"
  ANDROID_SERIAL=$1
  export ANDROID_SERIAL
  # Network adb (host:port) can drop; (re)connect before the device list is checked.
  if [[ "$1" == *:* ]]; then
    adb connect "$1" >/dev/null 2>&1 || true
  fi
}

# adb_dev — validate exactly one device and that it is the configured codename.
# Prints the serial on stdout. All diagnostics go to stderr.
adb_dev() {
  need_cmd adb "install platform-tools or set ANDROID_HOME"
  local devices count serial dev
  devices=$(adb_devices)
  count=$(printf '%s\n' "$devices" | grep -c . || true)

  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    case " $devices " in
      *" $ANDROID_SERIAL "*) serial=$ANDROID_SERIAL ;;
      *) die "ANDROID_SERIAL=$ANDROID_SERIAL is not an attached adb 'device' (attached: $(printf '%s ' $devices))" ;;
    esac
  elif [[ "$count" -eq 1 ]]; then
    serial=$(printf '%s\n' "$devices" | sed -n '1p')
  elif [[ "$count" -eq 0 ]]; then
    die "no adb device in 'device' state. Enable USB debugging, replug, accept the RSA prompt, then check: adb devices"
  else
    die "multiple adb devices attached: $(printf '%s ' $devices) — unplug the extras or set ANDROID_SERIAL=<serial>"
  fi

  dev=$(_ec_getprop "$serial" ro.product.device)
  [[ "$dev" == "${CODENAME:-cronos}" ]] \
    || die "device $serial reports ro.product.device='$dev', not '${CODENAME:-cronos}' — refusing to touch it"
  ok "device $serial is ${CODENAME:-cronos}"
  printf '%s\n' "$serial"
}

# assert_device_identity SERIAL — strict three-way cronos check used before
# destructive operations. Override with ALLOW_UNKNOWN_DEVICE=1 (loud warning).
assert_device_identity() {
  local serial=${1:?assert_device_identity requires a serial}
  local codename=${CODENAME:-cronos} dev name hw bad=0
  dev=$(_ec_getprop "$serial" ro.product.device)
  name=$(_ec_getprop "$serial" ro.product.name)
  hw=$(_ec_getprop "$serial" ro.boot.hardware)
  info "identity: ro.product.device='$dev' ro.product.name='$name' ro.boot.hardware='$hw'"
  case "$dev"  in *"$codename"*) ;;  *) warn "ro.product.device '$dev' does not contain '$codename'";  bad=1 ;; esac
  case "$name" in *"$codename"*) ;;  *) warn "ro.product.name '$name' does not contain '$codename'";  bad=1 ;; esac
  case "$hw"   in *mt8163*|*MT8163*) ;; *) warn "ro.boot.hardware '$hw' is not mt8163";                bad=1 ;; esac
  if [[ "$bad" -eq 1 ]]; then
    if [[ "${ALLOW_UNKNOWN_DEVICE:-0}" == 1 ]]; then
      warn "ALLOW_UNKNOWN_DEVICE=1 — continuing despite a failed identity check"
    else
      die "device $serial failed the '$codename / MT8163' identity check — refusing (set ALLOW_UNKNOWN_DEVICE=1 to override at your own risk)"
    fi
  else
    ok "device identity verified: $codename on MT8163"
  fi
  return 0
}
