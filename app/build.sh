#!/usr/bin/env bash
#
# EchoClock — build the Android app (docs/DESIGN.md §3).
#
# Usage: app/build.sh [--debug] [--help]
#
set -euo pipefail

PROG="$(basename "$0")"

usage() {
    cat <<EOF
EchoClock app builder

Usage: $PROG [options]

Options:
  --debug      assemble the debug APK instead of the release APK
  -h, --help   show this help

Output:
  app/out/EchoClock.apk         release build
  app/out/EchoClock-debug.apk   --debug build

Environment:
  ANDROID_HOME / ANDROID_SDK_ROOT   Android SDK (auto-detected otherwise)
  JAVA_HOME                         JDK 17+ (Homebrew openjdk@17 preferred)
EOF
}

BUILD_TYPE="release"
for arg in "$@"; do
    case "$arg" in
        -h|--help) usage; exit 0 ;;
        --debug) BUILD_TYPE="debug" ;;
        *) printf '%s: unknown option: %s\n\n' "$PROG" "$arg" >&2; usage >&2; exit 2 ;;
    esac
done

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---- Android SDK -------------------------------------------------------------
find_sdk() {
    local candidates=()
    [ -n "${ANDROID_HOME:-}" ] && candidates+=("$ANDROID_HOME")
    [ -n "${ANDROID_SDK_ROOT:-}" ] && candidates+=("$ANDROID_SDK_ROOT")
    candidates+=(
        "/opt/homebrew/share/android-commandlinetools"
        "$HOME/Library/Android/sdk"
        "$HOME/Android/Sdk"
    )
    local c
    for c in "${candidates[@]}"; do
        if [ -n "$c" ] && [ -d "$c/platforms" ] && [ -d "$c/build-tools" ]; then
            printf '%s\n' "$c"
            return 0
        fi
    done
    return 1
}

SDK="$(find_sdk || true)"
if [ -z "$SDK" ]; then
    echo "$PROG: error: Android SDK not found." >&2
    echo "         Set ANDROID_HOME (e.g. /opt/homebrew/share/android-commandlinetools) and retry." >&2
    exit 1
fi
echo "==> SDK: $SDK"

# ---- JDK 17 ------------------------------------------------------------------
if [ -z "${JAVA_HOME:-}" ] && [ -x /opt/homebrew/opt/openjdk@17/bin/javac ]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
fi
if [ -n "${JAVA_HOME:-}" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "==> JAVA_HOME: $JAVA_HOME"
fi
if ! command -v java >/dev/null 2>&1; then
    echo "$PROG: error: no 'java' in PATH; install a JDK 17." >&2
    exit 1
fi

# ---- local.properties --------------------------------------------------------
printf 'sdk.dir=%s\n' "${SDK//\\/\\\\}" > "$APP_DIR/local.properties"

# ---- signing keystore --------------------------------------------------------
KEYSTORE="$APP_DIR/keystore.jks"
if [ ! -f "$KEYSTORE" ]; then
    if command -v keytool >/dev/null 2>&1; then
        echo "==> creating signing keystore: $KEYSTORE"
        if ! keytool -genkeypair \
            -keystore "$KEYSTORE" \
            -alias echoclock -storepass android -keypass android \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=EchoClock, OU=EchoClock, O=EchoClock, C=US" >/dev/null 2>&1; then
            echo "$PROG: warning: keytool failed; Gradle will fall back to the debug signing key." >&2
            rm -f "$KEYSTORE"
        fi
    else
        echo "$PROG: warning: keytool not found; Gradle will fall back to the debug signing key." >&2
    fi
fi

# ---- build -------------------------------------------------------------------
cd "$APP_DIR"
if [ "$BUILD_TYPE" = "debug" ]; then
    TASK="assembleDebug"
    SRC="build/outputs/apk/debug/app-debug.apk"
    DEST="out/EchoClock-debug.apk"
else
    TASK="assembleRelease"
    SRC="build/outputs/apk/release/app-release.apk"
    DEST="out/EchoClock.apk"
fi

echo "==> ./gradlew --no-daemon $TASK"
./gradlew --no-daemon "$TASK"

if [ ! -f "$SRC" ]; then
    echo "$PROG: error: expected APK not found: $APP_DIR/$SRC" >&2
    exit 1
fi

mkdir -p out
cp -f "$SRC" "$DEST"
SIZE="$(wc -c < "$DEST" | tr -d ' ')"
echo "==> built $APP_DIR/$DEST (${SIZE} bytes)"
