#!/usr/bin/env python3
"""adb-closed.py - prove the TV is no longer listening on the ADB port.

    tools/adb-closed.py 10.0.0.97 [port]

Only "connection refused" proves the port is shut.  A timeout or "no route
to host" proves nothing on macOS, where Local Network privacy can block the
probe itself - which is also why this uses a plain socket rather than nc.
"""
import socket
import sys


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "10.0.0.97"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 5555
    s = socket.socket()
    s.settimeout(5)
    try:
        s.connect((host, port))
    except ConnectionRefusedError:
        print(f"OK: {host}:{port} refused the connection - ADB is closed.")
        return 0
    except socket.timeout:
        print(f"INCONCLUSIVE: {host}:{port} timed out. A filtered port and a "
              f"blocked probe look identical; this is not proof.")
        return 2
    except OSError as e:
        print(f"INCONCLUSIVE: {host}:{port} -> {e}. On macOS this is often "
              f"Local Network privacy blocking this process, not the TV.")
        return 2
    else:
        print(f"STILL OPEN: {host}:{port} accepted a connection. ADB is "
              f"listening; anyone on the LAN can reach it.")
        return 1
    finally:
        s.close()


if __name__ == "__main__":
    sys.exit(main())
