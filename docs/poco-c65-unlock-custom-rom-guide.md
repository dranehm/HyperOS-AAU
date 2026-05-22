# Poco C65 / Redmi 13C — Bootloader Unlock Bypass & Custom ROM Guide

> **Device codenames:** `gale` (Global / EEA) · `gust` (India)  
> **Chipset:** MediaTek Helio G85 (MT6769Z)  
> **Tested on:** MIUI 14 · HyperOS 1.x  
> ⚠️ Most methods below **do not work on HyperOS 2.x**. If you're on HyperOS 2, start with [Strategy C](#strategy-c--firmware-downgrade-via-sp-flash-tool) to downgrade first.

---

## Table of Contents

1. [Prerequisites & Safety](#1-prerequisites--safety)
2. [Check Your Device Variant & Firmware](#2-check-your-device-variant--firmware)
3. [Strategy A — Official Method + HyperOS AAU App](#3-strategy-a--official-method--hyperos-aau-app-safe-168h-wait)
4. [Strategy B — MTKClient BROM Exploit (No Wait)](#4-strategy-b--mtkclient-brom-exploit-no-wait-medium-risk)
5. [Strategy C — Firmware Downgrade via SP Flash Tool](#5-strategy-c--firmware-downgrade-via-sp-flash-tool-medium-risk)
6. [Flash Custom Recovery (TWRP)](#6-flash-custom-recovery-twrp)
7. [Custom ROM Options & Comparison](#7-custom-rom-options--comparison)
8. [Flash the ROM](#8-flash-the-rom)
9. [Troubleshooting](#9-troubleshooting)

---

## 1. Prerequisites & Safety

### Tools to install on your PC
| Tool | Purpose | Download |
|------|---------|----------|
| ADB + Fastboot (Platform Tools) | USB bridge to phone | [developer.android.com](https://developer.android.com/studio/releases/platform-tools) |
| Mi Unlock Tool (Windows only) | Official Xiaomi unlocking | [miui.com/unlock](https://en.miui.com/unlock/) |
| MTK USB Drivers | Required for BROM/SP Flash | [androidmtk.com](https://androidmtk.com/download-mtk-usb-all-drivers) |
| Python 3.x + pip | Required by MTKClient | [python.org](https://www.python.org/downloads/) |
| MTKClient | BROM exploit tool | `pip install mtkclient` |
| SP Flash Tool | Firmware / preloader flashing | [spflashtool.com](https://spflashtool.com/) |

### Backup checklist (do this before anything)
```bash
# Backup all important data — unlocking WILL wipe your phone
adb backup -all -apk -shared -f backup_$(date +%Y%m%d).ab
```
Also manually backup: photos, contacts, WhatsApp, 2FA app seeds, IMEI (dial `*#06#`).

### Risk levels used in this guide
| Label | Meaning |
|-------|---------|
| ✅ Safe | Xiaomi-supported, no bricking risk |
| ⚠️ Medium risk | Reversible if careful; follow steps exactly |
| ☠️ High risk | Hardware-level; wrong step = hard brick |

---

## 2. Check Your Device Variant & Firmware

```bash
# Connect phone with USB Debugging enabled, then:
adb shell getprop ro.product.device        # should print: gale OR gust
adb shell getprop ro.miui.ui.version.name  # e.g. HyperOS 1.0.x or MIUI 14
adb shell getprop ro.build.version.release # Android version
```

> **Critical:** Do not mix `gale` and `gust` firmware/recovery images — they have different preloaders and will hard-brick the other variant.

---

## 3. Strategy A — Official Method + HyperOS AAU App (Safe, 168h wait)

**Risk: ✅ Safe** | No hardware access needed | Works on all firmware versions

This is the only Xiaomi-approved method. The [HyperOS AAU app](https://github.com/dranehm/HyperOS-AAU) in this repository automates the hardest part: timing the slot request precisely at Beijing midnight.

### Step 1 — Fulfill Mi Community requirements
1. Log into the [Xiaomi Community app](https://play.google.com/store/apps/details?id=com.mi.global.bbs) with a Mi Account that is **30+ days old**.
2. Navigate to **Unlock Bootloader** → apply for unlock permission.
3. Complete any required quizzes or community activity to reach the required level.

### Step 2 — Bind your device
1. On the phone: **Settings → Additional Settings → Developer Options → Mi Unlock Status → Add account and device**
2. Ensure the phone is connected to a SIM card and has mobile data or Wi-Fi.

### Step 3 — Use HyperOS AAU to secure your slot
1. Install the app from this repo (or build it: `./gradlew assembleDebug`).
2. Capture your Cookie from the Xiaomi Community app using an HTTP sniffer:
   - Install [HTTP Toolkit](https://httptoolkit.com/) on your phone.
   - Intercept traffic to `https://sgp-api.buy.mi.com/bbs/api/global/apply/bl-auth`.
   - Copy the `Cookie:` header value.
3. Paste the cookie into the app, set **Max Triggers** to `4`, and tap **Verify & Start Process**.
4. Leave the app open with **Caffeine Mode** enabled — it will fire at Beijing midnight automatically.

### Step 4 — Unlock with Mi Unlock Tool (after 168h)
1. Power off phone → hold **Volume Down + Power** to enter Fastboot mode.
2. Connect to PC. Open **Mi Unlock Tool**, sign in with the same Mi Account.
3. Click **Unlock**. The tool will confirm and wipe the device.
4. Verify unlock:
   ```bash
   fastboot oem device-info
   # Should show: Device unlocked: true
   ```

---

## 4. Strategy B — MTKClient BROM Exploit (No Wait)

**Risk: ⚠️ Medium–☠️ High** | No Mi Account needed | Does not erase data (usually) | **Requires opening the phone**

This method exploits the MediaTek BootROM to write the `seccfg` partition directly, bypassing Xiaomi's server-side timer entirely.

> ⚠️ **Compatibility note:** This method works reliably on MIUI 14 and HyperOS 1.x. Xiaomi and MediaTek have been patching BROM access in newer security patches. If your device is on a late 2024+ security patch, success is not guaranteed.

### Step 1 — Install MTKClient
```bash
# On your PC (Python 3.8+ required)
git clone https://github.com/bkerler/mtkclient
cd mtkclient
pip install -r requirements.txt

# Install MTK USB VCOM drivers on Windows (see link in Prerequisites)
```

### Step 2 — Enter BROM mode

**Method A — Key combination (try this first, no disassembly)**
1. Power off the phone completely.
2. Hold **Volume Up + Volume Down** simultaneously.
3. While holding, plug in the USB cable to the PC.
4. MTKClient should detect the device in BROM mode.

**Method B — Test point (if Method A fails)**

> ☠️ **This requires opening the phone and carries risk of hardware damage if done incorrectly.**

1. Power off and disconnect all cables.
2. Remove the back cover and locate the mainboard.
3. The BROM test point on the Poco C65 (`gale`) is a small exposed pad **near the eMMC storage IC** on the motherboard. The exact position varies by board revision — consult the schematic at [Borneo Schematics (Xiaomi Poco C65)](https://www.borneoschematics.com/2024/10/borneo-schematics-update-xiaomi-poco-c65.html) or search "Poco C65 test point" on YouTube for a visual reference.
4. Using metal tweezers, **short the test point pad to a nearby ground point** (metal shield or ground pad), then plug in USB while maintaining the short for 1–2 seconds.
5. Release. Your PC should detect the device in BROM mode.

### Step 3 — Verify BROM detection
```bash
cd mtkclient
python -m mtk payload
# Should print: Found device in BROM mode
```

### Step 4 — Backup seccfg (important!)
```bash
python -m mtk r seccfg seccfg_backup.img
# Keep this backup — you can restore it if something goes wrong
```

### Step 5 — Unlock the bootloader
```bash
# Try the direct BROM method first:
python -m mtk seccfg unlock

# If that fails with permission error, try via Download Agent:
python -m mtk da seccfg unlock
```

### Step 6 — Reboot
```bash
python -m mtk reset
```

### Step 7 — Verify unlock
```bash
fastboot oem device-info
# Device unlocked: true
```

### Rollback if something goes wrong
```bash
# Restore original seccfg to relock:
python -m mtk w seccfg seccfg_backup.img
python -m mtk reset
```

---

## 5. Strategy C — Firmware Downgrade via SP Flash Tool (Medium Risk)

**Risk: ⚠️ Medium** | Use when on HyperOS 2.x which blocks unlock methods | Wipes all data

HyperOS 2.x introduced stricter bootloader restrictions and blocks TWRP. Downgrading to HyperOS 1.x or MIUI 14 restores compatibility with all bypass methods above.

> ⚠️ **Anti-rollback warning:** Never flash firmware with a lower anti-rollback index than your current firmware. This will permanently brick your device. Only downgrade to versions that are within one major version of your current firmware.

### Step 1 — Download the correct firmware
Go to one of these sources and download the **Fastboot ROM** (scatter file version) for codename **`gale`**:

- [roms.miuier.com/en-us/devices/gale](https://roms.miuier.com/en-us/devices/gale/) — official MIUI/HyperOS archive
- [xiaomifirmwareupdater.com](https://xiaomifirmwareupdater.com/miui/gale/stable/) — MIUI 14 global stable
- [ximitime.com/hyperos/gale](https://ximitime.com/hyperos/gale/) — HyperOS 1 builds

Recommended target: `V14.0.9.0.TGPMIXM` (MIUI 14 Global Stable) or the latest HyperOS 1.x stable for your region.

### Step 2 — Extract and open in SP Flash Tool
1. Extract the downloaded `.tgz` firmware archive.
2. Open **SP Flash Tool** → click **Choose** → navigate to the extracted folder → select `MT6769Z_Android_scatter.txt`.
3. The scatter file will populate the partition list automatically.

### Step 3 — Configure flash settings
- Set mode to **Download Only** (NOT Format+Download — this avoids wiping partitions you don't intend to change).
- **Uncheck `preloader`** unless you have confirmed your preloader version matches — flashing an incorrect preloader is the most common cause of hard bricks.
- Leave all other partitions checked.

### Step 4 — Flash
1. Click **Download** in SP Flash Tool.
2. Power off the phone.
3. Plug in USB while phone is off — SP Flash Tool will detect it and begin flashing automatically.
4. Wait for the green checkmark ✔.
5. Unplug and reboot.

---

## 6. Flash Custom Recovery (TWRP)

**Requires: Unlocked bootloader | MIUI 14 or HyperOS 1.x (NOT HyperOS 2.x)**

### Step 1 — Download TWRP for your variant
| Variant | Download |
|---------|---------|
| `gale` (Global) | [XDA Thread](https://xdaforums.com/t/shared-recovery-unofficial-twrp-for-redmi-13c-gale-gust.4674413/) |
| `gust` (India) | Same XDA thread — select the `gust` image |

> ⚠️ Do not use a `gale` image on a `gust` device — it will bootloop.

### Step 2 — Boot into Fastboot mode
```bash
adb reboot bootloader
# OR power off → hold Volume Down + Power
```

### Step 3 — Flash preloader via SP Flash Tool (MTK-specific, critical)
On MediaTek devices, `fastboot flash preloader` is NOT supported. Use SP Flash Tool:
1. Open SP Flash Tool with the scatter file from your **current** firmware (not a different version).
2. Check only the `preloader` partition.
3. Click **Download** and connect the powered-off phone.
4. Wait for green checkmark.

> This step is required because flashing a custom recovery on MTK without the matching preloader can cause a bootloop on next boot.

### Step 4 — Flash TWRP recovery
```bash
fastboot flash recovery twrp_gale.img   # use the correct image for your variant
fastboot reboot recovery
```

### Step 5 — Verify TWRP boots
TWRP should launch. If it says "No OS installed", that is normal at this stage.

---

## 7. Custom ROM Options & Comparison

All ROMs below are **unofficial** ports confirmed working on Poco C65 (`gale`/`gust`) as of 2025.

| ROM | Android | Stability | GApps | Links |
|-----|---------|-----------|-------|-------|
| **crDroid** | 14–16 | ⭐⭐⭐⭐⭐ Best for daily use | Optional | [GitHub](https://github.com/JanDimple/crdroid-gale/releases) · [XDA](https://xdaforums.com/t/crdroid-11-5-for-redmi13c-poco-c65-4g-unofficial-gale-gust.4741011/) |
| **LineageOS 21/23** | 14/16 | ⭐⭐⭐⭐ Near-stock, minimal | Not included | [XDA Thread](https://xdaforums.com/f/poco-c65.14893/) |
| **PixelOS** | 15/16 | ⭐⭐⭐⭐ Pixel-like UI | Included | [XDA](https://xdaforums.com/f/poco-c65.14893/) |
| **Evolution X** | 15/16 | ⭐⭐⭐ Feature-heavy | Optional | [XDA](https://xdaforums.com/f/poco-c65.14893/) |
| **AfterLifeOS** | 16 | ⭐⭐⭐ Bleeding edge | Optional | [XDA](https://xdaforums.com/f/poco-c65.14893/) |
| **crDroid** (recommended) is the most actively maintained and has the widest hardware compatibility on `gale`. |||||

### GApps options (if your ROM doesn't include Google apps)
- [MindTheGapps](https://github.com/MindTheGapps/MindTheGapps) — closest to AOSP
- [NikGApps](https://nikgapps.com/) — modular, choose what you install
- [BiTGApps](https://github.com/BiTGApps/BiTGApps) — privacy-conscious subset

---

## 8. Flash the ROM

### Step 1 — Download ROM + GApps
Download the ROM zip and, if needed, a GApps package matching your Android version and architecture (`arm64`).

### Step 2 — Transfer to phone
```bash
adb push rom.zip /sdcard/rom.zip
adb push gapps.zip /sdcard/gapps.zip   # if needed
```

### Step 3 — Boot into TWRP
```bash
adb reboot recovery
```

### Step 4 — Wipe
In TWRP:
1. **Wipe → Advanced Wipe** → check: `Dalvik/ART Cache`, `Cache`, `Data`, `System`
2. Swipe to wipe.
3. Do NOT wipe `Internal Storage` unless you transferred files to a PC first.

### Step 5 — Flash ROM
1. **Install → Select** `rom.zip` → swipe to flash.
2. If using a separate GApps package: **Install → Select** `gapps.zip` → swipe to flash.
3. Do NOT wipe again between flashing ROM and GApps.

### Step 6 — First boot
```
TWRP → Reboot → System
```
First boot takes **5–10 minutes** — this is normal. Do not interrupt it.

---

## 9. Troubleshooting

### Phone stuck in bootloop
```bash
# Boot back into TWRP without flashing anything:
fastboot boot twrp_gale.img

# Then wipe Dalvik/Cache only and try rebooting again.
# If still looping, reflash the ROM (repeat Step 3–5 above).
```

### TWRP shows "Failed to mount /data" or encryption errors
- In TWRP: **Wipe → Format Data** → type `yes`. This destroys all data but fixes encryption issues.

### Fastboot not detecting phone
```bash
# Check USB drivers are installed, try a different cable/port
fastboot devices

# If empty, reinstall MTK USB drivers and use a USB 2.0 port (not USB-C hub)
```

### Anti-rollback triggered (red screen with error code)
This happens when you flash a firmware with a lower rollback index than what is fused in hardware. **This is permanent.** Prevention is the only solution — always verify the anti-rollback version of the target firmware before flashing.

### Restore to stock (unbrick)
If the device is hard-bricked (no response, no fastboot, no recovery):
1. Enter BROM mode via test point (see [Strategy B Step 2](#step-2--enter-brom-mode)).
2. Use SP Flash Tool with the full stock scatter ROM for `gale`.
3. Flash **all partitions** including preloader.
4. Reference: [XDA Unbrick Guide for Redmi 13C](https://xdaforums.com/t/unbrick-xiaomi-redmi-13c-4g-sp-flash-tool.4675540/)

### MTKClient "permission denied" or "DA auth failed"
Your firmware may have a patched BROM. Try:
```bash
# Specify a preloader file from your firmware:
python -m mtk da seccfg unlock --loader path/to/preloader_ipo.bin
```
If it still fails, your security patch level is likely too new — proceed with [Strategy C](#strategy-c--firmware-downgrade-via-sp-flash-tool-medium-risk) to downgrade first, then retry.

---

## Quick Decision Guide

```
Are you on HyperOS 2.x?
├── YES → Do Strategy C first (downgrade), then come back here
└── NO (MIUI 14 or HyperOS 1.x)
    ├── Want zero risk, don't mind waiting 7 days? → Strategy A
    ├── Want no wait, willing to open the phone?  → Strategy B
    └── Want no wait, keep phone closed?          → Strategy C (downgrade to trigger shorter wait)
            └── After downgrade → retry Strategy B on older firmware
```

---

*Guide compiled from: XDA Developers · MTKClient GitHub · DroidWin · Xiaomi Firmware Updater*  
*Last researched: April 2026*
