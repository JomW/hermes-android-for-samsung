#!/usr/bin/env python3
"""Standalone relay daemon for hermes-android. Runs the WebSocket relay persistently."""

import os
import sys
import signal
import time
import logging
import threading

logging.basicConfig(
    level=logging.INFO, format="%(asctime)s [%(name)s] %(levelname)s: %(message)s"
)
logger = logging.getLogger("hermes-relay-daemon")


def _watch_gateway_and_parent(stop_event):
    """Exit the daemon (code 0) when the Hermes gateway or our supervisor dies.

    Polls HERMES_DATA/gateway.pid (JSON {"pid": N}). When the file exists but
    the recorded PID is gone for >10s the gateway is considered down. Also
    exits immediately if our parent (relay-supervisor.py) is killed — e.g. by
    `net stop android-relay-daemon` or an NSSM recycle — so no orphan daemon
    is ever left behind.
    """
    import ctypes
    import json

    hermes_data = os.environ.get("HERMES_DATA", "I:/hermes/data")
    pid_file = os.path.join(hermes_data, "gateway.pid")
    PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

    def pid_alive(pid):
        if pid <= 0:
            return False
        try:
            h = ctypes.windll.kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
            if h:
                ctypes.windll.kernel32.CloseHandle(h)
                return True
            return ctypes.windll.kernel32.GetLastError() == 5
        except Exception:
            return False

    parent_pid = os.getppid()
    dead_since = None
    while not stop_event.is_set():
        if not pid_alive(parent_pid):
            logger.info("supervisor (parent PID %d) is gone — relay daemon exiting", parent_pid)
            stop_event.set()
            break
        gw_pid = 0
        try:
            with open(pid_file, "r", encoding="utf-8") as f:
                gw_pid = int(json.load(f).get("pid", 0))
        except Exception:
            gw_pid = 0
        if gw_pid > 0 and pid_alive(gw_pid):
            dead_since = None
        elif gw_pid > 0:
            if dead_since is None:
                dead_since = time.time()
            elif time.time() - dead_since >= 10:
                logger.info("Hermes gateway process is down — relay daemon exiting")
                stop_event.set()
                break
        stop_event.wait(2)


def main():
    pairing_code = os.getenv("ANDROID_BRIDGE_TOKEN")
    port = int(os.getenv("ANDROID_RELAY_PORT", "8766"))

    if not pairing_code:
        logger.error(
            "ANDROID_BRIDGE_TOKEN not set. Set it in ~/.hermes/.env or environment."
        )
        sys.exit(1)

    script_dir = os.path.dirname(os.path.abspath(__file__))
    parent_dir = os.path.dirname(script_dir)
    tools_dir = os.path.join(parent_dir, "tools")
    if os.path.isdir(tools_dir):
        sys.path.insert(0, parent_dir)

    from tools.android_relay import start_relay, stop_relay

    start_relay(pairing_code=pairing_code, port=port)
    logger.info("Relay started on port %d with pairing code ****", port)

    stop_event = threading.Event()

    def handle_signal(signum, frame):
        logger.info("Received signal %s, shutting down...", signum)
        stop_event.set()

    signal.signal(signal.SIGTERM, handle_signal)
    signal.signal(signal.SIGINT, handle_signal)

    threading.Thread(target=_watch_gateway_and_parent, args=(stop_event,), daemon=True).start()

    stop_event.wait()
    stop_relay()
    logger.info("Daemon exiting")


if __name__ == "__main__":
    main()
