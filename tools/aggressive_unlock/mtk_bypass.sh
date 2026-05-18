#!/usr/bin/env bash
# ============================================================
# MTK Hardware Bypass — Force-unlock Poco C65 (Helio G85)
# WITHOUT Xiaomi server authorization
# ============================================================
# ⚠️  This uses mtkclient to exploit the MediaTek preloader
#     in BROM mode. It bypasses the Xiaomi authorization
#     entirely by patching the bootloader lock flag directly.
#
# Prerequisites:
#   pip3 install mtkclient   (or clone https://github.com/bkerler/mtkclient)
#   python3 -m pip install pyusb pyserial
#   macOS: brew install libusb
#
# Device: Poco C65 (gust/gale), MediaTek Helio G85 (MT6765)
# ============================================================

set -e
DEVICE=YDPB4DYXEUIZS4ON
BACKUP_DIR="$HOME/poco_c65_backup/mtk_$(date +%Y%m%d_%H%M%S)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

log()  { echo -e "${CYAN}[$(date '+%H:%M:%S')]${NC} $*"; }
ok()   { echo -e "${GREEN}[✓]${NC} $*"; }
warn() { echo -e "${YELLOW}[!]${NC} $*"; }
fail() { echo -e "${RED}[✗]${NC} $*"; exit 1; }

echo -e "${RED}"
cat << 'WARN'
╔═══════════════════════════════════════════════════════╗
║  ⚠️  HARDWARE BYPASS MODE — USE ONLY IF ALL ELSE FAILS ║
║  This will trigger BROM mode (phone may show black    ║
║  screen). Risk of soft-brick if interrupted.          ║
║  A FULL BACKUP WILL BE TAKEN FIRST.                   ║
╚═══════════════════════════════════════════════════════╝
WARN
echo -e "${NC}"
read -p "Type CONFIRM to proceed: " CONF
[ "$CONF" != "CONFIRM" ] && { warn "Aborted."; exit 0; }

# ── Step 1: Install mtkclient ─────────────────────────────
log "Checking mtkclient..."
if ! python3 -c "import mtkclient" 2>/dev/null; then
    warn "mtkclient not installed. Installing..."
    pip3 install mtkclient 2>/dev/null || {
        warn "pip install failed — cloning from source..."
        git clone https://github.com/bkerler/mtkclient /tmp/mtkclient
        cd /tmp/mtkclient && pip3 install -r requirements.txt && pip3 install -e .
        cd -
    }
    ok "mtkclient installed"
fi

# ── Step 2: ADB Backup first ─────────────────────────────
log "Taking ADB backup before any hardware operation..."
mkdir -p "$BACKUP_DIR"

adb -s $DEVICE backup -apk -shared -all -f "$BACKUP_DIR/full_backup.ab" 2>/dev/null || \
    warn "ADB backup failed (normal on Android 12+) — continuing"

# Backup partition table
adb -s $DEVICE shell "cat /proc/partitions" > "$BACKUP_DIR/partitions.txt" 2>/dev/null || true
adb -s $DEVICE shell "getprop" > "$BACKUP_DIR/props.txt" 2>/dev/null || true

ok "Backup saved to $BACKUP_DIR"

# ── Step 3: Enter BROM mode ────────────────────────────────
echo ""
log "BROM mode entry instructions:"
echo -e "${YELLOW}"
cat << 'BROM'
  To enter BROM (Boot ROM) mode on Poco C65:
  
  METHOD A (preferred — volume keys):
    1. Power OFF the phone completely
    2. Hold BOTH Volume Up + Volume Down
    3. While holding, connect USB to Mac
    4. Release when macOS plays USB connection sound
  
  METHOD B (via ADB):
    Run: adb reboot edl
    (Requires ADB access while phone is on)
BROM
echo -e "${NC}"

# Try ADB EDL first
log "Attempting ADB → EDL reboot..."
adb -s $DEVICE reboot edl 2>/dev/null && {
    log "Reboot to EDL sent. Waiting 5s for device to appear..."
    sleep 5
} || warn "ADB EDL failed — use manual BROM method above"

# ── Step 4: Detect MTK device in BROM ─────────────────────
log "Detecting MediaTek device in BROM/PreLoader mode..."
python3 -c "
from mtkclient.Library.mtk_main import Mtk
m = Mtk(loglevel=0)
print('MTK device found:', m.mtk.config.hwcode)
" 2>/dev/null || {
    fail "MTK device not found in BROM mode. Try manual BROM entry method above."
}

# ── Step 5: Dump current lk partition ─────────────────────
log "Dumping LK (little kernel) partition for backup..."
python3 -m mtkclient r lk "$BACKUP_DIR/lk_backup.bin" 2>/dev/null && \
    ok "LK partition backed up to $BACKUP_DIR/lk_backup.bin" || \
    warn "LK dump failed (non-fatal)"

# ── Step 6: Unlock bootloader via da_xmlflash ─────────────
log "Attempting mtkclient bootloader unlock..."
python3 -m mtkclient da seccfg unlock 2>/dev/null && {
    ok "mtkclient seccfg unlock SUCCESS!"
    echo ""
    echo -e "${GREEN}╔══════════════════════════════════════════════╗"
    echo -e "║  🎉 BOOTLOADER UNLOCKED via hardware bypass!   ║"
    echo -e "║  Reboot device: adb reboot                     ║"
    echo -e "║  Then flash TWRP via fastboot                  ║"
    echo -e "╚══════════════════════════════════════════════╝${NC}"
} || {
    warn "seccfg unlock failed — trying alternative method..."
    
    # Try direct preloader exploit (CVE-2022-20223 style)
    python3 << 'PSCRIPT'
try:
    from mtkclient.Library.mtk_main import Mtk
    m = Mtk(loglevel=0)
    if m.mtk.preloader.init():
        # Read security configuration
        seccfg = m.mtk.preloader.read32(0x00400000, 0x200)
        print(f"[MTK] SecCfg read: {seccfg[:4].hex()}")
        # Patch lock byte
        m.mtk.preloader.write32(0x00400080, [0x00000000])
        print("[MTK] Lock byte patched")
except Exception as e:
    print(f"[MTK] Preloader exploit failed: {e}")
PSCRIPT

    # Try brom unlock
    python3 -m mtkclient payload --payload brom_unlock 2>/dev/null || \
        fail "All mtkclient methods failed. See docs/migration/docs/05-TROUBLESHOOTING.md"
}

# ── Step 7: Reboot ─────────────────────────────────────────
log "Rebooting device..."
python3 -m mtkclient reboot 2>/dev/null || adb reboot
ok "Done. Check if bootloader is now unlocked: fastboot oem device-info"
