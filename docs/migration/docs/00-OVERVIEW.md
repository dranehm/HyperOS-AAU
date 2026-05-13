# 00 — Migration Overview

## Goal

Migrate a **Xiaomi Poco C65** (codename `gust` / `gale`) from stock HyperOS to  
**LineageOS 21 (Android 14) + NanoDroid microG** — a privacy-first OS with:

- No Google services or telemetry
- microG as a Google Play Services drop-in (95%+ APK compatibility)
- Monthly security patches
- Full data backup before migration

## Why Not GrapheneOS?

GrapheneOS is Pixel-only. The Poco C65 uses a **MediaTek Helio G85** SoC which  
GrapheneOS does not support and has no plans to. LineageOS + microG is the  
closest equivalent for Xiaomi/MediaTek devices.

## Process Overview

```
Phase 0: Prerequisites
  └─ Check adb/fastboot, device connectivity, disk space
  └─ Download LineageOS zip, TWRP img, NanoDroid microG zip

Phase 1: Backup  ← Safe to run NOW (bootloader can be locked)
  └─ Media (DCIM, Pictures, WhatsApp, etc.)
  └─ APKs (all user-installed apps)
  └─ Contacts (VCF + contacts2.db)
  └─ SMS/MMS (adb backup)

Phase 2: Bootloader Unlock + TWRP  ← REQUIRES device authorization first
  └─ adb reboot bootloader → fastboot oem unlock  [1 button press on device]
  └─ fastboot flash recovery twrp.img

Phase 3: Flash ROM
  └─ TWRP: wipe data / cache / dalvik
  └─ adb sideload lineageos-21-gust.zip
  └─ adb sideload NanoDroid-microG.zip

Phase 4: Restore
  └─ Push media back to /sdcard/
  └─ Optionally batch-install APKs
  └─ Import contacts from VCF

Phase 5 (Auto): Failure Recovery
  └─ On any flash error → auto-run restore
  └─ If stock ROM needed: fastboot flash back to MIUI
```

## Quick Start

```bash
# 1. Edit config.env — set download URLs (check LineageOS downloads page)
nano docs/migration/config.env

# 2. Run full migration (interactive at 2 confirmation points)
cd docs/migration
chmod +x migrate.sh scripts/*.sh
./migrate.sh

# Or run phase by phase:
./migrate.sh --phase 0   # prereqs only
./migrate.sh --phase 1   # backup only (safe)
./migrate.sh --phase 2   # unlock + TWRP (requires bootloader auth)
./migrate.sh --phase 3   # flash ROM
./migrate.sh --phase 4   # restore
```

## Bootloader Authorization Prerequisite

Before Phase 2 can run, Xiaomi must grant bootloader unlock authorization.  
This is done via the **HyperOS-AAU app** in this repo — it fires the slot  
request at Beijing midnight (18:00 Paris CEST).

Once authorization is granted, you have a 30-day window to actually unlock.

See: [docs/poco-c65-unlock-custom-rom-guide.md](../poco-c65-unlock-custom-rom-guide.md)

## Device Facts

| Property | Value |
|---|---|
| Codename | `gust` (India) / `gale` (Global) |
| SoC | MediaTek Helio G85 |
| RAM | 4GB or 6GB |
| Storage | 128GB or 256GB |
| Android | 15 (HyperOS V816) at time of writing |
| Bootloader | Locked by default |
| Recovery | TWRP available (gale port) |

## Data Loss Disclaimer

Unlocking the bootloader **WIPES ALL DEVICE DATA** (this is enforced by Android).  
Run Phase 1 (backup) before Phase 2. The script enforces this order.

---
*See also: [01-PREREQUISITES.md](01-PREREQUISITES.md) | [05-TROUBLESHOOTING.md](05-TROUBLESHOOTING.md)*
