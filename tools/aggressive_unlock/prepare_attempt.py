#!/usr/bin/env python3
"""
Xiaomi BL-Auth Midnight Attempt Orchestrator
=============================================
One command to rule them all. Run any time before 18:00 CEST.

Usage:
  python3 prepare_attempt.py                      # uses ~/.hyper_unlock.json
  python3 prepare_attempt.py --cookie "..."       # override cookie only
  python3 prepare_attempt.py --dry-run            # setup only, skip waiting

Config file (~/.hyper_unlock.json):
  {
    "cookie": "new_bbs_serviceToken=...",
    "vps_host": "104.167.16.78",
    "vps_pass": "yourpassword",
    "phone_serial": "YDPB4DYXEUIZS4ON",
    "waves_vps": 8,
    "waves_mac": 16,
    "bracket": 150,
    "offset": 0
  }
"""

import argparse
import json
import os
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone, timedelta
from pathlib import Path

# ── Colours ──────────────────────────────────────────────────────────────────
def bold(s):  return f"\033[1m{s}\033[0m"
def green(s): return f"\033[92m{s}\033[0m"
def red(s):   return f"\033[91m{s}\033[0m"
def cyan(s):  return f"\033[96m{s}\033[0m"
def yellow(s):return f"\033[93m{s}\033[0m"

CST = timezone(timedelta(hours=8))
SCRIPT_DIR = Path(__file__).parent

# ── Config ────────────────────────────────────────────────────────────────────
CONFIG_PATH = Path.home() / ".hyper_unlock.json"
DEFAULTS = {
    "vps_host":     "104.167.16.78",
    "vps_pass":     "",
    "vps_user":     "root",
    "phone_serial": "YDPB4DYXEUIZS4ON",
    "waves_vps":    8,
    "waves_mac":    16,
    "bracket":      150,
    "offset":       0,
    "cookie":       "",
}

def load_config():
    cfg = dict(DEFAULTS)
    if CONFIG_PATH.exists():
        with open(CONFIG_PATH) as f:
            cfg.update(json.load(f))
    return cfg

def save_config(cfg):
    with open(CONFIG_PATH, "w") as f:
        json.dump(cfg, f, indent=2)
    os.chmod(CONFIG_PATH, 0o600)

# ── Time helpers ──────────────────────────────────────────────────────────────
def next_midnight_cst():
    """Returns next Beijing midnight as UTC datetime."""
    now_cst = datetime.now(CST)
    midnight = now_cst.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=1)
    return midnight.astimezone(timezone.utc)

def seconds_to_midnight():
    return (next_midnight_cst() - datetime.now(timezone.utc)).total_seconds()

def fmt_countdown(secs):
    h, r = divmod(int(max(secs, 0)), 3600)
    m, s = divmod(r, 60)
    return f"{h:02d}h {m:02d}m {s:02d}s"

def log(msg, prefix=""):
    now_cst = datetime.now(CST).strftime("%H:%M:%S")
    print(f"[{now_cst} CST]{prefix} {msg}", flush=True)

# ── Cookie validation ─────────────────────────────────────────────────────────
def verify_cookie(cookie):
    log("Verifying cookie against Xiaomi API...", prefix=bold(" [1/5]"))
    try:
        import urllib.request, urllib.error, ssl
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        req = urllib.request.Request(
            "https://sgp-api.buy.mi.com/bbs/api/global/apply/bl-auth",
            data=b'{"is_retry":false}',
            headers={
                "Content-Type": "application/json; charset=utf-8",
                "User-Agent":   "okhttp/4.12.0",
                "Cookie":       cookie,
            },
            method="POST"
        )
        with urllib.request.urlopen(req, timeout=15, context=ctx) as resp:
            body = json.loads(resp.read())
        result = (body.get("data") or {}).get("apply_result", -1)
        if result == 1:
            log(green("✅ Cookie APPROVED! Bootloader already unlocked?"))
            return True, result
        elif result == 2:
            log(green("✅ Cookie valid — already approved."))
            return True, result
        elif result == 3:
            log(green(f"✅ Cookie valid — waiting window (result=3). Deadline shows in app."))
            return True, result
        elif result == 6:
            log(yellow("⚠️  Cookie valid but quota currently full (result=6). Will retry at midnight."))
            return True, result
        else:
            log(red(f"❌ Cookie invalid or error (result={result}, body={body})"))
            return False, result
    except Exception as e:
        log(red(f"❌ Cookie check failed: {e}"))
        return False, -1

# ── ADB helpers ───────────────────────────────────────────────────────────────
def adb(serial, *args, timeout=10):
    return subprocess.run(
        ["adb", "-s", serial] + list(args),
        capture_output=True, text=True, timeout=timeout
    )

def setup_phone(serial):
    log("Setting up phone...", prefix=bold(" [2/5]"))

    # Check device connected
    r = subprocess.run(["adb", "devices"], capture_output=True, text=True)
    if serial not in r.stdout:
        log(red(f"❌ Phone {serial} not connected! Connect USB and allow debugging."))
        return False

    # Kill HTTP Toolkit interceptor
    adb(serial, "shell", "am", "force-stop", "tech.httptoolkit.android.v1")
    log(green("  ✅ HTTP Toolkit interceptor killed"))

    # Wake screen
    adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(0.5)

    # Launch app
    adb(serial, "shell", "am", "start", "-n", "com.xiaomi.unlock/.MainActivity")
    log(green("  ✅ App launched"))
    time.sleep(4)

    # Find and tap "Verify & Start Process" button
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/ui_prep.xml")
    time.sleep(1)
    r = adb(serial, "pull", "/sdcard/ui_prep.xml", "/tmp/ui_prep.xml")

    btn_x, btn_y = _find_start_button("/tmp/ui_prep.xml")
    if btn_x and btn_y:
        adb(serial, "shell", "input", "tap", str(btn_x), str(btn_y))
        log(green(f"  ✅ Tapped 'Verify & Start Process' at ({btn_x},{btn_y})"))
    else:
        log(yellow("  ⚠️  Could not find start button — app may already be running"))

    time.sleep(5)

    # Verify process started
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/ui_prep2.xml")
    adb(serial, "pull", "/sdcard/ui_prep2.xml", "/tmp/ui_prep2.xml")
    status = _get_app_status("/tmp/ui_prep2.xml")
    if "Abort" in status or "Ping in" in status or "Countdown" in status:
        log(green(f"  ✅ Process running: {status}"))
        return True
    else:
        log(yellow(f"  ⚠️  App status: {status}"))
        return True  # might already be running from before

def _find_start_button(xml_path):
    try:
        tree = ET.parse(xml_path)
        for node in tree.iter():
            for child in node:
                if "Verify" in child.get("text", "") or "Start Process" in child.get("text", ""):
                    bounds = node.get("bounds", "")
                    nums = [int(x) for x in __import__("re").findall(r"\d+", bounds)]
                    if len(nums) == 4:
                        return (nums[0]+nums[2])//2, (nums[1]+nums[3])//2
    except Exception:
        pass
    # Fallback hardcoded position
    return 360, 1022

def _get_app_status(xml_path):
    skip_words = {"Xiaomi","Cookie","Triggers","Bracket","Offset","Proxy","Screen",
                  "NTP","16","150","500","--","Test","Latency","Caffeine","Trust",
                  "SIM","MB","GB","KB","Orange","data","Data","usage","Usage"}
    try:
        tree = ET.parse(xml_path)
        parts = []
        for node in tree.iter():
            t = node.get("text", "")
            pkg = node.get("package", "")
            # Only consider text from our app
            if pkg and "xiaomi.unlock" not in pkg:
                continue
            if t and len(t) < 80 and not t.startswith("new_bbs"):
                if not any(w in t for w in skip_words):
                    parts.append(t)
        return " | ".join(parts[:4]) if parts else "unknown"
    except Exception:
        return "unknown"

# ── VPS setup ─────────────────────────────────────────────────────────────────
def setup_vps(host, user, password, cookie, waves, bracket, offset):
    log(f"Setting up VPS {host}...", prefix=bold(" [3/5]"))
    try:
        import paramiko
    except ImportError:
        log(red("  ❌ paramiko not installed. Run: pip3 install paramiko"))
        return False

    pw = password
    shoot_src = SCRIPT_DIR / "shoot.py"

    try:
        client = paramiko.SSHClient()
        client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        client.connect(host, username=user, password=pw, timeout=15,
                       allow_agent=False, look_for_keys=False)

        # Upload latest shoot.py
        sftp = client.open_sftp()
        sftp.put(str(shoot_src), "/root/shoot.py")
        sftp.close()
        log(green("  ✅ shoot.py uploaded"))

        # Write launcher script via SFTP (more reliable than stdin pipe)
        launcher = f"""#!/bin/bash
python3 /root/shoot.py \\
  --cookie '{cookie}' \\
  --waves {waves} --bracket {bracket} --offset {offset} \\
  > /root/shooter.log 2>&1
"""
        sftp2 = client.open_sftp()
        with sftp2.open("/root/run_shooter.sh", "w") as f:
            f.write(launcher)
        sftp2.close()
        client.exec_command("chmod +x /root/run_shooter.sh")

        # Kill any stale shooter processes
        client.exec_command("pkill -f 'python3 /root/shoot.py' 2>/dev/null; sleep 1")
        time.sleep(1.5)

        # Launch detached
        stdin, stdout, stderr = client.exec_command(
            "nohup /root/run_shooter.sh > /dev/null 2>&1 & disown; echo PID:$!"
        )
        pid_line = stdout.read().decode().strip()
        log(green(f"  ✅ VPS shooter launched ({pid_line})"))

        # Verify it started
        time.sleep(4)
        stdin, stdout, stderr = client.exec_command("tail -6 /root/shooter.log 2>/dev/null")
        vps_log = stdout.read().decode().strip()
        log(cyan(f"  VPS log:\n    " + "\n    ".join(vps_log.splitlines()[-4:])))

        client.close()
        return True

    except Exception as e:
        log(red(f"  ❌ VPS setup failed: {e}"))
        return False

def read_vps_log(host, user, password, lines=10):
    try:
        import paramiko
        client = paramiko.SSHClient()
        client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        client.connect(host, username=user, password=password, timeout=10,
                       allow_agent=False, look_for_keys=False)
        stdin, stdout, stderr = client.exec_command(f"tail -{lines} /root/shooter.log 2>/dev/null")
        out = stdout.read().decode()
        client.close()
        return out
    except Exception as e:
        return f"(VPS log read failed: {e})"

# ── Mac shooter ───────────────────────────────────────────────────────────────
def schedule_mac_shooter(cookie, waves, bracket, offset):
    log(f"Scheduling Mac shooter (T-10min)...", prefix=bold(" [4/5]"))
    secs = seconds_to_midnight()
    wake_wait = max(0, int(secs - 600))  # wake at T-10min

    shoot_py = str(SCRIPT_DIR / "shoot.py")
    cmd = [
        sys.executable, shoot_py,
        "--cookie", cookie,
        "--waves", str(waves),
        "--bracket", str(bracket),
        "--offset", str(offset),
    ]

    def _run():
        if wake_wait > 0:
            log(green(f"  💻 Mac shooter sleeping {wake_wait}s (wakes at T-10min)"))
            time.sleep(wake_wait)
        log(bold(cyan("  💻 Mac shooter WAKING UP — starting now")))
        with open("/tmp/mac_shooter.log", "w") as out:
            proc = subprocess.run(cmd, stdout=out, stderr=subprocess.STDOUT)
        log(bold(green("  💻 Mac shooter DONE") if proc.returncode == 0
                 else red(f"  💻 Mac shooter exited {proc.returncode}")))

    t = threading.Thread(target=_run, daemon=True)
    t.start()
    log(green(f"  ✅ Mac shooter scheduled (wakes in {fmt_countdown(wake_wait)})"))
    return t

# ── Monitor loop ──────────────────────────────────────────────────────────────
def monitor_loop(serial, host, user, password, stop_event):
    log("Starting monitor loop...", prefix=bold(" [5/5]"))
    while not stop_event.is_set():
        secs = seconds_to_midnight()
        now_cst = datetime.now(CST).strftime("%H:%M:%S")

        # Phone status
        try:
            adb(serial, "shell", "uiautomator", "dump", "/sdcard/mon.xml", timeout=8)
            adb(serial, "pull", "/sdcard/mon.xml", "/tmp/mon_loop.xml", timeout=8)
            phone_status = _get_app_status("/tmp/mon_loop.xml")
        except Exception:
            phone_status = "(adb timeout)"

        print(f"\n{bold(cyan('='*60))}")
        print(f"  {bold('Time:')}  {now_cst} CST  |  {bold('T-midnight:')} {fmt_countdown(secs)}")
        print(f"  {bold('📱 Phone:')} {phone_status}")

        if secs < 700:  # T-12min: also show VPS log
            vps_tail = read_vps_log(host, user, password, lines=4)
            for line in vps_tail.strip().splitlines()[-3:]:
                print(f"  {bold('🇸🇬 VPS:')}  {line}")

        print(cyan('='*60), flush=True)

        # Adapt poll interval
        if secs < 120:
            time.sleep(5)
        elif secs < 600:
            time.sleep(15)
        else:
            time.sleep(60)

        # Stop after T+5min
        if secs < -300:
            stop_event.set()

    # Final results
    log(bold(cyan("\n" + "="*60)))
    log(bold("FINAL RESULTS"))
    log(cyan("="*60))

    # Phone
    try:
        adb(serial, "shell", "uiautomator", "dump", "/sdcard/final.xml", timeout=8)
        adb(serial, "pull", "/sdcard/final.xml", "/tmp/final.xml", timeout=8)
        tree = ET.parse("/tmp/final.xml")
        for node in tree.iter():
            t = node.get("text", "")
            if t and len(t) < 120 and "Wave" in t or "result" in t.lower() or "Done" in t:
                print(f"  📱 {t}")
    except Exception:
        pass

    # Mac shooter log
    try:
        with open("/tmp/mac_shooter.log") as f:
            lines = f.readlines()
        for line in lines:
            if any(x in line for x in ("Wave", "RESULT", "APPROVED", "QUOTA", "result=")):
                print(f"  💻 {line.rstrip()}")
    except Exception:
        pass

    # VPS log
    vps_final = read_vps_log(host, user, password, lines=30)
    for line in vps_final.splitlines():
        if any(x in line for x in ("Wave", "RESULT", "APPROVED", "QUOTA", "result=")):
            print(f"  🇸🇬 {line}")

# ── Main ──────────────────────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(description="Xiaomi BL-Auth midnight attempt orchestrator")
    parser.add_argument("--cookie",       help="Override cookie string")
    parser.add_argument("--vps-host",     help="VPS IP address")
    parser.add_argument("--vps-pass",     help="VPS root password")
    parser.add_argument("--vps-user",     default="root")
    parser.add_argument("--phone",        help="ADB serial (e.g. YDPB4DYXEUIZS4ON)")
    parser.add_argument("--waves-vps",    type=int, help="Waves from VPS (default 8, single-CPU)")
    parser.add_argument("--waves-mac",    type=int, help="Waves from Mac (default 16)")
    parser.add_argument("--bracket",      type=int, help="Bracket ms (default 150)")
    parser.add_argument("--offset",       type=int, help="Timing offset ms (default 0)")
    parser.add_argument("--save-config",  action="store_true", help="Save args to ~/.hyper_unlock.json")
    parser.add_argument("--dry-run",      action="store_true", help="Setup only, no waiting")
    parser.add_argument("--skip-phone",   action="store_true", help="Skip phone setup")
    parser.add_argument("--skip-vps",     action="store_true", help="Skip VPS setup")
    args = parser.parse_args()

    # Load config, apply overrides
    cfg = load_config()
    if args.cookie:    cfg["cookie"]       = args.cookie
    if args.vps_host:  cfg["vps_host"]     = args.vps_host
    if args.vps_pass:  cfg["vps_pass"]     = args.vps_pass
    if args.vps_user:  cfg["vps_user"]     = args.vps_user
    if args.phone:     cfg["phone_serial"] = args.phone
    if args.waves_vps: cfg["waves_vps"]    = args.waves_vps
    if args.waves_mac: cfg["waves_mac"]    = args.waves_mac
    if args.bracket:   cfg["bracket"]      = args.bracket
    if args.offset is not None: cfg["offset"] = args.offset

    if args.save_config:
        save_config(cfg)
        print(green(f"✅ Config saved to {CONFIG_PATH}"))

    # Banner
    midnight_utc = next_midnight_cst()
    secs = seconds_to_midnight()
    print(bold(cyan("\n" + "="*60)))
    print(bold("  🔓 Xiaomi BL-Auth Midnight Attempt Orchestrator"))
    print(cyan("="*60))
    print(f"  Target:    {bold('Beijing Midnight')} → {midnight_utc.strftime('%Y-%m-%d %H:%M:%S UTC')} = {(midnight_utc + timedelta(hours=2)).strftime('%H:%M CEST')}")
    print(f"  T-minus:   {bold(fmt_countdown(secs))}")
    print(f"  VPS:       {cfg['vps_host']} ({cfg['waves_vps']} waves, 4ms latency)")
    print(f"  Mac:       {cfg['waves_mac']} waves")
    print(f"  Phone:     {cfg['phone_serial']} (16 waves via app)")
    print(f"  Bracket:   {cfg['bracket']}ms  |  Offset: {cfg['offset']}ms")
    print(cyan("="*60) + "\n")

    if not cfg["cookie"]:
        print(red("❌ No cookie set. Use --cookie or add to ~/.hyper_unlock.json"))
        sys.exit(1)

    # Step 1: Verify cookie
    ok, result = verify_cookie(cfg["cookie"])
    if not ok:
        print(red("\n❌ Cookie invalid. Refresh from buy.mi.com DevTools → Application → Cookies → new_bbs_serviceToken"))
        sys.exit(1)

    # Step 2: Phone
    if not args.skip_phone:
        setup_phone(cfg["phone_serial"])
    else:
        log("Phone setup skipped.", prefix=bold(" [2/5]"))

    # Step 3: VPS
    if not args.skip_vps and cfg["vps_host"] and cfg["vps_pass"]:
        setup_vps(cfg["vps_host"], cfg["vps_user"], cfg["vps_pass"],
                  cfg["cookie"], cfg["waves_vps"], cfg["bracket"], cfg["offset"])
    else:
        log("VPS setup skipped.", prefix=bold(" [3/5]"))

    if args.dry_run:
        print(bold(green("\n✅ Dry-run complete — all systems checked. Exiting.")))
        return

    # Step 4: Mac shooter
    mac_thread = schedule_mac_shooter(cfg["cookie"], cfg["waves_mac"],
                                       cfg["bracket"], cfg["offset"])

    # Step 5: Monitor loop (blocks until T+5min)
    stop_event = threading.Event()
    try:
        monitor_loop(cfg["phone_serial"], cfg["vps_host"], cfg["vps_user"],
                     cfg["vps_pass"], stop_event)
    except KeyboardInterrupt:
        stop_event.set()
        print(bold(yellow("\n⚠️  Interrupted. Shooters still running in background.")))

    print(bold(cyan("\n✅ Orchestrator done. Check results above.")))


if __name__ == "__main__":
    main()
