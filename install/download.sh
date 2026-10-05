#!/usr/bin/env bash
# EchoClock — fetch everything needed to install EchoClock on an Echo Show 5 (2nd gen).
#
# Downloads the LineageOS ROM and GApps, verifies sha256 where a checksum is pinned in
# install/config.env (printing the computed hash when it is not), and builds
# app/out/EchoClock.apk when the SDK is present.
#
# Safe to re-run: files already present and checksum-clean are skipped.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# shellcheck source=install/lib.sh
source "$HERE/lib.sh"
# shellcheck source=install/config.env
source "$HERE/config.env"

DL_DIR="$HERE/dl"
FORCE=0

usage() {
  cat <<EOF
EchoClock downloader — fetches every install artifact into install/dl/,
verifying sha256 where config.env pins one.

Usage: ./install/download.sh [--force|--help]

Options:
  --force      re-download files even when present and checksum-clean
  -h, --help   show this help

Files:
  install/dl/$ROM_FILE
      LineageOS 18.1 for cronos (sha256 pinned in install/config.env)
  install/dl/$GAPPS_FILE
      MindTheGapps for ARM/Android 11 (checksum printed, not pinned)
  install/dl/$AMONET_FILE
      amonet unlock bundle — XDA-only, NOT auto-downloadable. Missing is not an
      error here; download.sh prints where to get it.
  app/out/$APK_NAME
      built via app/build.sh when missing and an Android SDK is available

EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    -f|--force) FORCE=1 ;;
    *) die "unknown argument: $1 (try --help)" ;;
  esac
  shift
done

need_cmd curl
need_cmd unzip
command -v shasum >/dev/null 2>&1 || command -v sha256sum >/dev/null 2>&1 \
  || die "need shasum (macOS) or sha256sum (Linux) on PATH"

mkdir -p "$DL_DIR"

# fetch LABEL URL DEST EXPECTED_SHA
# Skips when DEST exists and is trustworthy; otherwise (re)downloads + verifies.
fetch() {
  local label=$1 url=$2 dest=$3 sha=${4:-} actual
  if [[ -f "$dest" && "$FORCE" -eq 0 ]]; then
    if [[ -n "$sha" ]]; then
      actual=$(sha256_of "$dest")
      if [[ "$actual" == "$(printf '%s' "$sha" | tr 'A-Z' 'a-z')" ]]; then
        ok "$label: already downloaded (sha256 OK) — $(basename "$dest")"
        return 0
      fi
      warn "$label: existing file fails its pinned checksum — re-downloading"
    elif [[ "$dest" == *.zip ]] && unzip -tq "$dest" >/dev/null 2>&1; then
      warn "$label: already downloaded, archive intact — sha256: $(sha256_of "$dest")"
      return 0
    else
      warn "$label: existing file unusable (damaged archive or unknown type) — re-downloading"
    fi
  fi
  dl "$url" "$dest"
  verify_sha256 "$dest" "$sha"
}

# sdk_available — is there an Android SDK with platforms + build-tools?
sdk_available() {
  local dir
  for dir in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
             "$HOME/Library/Android/sdk" "/opt/homebrew/share/android-commandlinetools"; do
    [[ -n "$dir" && -d "$dir/platforms" && -d "$dir/build-tools" ]] && return 0
  done
  return 1
}

summarize() { # FILE LABEL
  local f=$1 label=$2
  if [[ -f "$f" ]]; then
    printf '  %-44s %8s  %s\n' "$label" "$(du -h "$f" | awk '{print $1}')" "$(sha256_of "$f")"
  else
    printf '  %-44s %8s  %s\n' "$label" "MISSING" "-"
  fi
}

info "EchoClock downloader — repo: $ROOT"
printf '%s\n' "----------------------------------------" >&2

# ---- install artifacts ------------------------------------------------------
fetch "LineageOS ROM"  "$ROM_URL"    "$DL_DIR/$ROM_FILE"    "${ROM_SHA256:-}"
fetch "GApps"          "$GAPPS_URL"  "$DL_DIR/$GAPPS_FILE"  "${GAPPS_SHA256:-}"

# ---- amonet bundle (manual, XDA login-gated) --------------------------------
if [[ -f "$DL_DIR/$AMONET_FILE" ]]; then
  ok "amonet bundle present: $DL_DIR/$AMONET_FILE"
  verify_sha256 "$DL_DIR/$AMONET_FILE" "${AMONET_SHA256:-}"
else
  warn "amonet bundle missing (it is NOT auto-downloadable — XDA requires a login):"
  printf '       page: %s\n       save: %s\n' "$AMONET_PAGE" "$DL_DIR/$AMONET_FILE" >&2
  printf '       flash-lineageos.sh cannot unlock the device without it.\n' >&2
fi

# ---- app APK ----------------------------------------------------------------
APK="$ROOT/app/out/$APK_NAME"
if [[ -f "$APK" ]]; then
  ok "app APK present: $APK ($(du -h "$APK" | awk '{print $1}'))"
elif [[ -f "$ROOT/app/build.sh" ]]; then
  if sdk_available; then
    info "app/out/$APK_NAME missing — building with app/build.sh"
    ( cd "$ROOT/app" && bash ./build.sh ) || die "app/build.sh failed"
    [[ -f "$APK" ]] || die "app/build.sh finished but $APK does not exist"
    ok "built $APK ($(du -h "$APK" | awk '{print $1}'))"
  else
    warn "Android SDK not found (ANDROID_HOME/ANDROID_SDK_ROOT unset) — skipping APK build."
    printf '       Install build-tools + a platform, or set ANDROID_HOME, then re-run.\n' >&2
  fi
else
  warn "app/build.sh not present — skipping APK build."
  printf '       provision.sh will need %s later.\n' "$APK" >&2
fi

# ---- summary ----------------------------------------------------------------
printf '%s\n' "----------------------------------------" >&2
info "summary (size, sha256):"
printf '  %-44s %8s  %s\n' "FILE" "SIZE" "SHA256" >&2
summarize "$DL_DIR/$ROM_FILE"    "$ROM_FILE"
summarize "$DL_DIR/$GAPPS_FILE"  "$GAPPS_FILE"
summarize "$DL_DIR/$AMONET_FILE" "$AMONET_FILE"
summarize "$APK"                 "app/out/$APK_NAME"

ok "downloads complete — next: ./install/flash-lineageos.sh (needs $AMONET_FILE)"
