# TL-WR941ND v6 `webflash.bin` — analysis (read-only, nothing built or flashed)

File: `tl-wr941ndv6-webflash.bin`, 3,932,188 B (0x3C001C), sha256 `b97d118946e0ccdb36d7edf60b4b6d7a700eae18969e8fbfdcfa64fd5c6f6aab`.
Everything below was read from the file; the original was not modified.

## What it is
| Item | Value |
|---|---|
| Outer wrapper | `HDR0`, 28 bytes (TRX-like). Its CRC32 is reproducible: CRC-32 (init 0xFFFFFFFF, **no** final XOR) over bytes 12..end = `0x5755B9CC` — verified |
| Inner header | TP-Link v1 at 0x1C, vendor string `TP-LINK Technologies`, **hardware id 0x09410006 rev 1** (TL-WR941ND v6), fw_length 0x3C0000, load/entry 0x80060000 |
| Kernel | LZMA, 919,381 B (2,700,800 B unpacked), Linux **3.18.140** |
| Rootfs | squashfs 4.0, xz, 262,144 B blocks, 670 inodes, built 2020-11-03, 2,994,506 B at file offset 0xE101C |
| Layout | the squashfs ends at 0x3BC14A of 0x3C0000, so the image leaves **no free space for a writable overlay** |
| Userland | **DD-WRT** (not OpenWrt): BusyBox 1.32.0, musl (mips, soft-float, big endian), `nvram`, `rc`, DD-WRT `httpd` with its web GUI in a single 2.5 MB `etc/www` blob, hostapd/wpad, `iw`, `iptables`, `tc`, `ip`, `ebtables`, dnsmasq, dropbear. Not present: `uci`, `ubus`, `uhttpd`, `jsonfilter`, `lua`, `opkg`, `procd` |

Consequence: the current weshah-agent (uci + jsonfilter + uhttpd + ubus) **cannot run on this firmware as it is**, and the OpenWrt
ipk cannot be installed on it. Applying Weshah here means a DD-WRT-specific agent (nvram, iptables, tc, awk/sed JSON handling and a
small HTTP listener) — a port, not a repack.

## Size study (3 MB target)
Fixed part: HDR0 + inner header + kernel + alignment = 921,628 B. For a whole image <= 3,000,000 B the rootfs must be <= **2,078,372 B**.

| Rootfs variant (xz, 1 MiB blocks) | squashfs size | fits? |
|---|---|---|
| Original tree rebuilt unchanged (256 KiB blocks) | 3,031,784 | no |
| Drop VPN servers/clients, hotspot (chilli), bird, sputnik, wiviz, tunnel/SIP/H323 modules | 2,539,928 | no (image ≈ 3.46 MB) |
| …and also drop DD-WRT's own web GUI (`etc/www`, ~0.56 MB compressed) | 1,949,992 | **yes** (≈ 128 KB left for Weshah) |

So <= 3 MB is reachable only by removing DD-WRT's own GUI. Wi-Fi/WAN/LAN setup would then have to be done by Weshah (nvram keys:
`wl0_ssid`, `wl0_*` security, `wan_proto`, `lan_ipaddr`, …), which does not exist yet.

## What could not be done
- The TP-Link `md5sum1` (inner header) could not be reproduced: I tried the salt I know from OpenWrt's `mktplinkfw` with several ranges
  and orderings, no match. A repacked image would therefore not be accepted by the TP-Link stock web updater; it could only be
  flashed from DD-WRT's own firmware-upgrade page or via TFTP/serial recovery. (The HDR0 CRC *is* reproducible.)
- No hardware check: kernel built-ins (netfilter `mac` match, `htb`), nvram key semantics and the boot of a trimmed image are
  unverified. Nothing was flashed; the flash gate stays BLOCKED.
