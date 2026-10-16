#!/bin/sh
# shellcheck shell=dash disable=SC3043
# WESHAH agent — system status, capabilities, interfaces, ports, WiFi, health,
# multi-WAN, DHCP static leases, login. Everything is read from the live system.

# ── primitives ────────────────────────────────────────────────────────────────

cpu_percent() { # prints a number with one decimal
    local u n s i w irq sirq st total idle pt pi pts now dt di
    read -r _ u n s i w irq sirq st _ < /proc/stat
    total=$((u + n + s + i + ${w:-0} + ${irq:-0} + ${sirq:-0} + ${st:-0})); idle=$((i + ${w:-0}))
    if [ ! -f "$RUN_DIR/cpu.prev" ]; then
        echo "$total $idle" > "$RUN_DIR/cpu.prev"
        sleep 1
        read -r _ u n s i w irq sirq st _ < /proc/stat
        total=$((u + n + s + i + ${w:-0} + ${irq:-0} + ${sirq:-0} + ${st:-0})); idle=$((i + ${w:-0}))
    fi
    read -r pt pi < "$RUN_DIR/cpu.prev"
    dt=$((total - pt)); di=$((idle - pi))
    if [ "$dt" -ge 100 ]; then echo "$total $idle" > "$RUN_DIR/cpu.prev"; fi
    if [ "$dt" -le 0 ]; then printf '0.0'; return; fi
    awk -v dt="$dt" -v di="$di" 'BEGIN { printf "%.1f", 100 * (dt - di) / dt }'
}

meminfo() { awk -v k="$1" '$1 == k ":" { print $2; exit }' /proc/meminfo; }

temp_c() { # one decimal or empty
    local t
    t=$(cat "$THERMAL_FILE" 2>/dev/null)
    case "$t" in ''|*[!0-9]*) return ;; esac
    awk -v t="$t" 'BEGIN { printf "%.1f", t / 1000 }'
}

wan_info() { # prints "<up 0|1> <ipv4> <gateway>"
    local j up ip gw
    j=$(ubus call network.interface.wan status 2>/dev/null)
    up=$(json_get "$j" '@.up'); ip=$(json_get "$j" '@["ipv4-address"][0].address')
    gw=$(json_get "$j" '@.route[0].nexthop')
    [ "$up" = "true" ] && up=1 || up=0
    printf '%s %s %s' "$up" "$ip" "$gw"
}

# ── status ────────────────────────────────────────────────────────────────────

get_status() {
    local model fw ram_t ram_a load up wi wan_up wan_ip
    model=$(cat /tmp/sysinfo/model 2>/dev/null)
    fw=$(sed -n "s/^DISTRIB_DESCRIPTION='\(.*\)'/\1/p" /etc/openwrt_release 2>/dev/null)
    ram_t=$(meminfo MemTotal); ram_a=$(meminfo MemAvailable)
    [ -z "$ram_a" ] && ram_a=$(meminfo MemFree)
    ram_t=${ram_t:-0}; ram_a=${ram_a:-0}
    set -- $(cat /proc/loadavg); load="$1 $2 $3"
    up=$(awk '{ printf "%d", $1 }' /proc/uptime)
    wi=$(wan_info); wan_up=$(printf '%s' "$wi" | awk '{print $1}'); wan_ip=$(printf '%s' "$wi" | awk '{print $2}')
    printf '{"model":%s,"firmware":%s,"kernel":%s,"hostname":%s,"uptimeSeconds":%s,"cpuPercent":%s,"ramTotalKb":%s,"ramUsedKb":%s,"temperatureCelsius":%s,"load1m":%s,"load5m":%s,"load15m":%s,"wanState":"%s","wanIp":%s,"agentVersion":"%s"}' \
        "$(jstr_n "$model")" "$(jstr_n "$fw")" "$(jstr "$(uname -r)")" "$(jstr "$(uname -n)")" \
        "$(jint "$up")" "$(jflt "$(cpu_percent)")" "$(jint "$ram_t")" "$(jint $((ram_t - ram_a)))" \
        "$(jflt "$(temp_c)")" "$(jflt "$1")" "$(jflt "$2")" "$(jflt "$3")" \
        "$([ "$wan_up" = 1 ] && echo up || echo down)" "$(jstr_n "$wan_ip")" "$WESHAH_VERSION"
}

# ── capabilities ──────────────────────────────────────────────────────────────

get_capabilities() {
    local qos_reason vlan_reason vmode be port_stats=false tdr=false rssi=false multi=false backup=false temp=false
    be=$(fw_backend)
    qos_reason=$(qos_unsupported_reason)
    vmode=$(vlan_mode); vlan_reason=""
    [ "$vmode" = none ] && vlan_reason="no_supported_switch_config"
    ls "$SYS_NET"/*/statistics/rx_bytes >/dev/null 2>&1 && port_stats=true
    have_cmd ethtool && ethtool --help 2>&1 | grep -q cable-test && tdr=true
    have_cmd iw && rssi=true
    [ -f /etc/config/mwan3 ] && multi=true
    have_cmd sysupgrade && backup=true
    [ -n "$(temp_c)" ] && temp=true
    printf '{"agentVersion":"%s","firewallBackend":"%s","bandwidthControl":%s,"perClientTraffic":%s,"portStats":%s,"cableDiagnosticsTdr":%s,"cableDiagnosticsBasic":%s,"wifiRssiPerClient":%s,"vlanManagement":%s,"vlanMode":"%s","multiWan":%s,"dhcpStaticLeases":true,"scheduling":true,"lldpNeighbors":false,"configBackup":%s,"configRestore":%s,"hotspot":false,"temperature":%s,"phyDriver":"UNKNOWN","reasons":{"bandwidthControl":%s,"vlanManagement":%s,"lldpNeighbors":"conversion_not_implemented","hotspot":"not_part_of_agent"}}' \
        "$WESHAH_VERSION" "$be" "$([ -z "$qos_reason" ] && echo true || echo false)" \
        "$([ "$be" != none ] && echo true || echo false)" "$port_stats" "$tdr" "$port_stats" "$rssi" \
        "$([ "$vmode" != none ] && echo true || echo false)" "$vmode" "$multi" "$backup" "$backup" "$temp" \
        "$(jstr_n "$qos_reason")" "$(jstr_n "$vlan_reason")"
}

# ── interfaces ────────────────────────────────────────────────────────────────

get_interfaces() {
    local lan wan first=1 i p st rx tx mac ip4 t
    lan=$(lan_dev); wan=$(wan_dev)
    printf '{"interfaces":['
    for p in "$SYS_NET"/*; do
        [ -e "$p" ] || continue
        i=${p##*/}
        if [ "$i" = lo ]; then t=LOOPBACK
        elif [ "$i" = "$wan" ]; then t=WAN
        elif [ "$i" = "$lan" ]; then t=LAN
        elif [ -d "$SYS_NET/$i/wireless" ] || [ -d "$SYS_NET/$i/phy80211" ]; then t=WIFI
        else t=OTHER; fi
        st=$(cat "$SYS_NET/$i/operstate" 2>/dev/null)
        rx=$(cat "$SYS_NET/$i/statistics/rx_bytes" 2>/dev/null)
        tx=$(cat "$SYS_NET/$i/statistics/tx_bytes" 2>/dev/null)
        mac=$(cat "$SYS_NET/$i/address" 2>/dev/null)
        ip4=$(ip -4 addr show dev "$i" 2>/dev/null | awk '/inet /{ sub(/\/.*/, "", $2); print $2; exit }')
        [ "$first" = 1 ] || printf ','
        first=0
        printf '{"name":%s,"ipAddress":%s,"macAddress":%s,"isUp":%s,"rxBytes":%s,"txBytes":%s,"type":"%s"}' \
            "$(jstr "$i")" "$(jstr_n "$ip4")" "$(jstr_n "$mac")" "$([ "$st" = up ] || [ "$st" = unknown ] && echo true || echo false)" \
            "$(jint "${rx:-0}")" "$(jint "${tx:-0}")" "$t"
    done
    printf ']}'
}

# ── ports ─────────────────────────────────────────────────────────────────────
# Netdev ports (DSA lanN/wan, ethN). On swconfig routers the physical switch
# ports are not netdevs; their link state is read with `swconfig ... get link`.

phys_ports() {
    local p i
    for p in "$SYS_NET"/*; do
        [ -e "$p" ] || continue
        i=${p##*/}
        case "$i" in
            eth[0-9]*|lan|lan[0-9]*|wan|wan[0-9]*|sfp[0-9]*|port[0-9]*) case "$i" in *.*) ;; *) echo "$i" ;; esac ;;
        esac
    done
}

get_ports() {
    local first=1 i n=0 wan up speed duplex rx tx rxe txe rxd txd crc flaps ptype sw link
    wan=$(wan_dev)
    printf '{"ports":['
    for i in $(phys_ports); do
        n=$((n + 1))
        up=false; [ "$(cat "$SYS_NET/$i/operstate" 2>/dev/null)" = up ] && up=true
        speed=$(cat "$SYS_NET/$i/speed" 2>/dev/null); case "$speed" in -*|'') speed="" ;; esac
        duplex=$(cat "$SYS_NET/$i/duplex" 2>/dev/null)
        rx=$(cat "$SYS_NET/$i/statistics/rx_bytes" 2>/dev/null); tx=$(cat "$SYS_NET/$i/statistics/tx_bytes" 2>/dev/null)
        rxe=$(cat "$SYS_NET/$i/statistics/rx_errors" 2>/dev/null); txe=$(cat "$SYS_NET/$i/statistics/tx_errors" 2>/dev/null)
        rxd=$(cat "$SYS_NET/$i/statistics/rx_dropped" 2>/dev/null); txd=$(cat "$SYS_NET/$i/statistics/tx_dropped" 2>/dev/null)
        crc=$(cat "$SYS_NET/$i/statistics/rx_crc_errors" 2>/dev/null); flaps=$(cat "$SYS_NET/$i/carrier_changes" 2>/dev/null)
        ptype=LAN; { [ "$i" = "$wan" ] || [ "$i" = wan ]; } && ptype=WAN
        [ "$first" = 1 ] || printf ','
        first=0
        printf '{"portId":%s,"label":"Port %s","isUp":%s,"speedMbps":%s,"duplexFull":%s,"autoNegotiation":null,"rxBytes":%s,"txBytes":%s,"rxErrors":%s,"txErrors":%s,"rxDropped":%s,"txDropped":%s,"crcErrors":%s,"linkFlaps":%s,"connectedMac":null,"connectedDevice":null,"vlanId":null,"portType":"%s"}' \
            "$(jstr "$i")" "$n" "$up" "$(jint "$speed")" \
            "$([ "$duplex" = full ] && echo true || { [ "$duplex" = half ] && echo false || echo null; })" \
            "$(jint "${rx:-0}")" "$(jint "${tx:-0}")" "$(jint "${rxe:-0}")" "$(jint "${txe:-0}")" \
            "$(jint "${rxd:-0}")" "$(jint "${txd:-0}")" "$(jint "${crc:-0}")" "$(jint "${flaps:-0}")" "$ptype"
    done
    if have_cmd swconfig; then
        sw=$(swconfig list 2>/dev/null | awk 'NR == 1 { print $2 }')
        if [ -n "$sw" ]; then
            for i in $(swconfig_port_numbers); do
                link=$(swconfig dev "$sw" port "$i" get link 2>/dev/null)
                n=$((n + 1))
                up=false; printf '%s' "$link" | grep -q 'link:up' && up=true
                speed=$(printf '%s' "$link" | sed -n 's/.*speed:\([0-9]*\)base.*/\1/p')
                duplex=null; printf '%s' "$link" | grep -q 'full-duplex' && duplex=true
                printf '%s' "$link" | grep -q 'half-duplex' && duplex=false
                [ "$first" = 1 ] || printf ','
                first=0
                printf '{"portId":"%s:%s","label":"Port %s","isUp":%s,"speedMbps":%s,"duplexFull":%s,"autoNegotiation":null,"rxBytes":0,"txBytes":0,"rxErrors":0,"txErrors":0,"rxDropped":0,"txDropped":0,"crcErrors":0,"linkFlaps":0,"connectedMac":null,"connectedDevice":null,"vlanId":null,"portType":"LAN"}' \
                    "$sw" "$i" "$n" "$up" "$(jint "$speed")" "$duplex"
            done
        fi
    fi
    printf ']}'
}

# "ports" strings of every switch_vlan section, named or anonymous
switch_vlan_ports() {
    local sec
    for sec in $(uci -q show network | sed -n 's/^network\.\(.*\)=switch_vlan$/\1/p'); do
        uci -q get "network.$sec.ports"
    done
}

swconfig_cpu_port() {
    switch_vlan_ports | tr ' ' '\n' | sed -n 's/^\([0-9][0-9]*\)t$/\1/p' | sort -un | head -n 1
}

# switch port numbers that carry a user-facing port (CPU port excluded)
swconfig_port_numbers() {
    local cpu
    cpu=$(swconfig_cpu_port)
    switch_vlan_ports | tr ' ' '\n' | sed 's/[tu*]//g' | sort -un | awk -v cpu="$cpu" '$1 != cpu && $1 != ""'
}

get_cable_diagnostics() { # <port>
    local port="$1" up=false speed duplex crc status=UNKNOWN out
    printf '%s' "$port" | grep -qE '^[a-zA-Z0-9_-]{1,16}$' || { fail "$RC_BAD" "invalid_port"; return; }
    [ -d "$SYS_NET/$port" ] || { fail "$RC_NOTFOUND" "port_not_found"; return; }
    [ "$(cat "$SYS_NET/$port/operstate" 2>/dev/null)" = up ] && up=true
    speed=$(cat "$SYS_NET/$port/speed" 2>/dev/null); case "$speed" in -*|'') speed="" ;; esac
    duplex=$(cat "$SYS_NET/$port/duplex" 2>/dev/null)
    crc=$(cat "$SYS_NET/$port/statistics/rx_crc_errors" 2>/dev/null)
    [ "$up" = true ] && status=CONNECTED
    # Real TDR only through the kernel cable-test API (ethtool >= 5.8 + PHY support).
    if have_cmd ethtool && ethtool --help 2>&1 | grep -q cable-test; then
        out=$(ethtool --cable-test "$port" 2>&1)
        if printf '%s' "$out" | grep -qi 'test completed'; then
            printf '%s' "$out" | grep -qi 'open' && status=OPEN
            printf '%s' "$out" | grep -qi 'short' && status=SHORT
        fi
    fi
    printf '{"portId":%s,"cableStatus":"%s","estimatedLengthMeters":null,"pairs":[],"linkSpeedMbps":%s,"duplexFull":%s,"crcErrors":%s}' \
        "$(jstr "$port")" "$status" "$(jint "$speed")" \
        "$([ "$duplex" = full ] && echo true || { [ "$duplex" = half ] && echo false || echo null; })" "$(jint "${crc:-0}")"
}

# ── WiFi ──────────────────────────────────────────────────────────────────────

wifi_ifaces() { iw dev 2>/dev/null | awk '$1 == "Interface" { print $2 }'; }

wifi_band_of() { # <iface> → BAND_*
    local f
    f=$(iw dev "$1" info 2>/dev/null | awk '/channel/ { gsub(/[(),]/, " "); for (i = 1; i <= NF; i++) if ($i == "MHz") { print $(i - 1); exit } }')
    [ -z "$f" ] && { printf 'BAND_2_4GHZ'; return; }
    if [ "$f" -gt 5900 ]; then printf 'BAND_6GHZ'; elif [ "$f" -gt 4900 ]; then printf 'BAND_5GHZ'; else printf 'BAND_2_4GHZ'; fi
}

wifi_stations() { # prints "<mac> <iface> <rssi|null> <band> <txRate|null> <rxRate|null>"
    local w band
    for w in $(wifi_ifaces); do
        band=$(wifi_band_of "$w")
        iw dev "$w" station dump 2>/dev/null | awk -v w="$w" -v b="$band" '
            function flush() { if (m != "") print m, w, (r == "" ? "null" : r), b, (tx == "" ? "null" : tx), (rx == "" ? "null" : rx) }
            /^Station/ { flush(); m = tolower($2); r = ""; tx = ""; rx = "" }
            /signal:/ { r = $2 }
            /tx bitrate:/ { tx = int($3) }
            /rx bitrate:/ { rx = int($3) }
            END { flush() }'
    done
}

# ssid → encryption, from UCI wireless
wifi_enc_map() {
    uci -q show wireless | awk -F"[.=']" '
        $3 == "ssid" { ssid[$2] = $5 }
        $3 == "encryption" { enc[$2] = $5 }
        END { for (s in ssid) print ssid[s] "\t" (enc[s] == "" ? "none" : enc[s]) }'
}

get_wifi() {
    local first=1 nfirst=1 w phy ssid mac ch freq pw band en cnt radios="" nets=""
    for w in $(wifi_ifaces); do
        phy=$(iw dev "$w" info 2>/dev/null | awk '/wiphy/ { print "phy" $2; exit }')
        ssid=$(iw dev "$w" info 2>/dev/null | awk '/^[[:space:]]*ssid / { sub(/^[[:space:]]*ssid /, ""); print; exit }')
        mac=$(cat "$SYS_NET/$w/address" 2>/dev/null)
        ch=$(iw dev "$w" info 2>/dev/null | awk '/channel/ { print $2; exit }')
        freq=$(iw dev "$w" info 2>/dev/null | awk '/channel/ { gsub(/[(),]/, " "); for (i = 1; i <= NF; i++) if ($i == "MHz") { print $(i - 1); exit } }')
        pw=$(iw dev "$w" info 2>/dev/null | awk '/txpower/ { print int($2); exit }')
        band=$(wifi_band_of "$w")
        en=$(wifi_enc_map | awk -F'\t' -v s="$ssid" '$1 == s { print $2; exit }')
        cnt=$(iw dev "$w" station dump 2>/dev/null | grep -c '^Station')
        [ "$first" = 1 ] || radios="$radios,"
        first=0
        radios="$radios{\"name\":$(jstr "${phy:-$w}"),\"ssid\":$(jstr_n "$ssid"),\"bssid\":$(jstr_n "$mac"),\"channel\":$(jint "$ch"),\"frequencyMHz\":$(jint "$freq"),\"band\":\"$band\",\"isEnabled\":true,\"txPowerDbm\":$(jint "$pw"),\"standard\":null}"
        [ "$nfirst" = 1 ] || nets="$nets,"
        nfirst=0
        nets="$nets{\"ssid\":$(jstr "$ssid"),\"bssid\":$(jstr_n "$mac"),\"band\":\"$band\",\"channel\":$(jint "$ch"),\"security\":$(jstr_n "$en"),\"isEnabled\":true,\"clientCount\":$cnt,\"radio\":$(jstr "${phy:-$w}")}"
    done
    printf '{"radios":[%s],"networks":[%s]}' "$radios" "$nets"
}

get_wifi_clients() {
    local tmp="$RUN_DIR/wifi.clients" first=1 mac ifc rssi band tx rx ip
    wifi_stations > "$tmp"
    printf '{"clients":['
    while read -r mac ifc rssi band tx rx; do
        ip=$(awk -v m="$mac" 'tolower($2) == m { print $3; exit }' "$LEASES_FILE" 2>/dev/null)
        [ "$first" = 1 ] || printf ','
        first=0
        printf '{"mac":"%s","ip":%s,"ssid":null,"bssid":null,"rssi":%s,"txRateMbps":%s,"rxRateMbps":%s,"band":"%s"}' \
            "$mac" "$(jstr_n "$ip")" "$([ "$rssi" = null ] && echo 0 || echo "$rssi")" "$(jint "$tx")" "$(jint "$rx")" "$band"
    done < "$tmp"
    printf ']}'
}

# ── health ────────────────────────────────────────────────────────────────────

ping_stats() { # <host> <count> → "<loss%> <min> <avg> <max>"  (empty fields when unreachable)
    local out loss
    out=$(ping -c "$2" -W 2 "$1" 2>/dev/null)
    loss=$(printf '%s' "$out" | sed -n 's/.* \([0-9]*\)% packet loss.*/\1/p')
    printf '%s %s' "${loss:-100}" "$(printf '%s' "$out" | sed -n 's/^round-trip min\/avg\/max = \(.*\) ms$/\1/p' | tr '/' ' ')"
}

get_health() {
    local gw gw_s inet_s gl="" il="" loss="" gwloss="" jit="" dns="" wan_state=UNKNOWN cpu ram_t ram_a temp clients errs first=1 i e t0 t1
    local target="${PING_TARGET:-1.1.1.1}"
    gw=$(ip route show default 2>/dev/null | awk '/^default/ { print $3; exit }')
    if [ -n "$gw" ]; then
        gw_s=$(ping_stats "$gw" 4); set -- $gw_s
        gwloss=$1; [ "$gwloss" -lt 100 ] 2>/dev/null && gl=$3
        wan_state=CONNECTED
        [ "$gwloss" = 100 ] && wan_state=DISCONNECTED
        [ "${gwloss:-0}" -gt 5 ] 2>/dev/null && [ "$gwloss" -lt 100 ] && wan_state=DEGRADED
    fi
    inet_s=$(ping_stats "$target" 4); set -- $inet_s
    if [ "$1" -lt 100 ] 2>/dev/null; then
        il=$3; loss=$1
        jit=$(awk -v mn="$2" -v mx="$4" 'BEGIN { printf "%.1f", mx - mn }')
        [ "$loss" -gt 5 ] 2>/dev/null && [ "$wan_state" = CONNECTED ] && wan_state=DEGRADED
    else
        wan_state=DISCONNECTED; loss=100
    fi
    t0=$(awk '{ print $1 }' /proc/uptime)
    nslookup openwrt.org 127.0.0.1 >/dev/null 2>&1 && {
        t1=$(awk '{ print $1 }' /proc/uptime)
        dns=$(awk -v a="$t0" -v b="$t1" 'BEGIN { printf "%.1f", (b - a) * 1000 }')
    }
    cpu=$(cpu_percent); ram_t=$(meminfo MemTotal); ram_a=$(meminfo MemAvailable); temp=$(temp_c)
    clients=$(awk 'NR > 1 && $3 != "0x0" { n++ } END { print n + 0 }' "$ARP_FILE" 2>/dev/null)
    errs="{"
    for i in $(phys_ports); do
        e=$(( $(cat "$SYS_NET/$i/statistics/rx_errors" 2>/dev/null || echo 0) + $(cat "$SYS_NET/$i/statistics/tx_errors" 2>/dev/null || echo 0) ))
        [ "$first" = 1 ] || errs="$errs,"
        first=0
        errs="$errs$(jstr "$i"):$e"
    done
    errs="$errs}"
    printf '{"gatewayLatencyMs":%s,"internetLatencyMs":%s,"packetLossPercent":%s,"jitterMs":%s,"dnsLatencyMs":%s,"wanState":"%s","cpuPercent":%s,"ramTotalKb":%s,"ramFreeKb":%s,"temperatureCelsius":%s,"interfaceErrors":%s,"activeClientCount":%s}' \
        "$(jflt "$gl")" "$(jflt "$il")" "$(jflt "$loss")" "$(jflt "$jit")" "$(jflt "$dns")" "$wan_state" \
        "$(jflt "$cpu")" "$(jint "$ram_t")" "$(jint "$ram_a")" "$(jflt "$temp")" "$errs" "$(jint "$clients")"
}

# ── multi-WAN (mwan3) ─────────────────────────────────────────────────────────

get_multi_wan() {
    local first=1 idx=0 name dev ip gw up rx tx en
    [ -f /etc/config/mwan3 ] || { printf '{"wans":[]}'; return; }
    printf '{"wans":['
    for name in $(uci -q show mwan3 | sed -n 's/^mwan3\.\([^.=]*\)=interface$/\1/p'); do
        en=$(uci -q get "mwan3.$name.enabled"); [ "$en" = 0 ] && continue
        dev=$(ubus call "network.interface.$name" status 2>/dev/null | jsonfilter -e '@.l3_device' 2>/dev/null)
        [ -z "$dev" ] && dev=$(uci -q get "network.$name.device"); [ -z "$dev" ] && dev=$(uci -q get "network.$name.ifname")
        [ -n "$dev" ] || continue
        ip=$(ip -4 addr show dev "$dev" 2>/dev/null | awk '/inet / { sub(/\/.*/, "", $2); print $2; exit }')
        gw=$(ip route show dev "$dev" 2>/dev/null | awk '/^default/ { print $3; exit }')
        up=false; [ "$(cat "$SYS_NET/$dev/operstate" 2>/dev/null)" = up ] && up=true
        rx=$(cat "$SYS_NET/$dev/statistics/rx_bytes" 2>/dev/null); tx=$(cat "$SYS_NET/$dev/statistics/tx_bytes" 2>/dev/null)
        idx=$((idx + 1))
        [ "$first" = 1 ] || printf ','
        first=0
        printf '{"id":%s,"name":"WAN%s","interface":%s,"isActive":%s,"isHealthy":%s,"ipAddress":%s,"gateway":%s,"rxBytes":%s,"txBytes":%s,"rxRateBps":0,"txRateBps":0,"latencyMs":null,"packetLossPercent":null,"weight":1,"priority":%s}' \
            "$(jstr "$name")" "$idx" "$(jstr "$dev")" "$up" "$up" "$(jstr_n "$ip")" "$(jstr_n "$gw")" \
            "$(jint "${rx:-0}")" "$(jint "${tx:-0}")" "$idx"
    done
    printf ']}'
}

# ── DHCP static leases (dnsmasq host sections) ────────────────────────────────

# 0 when <ip> is inside the router's LAN subnet (or the subnet is unknown)
ip_in_lan() {
    local ipaddr mask
    ipaddr=$(uci -q get network.lan.ipaddr); mask=$(uci -q get network.lan.netmask)
    awk -v ip="$1" -v la="$ipaddr" -v nm="$mask" '
        function toint(s,  a) { split(s, a, "."); return ((a[1] * 256 + a[2]) * 256 + a[3]) * 256 + a[4] }
        BEGIN {
            split(la, p, "/"); if (p[1] == "") exit 0
            if (p[2] != "") block = 2 ^ (32 - p[2]); else if (nm != "") block = 4294967296 - toint(nm); else exit 0
            exit (int(toint(ip) / block) == int(toint(p[1]) / block)) ? 0 : 1
        }'
}

static_lease_rows() { # prints "<section> <mac> <ip> <name>"
    local sec mac ip name
    for sec in $(uci -q show dhcp | sed -n 's/^dhcp\.\(.*\)=host$/\1/p'); do
        mac=$(uci -q get "dhcp.$sec.mac" | awk '{ print tolower($1) }')
        ip=$(uci -q get "dhcp.$sec.ip"); name=$(uci -q get "dhcp.$sec.name")
        echo "$sec ${mac:--} ${ip:--} ${name:--}"
    done
}

get_static_leases() {
    local first=1 sec mac ip name
    printf '{"leases":['
    static_lease_rows | while read -r sec mac ip name; do
        [ "$mac" = - ] || [ "$ip" = - ] && continue
        [ "$first" = 1 ] || printf ','
        first=0
        printf '{"mac":"%s","ip":"%s","hostname":%s}' "$mac" "$ip" "$([ "$name" = - ] && echo null || jstr "$name")"
    done
    printf ']}'
}

add_static_lease() { # <mac> <ip> <hostname>
    local mac ip hn sec other
    mac=$(norm_mac "$1"); ip="$2"; hn="$3"
    is_mac "$mac" || { fail "$RC_BAD" "invalid_mac"; return; }
    is_ipv4 "$ip" || { fail "$RC_BAD" "invalid_ip"; return; }
    if [ -n "$hn" ] && ! printf '%s' "$hn" | grep -qE '^[A-Za-z0-9]([A-Za-z0-9._-]{0,61}[A-Za-z0-9])?$'; then
        fail "$RC_BAD" "invalid_hostname"; return
    fi
    ip_in_lan "$ip" || { fail "$RC_BAD" "ip_outside_lan_subnet"; return; }
    sec="weshah_static_$(mac_key "$mac")"
    other=$(static_lease_rows | awk -v m="$mac" -v ip="$ip" -v s="$sec" '$3 == ip && $2 != m && $1 != s { print $2; exit }')
    [ -n "$other" ] && { fail "$RC_CONFLICT" "ip_already_reserved_for:$other"; return; }
    other=$(static_lease_rows | awk -v m="$mac" -v s="$sec" '$2 == m && $1 != s { print $1; exit }')
    [ -n "$other" ] && { fail "$RC_CONFLICT" "mac_has_unmanaged_reservation"; return; }
    uci -q delete "dhcp.$sec"
    uci set "dhcp.$sec=host" && uci set "dhcp.$sec.mac=$mac" && uci set "dhcp.$sec.ip=$ip" || { fail "$RC_FAIL" "uci_failed"; return; }
    [ -n "$hn" ] && uci set "dhcp.$sec.name=$hn"
    uci commit dhcp || { fail "$RC_FAIL" "uci_commit_failed"; return; }
    sh -c "$DNSMASQ_RELOAD" >/dev/null 2>&1 &
    log "static lease $mac -> $ip"
    ok_json
}

remove_static_lease() { # <mac>
    local mac sec
    mac=$(norm_mac "$1")
    is_mac "$mac" || { fail "$RC_BAD" "invalid_mac"; return; }
    sec="weshah_static_$(mac_key "$mac")"
    [ -n "$(uci -q get "dhcp.$sec")" ] || { fail "$RC_NOTFOUND" "no_managed_reservation"; return; }
    uci -q delete "dhcp.$sec"
    uci commit dhcp || { fail "$RC_FAIL" "uci_commit_failed"; return; }
    sh -c "$DNSMASQ_RELOAD" >/dev/null 2>&1 &
    log "static lease removed $mac"
    ok_json
}

# ── login ─────────────────────────────────────────────────────────────────────
# Validates router credentials through rpcd/ubus, then returns the agent token.

do_login() { # <json body>
    local user pass payload res
    user=$(json_get "$1" '@.username'); pass=$(json_get "$1" '@.password')
    [ -n "$user" ] && [ -n "$pass" ] || { fail "$RC_BAD" "credentials_required"; return; }
    [ -r /usr/share/libubox/jshn.sh ] || { fail "$RC_FAIL" "jshn_missing"; return; }
    # shellcheck disable=SC1091
    . /usr/share/libubox/jshn.sh
    json_init; json_add_string username "$user"; json_add_string password "$pass"; json_add_int timeout 3600
    payload=$(json_dump)
    res=$(ubus call session login "$payload" 2>/dev/null)
    if printf '%s' "$res" | grep -q '"ubus_rpc_session"'; then
        printf '{"token":%s,"expires":%s}' "$(jstr "$TOKEN")" "$(( $(epoch_now) + 3600 ))"
        return 0
    fi
    sleep 1
    fail "$RC_UNAUTH" "invalid_credentials"
}
