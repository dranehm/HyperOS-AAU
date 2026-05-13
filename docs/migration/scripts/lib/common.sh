#!/usr/bin/env bash
# =============================================================================
# lib/common.sh — Shared utilities for migration scripts
# =============================================================================

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

log_section() { echo -e "\n${BOLD}${CYAN}══ $1 ══${RESET}"; }
log_ok()      { echo -e "  ${GREEN}✓${RESET} $1"; }
log_fail()    { echo -e "  ${RED}✗${RESET} $1"; }
log_warn()    { echo -e "  ${YELLOW}⚠${RESET} $1"; }
log_info()    { echo -e "  ${BLUE}→${RESET} $1"; }
log_error()   { echo -e "\n${RED}${BOLD}ERROR: $1${RESET}\n" >&2; }

# Wait for device in a given ADB state: device | recovery | sideload | bootloader
wait_for_adb_state() {
    local state="$1"
    local timeout="${2:-120}"
    local elapsed=0
    local serial_flag="${ADB_SERIAL:+-s $ADB_SERIAL}"

    log_info "Waiting for device in state '$state' (timeout: ${timeout}s)..."
    while [[ $elapsed -lt $timeout ]]; do
        if adb $serial_flag get-state 2>/dev/null | grep -q "$state"; then
            log_ok "Device ready in state: $state"
            return 0
        fi
        sleep 2
        elapsed=$((elapsed + 2))
        printf "  Waiting... %ds\r" "$elapsed"
    done
    log_error "Timeout waiting for device state '$state'"
    return 1
}

# Reboot device and wait for target state
reboot_and_wait() {
    local target="$1"       # bootloader | recovery | system
    local wait_secs="${2:-$REBOOT_WAIT_SECS}"
    local serial_flag="${ADB_SERIAL:+-s $ADB_SERIAL}"

    log_info "Rebooting to $target..."
    case "$target" in
        bootloader) adb $serial_flag reboot bootloader ;;
        recovery)   adb $serial_flag reboot recovery   ;;
        system)     adb $serial_flag reboot             ;;
        sideload)   adb $serial_flag reboot sideload    ;;
    esac
    sleep 5
    wait_for_adb_state "$target" "$wait_secs"
}

# Write a timestamped log entry to the session log file
session_log() {
    local msg="$1"
    local logfile="${BACKUP_DIR:-$HOME/poco_c65_backup}/migration.log"
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $msg" >> "$logfile"
}

# Exit handler — print last command and location on unexpected error
err_trap() {
    local exit_code=$?
    local line=${BASH_LINENO[0]}
    log_error "Script failed at line $line (exit code $exit_code)"
    session_log "FAILED at ${BASH_SOURCE[1]:-unknown}:$line (exit=$exit_code)"
}
trap err_trap ERR
