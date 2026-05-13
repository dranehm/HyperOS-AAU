# 05 — Troubleshooting

## Bootloader Unlock Issues

### `FAILED (remote: 'device not authorized')`

The Mi account has not granted unlock authorization yet.  
Use the **HyperOS-AAU app** and wait for result `1` at Beijing midnight.

### `FAILED (remote: 'oem unlock is not allowed')`

HyperOS disables `fastboot oem unlock` on some models. Try:
```bash
fastboot flashing unlock
```
If that also fails, you need the full OEM unlock flow via Developer Options first:  
Settings → Developer Options → OEM unlocking → enable.

### Device stuck on "Mi" logo after unlock

Normal — it runs a factory reset which takes 2-5 minutes. Wait it out.

### `fastboot devices` shows nothing on macOS

```bash
# Install correct platform tools
brew install --cask android-platform-tools

# Unplug and replug USB
# Try a different USB cable (some are charge-only)
# Try a direct port (not a hub)
```

---

## TWRP Issues

### TWRP boots but shows "Failed to mount /data"

Data partition is encrypted (normal for stock MIUI). Wipe data in TWRP first:
```
TWRP → Wipe → Format Data → type "yes"
```
⚠️ This destroys all data. Only do this if backup is complete.

### TWRP not booting (back to stock recovery)

HyperOS patches the recovery partition on reboot. To work around:
```bash
# Flash TWRP again, then immediately sideload without rebooting
fastboot flash recovery twrp-gust.img
fastboot boot twrp-gust.img   # temp-boot — doesn't write to disk
```

### `adb shell twrp` command not found

TWRP's built-in `twrp` command may not be in PATH. Use:
```bash
adb shell /system/bin/twrp wipe data
```

---

## Sideload Issues

### `adb sideload` hangs at 47%

TWRP signature verification failing. In TWRP:
- Swipe to allow unsigned zips → retry sideload

### `adb sideload` completes but "signature verification failed"

1. In TWRP → Advanced → MTP → enable
2. `adb push lineageos.zip /sdcard/`
3. TWRP → Install → select zip → install

### Device not detected in sideload mode

```bash
# Kill and restart adb server
adb kill-server && adb start-server
adb devices
```

---

## LineageOS Boot Issues

### Bootloop after ROM flash

1. Reboot to TWRP: hold Power + Vol-Down until fastboot, then `fastboot boot twrp.img`
2. Check you wiped data/cache/dalvik before flashing
3. Re-flash ROM: TWRP → Wipe → Advanced Wipe → System → then re-sideload

### "System corrupted" warning on boot

This is the Android Verified Boot warning for unofficial ROMs.  
Press Power to dismiss — it appears every boot (expected for unlocked bootloader).

### Apps crash with "Google Play Services not found"

microG is not properly set up. Open **microG Settings** and:
1. Self-Check → grant all permissions
2. Enable "Google device registration"
3. Enable "Cloud Messaging"

---

## microG / App Compatibility

### Banking apps not working

Banking apps use SafetyNet / Play Integrity which detects unlocked bootloader.  
Options:
1. Use bank's mobile website instead
2. Install Magisk + SafetyNet Fix (advanced)
3. Use a secondary device for banking

### App requires Play Store (won't install)

Install **Aurora Store** (F-Droid) as a Play Store front-end:
```
F-Droid → search "Aurora Store"
```
Sign in anonymously or with Google account.

### Push notifications not working

1. microG Settings → Cloud Messaging → enable
2. microG Settings → Google device registration → register
3. Force-stop and reopen the app

---

## ADB Connection Issues

### Device not recognized (no popup)

```bash
# Revoke USB debugging authorizations and re-authorize
adb kill-server
# On device: Settings → Developer Options → Revoke USB debugging authorizations
adb start-server
adb devices
# Unlock screen, tap "Allow" on popup
```

### ADB works but device shows `unauthorized`

Unlock the device screen. The authorization popup only appears when screen is on.

---

## SP Flash Tool (Emergency Unbrick)

If the device is completely unresponsive to fastboot/ADB:

1. **macOS**: Download SP Flash Tool  
   https://spflashtools.com/mac/sp-flash-tool-for-mac
2. Download the **scatter file** for your exact firmware:  
   https://xiaomifirmwareupdater.com/hyperos/gust/
3. Select `MT6768_Android_scatter.txt` in SP Flash Tool
4. Choose "Download Only" (never "Format All + Download" on MIUI — bricks NV)
5. Power OFF device, hold Vol-Up, plug USB
6. SP Flash Tool detects BROM and starts flashing

---

## Getting Help

- XDA Forums Poco C65: https://xdaforums.com/c/xiaomi-poco-c65.13013/
- LineageOS Wiki: https://wiki.lineageos.org/devices/gust
- TWRP Device page: https://twrp.me/xiaomi/xiaomigale.html
- NanoDroid issues: https://github.com/Nanolx/NanoDroid/issues
