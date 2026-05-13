# 02 — Backup

## What Gets Backed Up

| Data | Method | Completeness |
|---|---|---|
| Photos / DCIM | `adb pull` | ✅ Complete |
| WhatsApp media | `adb pull /sdcard/WhatsApp` | ✅ Complete |
| Downloads, Documents | `adb pull` | ✅ Complete |
| User APKs | `adb pull` via `pm list packages -3 -f` | ✅ Complete |
| Contacts | `adb pull contacts2.db` + VCF | ✅ (if unlocked) |
| SMS / MMS | `adb backup` + `mmssms.db` | ⚠️ Partial (Android 11+ restricts) |
| App data / settings | Not supported (Android 11+ sandbox) | ❌ Not backed up |
| WhatsApp chats | Must use WhatsApp built-in backup first | ⚠️ Manual step |

## ⚠️ Manual Steps BEFORE Running Backup Script

### WhatsApp Chat Backup (Critical)

WhatsApp encrypts local backups on Android 12+. Before migrating:

1. Open WhatsApp → Settings → Chats → Chat Backup → **Back up now**
2. This saves to `/sdcard/WhatsApp/Databases/` — the script will pull it
3. On LineageOS + microG, use the WhatsApp restore flow at first launch

### 2FA / Authenticator Apps

Google Authenticator, Authy, Microsoft Authenticator — **export your codes BEFORE wiping**.  
Most apps have an "Export accounts" or "Transfer" option. Do this now.

### Banking / Fintech Apps

Apps with DRM or root detection (banking apps, Netflix, etc.) cannot be restored from  
APK backup — they detect the new device. You will need to re-register.

## Running the Backup

```bash
cd docs/migration
./migrate.sh --phase 1
```

Or directly:
```bash
bash scripts/01_backup.sh
```

The script creates a timestamped folder:
```
~/poco_c65_backup/
└── 20250101_120000/          ← timestamped backup
    ├── media/                ← DCIM, Pictures, WhatsApp, etc.
    ├── apks/                 ← com.example.app.apk, ...
    ├── contacts/             ← contacts2.db, *.vcf
    ├── sms/                  ← sms_backup.ab, mmssms.db
    ├── misc/                 ← package_list.txt, device_info.txt
    └── manifest.json         ← backup metadata
```

## Verifying Your Backup

```bash
# Check total size
du -sh ~/poco_c65_backup/20250101_120000

# List APKs
ls ~/poco_c65_backup/20250101_120000/apks/ | wc -l

# Verify photos
ls ~/poco_c65_backup/20250101_120000/media/DCIM/ | head -5
```

## What Is NOT Backed Up (and Alternatives)

| Data | Alternative |
|---|---|
| App login sessions | Re-login after migration |
| Game progress | Use in-game cloud save if available |
| Banking app data | Re-register (DRM restrictions) |
| Encrypted WhatsApp DB (pre-backup) | Run WhatsApp backup first (see above) |
| App settings / preferences | Manual reconfiguration |

---
*Back: [01-PREREQUISITES.md](01-PREREQUISITES.md) | Next: [03-INSTALL.md](03-INSTALL.md)*
