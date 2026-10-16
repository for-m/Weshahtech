# Build report — Weshah custom firmware / agent package for TL-WR841N/ND v11

Date: 2026-10-16 · Base: OpenWrt 18.06.9 r8077-7cbbab7246 ar71xx/tiny (the uploaded image, sha256
`a72d31a9a65dc39e20abeaa3f5ae8161d6811fa81de1a8cf3ddc16070861e54f`) · Weshah agent 2.1.0

**Flash gate: BLOCKED.** Nothing was written to any router. The image below is *structurally validated*; it has
**not** been booted and is **not** declared ready to install.

## 1. Approach and why

The uploaded image is a genuine OpenWrt 18.06.9 build (header string `OpenWrt`, `r8077-7cbbab7246`, squashfs + kernel,
`/etc/openwrt_release`, opkg database with 92 packages). Rebuilding OpenWrt from source was not possible: `git.openwrt.org`,
`downloads.openwrt.org` and the archive mirrors are unreachable from this environment, so no SDK/ImageBuilder/feeds.
Instead the image was **repacked**: kernel carried over byte-for-byte, the squashfs rebuilt from the original file tree + the
Weshah payload using the original squashfs parameters (xz, 256 KiB blocks, root-owned), and the TP-Link v1 header
fields updated for the new rootfs length. The same payload is also shipped as an opkg package, which is the
recommended way to deploy (no firmware change).

## 2. Deliverables (`output/Weshah-Custom-Firmware/`)

| File | Size | Status |
|---|---|---|
| `weshah-agent_2.1.0-1_all.ipk` | 26,656 B | **Built and installed/removed with the firmware's own `opkg` in a chroot of the image's userland.** Arch-independent shell package. Deps: libc uhttpd jsonfilter jshn uci ubus rpcd iw |
| `Weshah-rootfs.bin` | 2,319,204 B | squashfs 4.0 / xz / 256 KiB; validated (below) |
| `Weshah-firmware.bin` | 3,932,160 B | TP-Link v1 header + original kernel + Weshah rootfs, same total size as the original. **Structurally validated, never booted** |
| `SHA256SUMS` | | checksums of the three files above |
| `hardware-compatibility-report.md` | | identity + feature matrix |
| `validation.log`, `agent-e2e-on-weshah-rootfs.log` | | raw outputs of the checks below |
| **`Weshah-kernel.bin` — NOT PRODUCED** | | The kernel is unchanged (nothing in the agent needs kernel changes) and was not rebuilt; shipping a copy of the stock kernel under a "Weshah" name would be a mislabeled file. The kernel region is inside `Weshah-firmware.bin`, byte-identical to the original |
| **`.apk` agent package — NOT PRODUCED** | | OpenWrt 18.06 uses opkg/ipk; apk appeared in 24.10 |

Reproduce: `weshah-agent/packaging/build-all.sh <original factory.bin> <new work dir> <out dir> [testroot]` (needs root, squashfs-tools, python3, xz).

## 3. What was integrated (all real, no placeholders)

REST agent (uhttpd CGI on :8765, bearer token + router-credential login), device list/traffic, block/unblock (+expiry),
persistent schedules, static DHCP, VLAN CRUD with commit-confirm rollback, verified backup/restore with auto-rollback,
health/capabilities with reasons, firewall-counter traffic accounting, tc speed limits (code present, **unsupported on this
image**, see §6), procd service enabled at boot (`S99weshah-agent`), `/etc/weshah_release` identity file, banner line,
opkg registration (`opkg list-installed` shows `weshah-agent`), sysupgrade keep-list (`/etc/weshah-agent.conf`,
`/etc/weshah-agent/`).

Not integrated because it does not exist in the project or cannot be installed: Wi-Fi SSID/password management API
(stock LuCI in the image already does this), hotspot cards, a Weshah web UI/LuCI app, LLDP.

## 4. Verification actually performed

| Check | Result |
|---|---|
| Header/kernel/rootfs/layout validation of the image (`validate_firmware.py`) | **39 / 39 passed** (header fields, kernel byte-identity, squashfs params, only-intended-files-changed, 0600 token file, S99 symlink, 0xFF padding + `deadc0de` marker, overlay ≥128 KiB) |
| The firmware's own `platform_check_image` (from its `lib/upgrade/platform.sh`, run under qemu-user) | accepts the Weshah image; rejects a wrong hardware ID and an image containing a bootloader (negative controls); accepts the original (baseline) |
| Control rebuild of the *unmodified* rootfs with the same tooling | identical 1,135-entry listing and identical content of all 1,029 files → the repack process is lossless |
| Agent end-to-end on the userland extracted **from `Weshah-rootfs.bin`** (real BusyBox 1.28.4, uci, jsonfilter, uhttpd, rpcd, ubusd via qemu-user; netlink tools shimmed) | **185 / 185 passed** (agent came from the image, not from the repo) |
| Same suite against the official stock image userland in CI (GitHub Actions run of commit 2c3985c, job `agent-firmware-userland`) | passed; the official image's sha256 is verified in the job |
| Kernel-level: real `tc` htb/u32, nft and iptables in network namespaces with TCP transfers, both backends | 42 / 42 passed locally and in CI (`agent-kernel`) — upload limited 512 KiB ≈ 3.8 s at 1 Mbit, download ≈ 2.1 s at 2 Mbit, counters ≈ 527 KB |
| shellcheck (dash dialect) on all agent scripts | clean (CI `agent-lint`) |
| Kotlin: `TimeRange` 7, adapter protocol (MockWebServer) 15, **live contract tests against the real agent** 10, `NetworkGuard` 3 | 35 / 35 on the JVM harness, which compiles the real sources without the Android SDK |
| `weshah-preflight.sh` run in the image's userland chroot | runs; reports correctly the facts the chroot cannot provide (`/proc/mtd`, overlay) |

## 5. Needs a real router (not verified, not claimable)

Boot of `Weshah-firmware.bin`; Ethernet/Wi-Fi/switch operation (including Wi-Fi calibration read from `art`) after flashing;
actual traffic blocking/scheduling on a client; VLAN behaviour on the QCA953x switch; memory/CPU of the agent under load on
the router's RAM; real `sysupgrade -b/-r` + reboot cycle (the suite records the reboot request, it does not reboot);
remaining flash after `sysupgrade`; recovery from a failed flash; the identity of the user's physical unit
(`weshah-preflight.sh`).

## 6. Limitations and risks (honest list)

1. **Speed limits are unsupported on this image.** `/sbin/tc` and the modules `sch_htb`, `cls_u32`, `act_mirred` are not in the
   image (only `xt_mark`, `xt_mac`, `xt_time`, iptables mangle). They cannot be added here (no package feeds reachable; kernel
   4.9.243 modules must match exactly; the overlay is only 192 KiB). The agent reports `unsupported` + reason instead of pretending.
   A custom kernel/image build with `kmod-sched*` and `tc` needs the OpenWrt buildroot and is the real fix.
2. **TP-Link header MD5 not regenerated** (vendor salt unknown) → the stock TP-Link web updater / TP-Link bootloader recovery will reject
   `Weshah-firmware.bin`; only OpenWrt `sysupgrade` (which does not check it) is expected to accept it. This was inferred from the
   firmware's own `platform_check_image`, not tested on hardware.
3. **Overlay shrinks 256 KiB → 192 KiB** (+35 KB rootfs; ≈11 KB of it is host `xz` being less efficient than the original build).
4. `opkg remove` leaves empty `/usr/lib/weshah-agent/www` directory (cosmetic).
5. Not done: signing. 18.06 ar71xx images are not signed and the bootloader does no verification; nothing was bypassed.
6. Agent token is stored in cleartext in `/etc/weshah-agent.conf` (mode 0600, root) and the API is plain HTTP on the LAN —
   acceptable for a LAN-only management plane, not for exposing the port to WAN (the agent binds loopback + LAN IP only; do not forward it).

## 7. Android app build status

GitHub Actions run `38973350914` (`Weshah CI`, commit 16eb920), all four jobs green: `agent-lint`, `agent-kernel`,
`agent-firmware-userland`, `android`.

- `android` job: `./gradlew testDebugUnitTest` passed (TimeRange 7, adapter protocol 15, NetworkGuard 3; the 10 live
  contract tests are skipped there because they need a running agent and are executed in `agent-firmware-userland`),
  then `:app:assembleDebug` produced the debug APK.
- APK: artifact **`Weshah-NetworkManager-debug-apk`** of that run (20,833,626 B zip; sha256 of the artifact zip
  `b4920eec025c85ad82228ade920be8aaddc1707974c84232155b63c224cd7797`), URL
  `https://github.com/for-m/Weshahtech/actions/runs/38973350914` → Artifacts. It was **not** installed or run on any device or
  emulator: install/launch, Compose navigation at runtime, Hilt graph at runtime and Android-version behaviour are untested.
- The Android project had never been built before this work (no Gradle wrapper, missing module dependencies, ~120 compile
  errors in `ui`); these were fixed iteratively against the CI compiler (commits 2c3985c … 16eb920).
