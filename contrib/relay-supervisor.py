#!/usr/bin/env python3
"""Android relay supervisor — ties the relay daemon's lifecycle to the Hermes gateway.

NSSM runs THIS script (not the relay directly). It:

  * spawns hermes-relay-daemon.py as a child while the Hermes gateway is alive,
  * reaps the child when the gateway goes away (the daemon also self-exits via
    its own gateway/parent watch), then idles until the gateway returns,
  * respawns the daemon if it crashes while the gateway is still alive,
  * exits non-zero only after repeated crashes, so NSSM (AppExit Default
    Restart + AppRestartDelay) can recycle the service.

CRITICAL: this script must be launched with the RELAY venv interpreter
(I:/hermes/Jom/hermes-android/.venv-relay), NOT the Hermes venv — the whole
point is that this service never maps the Hermes venv's native modules, so
`hermes update` is never blocked by it and the daemon can keep running while
the gateway is up.

Gateway liveness comes from <HERMES_DATA>/gateway.pid (JSON {"pid": N}),
which the Hermes gateway rewrites on every start.
"""
import ctypes
import json
import logging
import os
import subprocess
import sys
import time

HERMES_DATA = os.environ.get("HERMES_DATA", "I:/hermes/data")
GATEWAY_PID_FILE = os.path.join(HERMES_DATA, "gateway.pid")
DAEMON = os.path.join(os.path.dirname(os.path.abspath(__file__)), "hermes-relay-daemon.py")
LOG_FILE = os.path.join(HERMES_DATA, "logs", "relay-supervisor.log")

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [relay-supervisor] %(levelname)s: %(message)s",
    handlers=[logging.FileHandler(LOG_FILE, encoding="utf-8"), logging.StreamHandler(sys.stderr)],
)
log = logging.getLogger("relay-supervisor")

PROCESS_QUERY_LIMITED_INFORMATION = 0x1000


def pid_alive(pid):
    """True if a process with this PID exists (or exists but we lack access)."""
    if pid <= 0:
        return False
    try:
        h = ctypes.windll.kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        if h:
            ctypes.windll.kernel32.CloseHandle(h)
            return True
        return ctypes.windll.kernel32.GetLastError() == 5  # access denied => exists
    except Exception:
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False


_PROC_SCAN_CACHE = {"ts": 0.0, "alive": False}


def _desktop_gateway_alive():
    """True if any python process runs `hermes_cli.main ... serve|gateway run` (desktop mode).

    The desktop app (`hermes_cli.main serve`) does NOT reliably maintain
    I:/hermes/data/gateway.pid (container_boot.py deletes stale copies), so
    the pid-file check alone misreads a healthy desktop gateway as down.
    Scans process command lines; cached for 5s to avoid spawning PowerShell
    on every poll. Same approach as vision_server.py --watch-pid detection.
    """
    now = time.time()
    if now - _PROC_SCAN_CACHE["ts"] < 5:
        return _PROC_SCAN_CACHE["alive"]
    alive = False
    try:
        out = subprocess.run(
            [
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "(Get-CimInstance Win32_Process -Filter \"Name='python.exe'\").CommandLine",
            ],
            capture_output=True, text=True, timeout=10,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        ).stdout or ""
        alive = any(
            "hermes_cli.main" in ln and ("serve" in ln or "gateway" in ln)
            for ln in out.splitlines()
        )
    except Exception:
        alive = False
    _PROC_SCAN_CACHE.update(ts=now, alive=alive)
    return alive


def gateway_alive():
    """Report whether the Hermes gateway process lives.

    Primary check: I:/hermes/data/gateway.pid (CLI `gateway run` mode).
    Fallback: scan for desktop-mode gateway processes, because the desktop
    app does not maintain that pid file.
    """
    try:
        with open(GATEWAY_PID_FILE, "r", encoding="utf-8") as f:
            gw_pid = int(json.load(f).get("pid", 0))
        if gw_pid > 0 and pid_alive(gw_pid):
            return True
    except Exception:
        pass
    return _desktop_gateway_alive()


def spawn_daemon():
    env = dict(os.environ)
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    return subprocess.Popen(
        [sys.executable, DAEMON],
        env=env,
        cwd=os.path.dirname(DAEMON),
        creationflags=flags,
    )


def main():
    log.info("relay supervisor starting (gateway.pid=%s)", GATEWAY_PID_FILE)
    crashes = 0
    while True:
        if not gateway_alive():
            time.sleep(3)  # gateway down: idle, hold nothing
            continue

        crashes = 0
        log.info("gateway alive — starting relay daemon")
        proc = spawn_daemon()
        while gateway_alive() and proc.poll() is None:
            time.sleep(2)

        if proc.poll() is None:
            # Gateway died while the daemon ran: stop the daemon, then idle.
            log.info("gateway went away — stopping relay daemon")
            proc.terminate()
            try:
                proc.wait(timeout=8)
            except subprocess.TimeoutExpired:
                proc.kill()
            continue

        code = proc.returncode
        if code == 0:
            log.info("relay daemon exited cleanly (code 0) — gateway down or service stop")
            continue

        crashes += 1
        log.error("relay daemon crashed (code %s) — attempt %d/3", code, crashes)
        if crashes >= 3:
            log.error("too many crashes in a row — exiting non-zero for NSSM restart")
            return 1
        time.sleep(5)


if __name__ == "__main__":
    sys.exit(main())
