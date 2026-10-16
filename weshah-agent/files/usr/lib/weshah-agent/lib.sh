#!/bin/sh
# shellcheck shell=dash disable=SC3043
# WESHAH agent — shared helpers. POSIX sh / BusyBox ash only (no arrays, no [[ ]]).
# Sourced by handler, tick and the feature modules.

WESHAH_VERSION="2.1.0"

CONF="${WESHAH_CONF:-/etc/weshah-agent.conf}"
STATE_DIR="${WESHAH_STATE_DIR:-/etc/weshah-agent}"
RUN_DIR="${WESHAH_RUN_DIR:-/tmp/weshah-agent}"
LEASES_FILE="${WESHAH_LEASES:-/tmp/dhcp.leases}"
SYS_NET="${WESHAH_SYS_NET:-/sys/class/net}"
ARP_FILE="${WESHAH_ARP_FILE:-/proc/net/arp}"
THERMAL_FILE="${WESHAH_THERMAL:-/sys/class/thermal/thermal_zone0/temp}"
FW_RELOAD="${WESHAH_FW_RELOAD:-/etc/init.d/firewall reload}"
NET_RELOAD="${WESHAH_NET_RELOAD:-/etc/init.d/network reload}"
DNSMASQ_RELOAD="${WESHAH_DNSMASQ_RELOAD:-/etc/init.d/dnsmasq reload}"
REBOOT_CMD="${WESHAH_REBOOT_CMD:-reboot}"
LOCK_DIR="$RUN_DIR/lock"
PORT=8765
TOKEN=""

# shellcheck disable=SC1090
[ -f "$CONF" ] && . "$CONF"

mkdir -p "$RUN_DIR" 2>/dev/null

# Exit codes used by operations; the HTTP layer maps them to status codes.
RC_OK=0
RC_BAD=2        # 400 validation
RC_NOTFOUND=3   # 404
RC_CONFLICT=4   # 409
RC_UNSUPPORTED=5 # 501
RC_FAIL=6       # 500
RC_BUSY=7       # 503
RC_UNAUTH=8     # 401

log() { logger -t weshah-agent "$*" 2>/dev/null || true; }

# ── validation ────────────────────────────────────────────────────────────────

is_mac() {
    printf '%s' "$1" | grep -qE '^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$' || return 1
    case "$(norm_mac "$1")" in
        00:00:00:00:00:00|ff:ff:ff:ff:ff:ff) return 1 ;;
    esac
    return 0
}

norm_mac() { printf '%s' "$1" | tr 'A-F' 'a-f'; }

mac_key() { printf '%s' "$1" | tr ':' '_'; }

is_uint() {
    case "$1" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ "${#1}" -le 10 ]
}

is_ipv4() {
    printf '%s' "$1" | awk -F. '
        NF != 4 { exit 1 }
        { for (i = 1; i <= 4; i++) {
              if ($i !~ /^[0-9]+$/ || length($i) > 3 || $i + 0 > 255) exit 1
          } exit 0 }'
}

is_ident() { printf '%s' "$1" | grep -qE '^[A-Za-z0-9_-]{1,32}$'; }

# Strips leading zeros so values like "08" are not parsed as octal by ash.
dec() {
    local v="$1"
    case "$v" in
        ''|*[!0-9]*) printf 0; return ;;
    esac
    while [ "${#v}" -gt 1 ] && [ "${v#0}" != "$v" ]; do v="${v#0}"; done
    printf '%s' "$v"
}

# ── JSON output helpers ───────────────────────────────────────────────────────

json_esc() {
    printf '%s' "$1" \
        | tr -d '\000-\010\013\014\015\016-\037' \
        | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/	/\\t/g' \
        | awk 'BEGIN { ORS = "" } NR > 1 { printf "\\n" } { print }'
}

jstr() { printf '"%s"' "$(json_esc "$1")"; }

jstr_n() { if [ -n "$1" ]; then jstr "$1"; else printf 'null'; fi; }

jint() {
    local v="${1#-}"
    case "$v" in
        ''|*[!0-9]*) printf 'null' ;;
        *) printf '%s' "$1" ;;
    esac
}

jflt() {
    printf '%s' "$1" | awk '/^-?[0-9]+(\.[0-9]+)?$/ { printf "%s", $0; ok = 1; exit } END { if (!ok) printf "null" }'
}

jbool() { case "$1" in 1|true|yes) printf 'true' ;; *) printf 'false' ;; esac; }

ok_json() { printf '{"success":true,"error":null}'; }

# fail <rc> <message>: prints a failure body and returns rc
fail() {
    printf '{"success":false,"error":%s}' "$(jstr "$2")"
    return "$1"
}

# json_get <json> <jsonfilter-expr>  → first match or empty (null is empty)
json_get() {
    local out
    out=$(jsonfilter -s "$1" -e "$2" 2>/dev/null | head -n 1)
    [ "$out" = "null" ] && out=""
    printf '%s' "$out"
}

json_list() { jsonfilter -s "$1" -e "$2" 2>/dev/null; }

# ── locking (mkdir based; works without flock) ────────────────────────────────

# BusyBox 1.28 sleep takes whole seconds only.
lock_acquire() {
    local tries=0 owner
    while ! mkdir "$LOCK_DIR" 2>/dev/null; do
        owner=$(cat "$LOCK_DIR/pid" 2>/dev/null)
        if [ -n "$owner" ] && ! kill -0 "$owner" 2>/dev/null; then
            rm -rf "$LOCK_DIR"
            continue
        fi
        tries=$((tries + 1))
        [ "$tries" -gt 20 ] && return 1
        sleep 1
    done
    echo "$$" > "$LOCK_DIR/pid"
    return 0
}

lock_release() { rm -rf "$LOCK_DIR"; }

with_lock() {
    local rc
    lock_acquire || { fail "$RC_BUSY" "agent_busy"; return "$RC_BUSY"; }
    "$@"
    rc=$?
    lock_release
    return "$rc"
}

# ── clock ─────────────────────────────────────────────────────────────────────
# Prints: <year> <dow 1=Mon..7=Sun> <HH> <MM> <epoch>
# WESHAH_FAKE_CLOCK is a test seam only; it is never set on a router.
clock_now() {
    if [ -n "$WESHAH_FAKE_CLOCK" ]; then
        printf '%s' "$WESHAH_FAKE_CLOCK"
        return
    fi
    date '+%Y %u %H %M %s'
}

epoch_now() { clock_now | awk '{print $5}'; }

# A router without RTC boots in 1970/build-date; never act on schedules then.
clock_synced() {
    local y
    y=$(clock_now | awk '{print $1}')
    [ "$y" -ge 2024 ] 2>/dev/null
}

boot_id() { cat /proc/sys/kernel/random/boot_id 2>/dev/null || echo unknown; }
uptime_s() { awk '{ printf "%d", $1 }' /proc/uptime; }

# ── network helpers ───────────────────────────────────────────────────────────

lan_dev() {
    local d
    d=$(ubus call network.interface.lan status 2>/dev/null | jsonfilter -e '@.l3_device' 2>/dev/null)
    [ -z "$d" ] && d=$(uci -q get network.lan.device)
    [ -z "$d" ] && d=$(uci -q get network.lan.ifname)
    printf '%s' "${d:-br-lan}"
}

wan_dev() {
    local d
    d=$(ubus call network.interface.wan status 2>/dev/null | jsonfilter -e '@.l3_device' 2>/dev/null)
    [ -z "$d" ] && d=$(uci -q get network.wan.device)
    [ -z "$d" ] && d=$(uci -q get network.wan.ifname)
    printf '%s' "$d"
}

fw_reload_bg() { sh -c "$FW_RELOAD" >/dev/null 2>&1 & }

have_cmd() { command -v "$1" >/dev/null 2>&1; }

# Firewall flavour: fw4/nftables (OpenWrt >= 22.03) or fw3/iptables (<= 21.02, incl. 18.06).
fw_backend() {
    if [ -n "$WESHAH_FW_BACKEND" ]; then printf '%s' "$WESHAH_FW_BACKEND"; return; fi
    if [ -x /sbin/fw4 ] && have_cmd nft; then printf nft
    elif have_cmd iptables; then printf ipt
    else printf none; fi
}

# Sections created by the agent are always prefixed so we never touch user config.
block_section() { printf 'weshah_block_%s' "$(mac_key "$1")"; }
sched_section() { printf 'weshah_sched_%s' "$(mac_key "$1")"; }
