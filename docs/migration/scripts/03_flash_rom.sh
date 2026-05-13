#!/usr/bin/env bash
# =============================================================================
# 03_flash_rom.sh
# Wipes device, flashes LineageOS 21 via TWRP sideload, then installs NanoDroid
# microG on top. Requires TWRP to be running (run 02_flash_twrp.sh first).
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../config.env"
source "${SCRIPT_DIR}/lib/common.sh"

DL_DIR="${SCRIPT_DIR}/../downloads"
ADB_CMD="adb${ADB_SERIAL:+ -s $ADB_SERIAL}"
CODENAME="${DEVICE_CODENAME:-gust}"

ROM_ZIP="${DL_DIR}/lineageos-21-${CODENAME}.zip"
MICROG_ZIP="${DL_DIR}/NanoDroid-microG.zip"

check_files() {
    log_section "Checking required files"
    local missing=0

    for f in "$ROM_ZIP" "$MICROG_ZIP"; do
        if [[ -f "$f" ]]; then
            log_ok "$(basename "$f"): $(du -sh "$f" | awk '{print $1}')"
        else
            log_warn "$(basename "$f"): NOT FOUND at $f"
            log_warn "  If filename differs, update config.env or rename the file"
            missing=$((missing + 1))
        fi
    done

    if [[ $missing -gt 0 ]]; then
        log_error "$missing required file(s) missing. Run 00_check_prerequisites.sh or download manually."
        exit 1
    fi
}

ensure_twrp_running() {
    log_section "Verifying TWRP is running"

    local state
    state=$($ADB_CMD get-state 2>/dev/null || echo "offline")

    if [[ "$state" == "recovery" ]]; then
        log_ok "Device is in recovery mode (TWRP)"
        return
    fi

    if [[ "$state" == "device" ]]; then
        log_info "Device in ADB mode — rebooting to recovery..."
        reboot_and_wait "recovery" 90
        return
    fi

    log_error "Device not detected (state: $state). Connect device and boot TWRP first."
    exit 1
}

wipe_device() {
    if [[ "${SKIP_WIPE:-false}" == "true" ]]; then
        log_section "Data wipe"
        log_warn "SKIP_WIPE=true — skipping wipe (not recommended)"
        return
    fi

    log_section "Wiping device"
    echo ""
    echo -e "  ${YELLOW}${BOLD}This will wipe DATA, CACHE, and DALVIK (system/ROM data preserved).${RESET}"
    echo -e "  ${YELLOW}All apps, settings, and files NOT in your backup will be lost.${RESET}"
    echo ""
    read -rp "  Type 'WIPE' to confirm: " confirm
    [[ "$confirm" == "WIPE" ]] || { log_error "Aborted"; exit 1; }

    log_info "Wiping data partition..."
    $ADB_CMD shell twrp wipe data && log_ok "Data wiped" || {
        log_error "twrp wipe data failed"
        exit 1
    }

    log_info "Wiping cache..."
    $ADB_CMD shell twrp wipe cache && log_ok "Cache wiped"

    log_info "Wiping dalvik..."
    $ADB_CMD shell twrp wipe dalvik && log_ok "Dalvik wiped"

    session_log "Device wiped (data/cache/dalvik)"
}

sideload_zip() {
    local label="$1"
    local zip_path="$2"

    log_section "Flashing: $label"
    log_info "Starting sideload ($(du -sh "$zip_path" | awk '{print $1}'))..."

    # TWRP needs to be in sideload mode
    $ADB_CMD shell twrp sideload 2>/dev/null || true
    sleep 3

    # Push into sideload mode
    $ADB_CMD sideload "$zip_path" && {
        log_ok "$label flashed successfully ✅"
        session_log "Flashed: $label"
    } || {
        log_error "Sideload failed for: $label"
        session_log "FAILED to flash: $label"
        exit 1
    }

    # Brief pause between zips
    sleep 5
}

flash_rom_and_microg() {
    sideload_zip "LineageOS 21" "$ROM_ZIP"

    # Re-enter recovery after ROM flash (TWRP may restart)
    log_info "Re-entering TWRP for microG install..."
    wait_for_adb_state "recovery" 60

    sideload_zip "NanoDroid microG" "$MICROG_ZIP"
}

first_boot() {
    log_section "First boot"
    log_info "Rebooting to LineageOS for first boot..."
    $ADB_CMD shell twrp reboot system 2>/dev/null || $ADB_CMD reboot

    log_info "First boot takes ${FIRST_BOOT_WAIT_SECS}s+ (Android optimizes apps)..."
    sleep 30  # give it time to detach

    local elapsed=0
    while [[ $elapsed -lt $FIRST_BOOT_WAIT_SECS ]]; do
        if $ADB_CMD shell getprop sys.boot_completed 2>/dev/null | grep -q "^1$"; then
            log_ok "LineageOS booted successfully ✅"
            session_log "LineageOS first boot complete"
            return
        fi
        sleep 10
        elapsed=$((elapsed + 10))
        printf "  Waiting for boot... %ds\r" "$elapsed"
    done

    log_warn "Boot timeout (${FIRST_BOOT_WAIT_SECS}s) — device may still be optimizing"
    log_warn "Wait for the setup wizard to appear, then run 04_restore.sh"
}

print_next_steps() {
    echo ""
    log_section "✅ ROM Flash Complete"
    echo ""
    echo "  LineageOS 21 + NanoDroid microG is installed."
    echo ""
    echo "  NEXT STEPS:"
    echo "  1. Complete the LineageOS setup wizard on the device"
    echo "  2. Enable USB debugging in Developer Options"
    echo "  3. Run:  ./scripts/04_restore.sh"
    echo "     to restore your media files"
    echo ""
    echo "  OPTIONAL — Install apps from backup:"
    echo "  cd ${BACKUP_DIR} and install APKs manually:"
    echo "  adb install backup/<date>/apks/com.example.app.apk"
    echo ""
}

# ── Main ─────────────────────────────────────────────────────────────────────
check_files
ensure_twrp_running
wipe_device
flash_rom_and_microg
first_boot
print_next_steps
