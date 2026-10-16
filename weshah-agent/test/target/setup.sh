#!/bin/sh
# Prepares and starts the "target" test environment: the REAL firmware userland
# (OpenWrt 18.06.9 ar71xx/tiny, MIPS BE, musl, BusyBox 1.28.4, uci, jsonfilter, uhttpd,
# ubusd, rpcd) run natively through qemu-user binfmt in a chroot, with the agent installed
# exactly as the ipk lays it out. Only netlink-bound tools are shimmed (see shims/).
#
#   setup.sh <extracted-rootfs> <work-dir>      (work-dir/testroot is created)
set -e
ROOTFS="$1"; WORK="$2"
[ -d "$ROOTFS/bin" ] && [ -n "$WORK" ] || { echo "usage: $0 <extracted-rootfs> <work-dir>" >&2; exit 2; }
HERE=$(cd "$(dirname "$0")" && pwd)
AGENT="$HERE/../.."
T="$WORK/testroot"

# 1. binfmt for MIPS big-endian (the ABIVERSION byte of MIPS ELF is 1, so it is not matched)
if [ ! -e /proc/sys/fs/binfmt_misc/status ]; then mount -t binfmt_misc none /proc/sys/fs/binfmt_misc; fi
if [ ! -e /proc/sys/fs/binfmt_misc/qemu-mips ]; then
    printf '%s' ':qemu-mips:M::\x7fELF\x01\x02\x01\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x02\x00\x08:\xff\xff\xff\xff\xff\xff\xff\x00\x00\xff\xff\xff\xff\xff\xff\xff\xff\xfe\xff\xff:/usr/bin/qemu-mips-static:F' > /proc/sys/fs/binfmt_misc/register
fi

# 2. pristine copy of the firmware userland (never touches $ROOTFS)
if [ ! -d "$T" ]; then cp -a "$ROOTFS" "$T"; fi
mkdir -p "$T/dev" "$T/proc" "$T/tmp" "$T/var/run" "$T/tmp/fixture"
[ -e "$T/dev/null" ] || mknod -m 666 "$T/dev/null" c 1 3
[ -e "$T/dev/urandom" ] || mknod -m 666 "$T/dev/urandom" c 1 9
mountpoint -q "$T/proc" || mount -t proc proc "$T/proc"

# 3. safety: nothing inside this chroot may power off the sandbox
for a in reboot poweroff halt; do rm -f "$T/sbin/$a" "$T/bin/$a"; done

# 4. install the agent exactly as the package lays it out
cp -a "$AGENT/files/." "$T/"

# 5. shims for netlink tools + service reload recorders
[ -e "$T/bin/ubus.real" ] || mv "$T/bin/ubus" "$T/bin/ubus.real"
cp "$HERE/shims/ubus" "$T/bin/ubus"
for pair in ip:/sbin/ip iw:/usr/sbin/iw swconfig:/sbin/swconfig ping:/bin/ping nslookup:/bin/nslookup mount:/bin/mount; do
    rm -f "$T${pair#*:}"; cp "$HERE/shims/${pair%%:*}" "$T${pair#*:}"
done
mkdir -p "$T/opt/realbin"; [ -e "$T/opt/realbin/tar" ] || ln -s /bin/busybox "$T/opt/realbin/tar"
rm -f "$T/bin/tar"; cp "$HERE/shims/tar" "$T/bin/tar"; chmod +x "$T/bin/tar"
for t in iptables ip6tables iptables-restore ip6tables-restore; do rm -f "$T/usr/sbin/$t"; cp "$HERE/shims/iptables-record" "$T/usr/sbin/$t"; done
for s in firewall network dnsmasq; do cp "$HERE/shims/fw-reload" "$T/etc/init.d/$s"; done
chmod +x "$T"/bin/ubus "$T"/sbin/ip "$T"/usr/sbin/iw "$T"/sbin/swconfig "$T"/bin/ping "$T"/bin/nslookup "$T"/bin/mount "$T"/usr/sbin/ip*tables* "$T"/etc/init.d/firewall "$T"/etc/init.d/network "$T"/etc/init.d/dnsmasq

# 6. fixtures and fake sysfs
cp "$HERE"/fixture/* "$T/tmp/fixture/"
mkdir -p "$T/tmp/sysnet"
for d in eth0 eth1 br-lan wlan0; do mkdir -p "$T/tmp/sysnet/$d/statistics"; echo 02:00:00:00:00:0${#d} > "$T/tmp/sysnet/$d/address"; done
echo up > "$T/tmp/sysnet/eth0/operstate"; echo up > "$T/tmp/sysnet/eth1/operstate"; echo up > "$T/tmp/sysnet/br-lan/operstate"; echo up > "$T/tmp/sysnet/wlan0/operstate"
echo 100 > "$T/tmp/sysnet/eth1/speed"; echo full > "$T/tmp/sysnet/eth1/duplex"
for d in eth0 eth1 br-lan wlan0; do for s in rx_bytes tx_bytes rx_errors tx_errors rx_dropped tx_dropped rx_crc_errors; do echo 0 > "$T/tmp/sysnet/$d/statistics/$s"; done; done
echo 5000000000 > "$T/tmp/sysnet/eth1/statistics/rx_bytes"; echo 3 > "$T/tmp/sysnet/eth1/statistics/rx_errors"
echo 42 > "$T/tmp/sysnet/eth1/carrier_changes"

# 7. board configuration produced by the firmware's own generators
mkdir -p "$T/tmp/sysinfo"
echo tl-wr841n-v11 > "$T/tmp/sysinfo/board_name"; echo "TP-Link TL-WR841N/ND v11" > "$T/tmp/sysinfo/model"
cat > "$T/tmp/gen.sh" <<'EOT'
rm -f /etc/board.json /etc/config/network /etc/config/system
/bin/board_detect >/dev/null 2>&1
/bin/config_generate >/dev/null 2>&1
EOT
chroot "$T" /bin/sh /tmp/gen.sh

# 8. agent configuration with test seams (read by lib.sh; production never sets these)
cat > "$T/etc/weshah-agent.conf" <<EOT
PORT=8765
TOKEN=0123456789abcdef0123456789abcdef0123456789abcdef
LEASES_FILE=/tmp/fixture/dhcp.leases
ARP_FILE=/tmp/fixture/arp
SYS_NET=/tmp/sysnet
THERMAL_FILE=/tmp/fixture/none
REBOOT_CMD="echo reboot >> /tmp/reload.log"
VLAN_CONFIRM_SECONDS=4
RESTORE_CONFIRM_SECONDS=4
EOT
chmod 600 "$T/etc/weshah-agent.conf"

# 9. router credentials for the real rpcd/ubus login path (md5-crypt, password: weshah-test)
HASH=$(openssl passwd -1 -salt weshahts weshah-test)
sed -i "s#^root:[^:]*:#root:$HASH:#" "$T/etc/shadow"
grep -q '^root:' "$T/etc/shadow" || echo "root:$HASH:0:0:99999:7:::" >> "$T/etc/shadow"
echo "setup ok: $T"
