#!/usr/bin/env bash
# =============================================================================
# 00_check_prerequisites.sh
# Validates environment, device state, and downloads all required files.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/../config.env"
source "${SCRIPT_DIR}/lib/common.sh"

check_host_tools() {
    log_section "Checking host tools"
    local missing=()

    for tool in adb fastboot curl sha256sum; do
        if command -v "$tool" &>/dev/null; then
            log_ok "$tool: $(command -v $tool)"
        else
            log_fail "$tool: NOT FOUND"
            missing+=("$tool")
        fi
    done

    if [[ ${#missing[@]} -gt 0 ]]; then
        log_error "Missing tools: ${missing[*]}"
        echo ""
        echo "  macOS:  brew install android-platform-tools"
        echo "  Linux:  sudo apt install adb fastboot"
        exit 1
    fi

    # Check minimum ADB version (needs 1.0.41+ for sideload)
    local adb_ver
    adb_ver=$(adb version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
    log_ok "ADB version: $adb_ver"
}

detect_device() {
    log_section "Detecting device"

    local devices
    devices=$(adb devices 2>&1 | grep -v "^List" | grep "device$" | awk '{print $1}')
    local count
    count=$(echo "$devices" | grep -c . || true)

    if [[ $count -eq 0 ]]; then
        log_error "No ADB device found. Connect your Poco C65 via USB and enable USB debugging."
        exit 1
    elif [[ $count -gt 1 ]]; then
        if [[ -z "${ADB_SERIAL:-}" ]]; then
            log_error "Multiple devices found. Set ADB_SERIAL in config.env to specify one:"
            echo "$devices"
            exit 1
        fi
    fi

    export ADB="${ADB_SERIAL:+adb -s $ADB_SERIAL}"
    export ADB="${ADB:-adb}"

    # Auto-detect codename if not set
    if [[ -z "${DEVICE_CODENAME:-}" ]]; then
        DEVICE_CODENAME=$($ADB shell getprop ro.product.device 2>/dev/null | tr -d '[:space:]')
        log_ok "Auto-detected device codename: $DEVICE_CODENAME"
    fi

    # Validate it's a known gust/gale device
    if [[ "$DEVICE_CODENAME" != "gust" && "$DEVICE_CODENAME" != "gale" ]]; then
        log_warn "Unexpected codename: $DEVICE_CODENAME (expected gust or gale)"
        log_warn "These scripts are designed for Poco C65 ONLY. Proceed at your own risk."
        read -rp "Continue anyway? [y/N] " confirm
        [[ "${confirm,,}" == "y" ]] || exit 1
    fi

    local model build android
    model=$($ADB shell getprop ro.product.model 2>/dev/null | tr -d '[:space:]')
    build=$($ADB shell getprop ro.miui.ui.version.name 2>/dev/null | tr -d '[:space:]')
    android=$($ADB shell getprop ro.build.version.release 2>/dev/null | tr -d '[:space:]')

    log_ok "Model: $model | Android: $android | HyperOS/MIUI: $build"
    export DEVICE_CODENAME
}

check_bootloader_status() {
    log_section "Bootloader lock status"

    local locked
    locked=$($ADB shell getprop ro.boot.flash.locked 2>/dev/null | tr -d '[:space:]')

    if [[ "$locked" == "0" ]]; then
        log_ok "Bootloader is UNLOCKED ✅ — ready to flash"
        export BOOTLOADER_UNLOCKED=true
    else
        log_warn "Bootloader is LOCKED 🔒"
        log_warn "You must unlock it before flashing. Use the HyperOS-AAU app to"
        log_warn "request authorization, then run: adb reboot bootloader && fastboot oem unlock"
        export BOOTLOADER_UNLOCKED=false
    fi
}

check_disk_space() {
    log_section "Checking disk space"

    # Available on device (we need to know what we're backing up)
    local used_kb
    used_kb=$($ADB shell df /sdcard 2>/dev/null | tail -1 | awk '{print $3}')
    local used_gb=$(( ${used_kb:-0} / 1024 / 1024 ))
    log_ok "Device storage in use: ~${used_gb}GB"

    # Available on host
    local host_free_kb
    host_free_kb=$(df -k "$HOME" | tail -1 | awk '{print $4}')
    local host_free_gb=$(( ${host_free_kb:-0} / 1024 / 1024 ))
    log_ok "Host free space: ~${host_free_gb}GB"

    local needed_gb=$(( used_gb + 10 )) # ROM + TWRP overhead
    if [[ $host_free_gb -lt $needed_gb ]]; then
        log_error "Not enough space on host. Need ~${needed_gb}GB, have ${host_free_gb}GB"
        log_error "Set BACKUP_DIR in config.env to a volume with more space"
        exit 1
    fi

    mkdir -p "$BACKUP_DIR"
    log_ok "Backup destination: $BACKUP_DIR"
}

download_and_verify() {
    local name="$1"
    local url="$2"
    local sha256="$3"
    local dest="$4"

    if [[ "${SKIP_DOWNLOAD:-false}" == "true" && -f "$dest" ]]; then
        log_ok "$name: already downloaded (SKIP_DOWNLOAD=true)"
    else
        if [[ "$url" == *"placeholder"* ]] || [[ -z "$url" ]]; then
            log_warn "$name: URL not configured in config.env — SKIPPING download"
            log_warn "  Visit the URL in config.env, download manually, place in: $dest"
            return 0
        fi
        log_info "Downloading $name..."
        curl -L --progress-bar --retry 3 -o "$dest" "$url" || {
            log_error "Failed to download $name from: $url"
            exit 1
        }
    fi

    if [[ -z "${sha256:-}" ]] || [[ "${SKIP_CHECKSUM:-false}" == "true" ]]; then
        log_warn "$name: checksum not configured — skipping SHA256 verification"
    else
        local actual
        actual=$(sha256sum "$dest" | awk '{print $1}')
        if [[ "$actual" == "$sha256" ]]; then
            log_ok "$name: SHA256 OK ✅"
        else
            log_error "$name: SHA256 MISMATCH!"
            log_error "  Expected: $sha256"
            log_error "  Got:      $actual"
            exit 1
        fi
    fi
}

download_files() {
    log_section "Downloading required files"

    local dl_dir
    dl_dir="$(dirname "$SCRIPT_DIR")/downloads"
    mkdir -p "$dl_dir"

    local codename="${DEVICE_CODENAME:-gust}"

    download_and_verify \
        "LineageOS 21 ROM" \
        "${LINEAGEOS_ZIP_URL}" \
        "${LINEAGEOS_ZIP_SHA256:-}" \
        "${dl_dir}/lineageos-21-${codename}.zip"

    download_and_verify \
        "NanoDroid microG" \
        "${MICROG_ZIP_URL}" \
        "${MICROG_ZIP_SHA256:-}" \
        "${dl_dir}/NanoDroid-microG.zip"

    download_and_verify \
        "TWRP Recovery" \
        "${TWRP_IMG_URL}" \
        "${TWRP_IMG_SHA256:-}" \
        "${dl_dir}/twrp-${codename}.img"

    log_ok "All downloads complete. Files are in: $dl_dir"
}

print_summary() {
    log_section "Prerequisites Summary"
    echo ""
    echo "  Device codename:   ${DEVICE_CODENAME}"
    echo "  Bootloader:        $(${BOOTLOADER_UNLOCKED} && echo "UNLOCKED ✅" || echo "LOCKED 🔒")"
    echo "  Backup destination:${BACKUP_DIR}"
    echo ""
    if [[ "${BOOTLOADER_UNLOCKED:-false}" == "false" ]]; then
        echo "  ⚠️  ACTION REQUIRED: Unlock bootloader before proceeding to flash phase."
        echo "     1. Get authorization via HyperOS-AAU app (wait for Beijing midnight)"
        echo "     2. Run: adb reboot bootloader"
        echo "     3. Run: fastboot oem unlock"
        echo "     4. Press Vol-Down on device to confirm (WIPES device data)"
        echo ""
    fi
    echo "  ✅ Backup phase can run NOW (does not require unlocked bootloader)"
    echo ""
}

# ── Main ─────────────────────────────────────────────────────────────────────
check_host_tools
detect_device
check_bootloader_status
check_disk_space
download_files
print_summary
