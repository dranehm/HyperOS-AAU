#!/usr/bin/env python3
"""
Xiaomi BL-Auth Aggressive Direct Shooter
=========================================
Fires unlock requests DIRECTLY from this machine (or a VPS) bypassing the Android app.

Usage:
  python3 shoot.py --cookie "new_bbs_serviceToken=..." [options]
  python3 shoot.py --cookie "..." --waves 32 --bracket 100 --offset -50
  python3 shoot.py --cookie "..." --proxy socks5://127.0.0.1:1080
  python3 shoot.py --cookie "..." --dry-run        # test cookie only, don't wait

Options:
  --cookie    Full cookie string (required)
  --waves     Number of concurrent requests [default: 16]
  --bracket   Time spread in ms [default: 100]
  --offset    Manual adjustment in ms, +/- [default: 0]
  --proxy     SOCKS5 or HTTP proxy URL
  --dry-run   Validate cookie + measure latency, then exit
  --vps-mode  Disable NTP (trust host clock); useful on a low-latency Asian VPS
"""
import argparse
import asyncio
import json
import ntplib
import statistics
import sys
import time
from datetime import datetime, timezone, timedelta
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Optional, Tuple

try:
    import httpx
except ImportError:
    print("[!] httpx not installed. Run: pip3 install httpx")
    sys.exit(1)

# ── Constants ────────────────────────────────────────────────────────────────
UNLOCK_URL  = "https://sgp-api.buy.mi.com/bbs/api/global/apply/bl-auth"
LATENCY_URL = "https://sgp-api.buy.mi.com/"
USER_AGENT  = "okhttp/4.12.0"
BEIJING_TZ  = timezone(timedelta(hours=8))
NTP_SERVERS = ["pool.ntp.org", "time.cloudflare.com", "time.google.com"]

RESULT_MEANINGS = {
    1: "✅ APPROVED! Bootloader unlock authorized!",
    2: "✅ Already approved (already have authorization)",
    3: "⏳ Not yet (waiting period active)",
    6: "❌ Quota full — all slots taken for this midnight window",
}

# ── Colors ───────────────────────────────────────────────────────────────────
def green(s):  return f"\033[92m{s}\033[0m"
def red(s):    return f"\033[91m{s}\033[0m"
def yellow(s): return f"\033[93m{s}\033[0m"
def cyan(s):   return f"\033[96m{s}\033[0m"
def bold(s):   return f"\033[1m{s}\033[0m"

def ts():
    return datetime.now(BEIJING_TZ).strftime("%H:%M:%S.%f")[:-3] + " CST"

def log(msg, prefix=""):
    print(f"[{ts()}] {prefix}{msg}", flush=True)

# ── NTP offset ────────────────────────────────────────────────────────────────
def get_ntp_offset_ms() -> float:
    """Query 3 NTP servers, return median offset in milliseconds."""
    offsets = []
    for server in NTP_SERVERS:
        try:
            c = ntplib.NTPClient()
            resp = c.request(server, version=3, timeout=3)
            offsets.append(resp.offset * 1000)  # seconds → ms
            log(f"  NTP {server}: offset={resp.offset*1000:+.1f}ms")
        except Exception as e:
            log(f"  NTP {server}: unavailable ({e})")
    if not offsets:
        log(yellow("[NTP] All servers failed — trusting host clock (offset=0)"))
        return 0.0
    med = statistics.median(offsets)
    log(green(f"[NTP] Median offset: {med:+.1f}ms (from {len(offsets)} servers)"))
    return med

# ── Latency measurement ────────────────────────────────────────────────────────
def measure_latency_ms(cookie: str, proxy_url: Optional[str], n=5) -> float:
    """Fire n HEAD requests to target, return minimum RTT/2 (one-way estimate)."""
    headers = _build_headers(cookie)
    rtts = []
    transport = httpx.HTTPTransport(proxy=proxy_url) if proxy_url else None
    with httpx.Client(http2=True,  timeout=10) as client:
        for i in range(n):
            t0 = time.monotonic_ns()
            try:
                client.head(LATENCY_URL, headers=headers)
                rtt = (time.monotonic_ns() - t0) / 1_000_000
                rtts.append(rtt)
                log(f"  Ping {i+1}/{n}: RTT={rtt:.0f}ms")
            except Exception as e:
                log(f"  Ping {i+1}/{n}: ERROR {e}")
    if not rtts:
        log(red("[Latency] All pings failed! Using 500ms estimate"))
        return 500.0
    one_way = min(rtts) / 2
    log(green(f"[Latency] min RTT={min(rtts):.0f}ms → one-way estimate: {one_way:.0f}ms"))
    return one_way

# ── Request helpers ────────────────────────────────────────────────────────────
def _build_headers(cookie: str) -> dict:
    return {
        "Accept": "application/json",
        "Accept-Encoding": "gzip",
        "Connection": "Keep-Alive",
        "Content-Type": "application/json; charset=utf-8",
        "Cookie": cookie,
        "Host": "sgp-api.buy.mi.com",
        "User-Agent": USER_AGENT,
    }

def test_cookie(cookie: str, proxy_url: Optional[str]) -> Tuple[bool, int]:
    """Returns (is_valid, apply_result). result=3 means waiting, 1=approved, 6=quota."""
    headers = _build_headers(cookie)
    transport = httpx.HTTPTransport(proxy=proxy_url) if proxy_url else None
    t0 = time.monotonic_ns()
    try:
        with httpx.Client(http2=True,  timeout=10) as client:
            resp = client.post(UNLOCK_URL, content=b'{"is_retry":false}', headers=headers)
        rtt = (time.monotonic_ns() - t0) // 1_000_000
        body = resp.json()
        msg = body.get("msg", "")
        result = (body.get("data") or {}).get("apply_result", -1)
        meaning = RESULT_MEANINGS.get(result, f"unknown code={result}")
        log(f"[Cookie Test] HTTP {resp.status_code} | msg={msg} | result={result} {meaning} | RTT={rtt}ms")
        return msg != "need login", result
    except Exception as e:
        log(red(f"[Cookie Test] FAILED: {e}"))
        return False, -1

def fire_shot(wave_id: int, cookie: str, proxy_url: Optional[str], fire_at_ns: int) -> dict:
    """Sleep until fire_at_ns, then POST. Returns result dict."""
    headers = _build_headers(cookie)
    transport = httpx.HTTPTransport(proxy=proxy_url) if proxy_url else None
    
    # Sleep until 20ms before fire, then spin.
    # NOTE: On single-vCPU VPS, keep --waves ≤8 to avoid GIL contention
    # across competing spin-loops (32 threads × ~5ms GIL quantum = 160ms+ drift).
    sleep_until = fire_at_ns - 20_000_000  # wake 20ms early
    while time.monotonic_ns() < sleep_until:
        time.sleep(0.001)  # 1ms sleeps — accurate enough, low CPU
    while time.monotonic_ns() < fire_at_ns:
        pass  # spin the last 20ms
    
    send_time = datetime.now(BEIJING_TZ).strftime("%H:%M:%S.%f")[:-3]
    t0 = time.monotonic_ns()
    
    try:
        with httpx.Client(http2=True,  timeout=10) as client:
            resp = client.post(UNLOCK_URL, content=b'{"is_retry":false}', headers=headers)
        rtt = (time.monotonic_ns() - t0) // 1_000_000
        body = resp.json()
        msg = body.get("msg", "")
        result = (body.get("data") or {}).get("apply_result", -1)
        meaning = RESULT_MEANINGS.get(result, f"code={result}")
        
        color = green if result in (1, 2) else (red if result == 6 else yellow)
        log(color(f"[Wave {wave_id:02d}] sent={send_time} CST | HTTP {resp.status_code} | {msg} | result={result} {meaning} | RTT={rtt}ms"))
        return {"wave": wave_id, "result": result, "msg": msg, "rtt": rtt}
    except Exception as e:
        log(red(f"[Wave {wave_id:02d}] ERROR: {e}"))
        return {"wave": wave_id, "result": -1, "error": str(e)}

# ── Pre-warm keepalive ────────────────────────────────────────────────────────
def prewarm(cookie: str, proxy_url: Optional[str], until_ns: int):
    """Send HEAD pings every 5s until 2s before fire time to keep TCP alive."""
    headers = _build_headers(cookie)
    transport = httpx.HTTPTransport(proxy=proxy_url) if proxy_url else None
    log(cyan("[PreWarm] Starting keepalive HEAD pings every 5s..."))
    
    with httpx.Client(http2=True,  timeout=5) as client:
        while time.monotonic_ns() < until_ns - 2_000_000_000:
            try:
                t0 = time.monotonic_ns()
                client.head(LATENCY_URL, headers=headers)
                rtt = (time.monotonic_ns() - t0) // 1_000_000
                log(cyan(f"[PreWarm] ping OK RTT={rtt}ms — {(until_ns - time.monotonic_ns())//1_000_000_000:.0f}s to fire"))
            except Exception as e:
                log(yellow(f"[PreWarm] ping failed: {e}"))
            time.sleep(5)

# ── Main ──────────────────────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(description="Xiaomi BL-Auth Direct Shooter")
    parser.add_argument("--cookie",  required=True, help="Full cookie string")
    parser.add_argument("--waves",   type=int, default=16, help="Concurrent requests [16]")
    parser.add_argument("--bracket", type=int, default=100, help="Spread window ms [100]")
    parser.add_argument("--offset",  type=int, default=0, help="Manual timing offset ms [0]")
    parser.add_argument("--proxy",   default=None, help="Proxy URL e.g. socks5://host:port")
    parser.add_argument("--dry-run", action="store_true", help="Test cookie+latency only")
    parser.add_argument("--vps-mode", action="store_true", help="Skip NTP, trust host clock")
    args = parser.parse_args()

    print(bold(cyan("\n" + "="*60)))
    print(bold(cyan("  Xiaomi BL-Auth Aggressive Direct Shooter")))
    print(bold(cyan("="*60 + "\n")))

    # 1. NTP sync
    if args.vps_mode:
        ntp_offset_ms = 0.0
        log(yellow("[NTP] VPS mode — trusting host clock (offset=0)"))
    else:
        log("[NTP] Querying time servers...")
        ntp_offset_ms = get_ntp_offset_ms()

    # 2. Test cookie
    log("[Auth] Testing cookie...")
    valid, test_result = test_cookie(args.cookie, args.proxy)
    if not valid:
        log(red("[!] Cookie rejected ('need login'). Please refresh your Mi account session."))
        sys.exit(1)
    log(green(f"[Auth] Cookie is valid! (test result={test_result})"))

    if args.dry_run:
        log("[Latency] Measuring one-way latency...")
        lat = measure_latency_ms(args.cookie, args.proxy)
        log(green(f"[DryRun] Done. One-way latency={lat:.0f}ms, NTP offset={ntp_offset_ms:+.1f}ms"))
        return

    # 3. Measure latency
    log("[Latency] Measuring one-way latency to target server...")
    one_way_ms = measure_latency_ms(args.cookie, args.proxy)

    # 4. Calculate target Beijing midnight
    now_real_ms = time.time() * 1000 + ntp_offset_ms
    now_dt = datetime.fromtimestamp(now_real_ms / 1000, tz=BEIJING_TZ)
    
    # Next midnight Beijing
    target_dt = now_dt.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=1)
    target_real_ms = target_dt.timestamp() * 1000
    target_mono_ns = int(time.monotonic_ns() + (target_real_ms - now_real_ms) * 1_000_000)

    # Adjust: send `one_way_ms` before midnight so packets ARRIVE at midnight
    # Apply manual offset
    adjusted_mono_ns = target_mono_ns - int((one_way_ms + args.offset) * 1_000_000)

    # Wave fire times: spread 0 → bracket_ms (all after midnight arrival)
    step_ns = int(args.bracket * 1_000_000 / max(1, args.waves - 1)) if args.waves > 1 else 0
    fire_times = [adjusted_mono_ns + i * step_ns for i in range(args.waves)]

    wait_s = (adjusted_mono_ns - time.monotonic_ns()) / 1_000_000_000
    log(bold(green(f"[Target] {target_dt.strftime('%Y-%m-%d %H:%M:%S')} CST (Beijing Midnight)")))
    log(f"[Setup] waves={args.waves} | bracket={args.bracket}ms | offset={args.offset:+d}ms | one_way={one_way_ms:.0f}ms | NTP={ntp_offset_ms:+.1f}ms")
    log(f"[Setup] First shot fires in {wait_s:.1f}s ({wait_s/60:.1f} min)")

    # 5. Pre-warm at T-30s
    prewarm_start_ns = adjusted_mono_ns - 30_000_000_000
    now_ns = time.monotonic_ns()
    if prewarm_start_ns > now_ns:
        sleep_until = (prewarm_start_ns - now_ns) / 1_000_000_000
        log(f"[Wait] Sleeping {sleep_until:.1f}s until pre-warm phase...")
        time.sleep(sleep_until)
    
    prewarm_thread = None
    import threading
    prewarm_thread = threading.Thread(
        target=prewarm, 
        args=(args.cookie, args.proxy, fire_times[0]),
        daemon=True
    )
    prewarm_thread.start()

    # 6. Fire waves concurrently
    log(bold(cyan(f"\n[FIRE] Launching {args.waves} concurrent waves!")))
    results = []
    with ThreadPoolExecutor(max_workers=args.waves) as pool:
        futures = {
            pool.submit(fire_shot, i+1, args.cookie, args.proxy, fire_times[i]): i
            for i in range(args.waves)
        }
        for future in as_completed(futures):
            results.append(future.result())

    # 7. Summary
    print(bold(cyan("\n" + "="*60)))
    print(bold("RESULTS SUMMARY"))
    print("="*60)
    
    approved  = [r for r in results if r.get("result") in (1, 2)]
    quota     = [r for r in results if r.get("result") == 6]
    waiting   = [r for r in results if r.get("result") == 3]
    errors    = [r for r in results if r.get("result") == -1]
    
    if approved:
        print(bold(green(f"\n🎉 SUCCESS! {len(approved)} wave(s) APPROVED!")))
        print(green("  → Bootloader unlock authorization granted!"))
        print(green("  → Go to Settings → Developer Options → Unlock Bootloader"))
    elif quota:
        print(bold(red(f"\n❌ QUOTA FULL ({len(quota)} waves). All slots taken.")))
        print(yellow("  → Strategies to try tomorrow:"))
        print(yellow("    1. Increase waves to 32"))
        print(yellow("    2. Use --proxy socks5://<Singapore VPS>:1080 for lower latency"))
        print(yellow("    3. Reduce --offset by 50ms (fire slightly earlier)"))
        print(yellow("    4. Use mtkclient for hardware-level bypass (see mtk_bypass.sh)"))
    elif waiting:
        print(bold(yellow(f"\n⏳ WAITING ({len(waiting)} waves). Server said 'not yet'.")))
        print(yellow("  → Your 168h waiting period may not be complete yet."))
    else:
        print(bold(red(f"\n❓ No clean result. errors={len(errors)}, check logs above.")))
    
    print(f"\nWave RTTs: {sorted([r.get('rtt',0) for r in results if 'rtt' in r])}")

if __name__ == "__main__":
    main()
