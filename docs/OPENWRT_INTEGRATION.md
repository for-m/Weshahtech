# OpenWrt Integration Guide

## Connection Methods

### Method 1: weshah-agent (Recommended)

Install the WESHAH agent on your OpenWrt router for full feature support including bandwidth control.

**Requirements:**
- OpenWrt 21.02+ with nftables OR iptables
- `tc` (traffic control) — included in OpenWrt base
- `hostapd-utils` for Wi-Fi deauth
- `nc` (netcat) for the HTTP server

**Installation:**
```sh
# From your computer
scp weshah-agent/src/weshah-agent root@192.168.1.1:/usr/sbin/
scp weshah-agent/etc/init.d/weshah-agent root@192.168.1.1:/etc/init.d/
ssh root@192.168.1.1 << 'EOF'
chmod +x /usr/sbin/weshah-agent /etc/init.d/weshah-agent
/etc/init.d/weshah-agent enable
/etc/init.d/weshah-agent start
EOF
```

**Verify:**
```sh
curl http://192.168.1.1:8765/api/status -H "Authorization: Bearer $(cat /etc/weshah-agent.conf | grep TOKEN | cut -d= -f2)"
```

**API Endpoints:**

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/auth/login` | Exchange credentials for token |
| GET | `/api/status` | Router info, WAN status, CPU/RAM |
| GET | `/api/devices` | All known devices |
| POST | `/api/devices/{mac}/speed` | Set speed limit |
| DELETE | `/api/devices/{mac}/speed` | Remove speed limit |
| POST | `/api/devices/{mac}/block` | Block internet access (WAN only) |
| DELETE | `/api/devices/{mac}/block` | Unblock internet access |
| POST | `/api/devices/{mac}/disconnect` | Deauth from Wi-Fi |

**Speed limit request body:**
```json
{"downloadKbps": 5120, "uploadKbps": 1024}
```

**Block request body:**
```json
{"expiresAt": null}
```

### Method 2: Raw ubus (Fallback)

The app connects via ubus JSON-RPC at `http(s)://<ip>/ubus`.

**Supported operations via raw ubus:**
- ✅ Router info (system.board, system.info)
- ✅ DHCP leases (luci-rpc or uci show dhcp)
- ✅ Connected clients (iwinfo, hostapd)
- ✅ Internet blocking (UCI firewall rules)
- ❌ Bandwidth control — requires weshah-agent
- ❌ Per-device traffic stats — requires nlbw (not standard)

**Required OpenWrt packages:**
```
opkg install rpcd rpcd-mod-rpcsys uhttpd luci-mod-rpc
```

## Speed Limit Implementation

Speed limits use Linux Traffic Control (tc) with HTB (Hierarchical Token Bucket):

```
# Packet marking (nftables)
nft add rule bridge filter FORWARD ether saddr {mac} meta mark set {mark}

# Download rate limit (on WAN egress)
tc qdisc add dev eth1 root handle 1: htb default 999
tc class add dev eth1 parent 1: classid 1:{mark} htb rate {down}kbit ceil {down}kbit
tc filter add dev eth1 parent 1: handle {mark} fw classid 1:{mark}

# Upload rate limit (on LAN bridge)
tc qdisc add dev br-lan root handle 1: htb default 999
tc class add dev br-lan parent 1: classid 1:{mark} htb rate {up}kbit ceil {up}kbit
tc filter add dev br-lan parent 1: handle {mark} fw classid 1:{mark}
```

Rules persist across:
- App close: ✅ (rules live in kernel)
- App uninstall: ✅ (rules live in kernel)
- Router reboot: ✅ (UCI firewall rules + weshah-agent restores on startup via rc.local)
- Phone reboot: ✅ (rules are on the router, not the phone)

## Firewall Block Implementation

Blocks use UCI firewall rules for persistence:

```sh
uci set firewall.weshah_block_{mac_underscore}=rule
uci set firewall.weshah_block_{mac_underscore}.src=lan
uci set firewall.weshah_block_{mac_underscore}.dest=wan
uci set firewall.weshah_block_{mac_underscore}.src_mac={mac}
uci set firewall.weshah_block_{mac_underscore}.target=REJECT
uci commit firewall
/etc/init.d/firewall reload
```

**LAN access is preserved** — only WAN (internet) is blocked. The device can still reach the router admin panel and other local devices.
