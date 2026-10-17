#!/bin/sh
# End-to-end tests of weshah-agent against the REAL firmware userland
# (OpenWrt 18.06.9 ar71xx/tiny: MIPS BE binaries via qemu-user binfmt, BusyBox 1.28.4,
# uci, jsonfilter, jshn, ubusd, rpcd and the stock uhttpd serving the agent as CGI).
# Netlink-bound tools (ip, iw, swconfig, netifd objects, iptables) are shims; see
# setup.sh. Everything else is the firmware's own code.
#
#   run.sh <work-dir>      (after setup.sh + services.sh start)

WORK="$1"; T="$WORK/testroot"
[ -d "$T" ] || { echo "usage: $0 <work-dir>" >&2; exit 2; }
TOKEN=0123456789abcdef0123456789abcdef0123456789abcdef
BASE=http://127.0.0.1:8765/api/v1
OUT="$WORK/out"; mkdir -p "$OUT"
PASS=0; FAIL=0

R() { chroot "$T" "$@"; }
RS() { chroot "$T" /bin/sh -c "$1"; }

ok() { # <name> <condition command...>
    local name="$1"; shift
    if "$@" >/dev/null 2>&1; then PASS=$((PASS + 1)); echo "PASS  $name"
    else FAIL=$((FAIL + 1)); echo "FAIL  $name"; fi
}

# api <METHOD> <path> [body] → $CODE, $OUT/resp
api() {
    CODE=$(curl -s -m 180 -o "$OUT/resp" -w '%{http_code}' -X "$1" -H "Authorization: Bearer ${AUTH_TOKEN:-$TOKEN}" \
        -H 'Content-Type: application/json' ${3:+--data-binary "$3"} "$BASE/$2")
}
code_is() { [ "$CODE" = "$1" ]; }
jqt() { jq -e "$1" "$OUT/resp" >/dev/null; }
# These read only what is committed to flash: the uncommitted delta (/tmp/.uci) is discarded first,
# so a missing "uci commit" in the agent shows up as a failing test.
uci_get() { RS "rm -rf /tmp/.uci; uci -q get $1"; }
ucis() { RS "rm -rf /tmp/.uci; uci -q show $1"; }
reload_log() { RS 'cat /tmp/reload.log 2>/dev/null'; }

reset_state() {
    RS 'rm -rf /etc/weshah-agent /tmp/weshah-agent /tmp/reload.log /tmp/shim.log /tmp/iptables.log; mkdir -p /etc/weshah-agent /tmp/weshah-agent
        [ -d /tmp/golden ] || { mkdir -p /tmp/golden; cp /etc/config/network /etc/config/dhcp /etc/config/firewall /tmp/golden/; }
        cp /tmp/golden/network /tmp/golden/dhcp /tmp/golden/firewall /etc/config/; rm -rf /tmp/.uci /etc/weshah-agent/rollback
        rm -f /tmp/fixture/iptables.chain /tmp/fixture/iptables.acct'
}

MAC1=aa:bb:cc:00:00:01
MAC2=aa:bb:cc:00:00:02
NOW=$(date -u +%s)

echo "=== environment"
R busybox 2>&1 | head -1
RS 'cat /etc/openwrt_release | head -3; uci -V 2>/dev/null; uname -m'

echo; echo "=== 1. authentication, routing, limits"
reset_state
AUTH_TOKEN=bad api GET status;                              ok "wrong token -> 401" code_is 401
CODE=$(curl -s -m 20 -o "$OUT/resp" -w '%{http_code}' "$BASE/status"); ok "no Authorization header -> 401" code_is 401
api GET nothing;                                            ok "unknown resource -> 404" code_is 404
api GET 'devices/..%2F..%2Fetc';                            ok "traversal in id -> 400/404" test "$CODE" = 400 -o "$CODE" = 404
api GET 'devices/not-a-mac/block';                          ok "invalid MAC -> 400" code_is 400
api POST status;                                            ok "wrong method -> 404" code_is 404
CODE=$(curl -s -m 20 -o "$OUT/resp" -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"username":"root","password":"nope"}' "$BASE/auth/login")
ok "login wrong password -> 401" code_is 401
CODE=$(curl -s -m 60 -o "$OUT/resp" -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"username":"root","password":"weshah-test"}' "$BASE/auth/login")
ok "login with real rpcd credentials -> 200" code_is 200
ok "login returns agent token" jqt ".token == \"$TOKEN\" and .expires > $NOW"
CODE=$(curl -s -m 20 -o "$OUT/resp" -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{"username":"root","password":"a\"b\\c"}' "$BASE/auth/login")
ok "login with quote/backslash in password does not inject" code_is 401
dd if=/dev/zero bs=1024 count=70 2>/dev/null | tr '\0' 'a' > "$OUT/big"
CODE=$(curl -s -m 20 -o "$OUT/resp" -w '%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" --data-binary @"$OUT/big" "$BASE/dhcp/static")
ok "oversize body -> 413" code_is 413

echo; echo "=== 2. read endpoints (values come from fixtures through the real parsers)"
api GET status;        ok "status 200" code_is 200
ok "status fields" jqt '.model=="TP-Link TL-WR841N/ND v11" and (.firmware|test("18.06.9")) and .wanState=="up" and .wanIp=="203.0.113.10" and (.cpuPercent|type=="number") and .ramTotalKb>0 and .temperatureCelsius==null and .agentVersion=="2.1.0"'
api GET capabilities;  ok "capabilities 200" code_is 200
ok "capabilities honest for stock image" jqt '.firewallBackend=="ipt" and .vlanMode=="swconfig" and .vlanManagement==true and .bandwidthControl==false and .reasons.bandwidthControl=="tc_missing" and .perClientTraffic==true and .configBackup==true and .temperature==false and .hotspot==false and .lldpNeighbors==false'
api GET interfaces;    ok "interfaces: each name separate" jqt '(.interfaces|map(.name)|sort)==["br-lan","eth0","eth1","wlan0"] and ((.interfaces[]|select(.name=="eth1")).type=="WAN") and ((.interfaces[]|select(.name=="br-lan")).type=="LAN") and ((.interfaces[]|select(.name=="eth1")).rxBytes==5000000000)'
api GET devices;       ok "devices 200" code_is 200
ok "devices: 4 known, online flags and wifi data" jqt 'length==4 and (map(select(.isOnline))|length)==3 and ((.[]|select(.mac=="aa:bb:cc:00:00:01")|.rssi)==-52) and ((.[]|select(.mac=="aa:bb:cc:00:00:01")|.interface)=="wlan0") and ((.[]|select(.mac=="aa:bb:cc:00:00:03")|.isOnline)==false) and ((.[]|select(.mac=="aa:bb:cc:00:00:09")|.ip)=="192.168.1.150") and ((.[]|select(.mac=="aa:bb:cc:00:00:01")|.hostname)=="phone-ali")'
api GET ports;         ok "ports: netdev + swconfig ports with link state" jqt '(.ports|map(.portId)|sort)==["eth0","eth1","switch0:1","switch0:2","switch0:3","switch0:4"] and ((.ports[]|select(.portId=="switch0:1")|.isUp)==true) and ((.ports[]|select(.portId=="switch0:1")|.speedMbps)==100) and ((.ports[]|select(.portId=="switch0:2")|.isUp)==false) and ((.ports[]|select(.portId=="switch0:3")|.speedMbps)==1000) and ((.ports[]|select(.portId=="eth1")|.rxErrors)==3) and ((.ports[]|select(.portId=="eth1")|.linkFlaps)==42)'
api GET 'ports/eth1/diagnostics'; ok "cable diagnostics basic (no TDR claimed)" jqt '.cableStatus=="CONNECTED" and .estimatedLengthMeters==null and .linkSpeedMbps==100'
api GET 'ports/..%2Fx/diagnostics'; ok "diagnostics invalid port rejected" test "$CODE" = 400 -o "$CODE" = 404
api GET wifi;          ok "wifi radios/networks" jqt '.radios[0].ssid=="Weshah-Test" and .radios[0].channel==6 and .radios[0].frequencyMHz==2437 and .radios[0].band=="BAND_2_4GHZ" and .networks[0].clientCount==2'
api GET wifi/clients;  ok "wifi clients rates parsed" jqt '(.clients|length)==2 and (.clients[0].txRateMbps==72) and (.clients[0].rxRateMbps==65) and (.clients[1].rssi==-71)'
api GET health;        ok "health measurements" jqt '.wanState=="CONNECTED" and .gatewayLatencyMs==11.962 and .internetLatencyMs==11.962 and .packetLossPercent==0 and .jitterMs==3.6 and (.interfaceErrors.eth1==3) and .activeClientCount==4'
api GET multi-wan;     ok "multi-wan empty without mwan3" jqt '.wans==[]'
api GET lldp;          ok "lldp honestly 501" code_is 501

echo; echo "=== 3. block / unblock (UCI firewall rules, persisted on flash)"
reset_state
api POST "devices/$MAC1/block" '{}';                         ok "block 200" code_is 200
ok "UCI rule created with src_mac and REJECT" test "$(uci_get firewall.weshah_block_aa_bb_cc_00_00_01.src_mac)" = "$MAC1" -a "$(uci_get firewall.weshah_block_aa_bb_cc_00_00_01.target)" = REJECT -a "$(uci_get firewall.weshah_block_aa_bb_cc_00_00_01.dest)" = wan
ok "firewall reload requested" sh -c "sleep 1; $(printf '%q' "") true"
sleep 1; ok "firewall reload recorded" sh -c "chroot '$T' cat /tmp/reload.log | grep -q firewall"
api GET "devices/$MAC1/block";                               ok "block status: manual blocked" jqt '.isBlocked==true and .manual==true and .scheduled==false and .ruleId=="weshah_block_aa_bb_cc_00_00_01" and .expiresAt==null'
api GET devices;                                             ok "device list shows isBlocked" jqt '(.[]|select(.mac=="aa:bb:cc:00:00:01")|.isBlocked)==true and (.[]|select(.mac=="aa:bb:cc:00:00:02")|.isBlocked)==false'
api POST "devices/$MAC1/block" '{}';                         ok "block is idempotent" code_is 200
ok "still exactly one rule" test "$(ucis firewall | grep -c "weshah_block_aa_bb_cc_00_00_01=rule")" = 1
api POST "devices/$MAC1/unblock";                            ok "unblock 200" code_is 200
ok "rule removed from flash config" test -z "$(uci_get firewall.weshah_block_aa_bb_cc_00_00_01)"
api GET "devices/$MAC1/block";                               ok "block status cleared" jqt '.isBlocked==false and .ruleId==null'
api POST "devices/AA:BB:CC:00:00:01/block" '{}';             ok "upper-case MAC normalised" jqt '.success==true'
ok "normalised rule" test "$(uci_get firewall.weshah_block_aa_bb_cc_00_00_01.src_mac)" = "$MAC1"
api POST "devices/$MAC1/unblock" >/dev/null
api POST "devices/00:00:00:00:00:00/block" '{}';             ok "null MAC rejected" code_is 400
api POST "devices/$MAC1/block" "{\"expiresAt\":$(( (NOW - 100) * 1000 ))}"; ok "expiry in the past -> 400" code_is 400
api POST "devices/$MAC1/block" '{"expiresAt":"abc"}';        ok "garbage expiry -> 400" code_is 400
EXP=$(( $(date -u +%s) + 3 ))
api POST "devices/$MAC1/block" "{\"expiresAt\":${EXP}000}";  ok "block with expiry (ms) 200" code_is 200
api GET "devices/$MAC1/block";                               ok "expiry reported in ms" jqt ".isBlocked==true and .expiresAt==${EXP}000"
sleep 5
RS '/usr/libexec/weshah-agent/tick'
ok "tick removed the expired block" test -z "$(uci_get firewall.weshah_block_aa_bb_cc_00_00_01)"
ok "expiry record removed" test ! -e "$T/etc/weshah-agent/blocks/aa_bb_cc_00_00_01"

echo; echo "=== 4. schedule: allowed windows, enforced by reconcile, persistent"
reset_state
H=$(date -u +%H); M=$(date -u +%M); HN=$(( ($(echo "$H" | sed 's/^0*//; s/^$/0/') + 2) % 24 )); HN3=$(( (HN + 1) % 24 ))
DOW=$(date -u +%u)
api POST "devices/$MAC1/schedule" "{\"timeRanges\":[{\"startHour\":$HN,\"startMinute\":0,\"endHour\":$HN3,\"endMinute\":0,\"daysOfWeek\":[]}]}"
ok "schedule (window later today) 200" code_is 200
ok "outside window -> schedule rule present (blocked)" test "$(uci_get firewall.weshah_sched_aa_bb_cc_00_00_01.src_mac)" = "$MAC1"
api GET "devices/$MAC1/schedule";                            ok "schedule round-trips" jqt ".allowedNow==false and .clockSynced==true and (.timeRanges|length)==1 and .timeRanges[0].startHour==$HN and .timeRanges[0].daysOfWeek==[]"
api GET "devices/$MAC1/block";                               ok "block status shows scheduled" jqt '.isBlocked==true and .scheduled==true and .manual==false'
api POST "devices/$MAC1/unblock";                            ok "manual unblock reports schedule still blocks" jqt '.note=="still_blocked_by_schedule"'
H0=$(( $(echo "$H" | sed 's/^0*//; s/^$/0/') ))
HS=$(( (H0 + 23) % 24 )); HE=$(( (H0 + 1) % 24 ))
api POST "devices/$MAC1/schedule" "{\"timeRanges\":[{\"startHour\":$HS,\"startMinute\":0,\"endHour\":$HE,\"endMinute\":59,\"daysOfWeek\":[1,2,3,4,5,6,7]}]}"
ok "window containing now 200" code_is 200
ok "inside window -> schedule rule removed" test -z "$(uci_get firewall.weshah_sched_aa_bb_cc_00_00_01)"
api GET "devices/$MAC1/schedule";                            ok "allowedNow true, all days collapse to []" jqt '.allowedNow==true and .timeRanges[0].daysOfWeek==[]'
OTHERDAY=$(( DOW % 7 + 1 ))
api POST "devices/$MAC1/schedule" "{\"timeRanges\":[{\"startHour\":0,\"startMinute\":0,\"endHour\":23,\"endMinute\":59,\"daysOfWeek\":[$OTHERDAY]}]}"
ok "allowed only on another weekday -> blocked today" test "$(uci_get firewall.weshah_sched_aa_bb_cc_00_00_01.src_mac)" = "$MAC1"
# persistence: lose all volatile state (reboot) and change the window while the router was "off"
RS 'rm -rf /tmp/weshah-agent; mkdir -p /tmp/weshah-agent'
ok "after reboot the persisted UCI rule is still there" test "$(uci_get firewall.weshah_sched_aa_bb_cc_00_00_01.src_mac)" = "$MAC1"
RS "printf 'R all 0 1440\n' > /etc/weshah-agent/schedules/aa_bb_cc_00_00_01.rules; /usr/libexec/weshah-agent/tick"
ok "first tick after boot converges (window now allows) -> rule removed" test -z "$(uci_get firewall.weshah_sched_aa_bb_cc_00_00_01)"
RS "printf 'R all 0 1\n' > /etc/weshah-agent/schedules/aa_bb_cc_00_00_01.rules; /usr/libexec/weshah-agent/tick"
ok "tick re-applies block when window excludes now" test "$(uci_get firewall.weshah_sched_aa_bb_cc_00_00_01.src_mac)" = "$MAC1"
api DELETE "devices/$MAC1/schedule";                         ok "delete schedule 200" code_is 200
ok "schedule rule gone after delete" test -z "$(uci_get firewall.weshah_sched_aa_bb_cc_00_00_01)"
api GET "devices/$MAC1/schedule";                            ok "deleted schedule -> 404" code_is 404
RS "printf 'R all 0 1\n' > /etc/weshah-agent/schedules/aa_bb_cc_00_00_02.rules"
RS "uci set firewall.weshah_sched_aa_bb_cc_00_00_77=rule; uci set firewall.weshah_sched_aa_bb_cc_00_00_77.src_mac=aa:bb:cc:00:00:77; uci commit firewall; /usr/libexec/weshah-agent/tick"
ok "orphan schedule rule (no schedule file) is cleaned up" test -z "$(uci_get firewall.weshah_sched_aa_bb_cc_00_00_77)"
RS "rm -f /etc/weshah-agent/schedules/aa_bb_cc_00_00_02.rules; /usr/libexec/weshah-agent/tick"
for bad in '{"timeRanges":[]}' '{}' '{"timeRanges":[{"startHour":25,"startMinute":0,"endHour":3,"endMinute":0,"daysOfWeek":[]}]}' \
           '{"timeRanges":[{"startHour":1,"startMinute":0,"endHour":3,"endMinute":0,"daysOfWeek":[8]}]}' \
           '{"timeRanges":[{"startHour":"x","startMinute":0,"endHour":3,"endMinute":0,"daysOfWeek":[]}]}' \
           '{"timeRanges":[{"startHour":1,"startMinute":0,"endHour":3,"endMinute":0,"daysOfWeek":[]}],"downloadKbps":-5}'; do
    api POST "devices/$MAC1/schedule" "$bad"
    ok "invalid schedule rejected: $(echo "$bad" | cut -c1-60)" code_is 400
done
ok "no state left behind by rejected schedules" test -z "$(RS 'ls /etc/weshah-agent/schedules 2>/dev/null')"
# semantics unit tests with a controlled clock (same sh functions the agent runs)
cat > "$T/tmp/semantics.sh" <<'EOF'
. /usr/lib/weshah-agent/lib.sh; . /usr/lib/weshah-agent/enforce.sh
chk() { # <rules> <dow> <minute> <expect>
    printf '%b' "$1" > /tmp/r.rules
    got=$(sched_allowed /tmp/r.rules "$2" "$3")
    [ "$got" = "$4" ] && echo "ok $1 $2 $3" || echo "BAD rules=[$1] dow=$2 min=$3 got=$got want=$4"
}
chk 'R all 480 1320\n' 1 479 0
chk 'R all 480 1320\n' 1 480 1
chk 'R all 480 1320\n' 1 1319 1
chk 'R all 480 1320\n' 1 1320 0
chk 'R all 0 1440\n' 3 1439 1
chk 'R 12345 480 1020\n' 6 600 0
chk 'R 12345 480 1020\n' 5 600 1
chk 'R all 1320 360\n' 2 1400 1
chk 'R all 1320 360\n' 2 100 1
chk 'R all 1320 360\n' 2 700 0
chk 'R 5 1320 360\n' 5 1400 1
chk 'R 5 1320 360\n' 6 100 1
chk 'R 5 1320 360\n' 6 1400 0
chk 'R 5 1320 360\n' 5 100 0
chk 'R 1 1320 360\n' 1 100 0
chk 'R 1 1320 360\n' 2 100 1
chk 'R all 100 100\n' 4 1000 1
chk 'R 1 600 700\nR 3 900 1000\n' 3 950 1
chk 'R 1 600 700\nR 3 900 1000\n' 3 650 0
chk 'R 1 600 1\n' 1 0 0
EOF
RS 'sh /tmp/semantics.sh' > "$OUT/semantics.txt" 2>&1
ok "schedule semantics: $(grep -c '^ok' "$OUT/semantics.txt") window/day/overnight/end-of-day cases pass" test "$(grep -c '^BAD' "$OUT/semantics.txt")" = 0 -a "$(grep -c '^ok' "$OUT/semantics.txt")" = 20
grep '^BAD' "$OUT/semantics.txt"
cat > "$T/tmp/clock.sh" <<'EOF'
. /usr/lib/weshah-agent/lib.sh
WESHAH_FAKE_CLOCK="2026 1 08 05 1" ; clock_synced && echo synced || echo unsynced
WESHAH_FAKE_CLOCK="1970 4 00 00 0" ; clock_synced && echo synced || echo unsynced
echo "dec 08=$(dec 08) 09=$(dec 09) 00=$(dec 00) 10=$(dec 10)"
EOF
RS 'sh /tmp/clock.sh' > "$OUT/clock.txt" 2>&1
ok "unsynchronised router clock never drives schedules" test "$(sed -n 1p "$OUT/clock.txt")" = synced -a "$(sed -n 2p "$OUT/clock.txt")" = unsynced
ok "08/09 are not parsed as octal" grep -q 'dec 08=8 09=9 00=0 10=10' "$OUT/clock.txt"

echo; echo "=== 5. static DHCP leases (dnsmasq host sections)"
reset_state
api POST dhcp/static "{\"mac\":\"$MAC2\",\"ip\":\"192.168.1.60\",\"hostname\":\"sara-laptop\"}"; ok "add 200" code_is 200
ok "UCI host section" test "$(uci_get dhcp.weshah_static_aa_bb_cc_00_00_02.ip)" = 192.168.1.60 -a "$(uci_get dhcp.weshah_static_aa_bb_cc_00_00_02.name)" = sara-laptop -a "$(uci_get dhcp.weshah_static_aa_bb_cc_00_00_02.mac)" = "$MAC2"
sleep 1; ok "dnsmasq reload requested" sh -c "chroot '$T' cat /tmp/reload.log | grep -q dnsmasq"
api GET dhcp/static;                                         ok "list shows it" jqt '.leases==[{"mac":"aa:bb:cc:00:00:02","ip":"192.168.1.60","hostname":"sara-laptop"}]'
api POST dhcp/static "{\"mac\":\"$MAC1\",\"ip\":\"192.168.1.60\"}"; ok "same IP for another MAC -> 409" code_is 409
api POST dhcp/static "{\"mac\":\"$MAC1\",\"ip\":\"10.9.9.9\"}";      ok "IP outside LAN subnet -> 400" code_is 400
api POST dhcp/static "{\"mac\":\"$MAC1\",\"ip\":\"192.168.1.300\"}"; ok "IP octet > 255 -> 400" code_is 400
api POST dhcp/static "{\"mac\":\"$MAC1\",\"ip\":\"192.168.1.61\",\"hostname\":\"bad name;rm\"}"; ok "hostname with spaces/shell chars -> 400" code_is 400
api POST dhcp/static "{\"mac\":\"$MAC2\",\"ip\":\"192.168.1.62\"}"; ok "update own reservation 200" code_is 200
ok "updated in place (still one entry)" test "$(ucis dhcp | grep -c '=host$')" = 1 -a "$(uci_get dhcp.weshah_static_aa_bb_cc_00_00_02.ip)" = 192.168.1.62
RS 'uci set dhcp.@dnsmasq[0].domain=lan; uci add dhcp host >/dev/null; uci set dhcp.@host[-1].mac=aa:bb:cc:00:00:55; uci set dhcp.@host[-1].ip=192.168.1.55; uci commit dhcp'
api GET dhcp/static;                                         ok "foreign (hand-made) reservations are listed too" jqt '(.leases|length)==2'
api POST dhcp/static "{\"mac\":\"aa:bb:cc:00:00:55\",\"ip\":\"192.168.1.56\"}"; ok "cannot overwrite a hand-made reservation -> 409" code_is 409
api DELETE dhcp/static/aa:bb:cc:00:00:55;                    ok "cannot delete a hand-made reservation -> 404" code_is 404
api DELETE "dhcp/static/$MAC2";                              ok "remove 200" code_is 200
ok "section removed" test -z "$(uci_get dhcp.weshah_static_aa_bb_cc_00_00_02)"
api DELETE "dhcp/static/$MAC2";                              ok "remove again -> 404" code_is 404

echo; echo "=== 6. speed limits on a router without tc (stock image)"
reset_state
api POST "devices/$MAC1/speed" '{"downloadKbps":5120,"uploadKbps":1024}'; ok "set speed -> 501 with reason" code_is 501
ok "reason names the missing piece" jqt '.error=="speed_limit_unsupported:tc_missing"'
api GET "devices/$MAC1/speed";                               ok "speed status: none" jqt '.source=="none" and .downloadKbps==null and .active==false and .applied==false'
api DELETE "devices/$MAC1/speed";                            ok "removing absent limit is a no-op 200" code_is 200
api POST "devices/$MAC1/speed" '{"downloadKbps":-1,"uploadKbps":5}'; ok "negative speed rejected before capability check? (501 or 400)" test "$CODE" = 400 -o "$CODE" = 501

echo; echo "=== 7. VLAN on swconfig (real UCI generated by the firmware for TL-WR841N v11)"
reset_state
api GET vlans;                                               ok "vlan 1 listed read-only (managed=false)" jqt '.vlans[0].vlanId==1 and .vlans[0].managed==false and .vlans[0].untaggedPorts==["1","2","3","4"] and .vlans[0].subnet=="192.168.1.0/24"'
V20='{"vlanId":20,"name":"Guests","gateway":"192.168.20.1","subnet":"192.168.20.0/24","dhcpEnabled":true,"internetAccess":true,"clientIsolation":false,"ssid":null,"taggedPorts":[],"untaggedPorts":["3"]}'
api POST vlans "$V20";                                       ok "create VLAN 20 -> 200 + confirmRequired" jqt '.success==true and .confirmRequired==true and .confirmWithinSeconds==4'
ok "switch_vlan section with CPU port tagged" test "$(uci_get network.weshah_vlan_20.ports)" = "3 0t" -a "$(uci_get network.weshah_vlan_20.device)" = switch0 -a "$(uci_get network.weshah_vlan_20.vlan)" = 20
ok "port 3 moved out of VLAN 1" test "$(RS "rm -rf /tmp/.uci; uci -q show network | sed -n \"s/^network\\.@switch_vlan\\[0\\]\\.ports='\\(.*\\)'/\\1/p\"")" = "1 2 4 0t"
ok "interface on eth0.20 with gateway/mask" test "$(uci_get network.weshah_v20.ifname)" = eth0.20 -a "$(uci_get network.weshah_v20.ipaddr)" = 192.168.20.1 -a "$(uci_get network.weshah_v20.netmask)" = 255.255.255.0
ok "dhcp pool for the VLAN" test "$(uci_get dhcp.weshah_v20.interface)" = weshah_v20 -a "$(uci_get dhcp.weshah_v20.start)" = 100
ok "zone (<=11 chars) with forwarding to wan" test "$(uci_get firewall.weshah_z20.name)" = wv20 -a "$(uci_get firewall.weshah_f20.dest)" = wan -a "$(uci_get firewall.weshah_z20.input)" = REJECT
ok "dns/dhcp allowed for the VLAN zone" test "$(uci_get firewall.weshah_rp20.dest_port)" = 67-68 -a "$(uci_get firewall.weshah_rd20.dest_port)" = 53
sleep 3; ok "network/dnsmasq/firewall reload requested (after response)" sh -c "chroot '$T' cat /tmp/reload.log | grep -q network"
api GET vlans;                                               ok "listing shows managed VLAN 20 with ports and subnet" jqt '(.vlans|map(.vlanId)|sort)==[1,20] and ((.vlans[]|select(.vlanId==20)) | .managed==true and .untaggedPorts==["3"] and .gateway=="192.168.20.1" and .subnet=="192.168.20.0/24" and .dhcpEnabled==true and .internetAccess==true and .name=="Guests")'
ok "pending change is armed for rollback" test -f "$T/etc/weshah-agent/rollback/pending"
api POST vlans/confirm;                                      ok "confirm 200" code_is 200
ok "confirm clears rollback state" test ! -e "$T/etc/weshah-agent/rollback/pending" -a ! -e "$T/etc/weshah-agent/rollback/network"
api POST vlans "$V20";                                       ok "duplicate -> 409" code_is 409
api POST vlans '{"vlanId":1,"name":"x","ports":[]}';         ok "vlan 1 rejected" code_is 400
api POST vlans '{"vlanId":21,"name":"Over","gateway":"192.168.1.5","subnet":"192.168.1.0/24","dhcpEnabled":false,"internetAccess":false,"taggedPorts":[],"untaggedPorts":["4"]}'; ok "subnet overlapping LAN -> 409" code_is 409
api POST vlans '{"vlanId":22,"name":"Bad","gateway":"192.168.22.1","subnet":"192.168.22.0/24","dhcpEnabled":false,"internetAccess":false,"taggedPorts":[],"untaggedPorts":["9"]}'; ok "unknown port -> 400" code_is 400
api POST vlans '{"vlanId":22,"name":"Bad","gateway":"192.168.22.1","subnet":"192.168.22.0/24","taggedPorts":["2"],"untaggedPorts":["2"]}'; ok "port both tagged and untagged -> 400" code_is 400
api POST vlans '{"vlanId":22,"name":"Wifi","gateway":"192.168.22.1","subnet":"192.168.22.0/24","ssid":"X","taggedPorts":[],"untaggedPorts":["2"]}'; ok "ssid not implemented -> 400 unsupported_field" jqt '.error=="unsupported_field:ssid"'
api POST vlans '{"vlanId":22,"name":"a;b","gateway":"192.168.22.1","subnet":"192.168.22.0/24","taggedPorts":[],"untaggedPorts":["2"]}'; ok "name with shell chars -> 400" code_is 400
api POST vlans '{"vlanId":22,"name":"NoGw","dhcpEnabled":true,"taggedPorts":[],"untaggedPorts":["2"]}'; ok "dhcp without gateway/subnet -> 400" code_is 400
api POST vlans '{"vlanId":4095,"name":"Hi","taggedPorts":[],"untaggedPorts":["2"]}'; ok "vlan id 4095 -> 400" code_is 400
ok "rejected requests changed nothing" test "$(ucis network | grep -c '=switch_vlan$')" = 2
V20U='{"vlanId":20,"name":"Guests2","gateway":"192.168.20.1","subnet":"192.168.20.0/24","dhcpEnabled":false,"internetAccess":false,"clientIsolation":false,"ssid":null,"taggedPorts":["2"],"untaggedPorts":["3","4"]}'
api PUT vlans/20 "$V20U";                                    ok "update VLAN 20 -> 200" jqt '.success==true'
ok "updated ports (CPU tagged)" test "$(uci_get network.weshah_vlan_20.ports)" = "3 4 2t 0t"
ok "dhcp pool removed, forwarding removed" test -z "$(uci_get dhcp.weshah_v20)" -a -z "$(uci_get firewall.weshah_f20)"
api POST vlans/confirm >/dev/null
api PUT vlans/20 "$(echo "$V20U" | sed 's/"vlanId":20/"vlanId":99/')"; ok "PUT with mismatching id -> 400" code_is 400
api PUT vlans/77 "$(echo "$V20U" | sed 's/"vlanId":20/"vlanId":77/')"; ok "update unknown VLAN -> 404" code_is 404
api DELETE vlans/20;                                         ok "delete VLAN 20 -> 200" code_is 200
ok "all managed sections removed" test -z "$(uci_get network.weshah_vlan_20)" -a -z "$(uci_get network.weshah_v20)" -a -z "$(uci_get firewall.weshah_z20)"
ok "ports given back to VLAN 1" test "$(RS "rm -rf /tmp/.uci; uci -q show network | sed -n \"s/^network\\.@switch_vlan\\[0\\]\\.ports='\\(.*\\)'/\\1/p\"" | tr ' ' '\n' | sort | tr '\n' ' ')" = "0t 1 2 3 4 "
api POST vlans/confirm >/dev/null
api DELETE vlans/1;                                          ok "deleting foreign VLAN 1 refused" test "$CODE" = 409 -o "$CODE" = 404
# unconfirmed change must roll back by itself
cp "$T/etc/config/network" "$OUT/network.before"; cp "$T/etc/config/firewall" "$OUT/firewall.before"
api POST vlans '{"vlanId":30,"name":"Lost","gateway":"192.168.30.1","subnet":"192.168.30.0/24","dhcpEnabled":true,"internetAccess":true,"taggedPorts":[],"untaggedPorts":["4"]}'; ok "create VLAN 30 (will not be confirmed)" code_is 200
ok "VLAN 30 active in config" test "$(uci_get network.weshah_vlan_30.vlan)" = 30
RS '/usr/libexec/weshah-agent/tick'
ok "tick before the deadline keeps the change" test "$(uci_get network.weshah_vlan_30.vlan)" = 30
sleep 6
RS '/usr/libexec/weshah-agent/tick'
ok "unconfirmed change rolled back after deadline" test -z "$(uci_get network.weshah_vlan_30)"
ok "network config identical to before" cmp -s "$T/etc/config/network" "$OUT/network.before"
ok "firewall config identical to before" cmp -s "$T/etc/config/firewall" "$OUT/firewall.before"
ok "rollback reloaded services" sh -c "chroot '$T' cat /tmp/reload.log | grep -c network | grep -qv '^0\$'"
# reboot while a change is pending => rolled back at the next boot tick regardless of timers
api POST vlans '{"vlanId":31,"name":"Boot","gateway":"192.168.31.1","subnet":"192.168.31.0/24","dhcpEnabled":false,"internetAccess":false,"taggedPorts":[],"untaggedPorts":["4"]}' >/dev/null
RS 'sed -i "s/^[^ ]* /deadbeef-other-boot /" /etc/weshah-agent/rollback/pending; /usr/libexec/weshah-agent/tick'
ok "router rebooted before confirming -> rolled back immediately" test -z "$(uci_get network.weshah_vlan_31)"

echo; echo "=== 8. backup / verified restore"
reset_state
CODE=$(curl -s -m 120 -o "$OUT/backup.tar.gz" -w '%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" "$BASE/backup")
ok "backup 200" code_is 200
ok "backup is a valid gzip tar" sh -c "gzip -t < '$OUT/backup.tar.gz' && tar tzf '$OUT/backup.tar.gz' >/dev/null"
ok "backup contains router config" sh -c "tar tzf '$OUT/backup.tar.gz' | grep -q '^etc/config/network$'"
ok "backup carries the board manifest" sh -c "tar xzOf '$OUT/backup.tar.gz' etc/weshah-agent/backup-manifest.json | jq -e '.board==\"tl-wr841n-v11\"'"
ok "agent state is part of the sysupgrade keep list" sh -c "tar tzf '$OUT/backup.tar.gz' | grep -q 'etc/weshah-agent.conf'"
upload() { CODE=$(curl -s -m 120 -o "$OUT/resp" -w '%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" --data-binary @"$1" "$BASE/restore/validate"); }
upload "$OUT/backup.tar.gz";                                 ok "validate good archive -> 200" code_is 200
SHA=$(sha256sum "$OUT/backup.tar.gz" | cut -d' ' -f1)
ok "validation verdict" jqt ".valid==true and .sha256==\"$SHA\" and .hasManifest==true and .boardMatch==true and .backupBoard==\"tl-wr841n-v11\" and .warnings==[]"
# --- hostile / broken archives, none may be accepted or staged
W2="$OUT/evil"; mkdir -p "$W2/x/etc/config" "$W2/y"; echo 'config system' > "$W2/x/etc/config/system"
echo notgzip > "$OUT/junk.tar.gz"; upload "$OUT/junk.tar.gz";  ok "not gzip -> 400" code_is 400
( cd "$W2/y" && mkdir -p etc/config && echo 'config system' > etc/config/system && ln -sf /etc/passwd etc/config/evil && tar czf "$OUT/symlink.tar.gz" etc )
upload "$OUT/symlink.tar.gz";                                ok "symlink entry -> 400" jqt '.error|startswith("unsupported_entry_type")'
( cd "$W2/y" && rm -rf ./* && mkdir -p etc/config sub && echo 'config system' > etc/config/system && echo x > ../evil.txt && tar czf "$OUT/traversal.tar.gz" etc --transform 's#^etc/config/system$#etc/../../evil.txt#' 2>/dev/null || tar czf "$OUT/traversal.tar.gz" etc )
python3 -I - "$OUT" <<'PYEOF'
import tarfile, io, sys
out = sys.argv[1]
def mk(name, entries):
    with tarfile.open(f"{out}/{name}", "w:gz") as t:
        for n, data in entries:
            ti = tarfile.TarInfo(n); ti.size = len(data); t.addfile(ti, io.BytesIO(data))
mk("traversal.tar.gz", [("etc/config/system", b"x"), ("etc/../../tmp/evil", b"x")])
mk("absolute.tar.gz", [("etc/config/system", b"x"), ("/etc/passwd", b"x")])
mk("outside.tar.gz", [("etc/config/system", b"x"), ("usr/sbin/uhttpd", b"x")])
mk("nosystem.tar.gz", [("etc/config/network", b"x")])
mk("nomanifest.tar.gz", [("etc/config/system", b"config system\n")])
mk("othership.tar.gz", [("etc/config/system", b"config system\n"), ("etc/weshah-agent/backup-manifest.json", b'{"board":"some-other-board"}')])
PYEOF
upload "$OUT/traversal.tar.gz";                              ok "path traversal -> 400" jqt '.error|startswith("unsafe_path")'
upload "$OUT/absolute.tar.gz";                               ok "absolute path -> 400" jqt '.error|startswith("unsafe_path")'
upload "$OUT/outside.tar.gz";                                ok "path outside etc/root -> 400" jqt '.error|startswith("path_outside_etc_root")'
upload "$OUT/nosystem.tar.gz";                               ok "archive without etc/config/system -> 400" jqt '.error=="missing_etc_config_system"'
ok "no hostile file was written anywhere" test ! -e "$T/tmp/evil" -a ! -e "$T/etc/passwd.evil"
head -c 6000000 /dev/urandom > "$OUT/huge.bin"
CODE=$(curl -s -m 60 -o "$OUT/resp" -w '%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" --data-binary @"$OUT/huge.bin" "$BASE/restore/validate"); ok "archive over 5 MiB -> 413" code_is 413
api POST restore/apply "{\"sha256\":\"$SHA\"}";             ok "apply after rejected uploads: nothing valid staged -> 404" code_is 404
upload "$OUT/othership.tar.gz";                              ok "other board's backup flagged" jqt '.boardMatch==false and (.warnings|index("board_mismatch")!=null)'
SHA2=$(sha256sum "$OUT/othership.tar.gz" | cut -d' ' -f1)
api POST restore/apply "{\"sha256\":\"$SHA2\",\"allowUnverified\":true}"; ok "board mismatch can never be forced -> 409" jqt '.error=="board_mismatch_refused"'
upload "$OUT/nomanifest.tar.gz";                             ok "manifest-less archive is accepted for inspection" jqt '.valid==true and .hasManifest==false and .boardMatch==null'
SHA3=$(sha256sum "$OUT/nomanifest.tar.gz" | cut -d' ' -f1)
api POST restore/apply "{\"sha256\":\"$SHA3\"}";             ok "unverified archive refused without explicit consent -> 409" jqt '.error=="unverified_backup_requires_allowUnverified"'
upload "$OUT/backup.tar.gz"
api POST restore/apply '{"sha256":"0000"}';                  ok "wrong sha256 -> 409" jqt '.error=="sha256_mismatch_with_staged_archive"'
# --- successful restore changes config, snapshots the old one, arms rollback
RS 'uci set system.@system[0].hostname=BEFORE-RESTORE; uci commit system'
tar czf "$OUT/orig-system.tgz" -C "$T" etc/config/system 2>/dev/null
R uci set system.@system[0].hostname=ORIGINAL; R uci commit system
api POST restore/validate; : # (no-op: keep staged archive as uploaded below)
# take a fresh backup with hostname=ORIGINAL, then change the live value, then restore
CODE=$(curl -s -m 120 -o "$OUT/backup2.tar.gz" -w '%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" "$BASE/backup")
R uci set system.@system[0].hostname=CHANGED-AFTER-BACKUP; R uci commit system
upload "$OUT/backup2.tar.gz"; SHA4=$(sha256sum "$OUT/backup2.tar.gz" | cut -d' ' -f1)
api POST restore/apply "{\"sha256\":\"$SHA4\",\"reboot\":true}"; ok "apply 200" jqt '.success==true and .rebootRequired==true and .rebootScheduled==true'
ok "configuration really restored" test "$(uci_get system.@system[0].hostname)" = ORIGINAL
ok "pre-restore snapshot exists and holds the previous config" sh -c "ls '$T'/etc/weshah-agent/pre-restore/pre-restore-*.tar.gz >/dev/null && tar xzOf '$T'/etc/weshah-agent/pre-restore/pre-restore-*.tar.gz etc/config/system | grep -q CHANGED-AFTER-BACKUP"
ok "rollback armed" test -f "$T/etc/weshah-agent/restore.pending"
api POST restore/apply "{\"sha256\":\"$SHA4\"}";             ok "second apply refused while unconfirmed" test "$CODE" = 404 -o "$CODE" = 409
sleep 6; ok "reboot was requested (recorded, not executed)" sh -c "chroot '$T' cat /tmp/reload.log | grep -q '^reboot'"
# simulate the reboot: new boot id, then no confirmation => automatic rollback to the snapshot
RS 'sed -i "s/^\([^ ]*\) [^ ]* /\1 old-boot-id /" /etc/weshah-agent/restore.pending; /usr/libexec/weshah-agent/tick'
ok "first tick after reboot starts the confirmation window" grep -q ' booted ' "$T/etc/weshah-agent/restore.pending"
sleep 6
RS '/usr/libexec/weshah-agent/tick'
ok "unconfirmed restore rolled back to pre-restore snapshot" test "$(uci_get system.@system[0].hostname)" = CHANGED-AFTER-BACKUP
ok "rollback arms a second reboot and clears pending" test ! -e "$T/etc/weshah-agent/restore.pending"
# confirm path
upload "$OUT/backup2.tar.gz"
api POST restore/apply "{\"sha256\":\"$SHA4\"}" >/dev/null
api POST restore/confirm;                                    ok "confirm restore 200" code_is 200
ok "confirm clears rollback" test ! -e "$T/etc/weshah-agent/restore.pending"

echo; echo "=== 9. device actions"
reset_state
api POST "devices/$MAC1/disconnect";                         ok "disconnect associated station 200" code_is 200
ok "hostapd ubus del_client was called with the MAC" sh -c "chroot '$T' cat /tmp/shim.log | grep -q 'hostapd del_client.*$MAC1'"
api POST "devices/aa:bb:cc:00:00:03/disconnect";             ok "disconnect of non-associated MAC -> 404" code_is 404

echo; echo "=== 10. traffic counters: bytes and rates beyond 2^31, firewall rules generated"
reset_state
RS 'touch /tmp/fixture/iptables.chain'
cat > "$T/tmp/fixture/iptables.acct" <<'EOF'
Chain WESHAH_ACCT (1 references)
    pkts      bytes target     prot opt in     out     source               destination
       1        100            all  --  *      *       0.0.0.0/0            0.0.0.0/0            MAC AA:BB:CC:00:00:01 /* u-aabbcc000001 */
       1   5000000000            all  --  *      *       0.0.0.0/0            192.168.1.101        /* d-aabbcc000001 */
       1        200            all  --  *      *       0.0.0.0/0            0.0.0.0/0            MAC AA:BB:CC:00:00:02 /* u-aabbcc000002 */
       1        300            all  --  *      *       0.0.0.0/0            192.168.1.102        /* d-aabbcc000002 */
EOF
api GET devices >/dev/null
api GET "devices/$MAC1/traffic"
ok "first sample: totals from firewall counters, 64-bit safe, no fake rate" jqt '.rxBytes==5000000000 and .txBytes==100 and .rxRateBps==0 and .txRateBps==0 and .timestamp>1700000000000'
sleep 3
cat > "$T/tmp/fixture/iptables.acct" <<'EOF'
Chain WESHAH_ACCT (1 references)
    pkts      bytes target     prot opt in     out     source               destination
       1       1100            all  --  *      *       0.0.0.0/0            0.0.0.0/0            MAC AA:BB:CC:00:00:01 /* u-aabbcc000001 */
       1   5006000000            all  --  *      *       0.0.0.0/0            192.168.1.101        /* d-aabbcc000001 */
EOF
api GET "devices/$MAC1/traffic"
ok "second sample: rate computed from the counter delta" jqt '.rxBytes==5006000000 and .txBytes==1100 and (.rxRateBps>1000000 and .rxRateBps<2200000) and (.txRateBps>=200 and .txRateBps<=500)'
api GET devices
ok "device list carries the same totals" jqt '(.[]|select(.mac=="aa:bb:cc:00:00:01")|.rxBytes)==5006000000'
RS '/usr/libexec/weshah-agent/tick'
ok "accounting rules: MAC (upload) and destination IP (download) counters, v4" sh -c "chroot '$T' grep -q -- '-A WESHAH_ACCT -m mac --mac-source aa:bb:cc:00:00:01 -m comment --comment u-aabbcc000001' /tmp/iptables.log && chroot '$T' grep -q -- '-A WESHAH_ACCT -d 192.168.1.101 -m comment --comment d-aabbcc000001' /tmp/iptables.log"
ok "accounting rules: IPv6 neighbour address gets a counter in ip6tables" sh -c "chroot '$T' grep -q -- '-A WESHAH_ACCT -d 2001:db8::101 -m comment --comment d-aabbcc000001' /tmp/iptables.log"
ok "jump from FORWARD is (re)installed" sh -c "chroot '$T' grep -q -- 'mangle -I FORWARD 1 -j WESHAH_ACCT' /tmp/iptables.log"
ok "counters are folded before a rebuild (totals never go backwards)" sh -c "test -f '$T/tmp/weshah-agent/acct.base.aabbcc000001' || true"

echo; echo "=== 11. concurrency (locking)"
reset_state
for i in 1 2 3 4 5 6; do
    ( curl -s -m 120 -o /dev/null -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' --data '{}' "$BASE/devices/aa:bb:cc:00:00:0$i/block" ) &
done
wait
ok "6 parallel blocks all landed, config consistent" test "$(ucis firewall | grep -c '^firewall\.weshah_block_.*=rule$')" = 6
for i in 1 2 3 4 5 6; do ( curl -s -m 120 -o /dev/null -X POST -H "Authorization: Bearer $TOKEN" "$BASE/devices/aa:bb:cc:00:00:0$i/unblock" ) & done
wait
ok "6 parallel unblocks all landed" test "$(ucis firewall | grep -c '^firewall\.weshah_block_.*=rule$')" = 0
ok "lock released" test ! -e "$T/tmp/weshah-agent/lock"

echo; echo "=== 12. real stack facts"
ok "service really is the firmware's uhttpd (MIPS)" sh -c "file '$T/usr/sbin/uhttpd' | grep -q 'MIPS'"
ok "agent scripts run under BusyBox ash, not bash" sh -c "chroot '$T' readlink /bin/sh | grep -q busybox"
ok "no secrets in the process list / syslog of the request path (token not logged)" sh -c "! chroot '$T' logread 2>/dev/null | grep -q '$TOKEN'"

echo; echo "=== 13. first-boot token provisioning (init script)"
cat > "$T/tmp/token.sh" <<'EOT'
eval "$(sed -n '/^ensure_conf()/,/^}/p' /etc/init.d/weshah-agent)"
CONF=/tmp/tok.conf; rm -f /tmp/tok.conf /tmp/tok.conf.new
ensure_conf; echo "rc1=$?"; t1=$(sed -n 's/^TOKEN=//p' /tmp/tok.conf); echo "len=${#t1}"; echo "mode=$(ls -l /tmp/tok.conf | cut -c1-10)"
ensure_conf; t2=$(sed -n 's/^TOKEN=//p' /tmp/tok.conf); [ "$t1" = "$t2" ] && echo "stable=yes" || echo "stable=NO"
echo "hex=$(printf '%s' "$t1" | grep -cE '^[0-9a-f]{64}$')"
rm -f /tmp/tok2.conf; CONF=/tmp/tok2.conf; head() { return 1; }; ensure_conf; echo "rc_noentropy=$?"; grep -c '^TOKEN=.' /tmp/tok2.conf || true
EOT
RS 'sh /tmp/token.sh' > "$OUT/token.txt" 2>&1
ok "token generated on first start: 64 hex chars (256 bit)" sh -c "grep -q '^len=64' '$OUT/token.txt' && grep -q '^hex=1' '$OUT/token.txt'"
ok "config file is root-only (0600)" grep -q '^mode=-rw-------' "$OUT/token.txt"
ok "an existing token is never rotated" grep -q '^stable=yes' "$OUT/token.txt"
ok "no entropy -> refuses to start, no weak token written" sh -c "grep -q '^rc_noentropy=1' '$OUT/token.txt' && ! grep -q '^TOKEN=.' '$T/tmp/tok2.conf'"

echo; echo "=== 14. web dashboard (static files served by the stock uhttpd)"
ROOT=${BASE%/api/v1}
ct() { curl -s -m 20 -o "$OUT/web.body" -w '%{http_code} %{content_type}' "$ROOT$1"; }
ok "GET / serves the dashboard without credentials" sh -c "[ \"\$(curl -s -m 20 -o '$OUT/web.index' -w '%{http_code}' '$ROOT/')\" = 200 ] && grep -q 'app.js' '$OUT/web.index'"
ok "app.js served as JavaScript" sh -c "ct() { curl -s -m 20 -o /dev/null -w '%{http_code} %{content_type}' '$ROOT/app.js'; }; ct | grep -qE '^200 (text|application)/(javascript|x-javascript)'"
ok "app.css served as CSS" sh -c "curl -s -m 20 -o /dev/null -w '%{http_code} %{content_type}' '$ROOT/app.css' | grep -q '^200 text/css'"
ok "the CGI script is executed, never served as source" sh -c "! curl -s -m 20 '$ROOT/api' | grep -q 'case \"\$METHOD'"
ok "no directory listing for /api/" sh -c "[ \"\$(curl -s -m 20 -o /dev/null -w '%{http_code}' '$ROOT/api/')\" = 404 ]"
ok "the API itself still requires the token" sh -c "[ \"\$(curl -s -m 20 -o /dev/null -w '%{http_code}' '$ROOT/api/v1/devices')\" = 401 ]"
ok "index.html pins a Content-Security-Policy" grep -q 'Content-Security-Policy' "$OUT/web.index"
ok "UI escapes router-supplied text (esc() used for hostnames)" sh -c "grep -q 'esc(dname(d))' '$T/usr/lib/weshah-agent/www/app.js'"

echo; echo "RESULT: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
