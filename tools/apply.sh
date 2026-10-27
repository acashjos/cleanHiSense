#!/usr/bin/env bash
# apply.sh - put the cleanyourTV experience back after tools/restore.sh:
# re-disable the debloat list, hand Home back to TV Home, re-enable its
# accessibility + notification-listener services, and restore the AdGuard
# Private DNS.  This is the inverse of tools/restore.sh.
#
# It does NOT build or install the launcher - run tools/build-launcher.sh
# (on an x86-64 box; this repo's host can't run aapt2) first if the APK is
# not installed.  It also does not re-create the Wi-Fi network oxylab_mo
# that disabling the setup wizard deleted; add that in Settings by hand.
#
# Usage: tools/apply.sh [--packages-only] [--launcher-only] [--dns-only]
#
#   (default)         everything
#   --packages-only   the debloat list + the stock launcher/wizard
#   --launcher-only   TV Home as Home + its services + the stock launcher
#   --dns-only        AdGuard Private DNS only
set -u
HOST="${TV_HOST:-10.0.0.97}"; PORT="${TV_PORT:-5555}"
ADB=(adb -s "$HOST:$PORT")
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/backup/disabled-by-cleanyourtv.txt"
mode="${1:---all}"

LAUNCHER=com.oxylab.tvhome
HOME_ACT="$LAUNCHER/$LAUNCHER.HomeActivity"
A11Y="$LAUNCHER/$LAUNCHER.KeyService"
NOTIF="$LAUNCHER/$LAUNCHER.TvNotificationListener"
STOCK_LAUNCHER=com.google.android.apps.tv.launcherx
STOCK_WIZARD=com.google.android.tungsten.setupwraith

# Add a component to a colon-separated secure-list setting without dropping
# whatever else is already in it (restore.sh deletes the whole setting).
ensure_list() {
  local key="$1" want="$2" cur
  cur="$("${ADB[@]}" shell settings get secure "$key" </dev/null 2>/dev/null | tr -d '\r')"
  case "$cur" in
    *"$want"*) return 0 ;;
    ""|null) "${ADB[@]}" shell settings put secure "$key" "$want" </dev/null >/dev/null ;;
    *) "${ADB[@]}" shell settings put secure "$key" "$cur:$want" </dev/null >/dev/null ;;
  esac
}

# The component currently holding the Home role; step 3 verifies against it
# after disabling the stock launcher.
current_home() {
  "${ADB[@]}" shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME \
    </dev/null 2>/dev/null | tr -d '\r' | tail -1
}

# If the launcher isn't installed there is nothing to hand Home to, and
# disabling the stock launcher would leave the TV with no Home screen at
# all - so skip the Home and stock-launcher steps and say why.
LAUNCHER_OK=1
if ! "${ADB[@]}" shell pm list packages "$LAUNCHER" 2>/dev/null \
     | tr -d '\r' | grep -qx "package:$LAUNCHER"; then
  LAUNCHER_OK=0
  echo "WARNING: $LAUNCHER is not installed - run tools/build-launcher.sh first." >&2
  echo "         Skipping the Home, service and stock-launcher steps." >&2
fi

# 1. Re-disable the debloat list.  The stock launcher and the setup wizard
#    are held back for step 3: turning them off before TV Home owns Home
#    would leave the TV with no Home screen.
if [[ "$mode" != "--dns-only" && "$mode" != "--launcher-only" ]]; then
  if [[ ! -f "$LOG" ]]; then
    echo "nothing to re-disable: $LOG not found" >&2
  else
    while read -r pkg; do
      [[ -z "$pkg" ]] && continue
      [[ "$pkg" == "$STOCK_LAUNCHER" || "$pkg" == "$STOCK_WIZARD" ]] && continue
      echo -n "disable $pkg ... "
      # </dev/null: without it `adb shell` drains the loop's stdin (the log),
      # so only the first package would ever be processed.
      "${ADB[@]}" shell pm disable-user --user 0 "$pkg" </dev/null 2>&1 | tr -d '\r'
    done < "$LOG"
  fi
fi

# 2. Make TV Home the launcher and switch its two services back on.
if [[ "$LAUNCHER_OK" == 1 && "$mode" != "--dns-only" && "$mode" != "--packages-only" ]]; then
  echo -n "home -> $HOME_ACT ... "
  "${ADB[@]}" shell cmd package set-home-activity "$HOME_ACT" 2>&1 | tr -d '\r'

  echo -n "remote-key accessibility service on ... "
  ensure_list enabled_accessibility_services "$A11Y"
  "${ADB[@]}" shell settings put secure accessibility_enabled 1 >/dev/null
  echo ok

  echo -n "notification listener on ... "
  ensure_list enabled_notification_listeners "$NOTIF"
  echo ok
fi

# 3. Disable the stock launcher + wizard, then confirm Home really resolves
#    to TV Home.  Disabling the stock launcher is what *forces* the system to
#    fall back to TV Home, so it must happen even when TV Home is not Home
#    yet - requiring it to be Home first deadlocks.  If the check after the
#    fact still doesn't see TV Home, put the stock launcher back so the TV
#    can never be left with no Home screen.
if [[ "$LAUNCHER_OK" == 1 && "$mode" != "--dns-only" ]]; then
  for pkg in "$STOCK_LAUNCHER" "$STOCK_WIZARD"; do
    echo -n "disable $pkg ... "
    "${ADB[@]}" shell pm disable-user --user 0 "$pkg" </dev/null 2>&1 | tr -d '\r'
  done

  # resolve-activity --brief reports the short form (pkg/.Activity), so
  # match on the package prefix rather than the full component name.
  case "$(current_home)" in
    "$LAUNCHER"/*) ;;
    *)
      echo "WARNING: Home did not resolve to $LAUNCHER - re-enabling the stock launcher" >&2
      "${ADB[@]}" shell pm enable --user 0 "$STOCK_LAUNCHER" </dev/null >/dev/null 2>&1
      ;;
  esac
fi

# 4. AdGuard Private DNS.
if [[ "$mode" != "--packages-only" && "$mode" != "--launcher-only" ]]; then
  echo -n "private dns -> adguard ... "
  "${ADB[@]}" shell settings put global private_dns_mode hostname >/dev/null
  "${ADB[@]}" shell settings put global private_dns_specifier dns.adguard-dns.com >/dev/null
  echo ok
fi

echo "Done. Reboot with: adb -s $HOST:$PORT reboot"
