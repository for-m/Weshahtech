# OpenWrt integration — weshah-agent 2.1

The agent is a set of POSIX shell modules served by the router's stock `uhttpd` as a CGI
(`/api`, port 8765, LAN + loopback only). No `nc`, no extra daemon besides a 30-second
reconcile ticker. Tested on OpenWrt 18.06.9 ar71xx/tiny (BusyBox 1.28.4, fw3/iptables); the code
also has nft and DSA paths that are exercised in the kernel test suite and in CI but **not**
on real hardware.

## Install

Prerequisite check (read-only, run on the router): `weshah-agent/preflight/weshah-preflight.sh`.

```sh
scp weshah-agent_2.1.0-1_all.ipk root@192.168.1.1:/tmp/
ssh root@192.168.1.1 'opkg install /tmp/weshah-agent_2.1.0-1_all.ipk'
```

`opkg` resolves `uhttpd jsonfilter jshn uci ubus rpcd iw` from the feed if one of them is missing.
The app does not need the token pasted: it logs in with the router's own credentials (`/auth/login`) and receives it. First start generates a 256-bit token from `/dev/urandom`; if that fails the agent refuses to
start instead of using a weak token. The token and `/etc/weshah-agent/` survive `sysupgrade`
(`/lib/upgrade/keep.d/weshah-agent`). See `docs/DEPLOYMENT.md` for the full procedure.

## API (all under `/api/v1`, header `Authorization: Bearer <token>`)

| Method | Path | Notes |
|---|---|---|
| POST | `/auth/login` | only unauthenticated route; ubus session login, 401 + 1 s delay on failure |
| GET | `/status` `/capabilities` `/interfaces` `/multi-wan` `/health` | `capabilities` carries `reasons` for every unsupported feature |
| GET | `/devices`, `/devices/{mac}/traffic` | leases + ARP + Wi-Fi + firewall counters (64-bit safe) |
| GET/POST/DELETE | `/devices/{mac}/speed` | tc htb; `501`-style "unsupported" with a reason when `tc`/kernel modules are missing |
| GET/POST | `/devices/{mac}/block`, POST `/devices/{mac}/unblock` | UCI rules `weshah_block_<mac>`, optional `expiresAt` (s or ms) |
| GET/POST/DELETE | `/devices/{mac}/schedule` | allowed windows, ISO weekdays (1=Mon), end exclusive, overnight wraps, never acts on an unsynced clock |
| POST | `/devices/{mac}/disconnect` | hostapd `del_client` → `hostapd_cli` → `iw station del` |
| GET/POST/DELETE | `/dhcp/static[/{mac}]` | UCI host sections, IP must be inside the LAN |
| GET | `/ports`, `/ports/{n}/diagnostics`, `/wifi`, `/wifi/clients` | read-only |
| GET/POST/PUT/DELETE, POST `/vlans/confirm` | `/vlans[/{id}]` | swconfig and DSA; id 2–4094; **commit-confirm**: reverts by itself unless confirmed |
| POST | `/backup` | `sysupgrade -b` + manifest (board, release) |
| POST | `/restore/validate`, `/restore/apply`, `/restore/confirm` | validate (size, gzip, tar safety, board) → apply by sha256 → auto-rollback if not confirmed after the reboot |
| GET | `/lldp` | `501` — not implemented |

Error mapping: 400 bad input, 401 auth, 404 unknown, 409 conflict, 413 body too large, 501
unsupported, 503 agent not configured. Every unsupported feature returns a reason; nothing is faked.

## What it changes on the router

UCI only (`firewall`, `dhcp`, `network`, section names prefixed `weshah_`), runtime `tc`/firewall
counters, `/etc/weshah-agent/`, `/tmp/weshah-agent/`. It never writes firmware or MTD partitions.
Only `weshah_*` sections are ever modified or deleted; foreign `tc` qdiscs are left alone.

## Known limits

- Speed limits need `tc` + `kmod-sched-core`/`kmod-sched` (htb, u32, mirred) + a mark match. The stock
  18.06.9 ar71xx/tiny image has **none** of them and the 4 MB flash cannot take them, so the
  capability reports unsupported with the reason (verified on the real image userland).
- Wi-Fi SSID/password management, hotspot cards and LLDP are not implemented in the agent.
- VLAN `ssid` and `clientIsolation` fields are rejected with `unsupported_field:*`.
