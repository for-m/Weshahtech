#!/bin/sh
# shellcheck shell=dash disable=SC3043
# WESHAH agent — enforcement: block, schedule and the reconcile loop.
#
# Persistent state (flash, survives reboot):
#   firewall UCI rules  weshah_block_<mac>  manual block
#                       weshah_sched_<mac>  schedule block (managed by reconcile)
#   $STATE_DIR/blocks/<mac>            expires=<epoch s, 0 = never>
#   $STATE_DIR/schedules/<mac>.rules   normalized schedule (R/D/U lines)
# Everything volatile (tc, iptables/nft marks and counters) is rebuilt by
# reconcile_all at boot and every tick, so a firewall/network reload self-heals.

BLOCK_DIR="$STATE_DIR/blocks"
SCHED_DIR="$STATE_DIR/schedules"
FW_CHANGED=0

# ── block ─────────────────────────────────────────────────────────────────────

# Accepts epoch seconds or milliseconds; prints epoch seconds (0 = permanent).
# rc 1 = malformed, rc 2 = already in the past.
expiry_to_s() {
    local v="$1" now
    case "$v" in ''|null|0) printf 0; return 0 ;; esac
    case "$v" in *[!0-9]*) return 1 ;; esac
    [ "${#v}" -le 13 ] || return 1
    [ "${#v}" -ge 12 ] && v=$((v / 1000))
    now=$(epoch_now)
    [ "$v" -gt "$now" ] || return 2
    printf '%s' "$v"
}

fw_add_block_rule() { # <section> <mac>
    uci -q delete "firewall.$1"
    uci set "firewall.$1=rule" || return 1
    uci set "firewall.$1.name=$1"
    uci set "firewall.$1.src=lan"
    uci set "firewall.$1.dest=wan"
    uci set "firewall.$1.src_mac=$2"
    uci set "firewall.$1.target=REJECT"
}

block_device() { # <mac> [expiresAt]
    local mac exp sec rc
    mac=$(norm_mac "$1")
    is_mac "$mac" || { fail "$RC_BAD" "invalid_mac"; return; }
    exp=$(expiry_to_s "$2"); rc=$?
    [ "$rc" = 1 ] && { fail "$RC_BAD" "invalid_expiry"; return; }
    [ "$rc" = 2 ] && { fail "$RC_BAD" "expiry_in_past"; return; }
    sec=$(block_section "$mac")
    fw_add_block_rule "$sec" "$mac" || { fail "$RC_FAIL" "uci_failed"; return; }
    uci commit firewall || { fail "$RC_FAIL" "uci_commit_failed"; return; }
    mkdir -p "$BLOCK_DIR"
    printf 'expires=%s\ncreated=%s\n' "$exp" "$(epoch_now)" > "$BLOCK_DIR/$(mac_key "$mac")"
    fw_reload_bg
    log "blocked $mac expires=$exp"
    ok_json
}

unblock_device() {
    local mac sec note=""
    mac=$(norm_mac "$1")
    is_mac "$mac" || { fail "$RC_BAD" "invalid_mac"; return; }
    sec=$(block_section "$mac")
    if [ -n "$(uci -q get "firewall.$sec")" ]; then
        uci -q delete "firewall.$sec"
        uci commit firewall || { fail "$RC_FAIL" "uci_commit_failed"; return; }
        fw_reload_bg
    fi
    rm -f "$BLOCK_DIR/$(mac_key "$mac")"
    [ -n "$(uci -q get "firewall.$(sched_section "$mac")")" ] && note="still_blocked_by_schedule"
    log "unblocked $mac"
    printf '{"success":true,"error":null,"note":%s}' "$(jstr_n "$note")"
}

block_status() {
    local mac sec ssec manual=0 sched=0 exp="" rule="" blocked=false
    mac=$(norm_mac "$1")
    is_mac "$mac" || { fail "$RC_BAD" "invalid_mac"; return; }
    sec=$(block_section "$mac"); ssec=$(sched_section "$mac")
    [ -n "$(uci -q get "firewall.$sec")" ] && { manual=1; rule="$sec"; }
    [ -n "$(uci -q get "firewall.$ssec")" ] && { sched=1; [ -z "$rule" ] && rule="$ssec"; }
    [ "$manual" = 1 ] && exp=$(sed -n 's/^expires=//p' "$BLOCK_DIR/$(mac_key "$mac")" 2>/dev/null)
    [ "$manual$sched" != "00" ] && blocked=true
    if [ -n "$exp" ] && [ "$exp" -gt 0 ] 2>/dev/null; then exp=$((exp * 1000)); else exp=""; fi
    printf '{"mac":"%s","isBlocked":%s,"manual":%s,"scheduled":%s,"expiresAt":%s,"ruleId":%s}' \
        "$mac" "$blocked" "$(jbool $manual)" "$(jbool $sched)" "$(jint "$exp")" "$(jstr_n "$rule")"
}

expire_blocks() {
    local f key mac exp now
    clock_synced || return 0
    now=$(epoch_now)
    for f in "$BLOCK_DIR"/*; do
        [ -e "$f" ] || continue
        key=${f##*/}; mac=$(printf '%s' "$key" | tr '_' ':')
        exp=$(sed -n 's/^expires=//p' "$f")
        { [ -n "$exp" ] && [ "$exp" -gt 0 ] && [ "$exp" -le "$now" ]; } 2>/dev/null || continue
        uci -q delete "firewall.$(block_section "$mac")"
        rm -f "$f"
        FW_CHANGED=1
        log "block expired $mac"
    done
}

# ── schedule ──────────────────────────────────────────────────────────────────
# timeRanges are the windows in which the device IS allowed online; outside all
# of them the device is blocked from WAN (rule weshah_sched_<mac>).
# Times are router-local. End is exclusive, except 23:59 which means end of day.
# start == end means the whole day. start > end wraps midnight and belongs to
# the day it started on.

day_in() { # <days: "all"|digits> <dow>
    case "$1" in all) return 0 ;; *"$2"*) return 0 ;; esac
    return 1
}

# prints 1 when allowed, 0 when blocked
sched_allowed() { # <rules file> <dow 1=Mon..7=Sun> <minute-of-day>
    local dow="$2" min="$3" prev tag days s e allowed=0
    prev=$(( (dow + 5) % 7 + 1 ))
    while read -r tag days s e; do
        [ "$tag" = "R" ] || continue
        if [ "$s" -eq "$e" ]; then
            day_in "$days" "$dow" && allowed=1
        elif [ "$s" -lt "$e" ]; then
            day_in "$days" "$dow" && [ "$min" -ge "$s" ] && [ "$min" -lt "$e" ] && allowed=1
        else
            day_in "$days" "$dow" && [ "$min" -ge "$s" ] && allowed=1
            day_in "$days" "$prev" && [ "$min" -lt "$e" ] && allowed=1
        fi
    done < "$1"
    printf '%s' "$allowed"
}

sched_now_args() { # prints "<dow> <minute-of-day>"
    local c hh mm dow
    c=$(clock_now)
    dow=$(printf '%s' "$c" | awk '{print $2}')
    hh=$(dec "$(printf '%s' "$c" | awk '{print $3}')")
    mm=$(dec "$(printf '%s' "$c" | awk '{print $4}')")
    printf '%s %s' "$dow" "$((hh * 60 + mm))"
}

# Validates the request body and writes a normalized rules file; prints an
# error token and returns 1 when invalid.
parse_schedule() { # <body> <outfile>
    local body="$1" out="$2" list="$RUN_DIR/sched-parse.$$" n el v sh sm eh em days d s e dlist key tag all
    json_list "$body" '@.timeRanges[*]' > "$list"
    n=$(wc -l < "$list")
    if [ "$n" -lt 1 ] || [ "$n" -gt 14 ]; then rm -f "$list"; echo "time_ranges_required_1_to_14"; return 1; fi
    : > "$out"
    while IFS= read -r el; do
        sh=$(json_get "$el" '@.startHour'); sm=$(json_get "$el" '@.startMinute')
        eh=$(json_get "$el" '@.endHour');   em=$(json_get "$el" '@.endMinute')
        for v in "$sh" "$sm" "$eh" "$em"; do
            is_uint "$v" || { rm -f "$list" "$out"; echo "invalid_time_range"; return 1; }
        done
        sh=$(dec "$sh"); sm=$(dec "$sm"); eh=$(dec "$eh"); em=$(dec "$em")
        if [ "$sh" -gt 23 ] || [ "$eh" -gt 23 ] || [ "$sm" -gt 59 ] || [ "$em" -gt 59 ]; then
            rm -f "$list" "$out"; echo "time_out_of_range"; return 1
        fi
        days=""
        dlist=$(json_list "$el" '@.daysOfWeek[*]')
        for d in $dlist; do
            case "$d" in [1-7]) ;; *) rm -f "$list" "$out"; echo "invalid_day_of_week"; return 1 ;; esac
            case "$days" in *"$d"*) ;; *) days="$days$d" ;; esac
        done
        [ -z "$days" ] && days="all"
        all=1
        for d in 1 2 3 4 5 6 7; do case "$days" in *"$d"*) ;; *) all=0 ;; esac; done
        [ "$all" = 1 ] && days="all"
        s=$((sh * 60 + sm)); e=$((eh * 60 + em))
        [ "$eh" -eq 23 ] && [ "$em" -eq 59 ] && e=1440
        echo "R $days $s $e" >> "$out"
    done < "$list"
    rm -f "$list"
    for key in downloadKbps uploadKbps; do
        d=$(json_get "$body" "@.$key")
        [ -z "$d" ] && continue
        { is_uint "$d" && [ "$d" -le 1000000 ]; } || { rm -f "$out"; echo "invalid_$key"; return 1; }
        d=$(dec "$d")
        if [ "$key" = downloadKbps ]; then tag=D; else tag=U; fi
        [ "$d" -gt 0 ] && echo "$tag $d" >> "$out"
    done
    return 0
}

set_schedule() { # <mac> <json body>
    local mac tmp err
    mac=$(norm_mac "$1")
    is_mac "$mac" || { fail "$RC_BAD" "invalid_mac"; return; }
    [ -n "$2" ] || { fail "$RC_BAD" "empty_body"; return; }
    mkdir -p "$SCHED_DIR"
    tmp="$SCHED_DIR/.$(mac_key "$mac").$$"
    err=$(parse_schedule "$2" "$tmp") || { rm -f "$tmp"; fail "$RC_BAD" "$err"; return; }
    mv "$tmp" "$SCHED_DIR/$(mac_key "$mac").rules"
    log "schedule set $mac"
    reconcile_all
    ok_json
}

remove_schedule() {
    local mac sec
    mac=$(norm_mac "$1")
    is_mac "$mac" || { fail "$RC_BAD" "invalid_mac"; return; }
    rm -f "$SCHED_DIR/$(mac_key "$mac").rules"
    sec=$(sched_section "$mac")
    if [ -n "$(uci -q get "firewall.$sec")" ]; then
        uci -q delete "firewall.$sec"
        FW_CHANGED=1
    fi
    log "schedule removed $mac"
    reconcile_all
    ok_json
}

schedule_status() {
    local mac f dow min allowed="null" synced=false first=1 tag a b c days d sep dl ul
    mac=$(norm_mac "$1")
    is_mac "$mac" || { fail "$RC_BAD" "invalid_mac"; return; }
    f="$SCHED_DIR/$(mac_key "$mac").rules"
    [ -f "$f" ] || { fail "$RC_NOTFOUND" "no_schedule"; return; }
    if clock_synced; then
        synced=true
        set -- $(sched_now_args); dow=$1; min=$2
        if [ "$(sched_allowed "$f" "$dow" "$min")" = 1 ]; then allowed=true; else allowed=false; fi
    fi
    printf '{"mac":"%s","timeRanges":[' "$mac"
    while read -r tag a b c; do
        [ "$tag" = "R" ] || continue
        [ "$first" = 1 ] || printf ','
        first=0
        if [ "$a" = "all" ]; then days="[]"; else
            days="["; sep=""
            for d in $(printf '%s' "$a" | sed 's/./& /g'); do days="$days$sep$d"; sep=","; done
            days="$days]"
        fi
        printf '{"startHour":%s,"startMinute":%s,"endHour":%s,"endMinute":%s,"daysOfWeek":%s}' \
            $((b / 60)) $((b % 60)) $(( (c % 1440) / 60 )) $(( (c % 1440) % 60 )) "$days"
    done < "$f"
    dl=$(sed -n 's/^D //p' "$f" | head -n 1); ul=$(sed -n 's/^U //p' "$f" | head -n 1)
    printf '],"downloadKbps":%s,"uploadKbps":%s,"allowedNow":%s,"clockSynced":%s}' \
        "$(jint "$dl")" "$(jint "$ul")" "$allowed" "$synced"
}

reconcile_schedules() {
    local f key mac dow min allowed sec exists orphan
    clock_synced || return 0
    set -- $(sched_now_args); dow=$1; min=$2
    for f in "$SCHED_DIR"/*.rules; do
        [ -e "$f" ] || continue
        key=${f##*/}; key=${key%.rules}; mac=$(printf '%s' "$key" | tr '_' ':')
        is_mac "$mac" || continue
        allowed=$(sched_allowed "$f" "$dow" "$min")
        sec=$(sched_section "$mac")
        exists=$(uci -q get "firewall.$sec")
        if [ "$allowed" = 0 ] && [ -z "$exists" ]; then
            fw_add_block_rule "$sec" "$mac" && FW_CHANGED=1 && log "schedule: blocking $mac"
        elif [ "$allowed" = 1 ] && [ -n "$exists" ]; then
            uci -q delete "firewall.$sec"; FW_CHANGED=1; log "schedule: unblocking $mac"
        fi
    done
    # rules whose schedule file no longer exists
    for orphan in $(uci -q show firewall | sed -n 's/^firewall\.\(weshah_sched_[0-9a-f_]*\)=rule$/\1/p'); do
        key=${orphan#weshah_sched_}
        [ -f "$SCHED_DIR/$key.rules" ] && continue
        uci -q delete "firewall.$orphan"; FW_CHANGED=1
    done
}

# ── reconcile everything (boot, every tick, after any change) ─────────────────

reconcile_all() {
    expire_blocks
    reconcile_schedules
    if [ "$FW_CHANGED" = 1 ]; then
        uci commit firewall && fw_reload_bg
        FW_CHANGED=0
    fi
    qos_reconcile
    acct_reconcile
    return 0
}
