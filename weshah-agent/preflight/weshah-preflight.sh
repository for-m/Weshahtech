#!/bin/sh
# WESHAH preflight — READ-ONLY identity / compatibility check, to run ON the router
# (BusyBox ash, OpenWrt 18.06+). It writes nothing: no files, no uci changes, no opkg
# calls that modify state, no network traffic. Safe to run on a production device.
#
#   scp weshah-preflight.sh root@192.168.1.1:/tmp/ && ssh root@192.168.1.1 sh /tmp/weshah-preflight.sh
#
# Exit code: 0 = every REQUIRED check passed, 1 = at least one REQUIRED check failed.
# It reports facts; it never says an image is "ready to flash" — that decision needs a
# human and a recovery plan (see docs/DEPLOYMENT.md).

PASS=0; FAIL=0; WARN=0
EXPECT_BOARD="tl-wr841n-v11"
EXPECT_ARCH="mips_24kc"
EXPECT_TARGET="ar71xx/tiny"
EXPECT_REL="18.06"

say()  { printf '%s\n' "$*"; }
ok()   { PASS=$((PASS + 1)); printf 'PASS  %s\n' "$*"; }
bad()  { FAIL=$((FAIL + 1)); printf 'FAIL  %s\n' "$*"; }
warn() { WARN=$((WARN + 1)); printf 'WARN  %s\n' "$*"; }
info() { printf 'INFO  %s\n' "$*"; }
have() { command -v "$1" >/dev/null 2>&1; }

rel() { [ -r /etc/openwrt_release ] && . /etc/openwrt_release 2>/dev/null; }

say "== Identity (read from the running device, not from any file name)"
rel
BOARD=$(cat /tmp/sysinfo/board_name 2>/dev/null)
MODEL=$(cat /tmp/sysinfo/model 2>/dev/null)
info "board_name=${BOARD:-?} model=${MODEL:-?}"
info "release=${DISTRIB_RELEASE:-?} target=${DISTRIB_TARGET:-?} arch=${DISTRIB_ARCH:-?} kernel=$(uname -r 2>/dev/null)"
info "cpu: $(sed -n 's/^cpu model[^:]*: *//p;s/^system type[^:]*: *//p' /proc/cpuinfo 2>/dev/null | tr '\n' ' ')"

if [ "$BOARD" = "$EXPECT_BOARD" ]; then ok "board_name is $EXPECT_BOARD"
else bad "board_name is '${BOARD:-unknown}', expected $EXPECT_BOARD (do NOT use the TL-WR841 v11 image on this device)"; fi
case "$MODEL" in *"WR841N/ND v11"*) ok "model string matches TL-WR841N/ND v11";; *) bad "model string '${MODEL:-unknown}' is not TL-WR841N/ND v11";; esac
[ "$DISTRIB_ARCH" = "$EXPECT_ARCH" ] && ok "package arch $EXPECT_ARCH" || bad "package arch '${DISTRIB_ARCH:-?}' != $EXPECT_ARCH"
[ "$DISTRIB_TARGET" = "$EXPECT_TARGET" ] && ok "target $EXPECT_TARGET" || warn "target '${DISTRIB_TARGET:-?}' != $EXPECT_TARGET"
case "$DISTRIB_RELEASE" in "$EXPECT_REL"*) ok "OpenWrt $DISTRIB_RELEASE";; *) warn "OpenWrt '${DISTRIB_RELEASE:-?}' is not $EXPECT_REL.x — the agent is only tested on 18.06.9";; esac

say; say "== Flash / memory"
if [ -r /proc/mtd ]; then
    sed 's/^/INFO  mtd: /' /proc/mtd
    grep '"firmware"' /proc/mtd >/dev/null && info "firmware partition: $(grep '"firmware"' /proc/mtd)" || warn "no 'firmware' mtd partition found"
    for p in u-boot art config; do grep -q "\"$p\"" /proc/mtd && ok "mtd '$p' present (kept untouched by sysupgrade)" || warn "mtd '$p' not listed"; done
else bad "/proc/mtd unreadable"; fi
mem_kb=$(awk '/^MemTotal:/ { print $2 }' /proc/meminfo 2>/dev/null)
free_kb=$(awk '/^MemAvailable:/ { a = $2 } /^MemFree:/ { f = $2 } /^Cached:/ { c = $2 } END { print (a ? a : f + c) }' /proc/meminfo 2>/dev/null)
info "RAM total=${mem_kb:-?} kB free=${free_kb:-?} kB"
[ "${free_kb:-0}" -ge 6000 ] 2>/dev/null && ok "free RAM >= 6 MB for the agent + uhttpd" || warn "free RAM below 6 MB"
ov=$(df -k /overlay 2>/dev/null | awk 'NR==2 { print $4 }')
rootdf=$(df -k / 2>/dev/null | awk 'NR==2 { print $4 }')
info "free space: /overlay=${ov:-n/a} kB  /=${rootdf:-n/a} kB"
need=120
if [ -n "$ov" ]; then
    [ "$ov" -ge "$need" ] && ok "overlay has >= $need kB free (agent installs ~90 kB unpacked)" || bad "overlay has only $ov kB free, agent needs ~$need kB"
else warn "no /overlay mount (stock/read-only root?) — packages cannot be installed with opkg"; fi

say; say "== Runtime pieces the agent uses"
for c in uhttpd jsonfilter uci ubus sha256sum tar gzip awk sed nslookup ping wc cut date; do
    have "$c" && ok "$c" || bad "$c missing"
done
for c in sysupgrade iw swconfig ip rpcd; do have "$c" || [ -x "/sbin/$c" ] || [ -x "/usr/sbin/$c" ] && ok "$c" || warn "$c missing (the matching feature reports unsupported)"; done
[ -e /usr/share/libubox/jshn.sh ] && ok "jshn.sh" || bad "jshn.sh missing (login endpoint)"
[ -x /etc/init.d/uhttpd ] && ok "uhttpd init script" || warn "uhttpd init script missing"
FW="none"; have nft && FW=nft; { [ "$FW" = none ] && have iptables; } && FW=iptables
[ "$FW" != none ] && ok "firewall backend: $FW" || bad "neither nft nor iptables is available (block/schedule/counters impossible)"

say; say "== Speed-limit prerequisites (tc + kernel modules)"
if have tc; then ok "tc present"; else warn "tc missing: install 'tc' (and 'kmod-sched-core', 'kmod-sched') — speed limits stay 'unsupported' until then"; fi
k=$(uname -r 2>/dev/null)
for m in sch_htb cls_u32 act_mirred xt_mark; do
    if [ -d "/sys/module/$m" ] || find "/lib/modules/$k" -name "$m.ko*" 2>/dev/null | grep -q .; then ok "kernel module $m"
    else warn "kernel module $m not present: speed limits unsupported on this build"; fi
done

say; say "== Weshah components already on the device"
if [ -x /etc/init.d/weshah-agent ]; then ok "weshah-agent installed ($(sed -n 's/^WESHAH_VERSION="\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' /usr/lib/weshah-agent/lib.sh 2>/dev/null | head -n 1))"
else info "weshah-agent not installed"; fi
have opkg && info "opkg: $(opkg list-installed 2>/dev/null | wc -l) packages installed" || warn "opkg unavailable"
[ -f /etc/weshah-agent.conf ] && info "agent config exists (token not printed)"

say; say "== Result"
say "required checks failed: $FAIL   passed: $PASS   warnings: $WARN"
if [ "$FAIL" -eq 0 ]; then say "RESULT: compatible for installing the weshah-agent package (nothing was changed on this device)."
else say "RESULT: NOT compatible — fix or do not proceed. Nothing was changed on this device."; fi
say "NOTE: this script cannot validate a firmware image or approve a flash; see docs/DEPLOYMENT.md."
[ "$FAIL" -eq 0 ]
