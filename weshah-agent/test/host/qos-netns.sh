#!/bin/bash
# Kernel-level test of the agent's speed limiting and traffic accounting.
#
# Real Linux kernel, real iproute2 `tc`, real nftables and iptables, three network
# namespaces (client C -- router R -- wan W) carrying real TCP traffic. The agent's own
# qos.sh / acct.sh / lib.sh run unmodified under BusyBox ash with the BusyBox applets
# the router uses (awk, sed, grep, md5sum, ...). Only uci/ubus lookups are stubbed.
#
# What this proves: the generated rule sets are accepted by the kernel, marks classify
# the right packets, htb limits measured throughput in both directions, counters count,
# the agent self-heals after a firewall flush and refuses to replace a foreign shaper.
# What it does NOT prove: behaviour of OpenWrt's fw3/fw4 around it, or any router hardware.
#
# usage: sudo qos-netns.sh            exit 0 = all pass, 77 = environment cannot run it

set -u
HERE=$(cd "$(dirname "$0")" && pwd)
LIB="$HERE/../../files/usr/lib/weshah-agent"
BB=$(command -v busybox) || { echo "busybox missing"; exit 77; }
# BusyBox sh prefers its own applets over $PATH; the kernel tools must be the real iproute2 ones.
TC_BIN=$(command -v tc); IP_BIN=$(command -v ip)
[ "$(id -u)" = 0 ] || { echo "needs root"; exit 77; }
command -v tc >/dev/null && command -v ip >/dev/null || { echo "iproute2 missing"; exit 77; }
ip netns add wq_probe 2>/dev/null || { echo "network namespaces unavailable"; exit 77; }
ip netns del wq_probe

PASS=0; FAIL=0
ok() { local n="$1"; shift; if "$@" >/dev/null 2>&1; then PASS=$((PASS + 1)); echo "PASS  $n"; else FAIL=$((FAIL + 1)); echo "FAIL  $n"; fi; }

WORK=$(mktemp -d /tmp/weshah-l1.XXXXXX)
APPL="$WORK/bin"; mkdir -p "$APPL"
for a in $("$BB" --list); do
    case "$a" in ip|tc|ifconfig|route|ping|ping6|nslookup|arp|brctl|sh|ash) continue ;; esac
    ln -sf "$BB" "$APPL/$a"
done
ln -sf "$BB" "$APPL/sh"

cleanup() { for n in wq_C wq_R wq_W; do ip netns del "$n" 2>/dev/null; done; rm -rf "$WORK"; }
trap cleanup EXIT

# topology: C(vc0 10.9.0.2) <-> R(lan0 10.9.0.1 | wan0 10.8.0.1) <-> W(vw0 10.8.0.2)
for n in wq_C wq_R wq_W; do ip netns add $n; done
ip link add vc0 type veth peer name lan0; ip link set vc0 netns wq_C; ip link set lan0 netns wq_R
ip link add vw0 type veth peer name wan0; ip link set vw0 netns wq_W; ip link set wan0 netns wq_R
ip netns exec wq_C ip link set vc0 address aa:bb:cc:00:00:01
ip netns exec wq_C sh -c 'ip addr add 10.9.0.2/24 dev vc0; ip link set vc0 up; ip link set lo up; ip route add default via 10.9.0.1'
ip netns exec wq_W sh -c 'ip addr add 10.8.0.2/24 dev vw0; ip link set vw0 up; ip link set lo up; ip route add 10.9.0.0/24 via 10.8.0.1'
ip netns exec wq_R sh -c 'ip addr add 10.9.0.1/24 dev lan0; ip addr add 10.8.0.1/24 dev wan0; ip link set lan0 up; ip link set wan0 up; ip link set lo up; sysctl -qw net.ipv4.ip_forward=1'
ip netns exec wq_C "$BB" ping -c1 -W2 10.8.0.2 >/dev/null 2>&1 || { echo "topology does not forward"; exit 77; }

MAC=aa:bb:cc:00:00:01

# run an agent snippet inside the router namespace under BusyBox ash
agent() {
    ip netns exec wq_R env PATH="$APPL:/usr/sbin:/usr/bin:/sbin:/bin" WESHAH_STATE_DIR="$WORK/state" WESHAH_RUN_DIR="$WORK/run" \
        WESHAH_FW_BACKEND="$BACKEND" WESHAH_LEASES="$WORK/none" LIB="$LIB" TC_BIN="$TC_BIN" IP_BIN="$IP_BIN" "$BB" sh -c '
        tc() { "$TC_BIN" "$@"; }
        ip() { "$IP_BIN" "$@"; }
        . "$LIB/lib.sh"; . "$LIB/enforce.sh"; . "$LIB/acct.sh"; . "$LIB/qos.sh"
        uci() { return 1; }
        lan_dev() { echo lan0; }
        wan_dev() { echo wan0; }
        '"$1"
}

# leaves the seconds to deliver N KiB in $XFER_T: up = client sends to W, down = client fetches from W.
# xfer.py confirms every byte arrived (BADXFER otherwise), so a broken transfer can never look "fast".
XFER="$HERE/xfer.py"; XPORT=0
xfer() { # <up|down> <KiB>
    local port=$((5100 + (XPORT += 1))) n=$(($2 * 1024))
    if [ "$1" = up ]; then
        ip netns exec wq_W python3 -I "$XFER" sink $port $n > /dev/null 2>&1 &
        sleep 0.6
        XFER_T=$(timeout 60 ip netns exec wq_C python3 -I "$XFER" send 10.8.0.2 $port $n 2>/dev/null)
    else
        ip netns exec wq_W python3 -I "$XFER" source $port $n > /dev/null 2>&1 &
        sleep 0.6
        XFER_T=$(timeout 60 ip netns exec wq_C python3 -I "$XFER" fetch 10.8.0.2 $port $n 2>/dev/null)
    fi
    wait 2>/dev/null
    [ -n "$XFER_T" ] || XFER_T=BADXFER
}
between() { case "$1" in BADXFER*) return 1 ;; esac; awk -v v="$1" -v lo="$2" -v hi="$3" 'BEGIN { exit (v >= lo && v <= hi) ? 0 : 1 }'; }

for BACKEND in nft ipt; do
    echo; echo "=== backend: $BACKEND"
    rm -rf "$WORK/state" "$WORK/run"; mkdir -p "$WORK/state" "$WORK/run"
    ip netns exec wq_R sh -c 'tc qdisc del dev lan0 root 2>/dev/null; tc qdisc del dev wan0 root 2>/dev/null
        nft delete table inet weshah_qos 2>/dev/null; nft delete table inet weshah_acct 2>/dev/null
        for c in WESHAH_QOS WESHAH_ACCT; do iptables -t mangle -D FORWARD -j $c 2>/dev/null; iptables -t mangle -F $c 2>/dev/null; iptables -t mangle -X $c 2>/dev/null; done; true'
    ip netns exec wq_R "$BB" ping -c1 -W2 10.9.0.2 >/dev/null 2>&1

    ok "capability check passes on this kernel" agent 'qos_supported'
    xfer up 512; T_UP0=$XFER_T; xfer down 512; T_DOWN0=$XFER_T
    echo "      unlimited: up ${T_UP0}s, down ${T_DOWN0}s (512 KiB)"
    ok "unlimited transfer is fast (baseline)" between "$T_UP0" 0 1.5

    agent "reconcile_all; set_speed $MAC 2048 1024" > "$WORK/set.out" 2>&1
    ok "set_speed accepted: $(cat "$WORK/set.out")" grep -q '"success":true' "$WORK/set.out"
    ok "htb root installed on LAN (download) and WAN (upload)" ip netns exec wq_R sh -c 'tc qdisc show dev lan0 | grep -q "htb 1: root" && tc qdisc show dev wan0 | grep -q "htb 1: root"'
    ok "per-device class carries the requested rate (download 2048kbit)" ip netns exec wq_R sh -c 'tc class show dev lan0 | grep -q "class htb 1:1 .*rate 2048Kbit"'
    ok "per-device class carries the requested rate (upload 1024kbit)" ip netns exec wq_R sh -c 'tc class show dev wan0 | grep -q "class htb 1:1 .*rate 1024Kbit"'
    ok "u32 filters steer marked packets to the device classes (both interfaces)" ip netns exec wq_R sh -c 'tc filter show dev wan0 | grep -q "flowid 1:1" && tc filter show dev lan0 | grep -q "flowid 1:1"'
    if [ "$BACKEND" = nft ]; then
        ok "nft mark rules are loaded (MAC for upload, IP for download)" ip netns exec wq_R sh -c 'nft list table inet weshah_qos | grep -q "ether saddr aa:bb:cc:00:00:01" && nft list table inet weshah_qos | grep -q "ip daddr 10.9.0.2"'
    else
        ok "iptables mark chain loaded and hooked into FORWARD" ip netns exec wq_R sh -c 'iptables -t mangle -S WESHAH_QOS | grep -q -- "--mac-source AA:BB:CC:00:00:01\|--mac-source aa:bb:cc:00:00:01" && iptables -t mangle -C FORWARD -j WESHAH_QOS'
    fi

    xfer up 512; T_UP=$XFER_T; xfer down 512; T_DOWN=$XFER_T
    echo "      limited : up ${T_UP}s (expect ~4.1), down ${T_DOWN}s (expect ~2.1)"
    ok "UPLOAD really limited to ~1 Mbit/s (measured)" between "$T_UP" 3.0 6.5
    ok "DOWNLOAD really limited to ~2 Mbit/s (measured)" between "$T_DOWN" 1.4 3.6

    # counters: ~512 KiB each way were moved twice above (unlimited + limited)
    agent 'acct_reconcile; acct_totals_file /tmp/wq.tot; cat /tmp/wq.tot' > "$WORK/tot.out" 2>&1
    TOT=$(grep aabbcc000001 "$WORK/tot.out" | head -1)
    echo "      counters: $TOT"
    D=$(echo "$TOT" | awk '{print $2}'); U=$(echo "$TOT" | awk '{print $3}')
    ok "download bytes counted (>= 400 KiB seen after limits applied)" test "${D:-0}" -ge 409600
    ok "upload bytes counted (>= 400 KiB seen after limits applied)" test "${U:-0}" -ge 409600

    # self-heal: the firewall layer wipes our hooks (fw3/fw4 reload) -> next reconcile rebuilds them
    if [ "$BACKEND" = nft ]; then ip netns exec wq_R nft delete table inet weshah_qos; else ip netns exec wq_R iptables -t mangle -F FORWARD; fi
    agent 'reconcile_all' >/dev/null 2>&1
    xfer up 384; T_UP2=$XFER_T
    echo "      after flush + reconcile: up 384 KiB in ${T_UP2}s (expect ~3.1)"
    ok "limit enforced again after the firewall layer wiped the hooks" between "$T_UP2" 2.2 5.0
    BEFORE=$(echo "$TOT" | awk '{print $2}')
    agent 'acct_totals_file /tmp/wq.tot; cat /tmp/wq.tot' > "$WORK/tot2.out" 2>&1
    AFTER=$(grep aabbcc000001 "$WORK/tot2.out" | awk '{print $2}')
    ok "totals never go backwards across a rebuild" test "${AFTER:-0}" -ge "${BEFORE:-1}"

    # foreign shaper must be left alone
    agent 'remove_speed '"$MAC"' >/dev/null'
    ip netns exec wq_R tc qdisc add dev wan0 root tbf rate 500mbit burst 64k latency 50ms
    agent 'set_speed '"$MAC"' 2048 1024' > "$WORK/foreign.out" 2>&1
    ok "refuses to replace a foreign root qdisc" grep -q 'conflicting_qdisc:wan0' "$WORK/foreign.out"
    ok "foreign tbf qdisc untouched" ip netns exec wq_R sh -c 'tc qdisc show dev wan0 | grep -q "qdisc tbf"'
    ip netns exec wq_R tc qdisc del dev wan0 root
    agent 'remove_speed '"$MAC"' >/dev/null'

    # full teardown
    agent 'remove_speed '"$MAC" > "$WORK/rm.out" 2>&1
    ok "remove_speed tears everything down (no htb, no marks)" ip netns exec wq_R sh -c '! tc qdisc show dev lan0 | grep -q htb && ! tc qdisc show dev wan0 | grep -q htb && ! nft list table inet weshah_qos && ! iptables -t mangle -nL WESHAH_QOS'
    xfer up 512; T_UP3=$XFER_T
    ok "throughput back to unlimited after removal" between "$T_UP3" 0 1.5

    # schedule-carried limit: applies only while the device is inside its allowed window
    mkdir -p "$WORK/state/schedules"
    printf 'R all 0 1440\nD 2048\nU 1024\n' > "$WORK/state/schedules/aa_bb_cc_00_00_01.rules"
    agent 'reconcile_all; cat "$RUN_DIR/speed.desired"' > "$WORK/sd.out" 2>&1
    ok "schedule limit active inside the allowed window" grep -q "^$MAC 2048 1024 1 schedule" "$WORK/sd.out"
    ok "...and shaped in the kernel" ip netns exec wq_R sh -c 'tc class show dev lan0 | grep -q "rate 2048Kbit"'
    printf 'R all 0 1\nD 2048\nU 1024\n' > "$WORK/state/schedules/aa_bb_cc_00_00_01.rules"
    agent 'reconcile_all' >/dev/null 2>&1
    ok "schedule limit removed outside the window" ip netns exec wq_R sh -c '! tc qdisc show dev lan0 | grep -q htb'
    rm -f "$WORK/state/schedules/"*.rules
    agent 'reconcile_all' >/dev/null 2>&1
done

echo; echo "RESULT: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
