#!/usr/bin/env bash
# disable.sh - disable one package for user 0, with a hard guard list.
# Usage: tools/disable.sh <package> [...]
# Records every success in backup/disabled-by-cleanyourtv.txt so
# tools/restore.sh can put the TV back exactly as it was.
set -u
HOST="${TV_HOST:-10.0.0.97}"; PORT="${TV_PORT:-5555}"
ADB=(adb -s "$HOST:$PORT")
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/backup/disabled-by-cleanyourtv.txt"

# Never disable these: the TV may not boot again (#884), or loses
# HDMI/tuner, remote, keyboard or the Play Store.
GUARD='^(android|com\.android\.systemui|com\.android\.shell|com\.android\.phone|com\.android\.settings|com\.android\.tv\.settings|com\.android\.providers\.(media|settings|downloads|telephony|tv)|com\.android\.providers\.media\.module|com\.(google\.)?android\.packageinstaller|com\.google\.android\.permissioncontroller|com\.(google\.)?android\.ext\.(services|shared)|com\.google\.android\.modulemetadata|com\.google\.android\.overlay\.modules.*|com\.android\.vending|com\.google\.android\.gms|com\.google\.android\.gsf|com\.google\.android\.inputmethod\.latin|com\.google\.android\.tv\.remote\.service|com\.android\.bluetooth|com\.mediatek\.autopair|com\.mediatek\.dtv\.tvinput\.framework\.server|com\.mediatek\.android\.tv\.globalkeyoverlay|com\.vt\.tv\.input|com\.vt\.source\.(tuner|external)|com\.vt\.avmiddleware|com\.vt\.tvservice|com\.vt\.tv\.commonui|com\.vt\.tv\.tvcenter|com\.vt\.tv\.videocolor|com\.vt\.edv\.drmp|com\.dolby\.android\.audio\.service|com\.android\.networkstack.*|com\.android\.connectivity\.resources|com\.android\.wifi\.resources|com\.android\.certinstaller|com\.android\.keychain|com\.android\.externalstorage)$'

# Factory / AT / production menus: never disable AND never launch -
# on some boards touching these flips a factory flag that kills the
# Input key and CEC, and the shell cannot undo it.
FACTORY='factory|\.fac\.|facmenu|storemode|\.at\.|production'

rc=0
for pkg in "$@"; do
  if [[ "$pkg" =~ $GUARD ]]; then
    echo "REFUSED (core)    $pkg" >&2; rc=1; continue
  fi
  if [[ "$pkg" =~ $FACTORY ]] && [[ "$pkg" != com.vt.storemode ]]; then
    echo "REFUSED (factory) $pkg" >&2; rc=1; continue
  fi
  out="$("${ADB[@]}" shell pm disable-user --user 0 "$pkg" 2>&1 | tr -d '\r')"
  if [[ "$out" == *"new state: disabled-user"* ]]; then
    echo "disabled          $pkg"
    grep -qxF "$pkg" "$LOG" 2>/dev/null || echo "$pkg" >> "$LOG"
  else
    echo "FAILED            $pkg :: $out" >&2; rc=1
  fi
done
exit $rc
