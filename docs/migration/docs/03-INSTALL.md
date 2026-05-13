# 03 — Installation

## Phase 2: Bootloader Unlock + TWRP

### Step 2a: Get Xiaomi Authorization

You must use the **HyperOS-AAU app** (this repo) to get authorization before  
`fastboot oem unlock` will succeed.

1. Install the app on your Poco C65 via ADB:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
2. Paste your Mi account cookie (from browser → xiaomi.com while logged in)
3. Enable **Trust Device Clock** toggle (avoids 4G NTP asymmetry bug)
4. Launch 10-30 minutes before **18:00 Paris CEST** (Beijing midnight UTC+8)
5. Set 4-8 trigger waves, offset ~0ms
6. Wait for result `1` (approved) or `2` (already approved) in the log

### Step 2b: Run the Flash Script

```bash
./migrate.sh --phase 2
```

This will:
1. Reboot device to fastboot bootloader mode
2. Run `fastboot oem unlock`
3. **Pause** — you must press **Vol-Down** on the device to confirm
4. Device wipes all data and reboots
5. Flash TWRP to recovery partition
6. Boot into TWRP to verify

### Manual Equivalent

```bash
# Reboot to bootloader
adb reboot bootloader

# Verify fastboot detects device
fastboot devices

# Unlock (triggers Vol-Down prompt on device)
fastboot oem unlock
# OR on newer fastboot:
fastboot flashing unlock

# Flash TWRP
fastboot flash recovery docs/migration/downloads/twrp-gust.img

# Temp-boot TWRP to verify
fastboot boot docs/migration/downloads/twrp-gust.img
```

---

## Phase 3: Flash LineageOS + microG

### Prerequisites
- Bootloader must be UNLOCKED (Phase 2 complete)
- TWRP must be running (device in recovery mode)
- LineageOS zip and NanoDroid microG zip must be in `downloads/`

### Run the Flash Script

```bash
./migrate.sh --phase 3
```

This will:
1. Wipe data / cache / dalvik via TWRP (confirms with you first)
2. Sideload LineageOS 21 zip
3. Sideload NanoDroid microG zip
4. Reboot to LineageOS

### Manual Equivalent

```bash
# Wipe in TWRP shell
adb shell twrp wipe data
adb shell twrp wipe cache
adb shell twrp wipe dalvik

# Sideload LineageOS
adb shell twrp sideload
adb sideload docs/migration/downloads/lineageos-21-gust.zip

# Sideload microG
adb shell twrp sideload
adb sideload docs/migration/downloads/NanoDroid-microG.zip

# Reboot
adb shell twrp reboot system
```

## First Boot

- First boot takes **3-5 minutes** (Android optimizes apps)
- Complete the LineageOS setup wizard
- Re-enable USB debugging: Settings → About Phone → tap Build Number 7× → Developer Options → USB Debugging ON
- Then run restore: `./migrate.sh --phase 4`

## Anti-Rollback Warning

LineageOS version must be **≥** your current MIUI/HyperOS version for anti-rollback security.  
If you flash an older ROM, the device may permanently fail to boot to stock MIUI.  
The prerequisite script checks this. Do not bypass the checksum/version checks.

---
*Back: [02-BACKUP.md](02-BACKUP.md) | Next: [04-RESTORE.md](04-RESTORE.md)*
