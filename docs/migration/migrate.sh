#!/usr/bin/env bash
# =============================================================================
# migrate.sh — Master Migration Orchestrator
# Poco C65 → LineageOS 21 + microG
#
# Usage:
#   ./migrate.sh              # Run all phases
#   ./migrate.sh --phase 1    # Resume from phase 1 (backup)
#   ./migrate.sh --phase 3    # Resume from phase 3 (flash ROM)
#   ./migrate.sh --help       # Show help
#
# Phases:
#   0 = Prerequisites check + file downloads
#   1 = Backup (media, APKs, contacts, SMS)
#   2 = Flash TWRP (includes bootloader unlock)
#   3 = Flash ROM (LineageOS + microG)
#   4 = Restore media
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Load config ───────────────────────────────────────────────────────────────
if [[ ! -f "${SCRIPT_DIR}/config.env" ]]; then
    echo "ERROR: config.env not found in ${SCRIPT_DIR}"
    echo "Copy and edit config.env before running migrate.sh"
    exit 1
fi
source "${SCRIPT_DIR}/config.env"
source "${SCRIPT_DIR}/scripts/lib/common.sh"

# ── Globals ───────────────────────────────────────────────────────────────────
START_PHASE=0
RESTORE_ON_FAILURE=true
LOG_FILE="${BACKUP_DIR}/migration.log"

# ── Parse arguments ───────────────────────────────────────────────────────────
parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --phase|-p)
                START_PHASE="${2:?--phase requires a value}"
                shift 2
                ;;
            --no-restore)
                RESTORE_ON_FAILURE=false
                shift
                ;;
            --help|-h)
                show_help
                exit 0
                ;;
            *)
                echo "Unknown argument: $1"
                show_help
                exit 1
                ;;
        esac
    done
}

show_help() {
    cat <<EOF

${BOLD}migrate.sh — Poco C65 → LineageOS 21 + microG${RESET}

  ${CYAN}Usage:${RESET}
    ./migrate.sh [options]

  ${CYAN}Options:${RESET}
    --phase N, -p N   Start from phase N (0-4)
    --no-restore      Do not attempt auto-restore on failure
    --help, -h        Show this help

  ${CYAN}Phases:${RESET}
    0  Prerequisites (check tools, download files)
    1  Backup (media, APKs, contacts, SMS)
    2  Flash TWRP (bootloader unlock + TWRP install)
    3  Flash ROM (LineageOS + microG sideload)
    4  Restore (media + APKs to new OS)

  ${CYAN}Examples:${RESET}
    ./migrate.sh               # Full run (phases 0-4)
    ./migrate.sh --phase 1     # Skip prereqs, start from backup
    ./migrate.sh --phase 3     # Skip to ROM flash (use if TWRP already installed)

  ${CYAN}Documentation:${RESET}
    docs/migration/docs/00-OVERVIEW.md

EOF
}

# ── Failure / restore handler ─────────────────────────────────────────────────
on_failure() {
    local exit_code=$?
    local line=${BASH_LINENO[0]}

    echo ""
    log_error "Migration failed at line $line (exit code $exit_code)"
    mkdir -p "$BACKUP_DIR"
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] MIGRATION FAILED at line $line" >> "$LOG_FILE"

    if [[ "${RESTORE_ON_FAILURE:-true}" == "true" ]]; then
        echo ""
        echo -e "  ${YELLOW}Attempting emergency restore...${RESET}"
        emergency_restore
    else
        echo ""
        echo "  --no-restore specified. Skipping auto-restore."
        echo "  To manually restore: ./scripts/04_restore.sh"
    fi
}
trap on_failure ERR

emergency_restore() {
    # If a backup exists and failure happened during/after flash, run restore
    local latest="${BACKUP_DIR}/latest_backup.txt"
    if [[ -f "$latest" ]]; then
        log_info "Running restore from: $(cat "$latest")"
        bash "${SCRIPT_DIR}/scripts/04_restore.sh" || true
    else
        log_warn "No backup found — cannot auto-restore"
        echo "  If device is in TWRP, you can reflash stock ROM manually:"
        echo "  See docs/migration/docs/04-RESTORE.md"
    fi
}

# ── Phase runner ──────────────────────────────────────────────────────────────
run_phase() {
    local phase_num="$1"
    local phase_name="$2"
    local script="$3"

    if [[ $phase_num -lt $START_PHASE ]]; then
        log_info "Skipping phase $phase_num: $phase_name (--phase ${START_PHASE})"
        return
    fi

    log_section "PHASE $phase_num: $phase_name"
    mkdir -p "$BACKUP_DIR"
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] Starting phase $phase_num: $phase_name" >> "$LOG_FILE"

    bash "$script"

    echo "[$(date '+%Y-%m-%d %H:%M:%S')] Completed phase $phase_num: $phase_name" >> "$LOG_FILE"
    log_ok "Phase $phase_num complete: $phase_name ✅"
    echo ""
}

print_banner() {
    echo ""
    echo -e "${BOLD}${CYAN}"
    echo "  ╔══════════════════════════════════════════════════╗"
    echo "  ║   Poco C65 → LineageOS 21 + microG Migration    ║"
    echo "  ║   HyperOS-AAU Migration Tool                    ║"
    echo "  ╚══════════════════════════════════════════════════╝"
    echo -e "${RESET}"
    if [[ $START_PHASE -gt 0 ]]; then
        echo -e "  ${YELLOW}Resuming from phase ${START_PHASE}${RESET}"
    fi
    echo ""
}

print_completion() {
    echo ""
    echo -e "${BOLD}${GREEN}"
    echo "  ╔══════════════════════════════════════════════════╗"
    echo "  ║         ✅ Migration Complete!                   ║"
    echo "  ╚══════════════════════════════════════════════════╝"
    echo -e "${RESET}"
    echo ""
    echo "  Your Poco C65 is now running LineageOS 21 + microG"
    echo "  Backup is stored at: ${BACKUP_DIR}"
    echo ""
    echo "  Next steps:"
    echo "  1. Install F-Droid: https://f-droid.org"
    echo "  2. Enable microG: Settings → microG → Self-Check"
    echo "  3. Install your apps from F-Droid or APK backup"
    echo "  4. Import contacts and set up SMS app"
    echo ""
    echo "  Documentation: docs/migration/docs/"
    echo ""
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] MIGRATION COMPLETE" >> "$LOG_FILE"
}

# ── Main ─────────────────────────────────────────────────────────────────────
parse_args "$@"
mkdir -p "$BACKUP_DIR"
print_banner

run_phase 0 "Prerequisites"  "${SCRIPT_DIR}/scripts/00_check_prerequisites.sh"
run_phase 1 "Backup"         "${SCRIPT_DIR}/scripts/01_backup.sh"
run_phase 2 "Flash TWRP"     "${SCRIPT_DIR}/scripts/02_flash_twrp.sh"
run_phase 3 "Flash ROM"      "${SCRIPT_DIR}/scripts/03_flash_rom.sh"
run_phase 4 "Restore"        "${SCRIPT_DIR}/scripts/04_restore.sh"

print_completion
