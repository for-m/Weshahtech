#!/bin/sh
# shellcheck shell=dash disable=SC3043
# WESHAH agent — VLAN management on real OpenWrt switch configuration.
#
# Supported layouts (detected, never assumed):
#   swconfig  config switch_vlan   (ar71xx/ath79 built-in switches, OpenWrt <= 21.02)
#   dsa       config bridge-vlan   (OpenWrt >= 21.02 DSA targets)
# Only sections created by this agent (named weshah_vlan_<id>) can be modified or
# deleted; VLANs configured by hand are listed read-only.
#
# Safety: every change is "commit-confirm". The previous network/dhcp/firewall
# configs are snapshotted and restored automatically by the tick unless the
# client calls POST /api/v1/vlans/confirm within VLAN_CONFIRM_SECONDS (default 90).
# The Android adapter confirms automatically once it can reach the agent again.

VLAN_DIR="$STATE_DIR/vlan"
ROLLBACK_DIR="$STATE_DIR/rollback"
VLAN_CONFIRM_SECONDS="${VLAN_CONFIRM_SECONDS:-90}"
CFG_DIR="${WESHAH_CFG_DIR:-/etc/config}"

vlan_mode() {
    local show
    show=$(uci -q show network)
    if printf '%s\n' "$show" | grep -q '=switch_vlan$' && have_cmd swconfig; then printf swconfig; return; fi
    if printf '%s\n' "$show" | grep -q '=bridge-vlan$'; then printf dsa; return; fi
    if ! printf '%s\n' "$show" | grep -q '=switch$' && [ -n "$(dsa_ports)" ]; then printf dsa; return; fi
    printf none
}

# ── helpers ───────────────────────────────────────────────────────────────────

# netdev that carries the switch CPU port, e.g. eth0 (from lan ifname eth0.1)
swconfig_cpu_netdev() {
    local n
    n=$(uci -q get network.lan.ifname)
    [ -z "$n" ] && n=$(uci -q show network | sed -n "s/.*ports='\(eth[0-9]*\)\.[0-9]*'.*/\1.0/p" | head -n 1)
    printf '%s' "${n%.*}"
}

dsa_bridge() {
    local b
    b=$(lan_dev)
    printf '%s' "$b"
}

dsa_bridge_section() { # section name of the bridge device
    uci -q show network | sed -n "s/^network\.\(.*\)\.name='$(dsa_bridge)'$/\1/p" | head -n 1
}

dsa_ports() { uci -q get "network.$(dsa_bridge_section).ports"; }

prefix_to_mask() { awk -v p="$1" 'BEGIN { m = 0; for (i = 0; i < 32; i++) m = m * 2 + (i < p ? 1 : 0); printf "%d.%d.%d.%d", int(m / 16777216), int(m / 65536) % 256, int(m / 256) % 256, m % 256 }'; }

mask_to_prefix() { printf '%s' "$1" | awk -F. '{ n = 0; for (i = 1; i <= 4; i++) { v = $i; while (v > 0) { n += v % 2; v = int(v / 2) } } print n }'; }


# 0 when CIDR <a> and CIDR <b> overlap
cidr_overlap() {
    awk -v a="$1" -v b="$2" '
        function toint(s,  q) { split(s, q, "."); return ((q[1] * 256 + q[2]) * 256 + q[3]) * 256 + q[4] }
        BEGIN {
            split(a, x, "/"); split(b, y, "/")
            ba = 2 ^ (32 - x[2]); bb = 2 ^ (32 - y[2])
            sa = int(toint(x[1]) / ba) * ba; sb = int(toint(y[1]) / bb) * bb
            exit ((sa < sb + bb) && (sb < sa + ba)) ? 0 : 1
        }'
}

# "<ip>/<prefix>" of an existing interface section, or nothing
iface_cidr() {
    local ip mask
    ip=$(uci -q get "network.$1.ipaddr"); mask=$(uci -q get "network.$1.netmask")
    case "$ip" in */*) printf '%s' "$ip"; return ;; esac
    [ -n "$ip" ] && [ -n "$mask" ] && printf '%s/%s' "$ip" "$(mask_to_prefix "$mask")"
}

all_used_cidrs() {
    local s
    for s in $(uci -q show network | sed -n 's/^network\.\([^.=]*\)=interface$/\1/p'); do
        [ "$s" = loopback ] && continue
        iface_cidr "$s"; echo
    done | sed '/^$/d'
}

# ── read ──────────────────────────────────────────────────────────────────────

# prints "<section> <vlan id>" for every VLAN section
vlan_sections() {
    local mode sec id
    mode=$(vlan_mode)
    case "$mode" in
        swconfig) for sec in $(uci -q show network | sed -n 's/^network\.\(.*\)=switch_vlan$/\1/p'); do
                      id=$(uci -q get "network.$sec.vlan"); [ -n "$id" ] && echo "$sec $id"
                  done ;;
        dsa)      for sec in $(uci -q show network | sed -n 's/^network\.\(.*\)=bridge-vlan$/\1/p'); do
                      id=$(uci -q get "network.$sec.vlan"); [ -n "$id" ] && echo "$sec $id"
                  done ;;
    esac
}

# interface section bound to a VLAN id (managed first, then by netdev name)
vlan_iface() { # <id>
    local id="$1" dev
    if [ -n "$(uci -q get "network.weshah_v$id")" ]; then printf 'weshah_v%s' "$id"; return; fi
    if [ "$(vlan_mode)" = swconfig ]; then dev="$(swconfig_cpu_netdev).$id"; else dev="$(dsa_bridge).$id"; fi
    uci -q show network | sed -n "s/^network\.\([^.=]*\)\.\(ifname\|device\)='$dev'$/\1/p" | head -n 1
}

json_ports() { # <space separated ports> → JSON array of strings
    local p first=1
    printf '['
    for p in $1; do
        [ "$first" = 1 ] || printf ','
        first=0
        printf '"%s"' "$p"
    done
    printf ']'
}

# splits a swconfig ports string into: tagged / untagged user ports (CPU excluded)
swconfig_split() { # <ports string> → sets SW_TAGGED SW_UNTAGGED SW_CPU
    local p cpu
    SW_TAGGED=""; SW_UNTAGGED=""; SW_CPU=""
    cpu=$(swconfig_cpu_port)
    for p in $1; do
        case "$p" in
            "${cpu}t") SW_CPU="$p" ;;
            *t) SW_TAGGED="$SW_TAGGED ${p%t}" ;;
            *) SW_UNTAGGED="$SW_UNTAGGED $p" ;;
        esac
    done
}

dsa_split() { # <list of "port:flags"> → sets SW_TAGGED SW_UNTAGGED
    local p
    SW_TAGGED=""; SW_UNTAGGED=""
    for p in $1; do
        case "$p" in
            *:t*) SW_TAGGED="$SW_TAGGED ${p%%:*}" ;;
            *) SW_UNTAGGED="$SW_UNTAGGED ${p%%:*}" ;;
        esac
    done
}

get_vlans() {
    local mode sec id first=1 name ifc cidr gw prefix mask dhcp inet managed ports
    mode=$(vlan_mode)
    [ "$mode" = none ] && { fail "$RC_UNSUPPORTED" "vlan_not_supported_on_this_switch"; return; }
    printf '{"vlans":['
    vlan_sections | while read -r sec id; do
        ifc=$(vlan_iface "$id")
        name=$(uci -q get "network.$sec.weshah_name"); [ -z "$name" ] && name="VLAN $id"
        cidr=""; gw=""
        if [ -n "$ifc" ]; then
            cidr=$(iface_cidr "$ifc"); gw=${cidr%/*}
            if [ -n "$cidr" ]; then
                prefix=${cidr#*/}
                cidr="$(awk -v ip="$gw" -v p="$prefix" 'BEGIN { split(ip, a, "."); b = 2 ^ (32 - p); n = ((a[1] * 256 + a[2]) * 256 + a[3]) * 256 + a[4]; n = int(n / b) * b; printf "%d.%d.%d.%d/%d", int(n / 16777216), int(n / 65536) % 256, int(n / 256) % 256, n % 256, p }')"
            fi
        fi
        dhcp=0; [ -n "$ifc" ] && [ -n "$(uci -q get "dhcp.$ifc")" ] && [ "$(uci -q get "dhcp.$ifc.ignore")" != 1 ] && dhcp=1
        inet=0; [ -n "$ifc" ] && uci -q show firewall | grep -q "^firewall\..*\.src='wv$id'$" && inet=1
        managed=0; case "$sec" in weshah_vlan_*) managed=1 ;; esac
        if [ "$mode" = swconfig ]; then swconfig_split "$(uci -q get "network.$sec.ports")"
        else ports=$(uci -q get "network.$sec.ports"); dsa_split "$ports"; fi
        [ "$first" = 1 ] || printf ','
        first=0
        printf '{"vlanId":%s,"name":%s,"gateway":%s,"subnet":%s,"dhcpEnabled":%s,"internetAccess":%s,"clientIsolation":false,"ssid":null,"taggedPorts":%s,"untaggedPorts":%s,"managed":%s}' \
            "$id" "$(jstr "$name")" "$(jstr_n "$gw")" "$(jstr_n "$cidr")" "$(jbool $dhcp)" "$(jbool $inet)" \
            "$(json_ports "$SW_TAGGED")" "$(json_ports "$SW_UNTAGGED")" "$(jbool $managed)"
    done
    printf ']}'
}

# ── request validation ────────────────────────────────────────────────────────

V_ID=""; V_NAME=""; V_GW=""; V_CIDR=""; V_DHCP=0; V_INET=0; V_TAGGED=""; V_UNTAGGED=""

valid_user_port() {
    case "$(vlan_mode)" in
        swconfig) swconfig_port_numbers | grep -qx "$1" ;;
        dsa) dsa_ports | tr ' ' '\n' | grep -qx "$1" ;;
    esac
}

parse_vlan_request() { # <body> → 0 ok / prints error token on failure
    local body="$1" v p ssid iso subnet
    V_ID=$(json_get "$body" '@.vlanId')
    { is_uint "$V_ID" && [ "$V_ID" -ge 2 ] && [ "$V_ID" -le 4094 ]; } || { echo "invalid_vlan_id_2_to_4094"; return 1; }
    V_ID=$(dec "$V_ID")
    V_NAME=$(json_get "$body" '@.name')
    printf '%s' "$V_NAME" | grep -qE '^[A-Za-z0-9 _-]{1,24}$' || { echo "invalid_name"; return 1; }
    ssid=$(json_get "$body" '@.ssid'); iso=$(json_get "$body" '@.clientIsolation')
    [ -n "$ssid" ] && { echo "unsupported_field:ssid"; return 1; }
    [ "$iso" = true ] && { echo "unsupported_field:clientIsolation"; return 1; }
    V_GW=$(json_get "$body" '@.gateway'); subnet=$(json_get "$body" '@.subnet')
    V_CIDR=""
    if [ -n "$V_GW$subnet" ]; then
        is_ipv4 "$V_GW" || { echo "invalid_gateway"; return 1; }
        printf '%s' "$subnet" | grep -qE '^[0-9.]+/[0-9]{1,2}$' || { echo "invalid_subnet"; return 1; }
        p=${subnet#*/}
        { [ "$p" -ge 8 ] && [ "$p" -le 30 ]; } || { echo "invalid_prefix_8_to_30"; return 1; }
        is_ipv4 "${subnet%/*}" || { echo "invalid_subnet"; return 1; }
        cidr_overlap "$V_GW/$p" "$subnet" || { echo "gateway_not_in_subnet"; return 1; }
        V_CIDR="$V_GW/$p"
    fi
    v=$(json_get "$body" '@.dhcpEnabled'); [ "$v" = true ] && V_DHCP=1 || V_DHCP=0
    v=$(json_get "$body" '@.internetAccess'); [ "$v" = true ] && V_INET=1 || V_INET=0
    { [ "$V_DHCP" = 1 ] || [ "$V_INET" = 1 ]; } && [ -z "$V_CIDR" ] && { echo "gateway_and_subnet_required"; return 1; }
    V_TAGGED=$(json_list "$body" '@.taggedPorts[*]' | tr '\n' ' ')
    V_UNTAGGED=$(json_list "$body" '@.untaggedPorts[*]' | tr '\n' ' ')
    for p in $V_TAGGED $V_UNTAGGED; do
        valid_user_port "$p" || { echo "unknown_port:$p"; return 1; }
    done
    for p in $V_TAGGED; do
        printf ' %s ' "$V_UNTAGGED" | grep -q " $p " && { echo "port_both_tagged_and_untagged:$p"; return 1; }
    done
    [ -n "$V_TAGGED$V_UNTAGGED" ] || { echo "ports_required"; return 1; }
    return 0
}

# ── apply (commit-confirm) ────────────────────────────────────────────────────

# pending file: "<boot id> <deadline in uptime seconds>"; uptime is immune to NTP steps
vlan_snapshot() {
    local f
    [ -f "$ROLLBACK_DIR/pending" ] && return 0
    mkdir -p "$ROLLBACK_DIR"
    for f in network dhcp firewall; do cp "$CFG_DIR/$f" "$ROLLBACK_DIR/$f"; done
    echo "$(boot_id) $(( $(uptime_s) + VLAN_CONFIRM_SECONDS ))" > "$ROLLBACK_DIR/pending"
}

services_reload_bg() {
    ( sleep 2; sh -c "$NET_RELOAD"; sh -c "$DNSMASQ_RELOAD"; sh -c "$FW_RELOAD" ) >/dev/null 2>&1 </dev/null &
}

vlan_revert() { uci revert network; uci revert dhcp; uci revert firewall; }

vlan_commit() {
    uci commit network && uci commit dhcp && uci commit firewall || return 1
    services_reload_bg
}

# Called from the tick and at boot: restores the snapshot when the change was
# not confirmed in time, or when the router rebooted before it was confirmed.
rollback_check() {
    local bid deadline f
    [ -f "$ROLLBACK_DIR/pending" ] || return 0
    read -r bid deadline < "$ROLLBACK_DIR/pending"
    if [ "$bid" = "$(boot_id)" ] && [ "$(uptime_s)" -lt "$deadline" ]; then return 0; fi
    for f in network dhcp firewall; do
        [ -f "$ROLLBACK_DIR/$f" ] && cp "$ROLLBACK_DIR/$f" "$CFG_DIR/$f"
    done
    rm -f "$ROLLBACK_DIR"/pending "$ROLLBACK_DIR"/network "$ROLLBACK_DIR"/dhcp "$ROLLBACK_DIR"/firewall
    log "VLAN change not confirmed: previous configuration restored"
    services_reload_bg
}

confirm_changes() {
    if [ -f "$ROLLBACK_DIR/pending" ]; then
        rm -f "$ROLLBACK_DIR"/pending "$ROLLBACK_DIR"/network "$ROLLBACK_DIR"/dhcp "$ROLLBACK_DIR"/firewall
        log "VLAN change confirmed"
        ok_json
    else
        printf '{"success":true,"error":null,"note":"nothing_pending"}'
    fi
}

# ── write ─────────────────────────────────────────────────────────────────────

vlan_remove_aux() { # <id>  removes interface/dhcp/zone/forwarding/rules we created
    local id="$1"
    uci -q delete "network.weshah_v$id"
    uci -q delete "dhcp.weshah_v$id"
    uci -q delete "firewall.weshah_z$id"
    uci -q delete "firewall.weshah_f$id"
    uci -q delete "firewall.weshah_rd$id"
    uci -q delete "firewall.weshah_rp$id"
}

vlan_write_aux() { # uses V_* globals
    local id="$V_ID" mask dev
    vlan_remove_aux "$id"
    [ -z "$V_CIDR" ] && return 0
    mask=$(prefix_to_mask "${V_CIDR#*/}")
    if [ "$(vlan_mode)" = swconfig ]; then dev="$(swconfig_cpu_netdev).$id"; else dev="$(dsa_bridge).$id"; fi
    uci set "network.weshah_v$id=interface"
    uci set "network.weshah_v$id.proto=static"
    uci set "network.weshah_v$id.$([ "$(vlan_mode)" = swconfig ] && echo ifname || echo device)=$dev"
    uci set "network.weshah_v$id.ipaddr=$V_GW"
    uci set "network.weshah_v$id.netmask=$mask"
    if [ "$V_DHCP" = 1 ]; then
        uci set "dhcp.weshah_v$id=dhcp"
        uci set "dhcp.weshah_v$id.interface=weshah_v$id"
        uci set "dhcp.weshah_v$id.start=100"
        uci set "dhcp.weshah_v$id.limit=150"
        uci set "dhcp.weshah_v$id.leasetime=12h"
    fi
    uci set "firewall.weshah_z$id=zone"
    uci set "firewall.weshah_z$id.name=wv$id"
    uci set "firewall.weshah_z$id.network=weshah_v$id"
    uci set "firewall.weshah_z$id.input=REJECT"
    uci set "firewall.weshah_z$id.output=ACCEPT"
    uci set "firewall.weshah_z$id.forward=REJECT"
    if [ "$V_INET" = 1 ]; then
        uci set "firewall.weshah_f$id=forwarding"
        uci set "firewall.weshah_f$id.src=wv$id"
        uci set "firewall.weshah_f$id.dest=wan"
    fi
    if [ "$V_DHCP" = 1 ] || [ "$V_INET" = 1 ]; then
        uci set "firewall.weshah_rd$id=rule"
        uci set "firewall.weshah_rd$id.name=weshah-dns-$id"
        uci set "firewall.weshah_rd$id.src=wv$id"
        uci set "firewall.weshah_rd$id.proto=tcp udp"
        uci set "firewall.weshah_rd$id.dest_port=53"
        uci set "firewall.weshah_rd$id.target=ACCEPT"
        uci set "firewall.weshah_rp$id=rule"
        uci set "firewall.weshah_rp$id.name=weshah-dhcp-$id"
        uci set "firewall.weshah_rp$id.src=wv$id"
        uci set "firewall.weshah_rp$id.proto=udp"
        uci set "firewall.weshah_rp$id.dest_port=67-68"
        uci set "firewall.weshah_rp$id.target=ACCEPT"
    fi
}

# swconfig: build the ports string for the new VLAN and steal untagged ports from
# the VLANs that currently own them (remembered so delete can give them back).
vlan_write_swconfig() { # uses V_* globals
    local id="$V_ID" sec="weshah_vlan_$V_ID" sw cpu ports="" p other oid oports newports moved=""
    sw=$(uci -q get network.@switch[0].name)
    [ -n "$sw" ] || { echo "no_switch_section"; return 1; }
    cpu=$(swconfig_cpu_port)
    [ -n "$cpu" ] || { echo "cpu_port_unknown"; return 1; }
    if [ -z "$(uci -q get "network.$sec")" ]; then
        [ "$(vlan_sections | wc -l)" -ge 15 ] && { echo "switch_vlan_table_full"; return 1; }
    fi
    mkdir -p "$VLAN_DIR"
    : > "$VLAN_DIR/$id.moved"
    for p in $V_UNTAGGED; do
        vlan_sections | while read -r other oid; do
            [ "$other" = "$sec" ] && continue
            oports=$(uci -q get "network.$other.ports")
            printf ' %s ' "$oports" | grep -q " $p " || continue
            newports=$(printf '%s' "$oports" | tr ' ' '\n' | grep -vx "$p" | tr '\n' ' ' | sed 's/ $//')
            uci set "network.$other.ports=$newports"
            echo "$p $other" >> "$VLAN_DIR/$id.moved"
        done
    done
    for p in $V_UNTAGGED; do ports="$ports $p"; done
    for p in $V_TAGGED; do ports="$ports ${p}t"; done
    ports="$(printf '%s' "$ports" | sed 's/^ //') ${cpu}t"
    uci -q delete "network.$sec"
    uci set "network.$sec=switch_vlan"
    uci set "network.$sec.device=$sw"
    uci set "network.$sec.vlan=$id"
    uci set "network.$sec.ports=$ports"
    uci set "network.$sec.weshah_name=$V_NAME"
}

vlan_write_dsa() {
    local id="$V_ID" sec="weshah_vlan_$V_ID" p first=1
    uci -q delete "network.$sec"
    uci set "network.$sec=bridge-vlan"
    uci set "network.$sec.device=$(dsa_bridge)"
    uci set "network.$sec.vlan=$id"
    uci set "network.$sec.weshah_name=$V_NAME"
    for p in $V_TAGGED; do uci add_list "network.$sec.ports=$p:t"; done
    for p in $V_UNTAGGED; do
        if [ "$first" = 1 ]; then uci add_list "network.$sec.ports=$p:u*"; first=0
        else uci add_list "network.$sec.ports=$p:u"; fi
    done
}

vlan_foreign_conflict() { # <id> → 0 when a non-managed VLAN already uses this id
    vlan_sections | awk -v id="$1" '$2 == id && $1 !~ /^weshah_vlan_/ { found = 1 } END { exit found ? 0 : 1 }'
}

vlan_subnet_conflict() { # uses V_CIDR / V_ID → prints the conflicting CIDR
    local c
    [ -z "$V_CIDR" ] && return 1
    all_used_cidrs | while read -r c; do
        [ -n "$(uci -q get "network.weshah_v$V_ID")" ] && [ "$c" = "$(iface_cidr "weshah_v$V_ID")" ] && continue
        cidr_overlap "$V_CIDR" "$c" && { echo "$c"; break; }
    done | head -n 1 | grep .
}

vlan_save() { # <body> <create|update>
    local err conflict mode rc
    mode=$(vlan_mode)
    [ "$mode" = none ] && { fail "$RC_UNSUPPORTED" "vlan_not_supported_on_this_switch"; return; }
    # must run in the current shell: it fills the V_* globals
    parse_vlan_request "$1" > "$RUN_DIR/vlan.parse"; rc=$?
    err=$(cat "$RUN_DIR/vlan.parse")
    [ "$rc" = 0 ] || { fail "$RC_BAD" "$err"; return; }
    vlan_foreign_conflict "$V_ID" && { fail "$RC_CONFLICT" "vlan_managed_externally"; return; }
    if [ "$2" = create ] && [ -n "$(uci -q get "network.weshah_vlan_$V_ID")" ]; then fail "$RC_CONFLICT" "vlan_exists"; return; fi
    if [ "$2" = update ] && [ -z "$(uci -q get "network.weshah_vlan_$V_ID")" ]; then fail "$RC_NOTFOUND" "vlan_not_found"; return; fi
    conflict=$(vlan_subnet_conflict) && { fail "$RC_CONFLICT" "subnet_overlaps:$conflict"; return; }
    vlan_snapshot
    if [ "$2" = update ] && [ "$mode" = swconfig ]; then vlan_restore_moved "$V_ID"; fi
    if [ "$mode" = swconfig ]; then err=$(vlan_write_swconfig) || { vlan_revert; fail "$RC_FAIL" "$err"; return; }
    else vlan_write_dsa; fi
    vlan_write_aux
    vlan_commit || { vlan_revert; fail "$RC_FAIL" "uci_commit_failed"; return; }
    log "vlan $2 $V_ID (confirm within ${VLAN_CONFIRM_SECONDS}s)"
    printf '{"success":true,"error":null,"confirmRequired":true,"confirmWithinSeconds":%s}' "$VLAN_CONFIRM_SECONDS"
}

create_vlan() { vlan_save "$1" create; }
update_vlan() { # <id> <body>
    local bid
    bid=$(json_get "$2" '@.vlanId')
    [ "$bid" = "$1" ] || { fail "$RC_BAD" "vlan_id_mismatch"; return; }
    vlan_save "$2" update
}

vlan_restore_moved() { # <id> give untagged ports back to the VLANs they were taken from
    local id="$1" p sec cur
    [ -f "$VLAN_DIR/$id.moved" ] || return 0
    while read -r p sec; do
        [ -n "$(uci -q get "network.$sec")" ] || continue
        cur=$(uci -q get "network.$sec.ports")
        printf ' %s ' "$cur" | grep -q " $p " && continue
        uci set "network.$sec.ports=$p $cur"
    done < "$VLAN_DIR/$id.moved"
    rm -f "$VLAN_DIR/$id.moved"
}

delete_vlan() { # <id>
    local id="$1" mode
    mode=$(vlan_mode)
    [ "$mode" = none ] && { fail "$RC_UNSUPPORTED" "vlan_not_supported_on_this_switch"; return; }
    is_uint "$id" || { fail "$RC_BAD" "invalid_vlan_id"; return; }
    id=$(dec "$id")
    vlan_foreign_conflict "$id" && { fail "$RC_CONFLICT" "vlan_managed_externally"; return; }
    [ -n "$(uci -q get "network.weshah_vlan_$id")" ] || { fail "$RC_NOTFOUND" "vlan_not_found"; return; }
    vlan_snapshot
    [ "$mode" = swconfig ] && vlan_restore_moved "$id"
    uci -q delete "network.weshah_vlan_$id"
    vlan_remove_aux "$id"
    vlan_commit || { vlan_revert; fail "$RC_FAIL" "uci_commit_failed"; return; }
    log "vlan delete $id (confirm within ${VLAN_CONFIRM_SECONDS}s)"
    printf '{"success":true,"error":null,"confirmRequired":true,"confirmWithinSeconds":%s}' "$VLAN_CONFIRM_SECONDS"
}
