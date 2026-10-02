# Hardware Capability Matrix

This document lists which router hardware/PHY combinations support each feature.
The app detects capabilities at runtime via `RouterAdapter.detectCapabilities()`.
**Never assume a capability — always detect it.**

---

## Cable Diagnostics (TDR)

TDR (Time-Domain Reflectometry) measures cable length and detects opens/shorts at the pair level.
Most consumer/SMB routers do NOT support hardware TDR.

| Router / PHY | TDR | Basic (link only) | Notes |
|---|---|---|---|
| TP-Link Archer / QCA8337 | ❌ | ✅ | No TDR register access |
| TP-Link Archer / QCA8075 | ❌ | ✅ | Vendor QDSS required |
| GL.iNet / MediaTek MT7621 | ❌ | ✅ | MT7621 switch has no TDR |
| GL.iNet / MediaTek MT7531 | ❌ | ✅ | MT7531 has no TDR |
| Turris Omnia / Marvell 88E6176 | ✅ | ✅ | Full TDR via ethtool |
| EdgeRouter / Marvell 88E6171 | ✅ | ✅ | Full TDR via ethtool |
| Lantiq VGV752 / GSWIP-2.2 | ✅ | ✅ | Native GSWIP TDR support |
| Realtek RTL8306E | ✅ | ✅ | Via realtek-poe driver |
| Broadcom BCM53125 | ⚠️ | ✅ | Possible via b53, untested |
| Netgear R7800 / QCA9984 | ❌ | ✅ | QCA ath10k, no TDR |

**UI rule:** If `cableDiagnosticsTdr == false`, show only link status (speed + duplex + CRC errors)
and display `unsupportedReason` from `CableDiagResult`. Never show a length estimate.

---

## Port Statistics

| Feature | Requires | Notes |
|---------|---------|-------|
| Per-port RX/TX bytes | `portStats == true` | Via `network.device` ubus or agent |
| Per-port error counters | `portStats == true` | RX/TX errors, drops, CRC |
| Link flap counter | `portStats == true` | Count of link up/down transitions |
| Connected MAC per port | `portStats + weshahAgent` | Needs agent bridge table query |
| Per-port VLAN assignment | `portStats + vlanManagement` | |

---

## Bandwidth Control

| Method | Requires | How | Persistent |
|--------|---------|-----|-----------|
| Per-client speed limit | `bandwidthControl == true` | nftables + tc via agent | ✅ Survives reboot |
| Block client | Any (ubus fallback) | firewall UCI rule | ✅ Survives reboot |
| Disconnect client | `weshahAgent` | hostapd_cli deauth | ❌ Until next association |
| Schedule (time-based) | `weshahAgent` | cron + nftables | ✅ Survives reboot |

**Without weshah-agent:** `bandwidthControl == false`. Speed limits return `NOT_SUPPORTED`.
Block is still possible via ubus/UCI firewall rules but has no scheduling or expiry.

---

## WiFi

| Feature | Requires | Notes |
|---------|---------|-------|
| RSSI per client | `wifiRssiPerClient == true` | Via `iwinfo` or hostapd |
| TX/RX rate per client | `wifiRssiPerClient == true` | iw station dump |
| Multi-SSID | `wifiMultiSsid == true` | UCI wireless config |
| Channel scan | `wifiChannelScan == true` | `iw dev scan` (30s delay) |
| 5 GHz clients | Always | If radio0/radio1 both present |

---

## VLAN

| Feature | Requires |
|---------|---------|
| List VLANs | `vlanManagement == true` |
| Create/edit/delete VLAN | `vlanManagement == true` + `weshahAgent` |
| VLAN tagging on switch ports | `vlanManagement == true` + swconfig or DSA |

**Note:** OpenWrt 21.02+ uses DSA (Distributed Switch Architecture) instead of swconfig.
The agent must abstract this difference; the app does not call swconfig directly.

---

## Multi-WAN

| Feature | Requires |
|---------|---------|
| List WAN interfaces | `multiWan == true` + `weshahAgent` |
| Per-WAN traffic stats | `multiWan == true` + `weshahAgent` |
| WAN health (latency/loss) | `multiWan == true` + `weshahAgent` |
| Load balancing config | Not implemented (read-only) |

---

## LLDP Neighbors

| Requirement | Detail |
|-------------|--------|
| Package | `lldpd` must be installed and running |
| Capability flag | `lldpNeighbors == true` |
| Discovery time | LLDP advertises every 30s; allow 60s after startup |
| Confidence level | `CONFIRMED` (from LLDP), vs `INFERRED` (ARP/DHCP heuristics) |

---

## Temperature Sensor

| Router SoC | Sensor | Access |
|------------|--------|--------|
| QCA IPQ806x | Thermal zone | `/sys/class/thermal/thermal_zone0/temp` |
| MediaTek MT7621 | No sensor | — |
| MediaTek MT7622 | Thermal zone | `/sys/class/thermal/thermal_zone0/temp` |
| Marvell Armada | Multiple zones | hwmon driver |
| Intel Atom (EdgeRouter) | x86 sensors | lm-sensors |

**Capability detection:** The agent probes `/sys/class/thermal/thermal_zone0/temp` and sets
`temperature = true` if readable. If the value is 0 or missing, `temperature = false`.

---

## Config Backup

| Requirement | Detail |
|-------------|--------|
| Requires | `configBackup == true` + `weshahAgent` |
| Format | OpenWrt `sysupgrade -b` tar.gz (LuCI-compatible) |
| Storage | Encrypted on device, checksum verified |
| Restore | Not auto-applied; requires manual confirmation + reboot |

---

## WESHAH Agent Requirement Summary

Features that **require** weshah-agent (not available via raw ubus):

- Bandwidth control (tc/nftables)
- Per-device traffic counters (nlbw/conntrack)
- Cable diagnostics (when TDR supported)
- Full health check (latency probes, DNS test)
- Config backup/restore
- VLAN CRUD
- Multi-WAN status
- Device schedule (time-based access)
- WiFi clients (all radios, not just wlan0)
- LLDP neighbors (lldpd integration)
- Static DHCP lease management

Features that work **without** agent (raw ubus):

- Router connection + authentication
- System info (model, firmware, uptime)
- DHCP lease list
- Internet block via UCI firewall rule
- WAN status (single WAN)
- WiFi radios (basic, phy0 only)
- System stats (CPU, RAM — limited accuracy)
