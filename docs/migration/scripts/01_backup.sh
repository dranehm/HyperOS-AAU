#!/usr/bin/env bash
# =============================================================================
# 01_backup.sh
# Full device backup: media, APKs, contacts, SMS/MMS, app list.
# Safe to run BEFORE unlocking bootloader (no flashing involved).
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../config.env"
source "${SCRIPT_DIR}/lib/common.sh"

TIMESTAMP=$(date '+%Y%m%d_%H%M%S')
BACKUP_ROOT="${BACKUP_DIR}/${TIMESTAMP}"
MANIFEST_FILE="${BACKUP_ROOT}/manifest.json"

ADB_CMD="adb${ADB_SERIAL:+ -s $ADB_SERIAL}"

init_backup_dir() {
    log_section "Initializing backup"
    mkdir -p "${BACKUP_ROOT}"/{media,apks,contacts,sms,misc}

    # Device info
    local model android build codename serial
    model=$($ADB_CMD shell getprop ro.product.model 2>/dev/null | tr -d '[:space:]')
    android=$($ADB_CMD shell getprop ro.build.version.release 2>/dev/null | tr -d '[:space:]')
    build=$($ADB_CMD shell getprop ro.miui.ui.version.name 2>/dev/null | tr -d '[:space:]')
    codename=$($ADB_CMD shell getprop ro.product.device 2>/dev/null | tr -d '[:space:]')
    serial=$($ADB_CMD shell getprop ro.serialno 2>/dev/null | tr -d '[:space:]')

    cat > "$MANIFEST_FILE" <<EOF
{
  "backup_date": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')",
  "timestamp": "${TIMESTAMP}",
  "device": {
    "model": "${model}",
    "codename": "${codename}",
    "serial": "${serial}",
    "android": "${android}",
    "build": "${build}"
  },
  "phases": {}
}
EOF
    log_ok "Backup root: ${BACKUP_ROOT}"
    session_log "Backup started → ${BACKUP_ROOT}"
}

backup_media() {
    log_section "Backing up media files"

    local media_dir="${BACKUP_ROOT}/media"
    local folders=("DCIM" "Pictures" "Movies" "Download" "WhatsApp" "Telegram" "Documents")
    local total_pulled=0

    for folder in "${folders[@]}"; do
        local size
        size=$($ADB_CMD shell du -sk "/sdcard/${folder}" 2>/dev/null | awk '{print $1}' || echo "0")
        if [[ "${size}" -gt 0 ]]; then
            log_info "Pulling /sdcard/${folder} (~$(( size / 1024 ))MB)..."
            $ADB_CMD pull "/sdcard/${folder}" "${media_dir}/" 2>/dev/null || \
                log_warn "  Some files in ${folder} may have been skipped (permission)"
            total_pulled=$((total_pulled + size))
        else
            log_info "  ${folder}: empty or not found — skipping"
        fi
    done

    log_ok "Media backup complete (~$(( total_pulled / 1024 ))MB)"
    update_manifest "media" "{\"pulled_kb\": ${total_pulled}}"
}

backup_apks() {
    log_section "Backing up user APKs"

    local apk_dir="${BACKUP_ROOT}/apks"
    local pkg_list_file="${BACKUP_ROOT}/misc/package_list.txt"

    # Get package paths
    log_info "Enumerating user-installed packages..."
    $ADB_CMD shell pm list packages -3 -f 2>/dev/null | sed 's/^package://' | sort > "${pkg_list_file}"
    local pkg_count
    pkg_count=$(wc -l < "$pkg_list_file")
    log_ok "Found ${pkg_count} user-installed packages"

    local pulled=0
    local failed=0

    while IFS= read -r line; do
        # line format: /data/app/~~xxx/com.example-xxx/base.apk=com.example
        local path pkg
        path=$(echo "$line" | cut -d= -f1)
        pkg=$(echo "$line" | cut -d= -f2)

        if [[ -z "$path" || -z "$pkg" ]]; then continue; fi

        local dest="${apk_dir}/${pkg}.apk"
        $ADB_CMD pull "$path" "$dest" &>/dev/null && \
            pulled=$((pulled + 1)) || \
            failed=$((failed + 1))

        # Progress dot every 10 packages
        if (( (pulled + failed) % 10 == 0 )); then
            printf "  Pulled: %d/%d\r" "$pulled" "$pkg_count"
        fi
    done < "$pkg_list_file"

    echo ""
    log_ok "APKs pulled: ${pulled}/${pkg_count} (failed: ${failed})"
    update_manifest "apks" "{\"total\": ${pkg_count}, \"pulled\": ${pulled}, \"failed\": ${failed}}"
}

backup_contacts() {
    log_section "Backing up contacts"

    local contacts_dir="${BACKUP_ROOT}/contacts"

    # Check if there are VCF files on sdcard already
    local existing_vcf
    existing_vcf=$($ADB_CMD shell find /sdcard -name "*.vcf" -maxdepth 4 2>/dev/null | head -5)
    if [[ -n "$existing_vcf" ]]; then
        log_info "Found existing VCF exports on device — pulling them..."
        echo "$existing_vcf" | while read -r f; do
            $ADB_CMD pull "$f" "${contacts_dir}/" &>/dev/null && log_ok "  Pulled: $f"
        done
    fi

    # Export contacts via content provider (works without root on most MIUI versions)
    log_info "Exporting contacts via content provider..."
    local vcf_path="/sdcard/poco_migration_contacts_${TIMESTAMP}.vcf"
    $ADB_CMD shell "content query --uri content://com.android.contacts/contacts \
        --projection display_name 2>/dev/null | head -1" &>/dev/null || true

    # Use pm-based export (more reliable on HyperOS)
    $ADB_CMD shell "am broadcast -a android.intent.action.EXPORT_CONTACTS 2>/dev/null" &>/dev/null || true

    # Pull contacts database (works if adb has sufficient permissions)
    $ADB_CMD pull /data/data/com.android.providers.contacts/databases/contacts2.db \
        "${contacts_dir}/contacts2.db" &>/dev/null && \
        log_ok "contacts2.db pulled (can be read with SQLite browser)" || \
        log_warn "contacts2.db: permission denied — manually export from Contacts app Settings"

    # Instruction file
    cat > "${contacts_dir}/README.txt" <<'EOF'
HOW TO RESTORE CONTACTS:
1. Import the .vcf file(s) in this folder into your contacts app
2. If only contacts2.db exists, open it with DB Browser for SQLite to extract contacts
3. On LineageOS: Settings → Apps → Contacts → Import from storage
EOF
    log_ok "Contacts backup complete (check contacts/ folder)"
    update_manifest "contacts" "{\"method\": \"adb_pull\"}"
}

backup_sms() {
    log_section "Backing up SMS/MMS"

    local sms_dir="${BACKUP_ROOT}/sms"

    # adb backup for telephony — stores in Android backup format (.ab)
    # Note: on Android 11+ this may require unlock confirmation on device
    log_info "Backing up SMS via adb backup (may require screen unlock)..."
    log_warn "If a dialog appears on the phone, tap 'Back up my data' to continue"

    timeout 60 $ADB_CMD backup -noapk com.android.providers.telephony \
        -f "${sms_dir}/sms_backup.ab" 2>/dev/null && \
        log_ok "SMS backup saved: sms_backup.ab" || \
        log_warn "SMS backup skipped or timed out — restore manually from phone"

    # Try pulling mmssms.db directly
    $ADB_CMD pull /data/data/com.android.providers.telephony/databases/mmssms.db \
        "${sms_dir}/mmssms.db" &>/dev/null && \
        log_ok "mmssms.db pulled (SQLite format)" || \
        log_warn "mmssms.db: root required — SMS export via app recommended"

    cat > "${sms_dir}/README.txt" <<'EOF'
HOW TO RESTORE SMS:
Option A: Use "SMS Backup & Restore" app (F-Droid) to create/restore XML backups
Option B: Open mmssms.db with DB Browser for SQLite
Option C: sms_backup.ab can be extracted with Android Backup Extractor (ABE)
          java -jar abe.jar unpack sms_backup.ab sms.tar ""
EOF
    update_manifest "sms" "{\"method\": \"adb_backup_and_db\"}"
}

backup_misc() {
    log_section "Backing up miscellaneous data"

    local misc_dir="${BACKUP_ROOT}/misc"

    # App version list (useful for knowing what to reinstall)
    log_info "Saving app version list..."
    $ADB_CMD shell pm list packages -3 2>/dev/null > "${misc_dir}/user_packages.txt"
    $ADB_CMD shell "pm list packages -3 | sed 's/package://' | while read pkg; do \
        ver=\$(dumpsys package \$pkg 2>/dev/null | grep versionName | head -1 | sed 's/.*versionName=//'); \
        echo \"\$pkg=\$ver\"; done" 2>/dev/null > "${misc_dir}/user_packages_with_versions.txt" || true
    log_ok "App list: $(wc -l < "${misc_dir}/user_packages.txt") packages"

    # System info dump
    log_info "Saving device info..."
    {
        echo "=== Device Info ==="
        $ADB_CMD shell getprop | grep -E 'ro\.(product|build|hardware|bootloader|boot)' 2>/dev/null
        echo ""
        echo "=== Storage ==="
        $ADB_CMD shell df -h 2>/dev/null
        echo ""
        echo "=== Network ==="
        $ADB_CMD shell dumpsys wifi 2>/dev/null | grep -E 'SSID|mLastBssid' | head -20
    } > "${misc_dir}/device_info.txt" 2>/dev/null

    log_ok "Misc backup complete"
    update_manifest "misc" "{\"app_count\": $(wc -l < "${misc_dir}/user_packages.txt")}"
}

finalize_manifest() {
    log_section "Finalizing backup manifest"

    # Calculate total backup size
    local total_size
    total_size=$(du -sh "${BACKUP_ROOT}" 2>/dev/null | awk '{print $1}')

    # Update manifest with completion
    local tmp
    tmp=$(mktemp)
    python3 -c "
import json, sys
with open('${MANIFEST_FILE}') as f:
    m = json.load(f)
m['completed'] = True
m['total_size'] = '${total_size}'
m['backup_path'] = '${BACKUP_ROOT}'
print(json.dumps(m, indent=2))
" > "$tmp" && mv "$tmp" "$MANIFEST_FILE" 2>/dev/null || true

    log_ok "Backup complete! Total size: ${total_size}"
    log_ok "Backup saved to: ${BACKUP_ROOT}"
    log_ok "Manifest: ${MANIFEST_FILE}"

    # Save latest backup path for restore script
    echo "${BACKUP_ROOT}" > "${BACKUP_DIR}/latest_backup.txt"
    session_log "Backup complete → ${BACKUP_ROOT} (${total_size})"
}

update_manifest() {
    local phase="$1"
    local data="$2"
    local tmp
    tmp=$(mktemp)
    python3 -c "
import json, sys
with open('${MANIFEST_FILE}') as f:
    m = json.load(f)
m['phases']['${phase}'] = json.loads('${data}')
print(json.dumps(m, indent=2))
" > "$tmp" && mv "$tmp" "$MANIFEST_FILE" 2>/dev/null || true
}

# ── Main ─────────────────────────────────────────────────────────────────────
init_backup_dir
backup_media
backup_apks
backup_contacts
backup_sms
backup_misc
finalize_manifest
