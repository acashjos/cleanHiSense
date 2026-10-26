#!/usr/bin/env bash
# build-launcher.sh - compile, sign and install the TV Home launcher.
#   tools/build-launcher.sh            build + install
#   tools/build-launcher.sh --build    build only
# Uses the Android SDK build-tools directly; no Gradle download needed.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$(/bin/ls -1d "$SDK"/build-tools/* | sort -V | tail -1)"
PLATFORM="$(/bin/ls -1d "$SDK"/platforms/android-* | sort -V | tail -1)"
AJAR="$PLATFORM/android.jar"
HOST="${TV_HOST:-10.0.0.97}"; PORT="${TV_PORT:-5555}"

SRC="$ROOT/launcher/app/src/main"
OUT="$ROOT/launcher/build"
rm -rf "$OUT"; mkdir -p "$OUT"/{resc,gen,classes,dex}

echo "[1/6] aapt2 compile resources"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/resc/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$AJAR" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --no-version-vectors \
  "$OUT/resc/res.zip"

echo "[3/6] javac"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 11 -nowarn -encoding UTF-8 -cp "$AJAR" \
  -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | grep -v '^Note:' || true

echo "[4/6] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --min-api 26 --output "$OUT/dex" --lib "$AJAR" @"$OUT/classes.txt"

echo "[5/6] package + sign"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
KS="$HOME/.android/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/launcher/tvhome.apk" "$OUT/aligned.apk" 2>&1 | grep -vi warning || true
echo "    -> launcher/tvhome.apk ($(du -h "$ROOT/launcher/tvhome.apk" | cut -f1))"

if [ "${1:-}" = "--build" ]; then exit 0; fi

echo "[6/6] install on $HOST:$PORT"
# Android revokes accessibility consent whenever a service's APK is
# replaced, which silently kills the remote-key service on every
# reinstall.  Remember whether it was on, and switch it back after.
A11Y_BEFORE="$(adb -s "$HOST:$PORT" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')"

# adb install can hang waiting for an on-screen confirmation on newer
# Android; pushing and using pm install avoids that entirely.
adb -s "$HOST:$PORT" push "$ROOT/launcher/tvhome.apk" /data/local/tmp/tvhome.apk >/dev/null
adb -s "$HOST:$PORT" shell pm install -r -g /data/local/tmp/tvhome.apk

case "$A11Y_BEFORE" in
  *com.oxylab.tvhome*)
    sleep 2
    adb -s "$HOST:$PORT" shell settings put secure \
        enabled_accessibility_services "$A11Y_BEFORE" >/dev/null
    adb -s "$HOST:$PORT" shell settings put secure accessibility_enabled 1 >/dev/null
    echo "    re-enabled the remote-key accessibility service"
    ;;
esac
