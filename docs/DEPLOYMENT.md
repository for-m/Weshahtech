# Deployment — weshah-agent on a real router

Two ways to get the agent onto a TP-Link TL-WR841N/ND v11 (or any OpenWrt 18.06+ router that passes
the preflight). **Use A.** B exists as a build artifact but its flash gate is BLOCKED.

| | A. Install the ipk on the running OpenWrt | B. Custom firmware image |
|---|---|---|
| Changes firmware | **No** (opkg adds files under `/usr`, `/etc`) | Yes (replaces kernel+rootfs partition) |
| Needs | working OpenWrt, ~120 kB free in `/overlay` | OpenWrt already running (sysupgrade) |
| Reversible | `opkg remove weshah-agent` | only via recovery you have prepared (see B) |
| Status | **tested** (userland + kernel suites, CI) | structurally validated, **never booted** |

## A. Package install (recommended)

1. **Identity + compatibility, read-only** — nothing is written:
   ```sh
   scp weshah-agent/preflight/weshah-preflight.sh root@192.168.1.1:/tmp/
   ssh root@192.168.1.1 sh /tmp/weshah-preflight.sh
   ```
   Stop if `RESULT: NOT compatible`. WARN lines (no `tc`, no `sch_htb`) mean speed limits will report
   "unsupported"; everything else still works.
2. **Configuration backup first** (so any later step can be undone):
   ```sh
   ssh root@192.168.1.1 'sysupgrade -b /tmp/pre-weshah.tar.gz' && scp root@192.168.1.1:/tmp/pre-weshah.tar.gz .
   ```
3. **Verify the file you are about to install:** `sha256sum -c SHA256SUMS` (in `output/Weshah-Custom-Firmware/`).
4. **Install** (this is the first write to the router — only after steps 1–3 are reviewed and approved):
   ```sh
   scp weshah-agent_2.1.0-1_all.ipk root@192.168.1.1:/tmp/
   ssh root@192.168.1.1 'opkg install /tmp/weshah-agent_2.1.0-1_all.ipk'
   ```
   The postinst enables and starts the service. If dependencies are missing, `opkg` fetches them from the
   configured feed (requires internet on the router).
5. **Verify** from a LAN machine (token is in `/etc/weshah-agent.conf`, root only):
   ```sh
   TOKEN=$(ssh root@192.168.1.1 "sed -n 's/^TOKEN=//p' /etc/weshah-agent.conf")
   curl -s -H "Authorization: Bearer $TOKEN" http://192.168.1.1:8765/api/v1/capabilities
   curl -s -H "Authorization: Bearer $TOKEN" http://192.168.1.1:8765/api/v1/status
   curl -s -o /dev/null -w '%{http_code}\n' http://192.168.1.1:8765/api/v1/status        # must be 401
   ```
6. **Tests that need the real router** (not covered by any automated run): block/unblock a real phone and
   confirm it loses/regains internet; a schedule window at the router's real clock (NTP synced); VLAN create
   with the commit-confirm timeout (do it with a second path to the router, e.g. Wi-Fi + cable); backup →
   restore → reboot → confirm on a spare config.

### Rollback (package)
```sh
ssh root@192.168.1.1 '/etc/init.d/weshah-agent stop; opkg remove weshah-agent'
```
Agent-created UCI sections are all named `weshah_*`; `uci show | grep weshah_` lists any left behind
(`uci delete <name>; uci commit; /etc/init.d/firewall reload`). VLAN changes and restores additionally
roll themselves back if they are not confirmed.

## B. Custom firmware image — gate BLOCKED

`Weshah-firmware.bin` is the stock OpenWrt 18.06.9 TL-WR841 v11 image with the agent baked into the
squashfs and enabled at boot. **Nothing in this repository flashes it, and it must not be flashed until
every item below is satisfied and you approve:**

- [ ] `weshah-preflight.sh` run on the actual unit: `board_name=tl-wr841n-v11`, model `TL-WR841N/ND v11`
      (the unit's hardware revision is printed on its label — a v10/v12 board with a v11 filename is the classic brick).
- [ ] The unit already runs OpenWrt (the image has no valid TP-Link MD5, so the stock web UI and the TP-Link
      bootloader recovery will reject it; only OpenWrt's `sysupgrade` accepts it).
- [ ] A **tested** recovery path exists: UART access (3.3 V TTL, U-Boot console) or a known-good way to
      TFTP-restore the stock/OpenWrt image, plus a copy of the `art` (Wi-Fi calibration) and `u-boot`
      partitions read with `dd if=/dev/mtd<N> | gzip` — `sysupgrade` leaves them untouched, but a copy costs nothing.
- [ ] `sha256sum -c SHA256SUMS` passes on the machine that will do the flash.
- [ ] The overlay shrinks from 256 KiB to 192 KiB with this image; confirm that is enough for your configuration.
- [ ] Explicit approval for this specific device.

The command, once approved, is OpenWrt's own (keeps config, refuses a wrong hardware id):
`sysupgrade -v /tmp/Weshah-firmware.bin` (use `-n` only if you want a factory reset — not the default).
`sysupgrade -T` (test only, writes nothing) can be run first.
