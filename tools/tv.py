#!/usr/bin/env python3
"""tv.py - small ADB wrapper for cleanyourTV.

Usage:
  tools/tv.py 'shell command'                  run a shell command on the TV
  tools/tv.py --screenshot shots/01.png        capture what is on screen now
  tools/tv.py --screenshot shots/02.png DPAD_DOWN ENTER
                                               press keys, then capture
  tools/tv.py --keys DPAD_DOWN ENTER           press keys only

Host/port come from TV_HOST / TV_PORT (default 10.0.0.97 / 5555), so the
relay workaround in tools/relay.md works without editing anything.
"""
import os
import subprocess
import sys
import time

HOST = os.environ.get("TV_HOST", "10.0.0.97")
PORT = os.environ.get("TV_PORT", "5555")
SERIAL = f"{HOST}:{PORT}"
ADB = os.environ.get("ADB", "adb")


def adb(*args, **kw):
    return subprocess.run([ADB, "-s", SERIAL, *args],
                          capture_output=True, text=True, **kw)


def ensure_connected():
    out = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
    if f"{SERIAL}\tdevice" in out:
        return
    subprocess.run([ADB, "connect", SERIAL], capture_output=True, text=True)
    time.sleep(1)


def press(keys):
    for k in keys:
        k = k if k.startswith("KEYCODE_") else f"KEYCODE_{k}"
        adb("shell", "input", "keyevent", k)
        time.sleep(0.45)


def screenshot(path):
    """Capture on the TV, then pull. Empty PNGs mean the TV refused to
    capture (Live TV / HDMI / Netflix) - that is the TV, not a broken TV."""
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    remote = "/sdcard/_cytv_shot.png"
    adb("shell", "rm", "-f", remote)
    adb("shell", "screencap", "-p", remote)
    adb("pull", remote, path)
    adb("shell", "rm", "-f", remote)
    size = os.path.getsize(path) if os.path.exists(path) else 0
    if size == 0:
        print(f"{path}: 0 bytes - the TV refused to capture this screen "
              f"(Live TV, an HDMI input or a DRM app such as Netflix). "
              f"Check 'dumpsys activity activities | grep topResumedActivity' "
              f"instead.", file=sys.stderr)
        return 1
    print(f"{path}: {size} bytes")
    return 0


def main():
    argv = sys.argv[1:]
    if not argv:
        print(__doc__)
        return 2
    ensure_connected()

    shot = None
    if argv[0] == "--screenshot":
        shot, argv = argv[1], argv[2:]
    elif argv[0] == "--keys":
        argv = argv[1:]
        press(argv)
        return 0

    if shot is not None:
        if argv:
            press(argv)
            time.sleep(1.0)
        return screenshot(shot)

    r = adb("shell", *argv)
    sys.stdout.write(r.stdout)
    sys.stderr.write(r.stderr)
    return r.returncode


if __name__ == "__main__":
    sys.exit(main())
