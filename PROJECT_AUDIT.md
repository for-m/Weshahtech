# WESHAH Network Manager — Project Audit

**Date:** 2026-10-02  
**Branch:** `claude/weshah-network-manager-1xs505`  
**Audited by:** Claude Code (automated + manual review)

---

## Module Structure

| Module | Purpose | Status |
|--------|---------|--------|
| `:app` | Entry point, Hilt setup, Navigation | ✅ Working |
| `:core` | Domain models, utilities, OUI database | ✅ Complete |
| `:domain` | Repository interfaces, use cases | ✅ Interfaces defined |
| `:data` | Room DB, repository implementations | ⚠️ Partial (see below) |
| `:router-api` | RouterAdapter interface + data types | ✅ Complete (v2) |
| `:router-openwrt` | OpenWrt/WESHAH adapter implementation | ✅ Complete (v2) |
| `:network-discovery` | Network scanner, device fingerprinting | ✅ Working |
| `:ui` | All Compose screens and ViewModels | ⚠️ Partial (see below) |

---

## Capability Detection System

The `RouterCapabilities` data class is the central gate for all hardware-dependent features.
**Rule:** No UI screen or ViewModel may call any router operation without first checking the
relevant capability flag. Operations on unsupported hardware return
`RouterResult.Error(NOT_SUPPORTED, ...)` — never fake data.

### Capability Flags

| Flag | Meaning | Gate Check Required |
|------|---------|---------------------|
| `weshahAgent` | WESHAH agent v2 running on port 8765 | All advanced features |
| `bandwidthControl` | tc/nftables speed limiting via agent | setClientSpeedLimit |
| `perClientTraffic` | Per-device byte counters (nlbw/conntrack) | getClientTrafficStats |
| `portStats` | Ethernet port statistics | getPortStats |
| `cableDiagnosticsTdr` | Hardware TDR (PHY-level) | runCableDiagnostics full |
| `cableDiagnosticsBasic` | Link speed/duplex only | runCableDiagnostics basic |
| `wifiRssiPerClient` | Per-client RSSI | getWifiClients |
| `vlanManagement` | VLAN CRUD via agent | getVlans, createVlan, etc. |
| `multiWan` | Multi-WAN load balancing | getMultiWanInterfaces |
| `dhcpStaticLeases` | DHCP reservations | createStaticLease |
| `lldpNeighbors` | LLDP topology discovery | getLldpNeighbors |
| `configBackup` | Config export/import via agent | createConfigBackup |
| `hotspot` | Hotspot/subscriber management | HotSpot features |
| `temperature` | Thermal sensor available | temperature display |

### PHY Driver Matrix (Cable Diagnostics)

| PHY Driver | TDR Support | Notes |
|------------|-------------|-------|
| `ATHEROS_AR8327` | ❌ | No ethtool TDR exposure |
| `ATHEROS_AR8216` | ❌ | No ethtool TDR exposure |
| `QUALCOMM_QCA8075` | ⚠️ | Vendor-specific registers |
| `QUALCOMM_QCA8337` | ⚠️ | Vendor-specific registers |
| `MEDIATEK_MT7531` | ❌ | No standard TDR interface |
| `MEDIATEK_MT7621` | ❌ | No standard TDR interface |
| `BROADCOM_BCM53XX` | ⚠️ | Possible via b53 driver |
| `LANTIQ_GSWIP` | ✅ | GSWIP-2.2 supports TDR |
| `REALTEK_RTL83XX` | ✅ | Via realtek-poe driver |
| `MARVELL_88E6XXX` | ✅ | Full TDR via ethtool |
| `UNKNOWN` | ❌ | Conservative default |

---

## Bug Fixes Applied (This Session)

### Crash-Level

| File | Bug | Fix |
|------|-----|-----|
| `DeviceDetailScreen.kt:48` | `savedStateHandle["mac"]` — NavHost argument is `"macAddress"` → always null → NPE | Changed to `savedStateHandle["macAddress"]` |
| `app/build.gradle.kts` | Wrong catalog aliases: `libs.compose.bom`, `libs.compose.ui`, `libs.navigation.compose` — catalog defines `libs.androidx.compose.bom` etc. | Fixed to use correct `androidx.*` prefixed aliases |

### Logic Errors

| File | Bug | Fix |
|------|-----|-----|
| `DashboardViewModel.kt:73` | `blocked` computed as online devices with no subscriber — wrong metric | Removed dead variable; blocked count already flows from `getBlockedCount()` |
| `DashboardViewModel.kt:97` | `observeRouterStats()` only subscribes if already CONNECTED at ViewModel creation — misses late connects | Rewrote with `flatMapLatest` on connection state |
| `DevicesViewModel.kt:50,53` | BLOCKED filter → always false; FAVORITES filter → always false | Fixed to use `device.isBlocked` / `device.isFavorite` |
| `NetworkDeviceEntity.toModel()` | `isBlocked` and `isFavorite` existed on entity but were not passed to domain model | Added both fields to `NetworkDevice.toModel()` |
| `NetworkDevice.kt` | Missing `isBlocked: Boolean` and `isFavorite: Boolean` fields | Added with default `false` |
| `OpenWrtAdapter.kt:111` | Ubus session ID parsing: `moshi.fromJson(it.toString())` on already-decoded Map → always null | Cast directly: `(result?.getOrNull(1) as? Map<*,*>)?.get("ubus_rpc_session")` |
| `UbusApiService.kt` | `AgentRouterStatus` field names differ from shell script output: `uptime`→`uptimeSeconds`, no `wanState`, `temperature`→`temperatureCelsius`, no `ramUsedKb` | Updated to match shell output; OpenWrtAdapter was already written for new names |

---

## Known Gaps (Not Yet Implemented)

### Agent v2 Backend
The shell-based `weshah-agent` needs new endpoints to match `WeshahAgentApiService`:
```
GET  /api/v1/capabilities       → AgentCapabilitiesResponse
GET  /api/v1/ports              → AgentPortList
GET  /api/v1/ports/{id}/diagnostics → AgentCableDiagResult
GET  /api/v1/wifi               → AgentWifiResponse
GET  /api/v1/wifi/clients       → AgentWifiClientList
GET  /api/v1/vlans              + POST/PUT/DELETE
GET  /api/v1/lldp               → AgentLldpResponse
GET  /api/v1/health             → AgentHealthReport
POST /api/v1/backup             → binary config tar
GET  /api/v1/multi-wan          → AgentMultiWanResponse
GET  /api/v1/dhcp/static        + POST/DELETE
POST /api/v1/devices/{mac}/schedule + DELETE
DELETE /api/v1/devices/{mac}/speed
```
All existing v1 paths (`/api/auth/login` → `/api/v1/auth/login`) are also renamed.

### UI Screens — Pending
| Screen | Status | Note |
|--------|--------|------|
| Port Manager | ❌ Not built | Needs capability gate |
| Cable Diagnostics | ❌ Not built | Must show "Not Supported" if !cableDiagnosticsTdr |
| WiFi Analyzer | ❌ Not built | Channel scan + clients |
| VLAN Manager | ❌ Not built | Gated on vlanManagement |
| DHCP Manager | ❌ Not built | Static leases UI |
| Multi-WAN | ❌ Not built | Gated on multiWan |
| Network Health | ❌ Not built | Requires agent |
| Network Topology | ❌ Not built | LLDP + ARP inferred |
| Network Timeline | ❌ Not built | Event stream |
| Config Backup | ❌ Not built | Gated on configBackup |
| Technician Mode | ❌ Not built | 15-step automated check |
| Security Audit | ❌ Not built | |
| NOC Dashboard | ❌ Not built | |
| Subscriber Detail | ❌ Not built | TODO in MainActivity |
| Settings (persistent) | ❌ DataStore not wired | In-memory only |

### Feature Gaps in Existing Screens
| Feature | Gap | Resolution |
|---------|-----|------------|
| Block/unblock | Router adapter called but DB not updated → UI reverts after scan | Add `deviceDao.updateBlocked(mac, blocked)` in ViewModel after router call |
| Subscriber expiry | Sets Room status EXPIRED but no router block | `checkAndExpireSubscribers()` must call `blockClient()` |
| Auto-scan | WorkManager dep declared, never scheduled | Wire `autoScanEnabled`/interval from Settings to WorkManager |
| Subscriber MAC assignment | `macAddresses` always empty list | Build MAC assignment UI → `assignSubscriber()` DAO exists |
| Per-device traffic on ubus path | Always returns empty | Requires nlbw or conntrack; correctly gated by `perClientTraffic` capability |

---

## Implementation Roadmap

### Phase 1 — In Progress (Architecture & Core Layer)
- [x] RouterCapabilities model
- [x] RouterAdapter v2 interface
- [x] OpenWrtAdapter v2 implementation
- [x] UbusApiService / WeshahAgentApiService v2
- [x] Core model expansion (Port, Cable, VLAN, Health, Alert, Topology)
- [ ] weshah-agent v2 shell backend

### Phase 2 — Data & Engine Layer
- [ ] Room migration: add `isBlocked`/`isFavorite` schema version bump
- [ ] AlertEngine — generate alerts from events and metrics
- [ ] HealthEngine domain interface + impl
- [ ] TopologyEngine (LLDP + ARP + DHCP heuristics)
- [ ] EventEngine — parse RouterEventStream → insert NetworkEvents
- [ ] SettingsRepository (DataStore) — persist all settings
- [ ] WorkManager background scan

### Phase 3 — New UI Screens
- [ ] Port Manager screen
- [ ] Cable Diagnostics screen (TDR vs basic, honest "Not Supported" flow)
- [ ] WiFi Analyzer screen
- [ ] Internet Health / Latency monitor
- [ ] Network Topology screen
- [ ] VLAN Manager screen
- [ ] DHCP Manager screen
- [ ] Multi-WAN screen
- [ ] Network Timeline / Events
- [ ] Config Backup & Restore
- [ ] Subscriber Detail + MAC assignment
- [ ] Settings (DataStore-backed)
- [ ] Technician Mode (15-step check)
- [ ] Security Audit
- [ ] NOC Dashboard

### Phase 4 — Testing & Documentation
- [ ] Unit tests: repository layer, validators, parsers
- [ ] Integration tests: adapter mock server
- [ ] HARDWARE_CAPABILITIES.md
- [ ] WESHAH_AGENT.md (v2 protocol spec)
- [ ] CABLE_DIAGNOSTICS.md
- [ ] TOPOLOGY.md

---

## Security Notes

- **Credentials:** All passwords stored via Android Keystore + AES-256-GCM. Never in SharedPreferences or plain text.
- **No shell injection:** No user input is ever interpolated into shell commands. Only structured API calls.
- **Input validation:** IpUtils, MacUtils validate all addresses. VLAN IDs, ports, speed values must be validated before calling adapter.
- **SSL:** Agent uses plain HTTP on port 8765 (LAN-only). HTTPS with self-signed cert acceptance for LUCI on 443 (within LAN).
- **TLS bypass:** The current `trustAll` X509TrustManager is acceptable for private LAN routers only. Must NOT be used for any public internet endpoint.

---

## Data Integrity Rules

1. **No fake data.** If a router doesn't support a feature, `RouterResult.Error(NOT_SUPPORTED)` is returned. The UI shows "Not supported by hardware."
2. **Capability gate first.** Every hardware-dependent operation checks `RouterCapabilities` before executing.
3. **Real measurements only.** `NetworkHealthReport`, `CableDiagResult`, latency probes — all real. No synthesized values.
4. **Supported flag in CableDiagResult.** `CableDiagResult.supported = false` + `unsupportedReason` when TDR unavailable. UI must display the reason, not show a length measurement.
