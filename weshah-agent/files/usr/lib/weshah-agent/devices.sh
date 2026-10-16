#!/bin/sh
# shellcheck shell=dash disable=SC3043
# WESHAH agent — device inventory, per-device traffic, WiFi disconnect.
#
# A device is "online" when it is associated to a local AP or has a complete ARP
# entry on the LAN device; a lease alone only makes it "known". Byte counters
# and rates come from the firewall counters in acct.sh (real, since agent start).
# rxBytes/rxRateBps = delivered TO the device (download); tx = sent BY it (upload).
# Rates are bytes per second between two samples taken at least 2 s apart.

# mode: list | traffic. For traffic, <mac> is required.
devices_json() { # <mode> [mac]
    local mode="$1" only="$2" d="$RUN_DIR/dev" lan now
    mkdir -p "$d"
    lan=$(lan_dev); now=$(epoch_now)
    if [ -f "$LEASES_FILE" ]; then
        awk '{ h = $4; if (h == "*") h = ""; print tolower($2), $3, h }' "$LEASES_FILE" > "$d/leases"
    else : > "$d/leases"; fi
    awk -v dev="$lan" 'NR > 1 && $6 == dev && $3 != "0x0" { print tolower($4), $1 }' "$ARP_FILE" > "$d/arp" 2>/dev/null
    wifi_stations > "$d/wifi"
    uci -q show firewall | sed -n 's/^firewall\.weshah_\(block\|sched\)_\([0-9a-f_]*\)=rule$/\2 \1/p' \
        | awk '{ gsub(/_/, ":", $1); print }' > "$d/blocked"
    cat "$RUN_DIR/speed.desired" > "$d/speed" 2>/dev/null || : > "$d/speed"
    acct_totals_file "$d/acct"
    [ -f "$RUN_DIR/rate.prev" ] || : > "$RUN_DIR/rate.prev"
    awk -v D="$d" -v prevf="$RUN_DIR/rate.prev" -v newf="$RUN_DIR/rate.new" -v now="$now" \
        -v lan="$lan" -v mode="$mode" -v only="$only" '
    function jesc(s) { gsub(/\\/, "\\\\", s); gsub(/"/, "\\\"", s); gsub(/[\001-\037]/, "", s); return s }
    function jstr(s) { return (s == "" || s == "-") ? "null" : "\"" jesc(s) "\"" }
    function rate(k, cur, prevv, pt) {
        if (pt <= 0 || now - pt < 1 || now - pt > 900 || cur < prevv) return "null"
        return sprintf("%.0f", int((cur - prevv) / (now - pt)))
    }
    BEGIN {
        while ((getline l < (D "/leases")) > 0) { split(l, p, " "); ip[p[1]] = p[2]; host[p[1]] = p[3]; known[p[1]] = 1 }
        while ((getline l < (D "/arp")) > 0) { split(l, p, " "); if (!(p[1] in ip)) ip[p[1]] = p[2]; online[p[1]] = 1; known[p[1]] = 1 }
        while ((getline l < (D "/wifi")) > 0) { split(l, p, " "); wif[p[1]] = p[2]; rssi[p[1]] = p[3]; band[p[1]] = p[4]; online[p[1]] = 1; known[p[1]] = 1 }
        while ((getline l < (D "/blocked")) > 0) { split(l, p, " "); blocked[p[1]] = 1 }
        while ((getline l < (D "/speed")) > 0) { split(l, p, " "); sd[p[1]] = p[2]; su[p[1]] = p[3] }
        while ((getline l < (D "/acct")) > 0) { split(l, p, " "); dn[p[1]] = p[2]; upb[p[1]] = p[3] }
        while ((getline l < prevf) > 0) { split(l, p, " "); pts[p[1]] = p[2]; pdn[p[1]] = p[3]; pup[p[1]] = p[4] }
        for (mac in known) {
            key = mac; gsub(/:/, "", key)
            cd = dn[key] + 0; cu = upb[key] + 0
            rd = rate(key, cd, pdn[key] + 0, pts[key] + 0); ru = rate(key, cu, pup[key] + 0, pts[key] + 0)
            if (key in dn) {
                if (!(key in pts) || now - pts[key] >= 2) printf "%s %s %.0f %.0f\n", key, now, cd, cu > newf
                else printf "%s %s %.0f %.0f\n", key, pts[key], pdn[key], pup[key] > newf
            }
            if (mode == "traffic") {
                if (mac != only) continue
                printf "%s\t{\"mac\":\"%s\",\"rxBytes\":%.0f,\"txBytes\":%.0f,\"rxRateBps\":%s,\"txRateBps\":%s,\"timestamp\":%.0f}\n", mac, mac, cd, cu, (rd == "null" ? 0 : rd), (ru == "null" ? 0 : ru), now * 1000
                continue
            }
            isw = (mac in wif)
            printf "%s\t{\"mac\":\"%s\",\"ip\":%s,\"hostname\":%s,\"interface\":%s,\"isWifi\":%s,\"isOnline\":%s,\"rssi\":%s,\"band\":%s,\"rxBytes\":%.0f,\"txBytes\":%.0f,\"rxRateBps\":%s,\"txRateBps\":%s,\"isBlocked\":%s,\"speedDownKbps\":%s,\"speedUpKbps\":%s}\n", \
                mac, mac, jstr(ip[mac]), jstr(host[mac]), jstr(isw ? wif[mac] : lan), (isw ? "true" : "false"), ((mac in online) ? "true" : "false"), \
                (isw && rssi[mac] != "null" ? rssi[mac] : "null"), (isw ? jstr(band[mac]) : "null"), cd, cu, rd, ru, ((mac in blocked) ? "true" : "false"), \
                ((mac in sd && sd[mac] + 0 > 0) ? sd[mac] : "null"), ((mac in su && su[mac] + 0 > 0) ? su[mac] : "null")
        }
    }' > "$d/out" 2>/dev/null
    [ -f "$RUN_DIR/rate.new" ] && mv "$RUN_DIR/rate.new" "$RUN_DIR/rate.prev"
    if [ "$mode" = traffic ]; then
        [ -s "$d/out" ] || return 1
        cut -f2- "$d/out"
        return 0
    fi
    sort "$d/out" | cut -f2- | awk 'BEGIN { printf "[" } NR > 1 { printf "," } { printf "%s", $0 } END { printf "]" }'
}

get_devices() { devices_json list; }

get_device_traffic() { # <mac>
    local mac out
    mac=$(norm_mac "$1")
    is_mac "$mac" || { fail "$RC_BAD" "invalid_mac"; return; }
    out=$(devices_json traffic "$mac") || { fail "$RC_NOTFOUND" "unknown_device"; return; }
    printf '%s' "$out"
}

# Deauthenticates a station. Preference: hostapd ubus object (no extra package),
# then hostapd_cli, then iw station del.
disconnect_device() {
    local mac w done_any=0 payload
    mac=$(norm_mac "$1")
    is_mac "$mac" || { fail "$RC_BAD" "invalid_mac"; return; }
    for w in $(wifi_ifaces); do
        iw dev "$w" station get "$mac" >/dev/null 2>&1 || continue
        payload="{\"addr\":\"$mac\",\"reason\":5,\"deauth\":true,\"ban_time\":0}"
        if ubus call "hostapd.$w" del_client "$payload" >/dev/null 2>&1; then done_any=1
        elif have_cmd hostapd_cli && hostapd_cli -i "$w" deauthenticate "$mac" >/dev/null 2>&1; then done_any=1
        elif iw dev "$w" station del "$mac" >/dev/null 2>&1; then done_any=1; fi
    done
    if [ "$done_any" = 1 ]; then log "deauth $mac"; ok_json
    else fail "$RC_NOTFOUND" "not_associated_to_any_local_ap"; fi
}
