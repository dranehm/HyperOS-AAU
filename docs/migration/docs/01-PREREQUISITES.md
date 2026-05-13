# 01 — Prerequisites

## Host Machine

| Tool | macOS Install | Linux Install | Minimum Version |
|---|---|---|---|
| `adb` | `brew install android-platform-tools` | `sudo apt install adb` | 1.0.41+ |
| `fastboot` | included above | `sudo apt install fastboot` | 28.0+ |
| `curl` | pre-installed | pre-installed | any |
| `sha256sum` | `brew install coreutils` | pre-installed | any |
| `python3` | pre-installed macOS 12+ | `sudo apt install python3` | 3.8+ |

### Verify ADB Detects Your Device

```bash
adb devices
# Should show: YDPB4DYXEUIZS4ON  device
```

If it shows `unauthorized`: unlock device screen and tap "Allow" on the USB debugging popup.

## Device Prerequisites

1. **USB Debugging enabled**  
   Settings → About Phone → tap MIUI version 7× → Developer Options → USB Debugging ON

2. **Mi Account linked** (for bootloader unlock authorization)  
   Settings → Mi Account → sign in with the same account used in the HyperOS-AAU app

3. **SIM card inserted** (Xiaomi requires cellular for unlock authorization)  
   4G connection recommended (not WiFi-only) during the unlock request window

4. **Battery ≥ 50%** before starting any flash operation

5. **Storage space on host machine**: at least `device_used_GB + 10GB` free

## Files to Download

Before running `migrate.sh`, download these files manually and update `config.env`:

### LineageOS 21 for gust/gale

- Check official or unofficial builds:  
  https://lineageosrom.com/poco-c65-lineage-os-21/  
  https://xdaforums.com/t/poco-c65-gust-lineageos-roms.html  

- Filename pattern: `lineage-21.0-YYYYMMDD-UNOFFICIAL-gust.zip`
- Note the SHA256 checksum from the download page and paste it in `config.env`

### NanoDroid microG

- GitHub releases: https://github.com/Nanolx/NanoDroid/releases  
- Download the **Micro** package: `NanoDroid-microG-X.Y.Z.zip`
- Provides: GmsCore, FakeStore, UnifiedNlp (no full Play Store)

### TWRP Recovery

- Download from: https://dl.twrp.me/gale/  
- Use the latest `twrp-3.7.x-gale.img`
- Note: `gust` (India) uses the same recovery as `gale` (Global)

## config.env Checklist

Open `docs/migration/config.env` and fill in:

```bash
DEVICE_CODENAME="gust"          # or "gale"
BACKUP_DIR="/your/backup/path"  # needs ~120GB free
LINEAGEOS_ZIP_URL="https://..."
LINEAGEOS_ZIP_SHA256="abc123..."
MICROG_ZIP_URL="https://..."
TWRP_IMG_URL="https://..."
```

Run the prerequisites check:
```bash
./migrate.sh --phase 0
```

## Bootloader Authorization

The **HyperOS-AAU** app (this repo) must successfully get authorization from  
Xiaomi before you can run `fastboot oem unlock`. This requires:

1. Mi account linked on device and in the app (copy cookie from browser)
2. App launched before 18:00 Paris CEST (Beijing midnight)
3. Result `1` or `2` in the app log (not `6` = quota full)

Once you see result `1`, you have up to 30 days to actually unlock.

---
*Back: [00-OVERVIEW.md](00-OVERVIEW.md) | Next: [02-BACKUP.md](02-BACKUP.md)*
