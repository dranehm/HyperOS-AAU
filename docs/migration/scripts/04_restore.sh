#!/usr/bin/env bash
# =============================================================================
# 04_restore.sh
# Restores media files to the device after LineageOS install.
# Also provides helpers to sideload APKs from the backup.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../config.env"
source "${SCRIPT_DIR}/lib/common.sh"

ADB_CMD="adb${ADB_SERIAL:+ -s $ADB_SERIAL}"

# Determine which backup to restore from
find_backup() {
    log_section "Finding backup"

    local latest_file="${BACKUP_DIR}/latest_backup.txt"
    local backup_path="${RESTORE_FROM_BACKUP:-}"

    if [[ -z "$backup_path" ]]; then
        if [[ -f "$latest_file" ]]; then
            backup_path=$(cat "$latest_file")
            log_ok "Using latest backup: $backup_path"
        else
            # Auto-find most recent timestamped folder
            backup_path=$(ls -dt "${BACKUP_DIR}"/[0-9]*_[0-9]* 2>/dev/null | head -1)
            if [[ -n "$backup_path" ]]; then
                log_ok "Auto-detected most recent backup: $backup_path"
            else
                log_error "No backup found in ${BACKUP_DIR}"
                log_error "Set RESTORE_FROM_BACKUP=/path/to/backup or run 01_backup.sh first"
                exit 1
            fi
        fi
    fi

    if [[ ! -d "$backup_path" ]]; then
        log_error "Backup directory does not exist: $backup_path"
        exit 1
    fi

    export BACKUP_PATH="$backup_path"
    log_ok "Backup: $BACKUP_PATH"
}

wait_for_device_ready() {
    log_section "Waiting for device"

    wait_for_adb_state "device" 120

    # Wait until boot_completed
    log_info "Waiting for Android to finish booting..."
    local elapsed=0
    while [[ $elapsed -lt 120 ]]; do
        if $ADB_CMD shell getprop sys.boot_completed 2>/dev/null | grep -q "^1$"; then
            log_ok "Device ready"
            return
        fi
        sleep 5
        elapsed=$((elapsed + 5))
    done
    log_warn "Boot timeout — attempting restore anyway..."
}

restore_media() {
    log_section "Restoring media"

    local media_dir="${BACKUP_PATH}/media"
    if [[ ! -d "$media_dir" ]]; then
        log_warn "No media directory in backup — skipping"
        return
    fi

    local total=0
    for folder in "${media_dir}"/*/; do
        local name
        name=$(basename "$folder")
        log_info "Pushing ${name}..."
        $ADB_CMD push "$folder" "/sdcard/${name}/" 2>/dev/null && \
            log_ok "  ${name} restored" || \
            log_warn "  ${name}: some files may have been skipped"
        total=$((total + 1))
    done
    log_ok "Media restore complete (${total} folders)"
    session_log "Media restored from ${BACKUP_PATH}"
}

restore_contacts_hint() {
    log_section "Contacts"

    local contacts_dir="${BACKUP_PATH}/contacts"
    if [[ ! -d "$contacts_dir" ]]; then
        log_warn "No contacts in backup"
        return
    fi

    # Push VCF files to sdcard
    local vcf_files
    vcf_files=$(find "$contacts_dir" -name "*.vcf" 2>/dev/null)
    if [[ -n "$vcf_files" ]]; then
        echo "$vcf_files" | while read -r f; do
            $ADB_CMD push "$f" "/sdcard/$(basename "$f")" 2>/dev/null && \
                log_ok "Pushed $(basename "$f") → /sdcard/"
        done
        log_info "To import: Contacts app → Settings → Import from storage → select VCF"
    fi

    if [[ -f "${contacts_dir}/contacts2.db" ]]; then
        log_info "contacts2.db in backup — manual import possible via DB Browser for SQLite"
    fi
}

sideload_apks() {
    log_section "APK restore (interactive)"

    local apk_dir="${BACKUP_PATH}/apks"
    if [[ ! -d "$apk_dir" ]]; then
        log_warn "No APK directory in backup"
        return
    fi

    local apk_count
    apk_count=$(find "$apk_dir" -name "*.apk" | wc -l)
    log_ok "${apk_count} APKs available in backup"

    echo ""
    read -rp "  Install all APKs automatically? [y/N] " confirm
    if [[ "${confirm,,}" != "y" ]]; then
        log_info "Skipping APK restore. To install manually:"
        echo "  adb install ${apk_dir}/com.example.app.apk"
        echo "  Or push all APKs to sdcard:  adb push ${apk_dir}/ /sdcard/APKs/"
        return
    fi

    local installed=0
    local failed=0

    find "$apk_dir" -name "*.apk" | while read -r apk; do
        local pkg
        pkg=$(basename "$apk" .apk)
        printf "  Installing %-60s\r" "$pkg"
        $ADB_CMD install -r "$apk" &>/dev/null && \
            installed=$((installed + 1)) || \
            failed=$((failed + 1))
    done
    echo ""
    log_ok "Installed: ${installed} | Failed: ${failed}"
    log_info "Failed APKs may require enabling 'Install unknown apps' on device"
}

print_restore_report() {
    log_section "✅ Restore Complete"
    echo ""
    echo "  Backup used: ${BACKUP_PATH}"
    echo ""
    echo "  STILL MANUAL:"
    echo "  • SMS/MMS: use 'SMS Backup & Restore' app (available on F-Droid)"
    echo "  • Contacts: check if VCF was imported (Contacts → Settings → Import)"
    echo "  • App data: not restorable via ADB on Android 11+ without root"
    echo "  • 2FA apps (Google Authenticator etc): export BEFORE wiping!"
    echo ""
    echo "  microG setup:"
    echo "  • Open microG Settings → Self-Check → grant all permissions"
    echo "  • Enable Google device registration if you want push notifications"
    echo "  • Install F-Droid from https://f-droid.org for open-source apps"
    echo ""
}

# ── Main ─────────────────────────────────────────────────────────────────────
find_backup
wait_for_device_ready
restore_media
restore_contacts_hint
sideload_apks
print_restore_report
