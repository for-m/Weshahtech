#!/bin/sh
# shellcheck shell=dash disable=SC3043
# WESHAH agent — client address discovery and real per-client traffic counters.
#
# Counters live in firewall chains owned by the agent (nft table inet weshah_acct,
# or iptables chain WESHAH_ACCT in mangle). Direction convention (matches the app):
#   down = bytes delivered TO the device, up = bytes sent BY the device.
# Totals are "since agent start": counters are folded into $RUN_DIR/acct.base.*
# before every rebuild so they never go backwards.

ACCT_TABLE="weshah_acct"
ACCT_CHAIN="WESHAH_ACCT"
ACCT_MARK_MASK="0x00ff0000"

# prints "<mac> <addr>" for every neighbour / lease address of LAN clients
client_addrs() {
    local lan
    lan=$(lan_dev)
    [ -r "$ARP_FILE" ] && awk -v dev="$lan" 'NR > 1 && $6 == dev && $3 != "0x0" { print tolower($4), $1 }' "$ARP_FILE"
    ip -6 neigh show dev "$lan" 2>/dev/null \
        | awk '{ for (i = 1; i <= NF; i++) if ($i == "lladdr") print tolower($(i + 1)), $1 }'
    [ -f "$LEASES_FILE" ] && awk '{ print tolower($2), $3 }' "$LEASES_FILE"
}

# global-scope addresses only; sorted unique
client_addrs_global() {
    client_addrs | awk '$1 ~ /^([0-9a-f][0-9a-f]:){5}[0-9a-f][0-9a-f]$/ && $2 !~ /^fe80:/ && $2 !~ /^ff/ { print }' | sort -u
}

# ── backend: nftables ─────────────────────────────────────────────────────────

acct_dump_nft() { # prints "<mackey> <u|d> <bytes>"
    nft list chain inet "$ACCT_TABLE" acct 2>/dev/null | awk '
        /counter packets/ {
            b = 0; c = ""
            for (i = 1; i <= NF; i++) { if ($i == "bytes") b = $(i + 1); if ($i == "comment") c = $(i + 1) }
            gsub(/"/, "", c); n = split(c, p, "-")
            if (n >= 2) sum[p[2] " " p[1]] += b
        }
        END { for (k in sum) print k, sum[k] }'
}

acct_live_nft() { nft list table inet "$ACCT_TABLE" >/dev/null 2>&1; }

acct_apply_nft() { # <addr file>
    {
        printf 'table inet %s\ndelete table inet %s\ntable inet %s {\n  chain acct {\n    type filter hook forward priority -152; policy accept;\n' \
            "$ACCT_TABLE" "$ACCT_TABLE" "$ACCT_TABLE"
        awk '
            { key = $1; gsub(/:/, "", key)
              if (!seen[$1]++) printf "    ether saddr %s counter comment \"u-%s\"\n", $1, key
              fam = ($2 ~ /:/) ? "ip6" : "ip"
              printf "    %s daddr %s counter comment \"d-%s\"\n", fam, $2, key }' "$1"
        printf '  }\n}\n'
    } | nft -f - 2>/dev/null
}

# ── backend: iptables (fw3, OpenWrt <= 21.02 incl. 18.06) ─────────────────────

acct_dump_ipt() {
    { iptables -t mangle -nvxL "$ACCT_CHAIN" 2>/dev/null; ip6tables -t mangle -nvxL "$ACCT_CHAIN" 2>/dev/null; } | awk '
        /\/\* [ud]-/ {
            c = ""
            for (i = 1; i <= NF; i++) if ($i ~ /^[ud]-[0-9a-f]+$/) c = $i
            if (c == "") next
            n = split(c, p, "-"); sum[p[2] " " p[1]] += $2
        }
        END { for (k in sum) print k, sum[k] }'
}

acct_live_ipt() { iptables -t mangle -nL "$ACCT_CHAIN" >/dev/null 2>&1; }

# Re-installs the jump from FORWARD; fw3 flushes mangle FORWARD on every reload.
ipt_ensure_jump() { # <chain>
    iptables -t mangle -C FORWARD -j "$1" 2>/dev/null || iptables -t mangle -I FORWARD 1 -j "$1"
    if have_cmd ip6tables; then
        ip6tables -t mangle -C FORWARD -j "$1" 2>/dev/null || ip6tables -t mangle -I FORWARD 1 -j "$1" 2>/dev/null
    fi
}

acct_apply_ipt() { # <addr file>
    local v4="$RUN_DIR/acct.ipt4" v6="$RUN_DIR/acct.ipt6"
    for f in "$v4" "$v6"; do
        printf '*mangle\n:%s - [0:0]\n-F %s\n' "$ACCT_CHAIN" "$ACCT_CHAIN" > "$f"
    done
    awk -v v4="$v4" -v v6="$v6" -v chain="$ACCT_CHAIN" '
        { key = $1; gsub(/:/, "", key)
          if (!seen[$1]++) {
              printf "-A %s -m mac --mac-source %s -m comment --comment u-%s\n", chain, $1, key >> v4
              printf "-A %s -m mac --mac-source %s -m comment --comment u-%s\n", chain, $1, key >> v6
          }
          out = ($2 ~ /:/) ? v6 : v4
          printf "-A %s -d %s -m comment --comment d-%s\n", chain, $2, key >> out }' "$1"
    echo COMMIT >> "$v4"; echo COMMIT >> "$v6"
    iptables-restore --noflush < "$v4" || return 1
    if have_cmd ip6tables-restore; then ip6tables-restore --noflush < "$v6" 2>/dev/null; fi
    ipt_ensure_jump "$ACCT_CHAIN"
}

# ── backend dispatch ──────────────────────────────────────────────────────────

acct_dump() { case "$(fw_backend)" in nft) acct_dump_nft ;; ipt) acct_dump_ipt ;; esac; }

acct_fold() { # persist live counters into base files before a rebuild
    local key dir bytes f cur_d cur_u
    acct_dump > "$RUN_DIR/acct.fold"
    while read -r key dir bytes; do
        f="$RUN_DIR/acct.base.$key"; cur_d=0; cur_u=0
        [ -f "$f" ] && read -r cur_d cur_u < "$f"
        if [ "$dir" = d ]; then cur_d=$((cur_d + bytes)); else cur_u=$((cur_u + bytes)); fi
        echo "$cur_d $cur_u" > "$f"
    done < "$RUN_DIR/acct.fold"
}

acct_reconcile() {
    local be addrs="$RUN_DIR/acct.addrs" sig old live=0
    be=$(fw_backend)
    [ "$be" = none ] && return 0
    client_addrs_global > "$addrs"
    sig=$(md5sum < "$addrs" | cut -d' ' -f1)
    old=$(cat "$RUN_DIR/acct.sig" 2>/dev/null)
    case "$be" in nft) acct_live_nft && live=1 ;; ipt) acct_live_ipt && live=1 ;; esac
    if [ "$be" = ipt ] && [ "$live" = 1 ]; then ipt_ensure_jump "$ACCT_CHAIN"; fi
    [ "$sig" = "$old" ] && [ "$live" = 1 ] && return 0
    [ "$live" = 1 ] && acct_fold
    case "$be" in
        nft) acct_apply_nft "$addrs" ;;
        ipt) acct_apply_ipt "$addrs" ;;
    esac && echo "$sig" > "$RUN_DIR/acct.sig"
}

# Writes "<mackey> <down> <up>" (bytes since agent start) for every known client.
acct_totals_file() { # <out file>
    local key dir bytes
    acct_dump > "$RUN_DIR/acct.live"
    {
        for f in "$RUN_DIR"/acct.base.*; do
            [ -f "$f" ] || continue
            key=${f##*/acct.base.}
            read -r d u < "$f"
            echo "$key d $d"; echo "$key u $u"
        done
        cat "$RUN_DIR/acct.live"
    } | awk '{ s[$1 " " $2] += $3; k[$1] = 1 } END { for (m in k) print m, s[m " d"] + 0, s[m " u"] + 0 }' > "$1"
}
