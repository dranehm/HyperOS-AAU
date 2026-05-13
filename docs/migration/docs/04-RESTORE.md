# 04 — Restore

## What Can Be Restored Automatically

| Data | Script Support | Notes |
|---|---|---|
| Photos / DCIM | ✅ Auto | Pushed back to /sdcard/DCIM/ |
| WhatsApp media | ✅ Auto | /sdcard/WhatsApp/ restored |
| Downloads | ✅ Auto | /sdcard/Download/ restored |
| APKs | ✅ Interactive | Batch or selective install |
| Contacts VCF | ✅ Auto-push | Then import manually in Contacts app |
| SMS/MMS | ⚠️ Manual | Use SMS Backup & Restore app |

## Running the Restore

```bash
./migrate.sh --phase 4
```

Or directly:
```bash
bash scripts/04_restore.sh
```

The script uses the most recent backup in `BACKUP_DIR` (from `config.env`).  
To restore from a specific backup:
```bash
RESTORE_FROM_BACKUP=~/poco_c65_backup/20250101_120000 bash scripts/04_restore.sh
```

## Manual Restore Steps

### Contacts

1. Find the VCF file pushed to `/sdcard/`:
   ```bash
   adb shell ls /sdcard/*.vcf
   ```
2. On device: Contacts → ⋮ → Import → From storage → select VCF

### SMS / MMS

Install **SMS Backup & Restore** from F-Droid:
```
F-Droid → search "SMS Backup Restore"
```
Then restore from the `.xml` backup if you made one, or manually re-receive SMS.

Alternatively, extract from `mmssms.db` in your backup using DB Browser for SQLite.

### APKs

The restore script offers to install all backed-up APKs automatically.  
For selective install:
```bash
adb install ~/poco_c65_backup/20250101_120000/apks/com.example.app.apk
```

Note: apps that use SafetyNet / Play Integrity (banking, Netflix) may not work  
without Magisk + SafetyNet fix. See troubleshooting.

## Emergency Stock ROM Restore

If LineageOS flash fails and device is bricked:

### Option A: TWRP (device boots to recovery)

```bash
# Put your stock HyperOS ZIP in downloads/
adb shell twrp sideload
adb sideload docs/migration/downloads/stock-rom.zip
```

### Option B: Fastboot (device in bootloader)

Download stock ROM from:  
https://xiaomifirmwareupdater.com/hyperos/gust/

Flash all partitions:
```bash
fastboot flash boot boot.img
fastboot flash system system.img
fastboot flash recovery recovery.img
fastboot flash vendor vendor.img
fastboot -w  # wipe userdata
fastboot reboot
```

### Option C: SP Flash Tool (device completely unresponsive — BROM mode)

1. Download SP Flash Tool for macOS/Linux from:  
   https://spflashtools.com/
2. Download the full firmware scatter file for your exact MIUI/HyperOS version
3. Select "Download Only" (NOT format)
4. Power off device, hold Vol+ while connecting USB → BROM detected
5. Click Download in SP Flash Tool

This is the nuclear option and can recover even a hard-bricked device.

---
*Back: [03-INSTALL.md](03-INSTALL.md) | Next: [05-TROUBLESHOOTING.md](05-TROUBLESHOOTING.md)*
