#!/bin/sh
# shellcheck shell=dash disable=SC3043
# WESHAH agent — per-device speed limits (tc HTB + firewall marks).
#
# Persistent state:
#   $STATE_DIR/speed/<mac>     down=<kbps> up=<kbps> at=<epoch>   (manual limit)
#   $STATE_DIR/speed/classes   "<mac> <class id>" allocation table
# A schedule may also carry D/U lines (limit applied while the device is allowed).
# Direction: down = LAN egress (to the device), up = WAN egress (from the device).
#
# Marks occupy bits 16..23 (mask 0x00ff0000) so other users of fwmark (mwan3,
# sqm) are untouched. The root qdisc is only taken over when it is a default
# one; a foreign shaper (cake/sqm/other htb) is never replaced.

SPEED_DIR="$STATE_DIR/speed"
QOS_TABLE="weshah_qos"
QOS_CHAIN="WESHAH_QOS"
QOS_MASK="0x00ff0000"

# prints the reason this router cannot enforce speed limits, or nothing if it can
qos_unsupported_reason() {
    local k
    have_cmd tc || { echo "tc_missing"; return; }
    [ "$(fw_backend)" = none ] && { echo "no_firewall_backend"; return; }
    k=$(uname -r)
    for m in sch_htb cls_u32; do
        if ! { [ -d "/sys/module/$m" ] || ls /lib/modules/"$k"/"$m".ko >/dev/null 2>&1 \
               || zcat /proc/config.gz 2>/dev/null | grep -qi "^CONFIG_$(printf '%s' "$m" | tr 'a-z' 'A-Z' | sed 's/^SCH_/NET_SCH_/; s/^CLS_/NET_CLS_/')=[ym]"; }; then
            echo "kernel_module_missing:$m"; return
        fi
    done
    [ "$(uci -q get firewall.@defaults[0].flow_offloading)" = 1 ] && { echo "flow_offloading_enabled"; return; }
    return 0
}

qos_supported() { [ -z "$(qos_unsupported_reason)" ]; }

class_for() { # <mac> → class id 1..250 (allocated once, then stable)
    local mac="$1" id used
    mkdir -p "$SPEED_DIR"
    id=$(awk -v m="$mac" '$1 == m { print $2; exit }' "$SPEED_DIR/classes" 2>/dev/null)
    if [ -z "$id" ]; then
        id=1
        used=$(awk '{ print $2 }' "$SPEED_DIR/classes" 2>/dev/null)
        while printf '%s\n' "$used" | grep -qx "$id"; do id=$((id + 1)); done
        [ "$id" -gt 250 ] && return 1
        echo "$mac $id" >> "$SPEED_DIR/classes"
    fi
    printf '%s' "$id"
}

set_speed() { # <mac> <down kbps> <up kbps>   (0 = unlimited in that direction)
    local mac down up reason
    mac=$(norm_mac "$1"); down="$2"; up="$3"
    is_mac "$mac" || { fail "$RC_BAD" "invalid_mac"; return; }
    { is_uint "$down" && is_uint "$up"; } || { fail "$RC_BAD" "invalid_speed"; return; }
    down=$(dec "$down"); up=$(dec "$up")
    if [ "$down" -gt 1000000 ] || [ "$up" -gt 1000000 ]; then fail "$RC_BAD" "speed_out_of_range"; return; fi
    if [ "$down" -eq 0 ] && [ "$up" -eq 0 ]; then remove_speed "$mac"; return; fi
    reason=$(qos_unsupported_reason)
    [ -n "$reason" ] && { fail "$RC_UNSUPPORTED" "speed_limit_unsupported:$reason"; return; }
    class_for "$mac" >/dev/null || { fail "$RC_FAIL" "no_free_class"; return; }
    mkdir -p "$SPEED_DIR"
    printf 'down=%s\nup=%s\nat=%s\n' "$down" "$up" "$(epoch_now)" > "$SPEED_DIR/$(mac_key "$mac")"
    log "speed set $mac down=$down up=$up"
    reconcile_all
    if [ -s "$RUN_DIR/speed.err" ]; then
        fail "$RC_FAIL" "saved_but_not_applied:$(cat "$RUN_DIR/speed.err")"
        return
    fi
    ok_json
}

remove_speed() {
    local mac
    mac=$(norm_mac "$1")
    is_mac "$mac" || { fail "$RC_BAD" "invalid_mac"; return; }
    rm -f "$SPEED_DIR/$(mac_key "$mac")"
    log "speed removed $mac"
    reconcile_all
    ok_json
}

# Effective desired limits: "<mac> <down> <up> <class> <source>"
speed_desired() {
    local f key mac d u cls dow="" min="" sfile ok_clock=0
    if clock_synced; then ok_clock=1; set -- $(sched_now_args); dow=$1; min=$2; fi
    for f in "$SPEED_DIR"/*; do
        [ -f "$f" ] || continue
        key=${f##*/}; [ "$key" = "classes" ] && continue
        mac=$(printf '%s' "$key" | tr '_' ':'); is_mac "$mac" || continue
        d=$(sed -n 's/^down=//p' "$f"); u=$(sed -n 's/^up=//p' "$f")
        cls=$(class_for "$mac") || continue
        echo "$mac ${d:-0} ${u:-0} $cls manual"
    done
    for sfile in "$SCHED_DIR"/*.rules; do
        [ -e "$sfile" ] || continue
        key=${sfile##*/}; key=${key%.rules}; mac=$(printf '%s' "$key" | tr '_' ':'); is_mac "$mac" || continue
        [ -f "$SPEED_DIR/$key" ] && continue
        d=$(sed -n 's/^D //p' "$sfile" | head -n 1); u=$(sed -n 's/^U //p' "$sfile" | head -n 1)
        [ -z "$d$u" ] && continue
        [ "$ok_clock" = 1 ] && [ "$(sched_allowed "$sfile" "$dow" "$min")" = 1 ] || continue
        cls=$(class_for "$mac") || continue
        echo "$mac ${d:-0} ${u:-0} $cls schedule"
    done
}

# 0 = we may use this device's root qdisc, 1 = a foreign shaper is present
qdisc_usable() { # <dev>
    local root
    root=$(tc qdisc show dev "$1" 2>/dev/null | awk '/ root / { print $2 " " $3; exit }')
    case "$root" in
        ""|"noqueue "*|"fq_codel "*|"pfifo_fast "*|"pfifo "*|"mq "*|"fq "*|"htb 1:") return 0 ;;
    esac
    return 1
}

our_root() { tc qdisc show dev "$1" 2>/dev/null | grep -q '^qdisc htb 1: root'; }

shape_dev() { # <dev> <rate column: 2=down 3=up> <desired file>
    local dev="$1" col="$2" file="$3" mac d u cls src rate hex mark
    tc qdisc del dev "$dev" root 2>/dev/null
    tc qdisc add dev "$dev" root handle 1: htb default fff0 || return 1
    tc class add dev "$dev" parent 1: classid 1:fff0 htb rate 10000000kbit || return 1
    while read -r mac d u cls src; do
        if [ "$col" = 2 ]; then rate=$d; else rate=$u; fi
        [ "$rate" -gt 0 ] || rate=10000000
        hex=$(printf '%x' "$cls")
        mark=$(printf '0x%08x' $((cls * 65536)))
        tc class add dev "$dev" parent 1: classid "1:$hex" htb rate "${rate}kbit" ceil "${rate}kbit" || return 1
        tc qdisc add dev "$dev" parent "1:$hex" handle "$hex:" fq_codel 2>/dev/null
        tc filter add dev "$dev" parent 1: protocol all prio 10 u32 \
            match mark "$mark" "$QOS_MASK" flowid "1:$hex" || return 1
    done < "$file"
    return 0
}

# ── marking backends ──────────────────────────────────────────────────────────

qos_apply_nft() { # <desired> <addrs>
    local mac d u cls src mark
    {
        printf 'table inet %s\ndelete table inet %s\ntable inet %s {\n  chain shape {\n    type filter hook forward priority -151; policy accept;\n' \
            "$QOS_TABLE" "$QOS_TABLE" "$QOS_TABLE"
        while read -r mac d u cls src; do
            mark=$(printf '0x%08x' $((cls * 65536)))
            printf '    ether saddr %s meta mark set meta mark & 0xff00ffff | %s\n' "$mac" "$mark"
            awk -v m="$mac" -v mk="$mark" '$1 == m {
                fam = ($2 ~ /:/) ? "ip6" : "ip"
                printf "    %s daddr %s meta mark set meta mark & 0xff00ffff | %s\n", fam, $2, mk }' "$2"
        done < "$1"
        printf '  }\n}\n'
    } | nft -f - 2>"$RUN_DIR/qos.apply.err"
}

qos_apply_ipt() { # <desired> <addrs>
    local v4="$RUN_DIR/qos.ipt4" v6="$RUN_DIR/qos.ipt6" mac d u cls src mark
    for f in "$v4" "$v6"; do printf '*mangle\n:%s - [0:0]\n-F %s\n' "$QOS_CHAIN" "$QOS_CHAIN" > "$f"; done
    while read -r mac d u cls src; do
        mark=$(printf '0x%08x' $((cls * 65536)))
        printf -- '-A %s -m mac --mac-source %s -j MARK --set-xmark %s/%s\n' "$QOS_CHAIN" "$mac" "$mark" "$QOS_MASK" >> "$v4"
        printf -- '-A %s -m mac --mac-source %s -j MARK --set-xmark %s/%s\n' "$QOS_CHAIN" "$mac" "$mark" "$QOS_MASK" >> "$v6"
        awk -v m="$mac" -v mk="$mark" -v mask="$QOS_MASK" -v ch="$QOS_CHAIN" -v v4="$v4" -v v6="$v6" '$1 == m {
            out = ($2 ~ /:/) ? v6 : v4
            printf "-A %s -d %s -j MARK --set-xmark %s/%s\n", ch, $2, mk, mask >> out }' "$2"
    done < "$1"
    echo COMMIT >> "$v4"; echo COMMIT >> "$v6"
    iptables-restore --noflush < "$v4" 2>"$RUN_DIR/qos.apply.err" || return 1
    if have_cmd ip6tables-restore; then ip6tables-restore --noflush < "$v6" 2>/dev/null; fi
    ipt_ensure_jump "$QOS_CHAIN"
}

qos_live() {
    case "$(fw_backend)" in
        nft) nft list table inet "$QOS_TABLE" >/dev/null 2>&1 ;;
        ipt) iptables -t mangle -nL "$QOS_CHAIN" >/dev/null 2>&1 ;;
        *) return 1 ;;
    esac
}

qos_teardown() {
    local lan wan
    case "$(fw_backend)" in
        nft) nft list table inet "$QOS_TABLE" >/dev/null 2>&1 && nft delete table inet "$QOS_TABLE" 2>/dev/null ;;
        ipt)
            iptables -t mangle -D FORWARD -j "$QOS_CHAIN" 2>/dev/null
            iptables -t mangle -F "$QOS_CHAIN" 2>/dev/null; iptables -t mangle -X "$QOS_CHAIN" 2>/dev/null
            if have_cmd ip6tables; then
                ip6tables -t mangle -D FORWARD -j "$QOS_CHAIN" 2>/dev/null
                ip6tables -t mangle -F "$QOS_CHAIN" 2>/dev/null; ip6tables -t mangle -X "$QOS_CHAIN" 2>/dev/null
            fi ;;
    esac
    have_cmd tc || return 0
    lan=$(lan_dev); wan=$(wan_dev)
    our_root "$lan" && tc qdisc del dev "$lan" root 2>/dev/null
    [ -n "$wan" ] && our_root "$wan" && tc qdisc del dev "$wan" root 2>/dev/null
    rm -f "$RUN_DIR/speed.sig"
    return 0
}

qos_reconcile() {
    local desired="$RUN_DIR/speed.desired" addrs="$RUN_DIR/speed.addrs" lan wan sig old live=1 reason d be
    rm -f "$RUN_DIR/speed.err"
    speed_desired | sort > "$desired"
    if [ ! -s "$desired" ]; then
        { our_root "$(lan_dev)" || qos_live; } 2>/dev/null && qos_teardown
        return 0
    fi
    reason=$(qos_unsupported_reason)
    [ -n "$reason" ] && { echo "$reason" > "$RUN_DIR/speed.err"; return 1; }
    be=$(fw_backend)
    lan=$(lan_dev); wan=$(wan_dev)
    [ -n "$wan" ] || { echo "wan_device_unknown" > "$RUN_DIR/speed.err"; return 1; }
    client_addrs_global > "$addrs"
    sig=$({ cat "$desired"; echo "$lan $wan"; awk 'NR == FNR { m[$1] = 1; next } ($1 in m)' "$desired" "$addrs"; } | md5sum | cut -d' ' -f1)
    old=$(cat "$RUN_DIR/speed.sig" 2>/dev/null)
    { our_root "$lan" && our_root "$wan" && qos_live; } || live=0
    if [ "$be" = ipt ] && [ "$live" = 1 ]; then ipt_ensure_jump "$QOS_CHAIN"; fi
    [ "$sig" = "$old" ] && [ "$live" = 1 ] && return 0
    for d in "$lan" "$wan"; do
        qdisc_usable "$d" || { echo "conflicting_qdisc:$d" > "$RUN_DIR/speed.err"; return 1; }
    done
    case "$be" in
        nft) qos_apply_nft "$desired" "$addrs" ;;
        ipt) qos_apply_ipt "$desired" "$addrs" ;;
    esac || { echo "marking_failed:$(tr '\n' ' ' < "$RUN_DIR/qos.apply.err")" > "$RUN_DIR/speed.err"; return 1; }
    shape_dev "$lan" 2 "$desired" || { echo "tc_failed:$lan" > "$RUN_DIR/speed.err"; return 1; }
    shape_dev "$wan" 3 "$desired" || { echo "tc_failed:$wan" > "$RUN_DIR/speed.err"; return 1; }
    echo "$sig" > "$RUN_DIR/speed.sig"
    log "speed limits applied ($(wc -l < "$desired") devices)"
    return 0
}

speed_status() {
    local mac f line d="" u="" src="none" active=false applied=false reason="" cls lan hex at=""
    mac=$(norm_mac "$1")
    is_mac "$mac" || { fail "$RC_BAD" "invalid_mac"; return; }
    qos_reconcile >/dev/null 2>&1
    line=$(grep "^$mac " "$RUN_DIR/speed.desired" 2>/dev/null | head -n 1)
    f="$SPEED_DIR/$(mac_key "$mac")"
    if [ -n "$line" ]; then
        set -- $line; d=$2; u=$3; cls=$4; src=$5; active=true
        lan=$(lan_dev); hex=$(printf '%x' "$cls")
        tc class show dev "$lan" 2>/dev/null | grep -q "class htb 1:$hex " && applied=true
        [ -s "$RUN_DIR/speed.err" ] && reason=$(cat "$RUN_DIR/speed.err")
        [ "$src" = manual ] && at=$(sed -n 's/^at=//p' "$f")
    elif [ -f "$f" ]; then
        src=manual; d=$(sed -n 's/^down=//p' "$f"); u=$(sed -n 's/^up=//p' "$f"); at=$(sed -n 's/^at=//p' "$f")
        reason=$(cat "$RUN_DIR/speed.err" 2>/dev/null)
    fi
    [ "$d" = 0 ] && d=""; [ "$u" = 0 ] && u=""
    [ -n "$at" ] && at=$((at * 1000))
    printf '{"mac":"%s","source":"%s","downloadKbps":%s,"uploadKbps":%s,"active":%s,"applied":%s,"reason":%s,"appliedAt":%s}' \
        "$mac" "$src" "$(jint "$d")" "$(jint "$u")" "$active" "$applied" "$(jstr_n "$reason")" "$(jint "$at")"
}
