#!/usr/bin/env bash
# =============================================================================
# 02_flash_twrp.sh
# Unlocks bootloader (with 1 required user confirmation on device), then
# flashes TWRP recovery. ⚠️ DEVICE DATA IS WIPED by fastboot oem unlock.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../config.env"
source "${SCRIPT_DIR}/lib/common.sh"

DL_DIR="${SCRIPT_DIR}/../downloads"
ADB_CMD="adb${ADB_SERIAL:+ -s $ADB_SERIAL}"
FASTBOOT_CMD="fastboot${ADB_SERIAL:+ -s $ADB_SERIAL}"

check_twrp_image() {
    log_section "Checking TWRP image"
    local codename="${DEVICE_CODENAME:-gust}"
    local img="${DL_DIR}/twrp-${codename}.img"

    if [[ ! -f "$img" ]]; then
        log_error "TWRP image not found: $img"
        log_error "Run 00_check_prerequisites.sh first, or place the image manually"
        exit 1
    fi
    log_ok "TWRP image: $img ($(du -sh "$img" | awk '{print $1}'))"
    echo "$img"
}

check_bootloader_status() {
    log_section "Verifying bootloader state"

    # Must be in fastboot mode already OR in ADB mode
    local mode
    mode=$($ADB_CMD get-state 2>/dev/null || echo "offline")

    if [[ "$mode" == "device" ]]; then
        local locked
        locked=$($ADB_CMD shell getprop ro.boot.flash.locked 2>/dev/null | tr -d '[:space:]')
        if [[ "$locked" == "0" ]]; then
            log_ok "Bootloader already UNLOCKED — skipping unlock step"
            export ALREADY_UNLOCKED=true
            return
        fi
        export ALREADY_UNLOCKED=false
    elif [[ "$mode" == "offline" ]]; then
        log_error "No ADB device detected. Make sure device is connected and USB debugging is ON."
        exit 1
    fi
    export ALREADY_UNLOCKED=false
}

unlock_bootloader() {
    if [[ "${ALREADY_UNLOCKED:-false}" == "true" ]]; then
        log_section "Bootloader unlock"
        log_ok "Already unlocked — skipping"
        return
    fi

    log_section "⚠️  Bootloader Unlock"
    echo ""
    echo -e "  ${YELLOW}${BOLD}WARNING: This will WIPE ALL DATA on your device!${RESET}"
    echo -e "  ${YELLOW}Make sure backup (01_backup.sh) completed successfully.${RESET}"
    echo ""
    read -rp "  Type 'UNLOCK' to confirm you have a backup and want to proceed: " confirm
    if [[ "$confirm" != "UNLOCK" ]]; then
        log_error "Aborted by user"
        exit 1
    fi

    # Reboot to bootloader
    reboot_and_wait "bootloader" 60

    # Check fastboot connectivity
    log_info "Checking fastboot connectivity..."
    local fb_devices
    fb_devices=$(fastboot devices 2>/dev/null)
    if [[ -z "$fb_devices" ]]; then
        log_error "No device in fastboot mode detected."
        log_error "On macOS, try: brew install --cask android-platform-tools"
        exit 1
    fi
    log_ok "Device detected in fastboot: $fb_devices"

    # Request unlock
    log_info "Sending fastboot oem unlock..."
    echo ""
    echo -e "  ${BOLD}👉 ACTION REQUIRED:${RESET}"
    echo "     On your Poco C65 screen, use Vol-Down to select 'Unlock bootloader'"
    echo "     then press Power button to confirm."
    echo "     The device will WIPE and reboot automatically."
    echo ""

    $FASTBOOT_CMD oem unlock 2>&1 || {
        # On newer fastboot, use 'flashing unlock' instead
        $FASTBOOT_CMD flashing unlock 2>&1 || {
            log_error "fastboot unlock failed. See docs/migration/docs/05-TROUBLESHOOTING.md"
            exit 1
        }
    }

    # Wait for device to come back after wipe + reboot
    log_info "Waiting for device to finish wiping and reboot..."
    sleep 30
    wait_for_adb_state "device" 120

    # Verify unlock
    local locked_after
    locked_after=$($ADB_CMD shell getprop ro.boot.flash.locked 2>/dev/null | tr -d '[:space:]')
    if [[ "$locked_after" == "0" ]]; then
        log_ok "Bootloader successfully UNLOCKED ✅"
        session_log "Bootloader unlocked"
    else
        log_error "Bootloader still shows as locked (flash.locked=${locked_after})"
        log_error "The device may need Mi authorization first — use the HyperOS-AAU app"
        exit 1
    fi
}

flash_twrp() {
    log_section "Flashing TWRP"
    local img
    img=$(check_twrp_image)

    log_info "Rebooting to bootloader for TWRP flash..."
    reboot_and_wait "bootloader" 60

    log_info "Flashing TWRP to recovery partition..."
    $FASTBOOT_CMD flash recovery "$img" 2>&1 && \
        log_ok "TWRP flashed to recovery partition" || {
        log_error "Failed to flash TWRP. Check fastboot output above."
        exit 1
    }

    # Verify flash succeeded by booting TWRP temporarily
    log_info "Booting TWRP to verify..."
    $FASTBOOT_CMD boot "$img" 2>&1

    log_info "Waiting for TWRP to start (recovery mode)..."
    sleep 10
    wait_for_adb_state "recovery" 90

    local twrp_check
    twrp_check=$($ADB_CMD shell "getprop ro.twrp.version 2>/dev/null || echo unknown" | tr -d '[:space:]')
    if [[ "$twrp_check" != "unknown" && -n "$twrp_check" ]]; then
        log_ok "TWRP version: $twrp_check ✅"
    else
        log_warn "TWRP booted (device is in recovery) but version prop not found — may still be OK"
    fi

    session_log "TWRP flashed and booted successfully"
    log_ok "TWRP is running. Ready for ROM flash (03_flash_rom.sh)."
}

# ── Main ─────────────────────────────────────────────────────────────────────
check_twrp_image > /dev/null  # validate file exists early
check_bootloader_status
unlock_bootloader
flash_twrp
