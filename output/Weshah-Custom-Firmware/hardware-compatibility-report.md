# Hardware compatibility report — Weshah on TL-WR841N/ND v11 (OpenWrt 18.06.9 ar71xx/tiny)

Everything below is derived from the uploaded image `openwrt-18.06.9-ar71xx-tiny-tl-wr841-v11-squashfs-factory.bin`
(sha256 `a72d31a9a65dc39e20abeaa3f5ae8161d6811fa81de1a8cf3ddc16070861e54f`, 3,932,160 bytes). The file was only read,
never modified. **The identity of the physical router the user owns could not be verified from here** — that needs
`weshah-agent/preflight/weshah-preflight.sh` run on the device (read-only).

## 1. What the image says it is (confirmed by parsing the header and the rootfs, not the filename)

| Item | Value | Source |
|---|---|---|
| Vendor string / version | `OpenWrt` / `r8077-7cbbab7246` | TP-Link v1 header, bytes 4–68 |
| Hardware ID / rev / model ID | `0x08410011` / 1 / `0x00000001` | header @64–72 (what `sysupgrade` compares) |
| Image length | 3,932,160 B = 0x3C0000 | header @124 == file size |
| Kernel | offset 0x200, 1,367,016 B (Linux 4.9.243) | header @128/132, `/lib/modules/4.9.243` |
| Rootfs | offset 0x14dde8, 2,284,194 B, squashfs 4.0, xz, 256 KiB blocks | header @136/140, superblock |
| Bootloader in image | none (len 0) | header @144/148 — a `sysupgrade`-style image |
| OpenWrt | 18.06.9 r8077, target `ar71xx/tiny`, arch `mips_24kc` | `/etc/openwrt_release` |
| Board name | `tl-wr841n-v11` (model `TL-WR841N/ND v11`) | `/lib/ar71xx.sh`, `/lib/upgrade/platform.sh` |
| SoC / network | QCA953x (MIPS 24Kc, BE); LAN `eth0.1`, WAN `eth1`, swconfig switch | `/etc/board.d/02_network` |
| Flash | 4 MB; firmware partition 0x3C0000 (= image size); `u-boot`, `config`, `art` (calibration) are separate partitions | image size confirmed; the 4 MB total and the other partition names are the standard layout of this board and **not verified on the device** (`/proc/mtd` is checked by the preflight) |
| Wi-Fi | ath9k (`kmod-ath9k`), calibration from the `art` partition | image modules |
| Userland | BusyBox 1.28.4, fw3/iptables 1.6.2, dnsmasq, uhttpd, rpcd, ubus, uci, jsonfilter, opkg, LuCI | `/usr/lib/opkg/status` (92 packages) |

Compatibility of the *agent* with this userland was tested by running the image's own binaries (see build-report.md).

## 2. What the Weshah image preserves (verified by `validate_firmware.py`, 39 checks)

- Header bytes 0–64 (vendor/version), hardware ID, model ID, load address/entry point, kernel offset/length — unchanged.
- Kernel region byte-identical to the original; the kernel was **not rebuilt**.
- Everything of the original rootfs is still present and unchanged except `/etc/banner` (+1 line) and `/usr/lib/opkg/status`
  (+ the Weshah package stanza). Board scripts, network/Ethernet/switch definitions, Wi-Fi drivers, `art` handling,
  opkg feeds and the OpenWrt identity are untouched.
- Total image length unchanged; the unused tail stays erased (0xFF) and the `deadc0de` JFFS2 end marker is in place.
- The firmware's own `platform_check_image` (run from its `lib/upgrade/platform.sh`) **accepts** the Weshah image and
  **rejects** a wrong hardware ID and an image carrying a bootloader (negative controls).

## 3. Differences from the stock image

| Change | Effect |
|---|---|
| +35,010 bytes in the squashfs (agent ≈ 89 kB unpacked, xz-compressed; ≈11 kB comes from host `xz` vs. the original build) | rootfs_data (overlay) 256 KiB → **192 KiB** |
| `md5sum1` in the TP-Link header zeroed | The MD5 uses a vendor salt that is not available. OpenWrt's `sysupgrade` does not check it; the TP-Link web updater and TP-Link bootloader recovery will **reject** this file |
| `/etc/rc.d/S99weshah-agent`, `K10weshah-agent`; `/etc/weshah_release`; banner line | agent starts at boot; identity marker |

## 4. Feature compatibility on this exact hardware/firmware

| Weshah feature | Status on this image | Evidence |
|---|---|---|
| REST agent (uhttpd CGI, port 8765, token auth) | **Works** (userland-verified) | 185/185 end-to-end checks on the extracted rootfs of the Weshah image |
| Devices, status, interfaces, ports, Wi-Fi clients, DHCP leases/static | Works (userland-verified; Wi-Fi/swconfig netlink calls shimmed) | same suite |
| Block / unblock / schedule | Works via UCI firewall rules (iptables backend) | suite + kernel test with real iptables |
| Per-device counters | Works (iptables counters, 64-bit safe) | suite + kernel test |
| VLAN CRUD with commit-confirm | Works on swconfig (config level; **switch hardware behaviour untested**) | suite |
| Backup / verified restore with auto-rollback | Works (`sysupgrade -b/-r`; real flash/reboot untested) | suite |
| **Speed limits (tc)** | **Not supported on this image** | rootfs has no `tc` binary and no `sch_htb`, `cls_u32`, `act_mirred` modules (only `xt_mark`); the agent reports `unsupported` with the reason. They cannot be added: no network access here to fetch ipks matching kernel 4.9.243 + the 192 KiB overlay of the Weshah image leaves little room |
| Wi-Fi SSID/password, hotspot cards, Weshah web UI / LuCI app | **Not implemented** in the project | Wi-Fi settings are available through the stock LuCI that is in the image; the Weshah Android app has no router-side counterpart for hotspot cards |
| LLDP | Not implemented (`501`) | |

## 5. What can only be verified on a real router

Boot of the image; Ethernet/Wi-Fi/switch operation after the flash; actual block/schedule effect on a client; VLAN on the real
switch; agent memory use on the unit's RAM (32 MB on this model per OpenWrt docs — not measured) under load; free flash after a real `sysupgrade`; recovery from a failed flash.
Flash gate: **BLOCKED** (see `docs/DEPLOYMENT.md`, section B).
