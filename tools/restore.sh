#!/usr/bin/env bash
# restore.sh - re-enable everything cleanyourTV disabled, and undo Private DNS.
# Usage: tools/restore.sh [--dns-only] [--packages-only]
set -u
HOST="${TV_HOST:-10.0.0.97}"; PORT="${TV_PORT:-5555}"
ADB=(adb -s "$HOST:$PORT")
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/backup/disabled-by-cleanyourtv.txt"
mode="${1:---all}"

if [[ "$mode" != "--dns-only" ]]; then
  if [[ ! -f "$LOG" ]]; then
    echo "nothing to restore: $LOG not found" >&2
  else
    while read -r pkg; do
      [[ -z "$pkg" ]] && continue
      echo -n "enable $pkg ... "
      "${ADB[@]}" shell pm enable --user 0 "$pkg" 2>&1 | tr -d '\r'
    done < "$LOG"
  fi
  # Hand Home back to the stock Google TV launcher.
  "${ADB[@]}" shell pm enable --user 0 com.google.android.apps.tv.launcherx 2>&1 | tr -d '\r'
  "${ADB[@]}" shell cmd package set-home-activity \
    com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.home.HomeActivity 2>&1 | tr -d '\r'
fi

if [[ "$mode" != "--dns-only" ]]; then
  # The remote-key service; turning it off also restores volume key repeat.
  echo -n "remote-key accessibility service off ... "
  "${ADB[@]}" shell settings delete secure enabled_accessibility_services >/dev/null 2>&1
  "${ADB[@]}" shell settings put secure accessibility_enabled 0 >/dev/null 2>&1
  echo ok
fi

if [[ "$mode" != "--packages-only" ]]; then
  echo -n "private dns -> opportunistic ... "
  "${ADB[@]}" shell settings put global private_dns_mode opportunistic 2>&1 | tr -d '\r'
  echo ok
fi
echo "Done. Reboot with: adb -s $HOST:$PORT reboot"
