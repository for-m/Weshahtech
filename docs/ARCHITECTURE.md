# WESHAH Network Manager — Architecture

## Module Structure

```
┌─────────────────────────────────────────────────────────┐
│                        :app                              │
│  MainActivity, WeshahApp, Navigation, AppModule          │
└───────────────────┬─────────────────────────────────────┘
                    │ depends on
         ┌──────────▼──────────┐
         │        :ui           │
         │  Compose screens,    │
         │  ViewModels, Theme   │
         └──────────┬──────────┘
                    │
         ┌──────────▼──────────┐
         │      :domain         │
         │  Repository          │
         │  interfaces          │
         └──┬──────────────┬───┘
            │              │
   ┌────────▼──────┐  ┌────▼────────────────┐
   │    :data       │  │   :router-api        │
   │  Room DB,      │  │  RouterAdapter       │
   │  Repo impls    │  │  interface           │
   └────────┬──────┘  └────┬────────────────┘
            │              │ implemented by
            │         ┌────▼────────────────┐
            │         │  :router-openwrt      │
            │         │  OpenWrtAdapter,      │
            │         │  WeshahAgentClient,   │
            │         │  CredentialManager    │
            │         └─────────────────────┘
            │
   ┌────────▼──────────────┐
   │   :network-discovery   │
   │  NetworkScanner,       │
   │  DeviceFingerprinter   │
   └───────────────────────┘
            │
   ┌────────▼──────────────┐
   │        :core           │
   │  Models, Utils, OUI DB │
   └───────────────────────┘
```

## Key Design Decisions

### RouterAdapter Pattern (Strategy/Bridge)
The `RouterAdapter` interface in `:router-api` completely decouples the UI and data layers from any specific router implementation. Adding support for a new router type (MikroTik, Ubiquiti, AminLink) only requires:
1. Implementing `RouterAdapter`
2. Adding a Hilt binding

The UI never calls router-specific APIs directly.

### Bandwidth Control on the Router
Speed limits are written to the router's persistent configuration (tc + nftables HTB rules) via `weshah-agent`. When the app is closed, killed, or the phone is rebooted, the rules continue to run on the router. The phone is a management console, not a traffic shaper.

### weshah-agent Auto-detection
`OpenWrtAdapter` probes `GET /weshah/api/status` on connect. If it responds with the expected JSON, weshah-agent mode is enabled. Otherwise, it falls back to raw ubus. This means the app works with plain OpenWrt out of the box (limited feature set) and gets full features when weshah-agent is installed.

### Credential Security
Credentials are stored in Android Keystore with AES-256-GCM encryption. The key is hardware-backed (TEE/SE) when available and never leaves the secure enclave. The plaintext password is only held in memory during the initial connection handshake.

## Data Flow — Device Discovery

```
triggerScan()
    │
    ├── RouterAdapter.getDhcpLeases()     ← router DHCP table (authoritative IP-MAC)
    ├── RouterAdapter.getConnectedClients() ← active sessions
    │
    ├── NetworkScanner.scanNetwork()
    │       ├── readArpCache()            ← /proc/net/arp (instant)
    │       └── parallel TCP probe        ← semaphore(64) goroutine-style
    │
    └── DeviceFingerprinter.identify()    ← mDNS + ports + hostname + OUI
            │
            └── NetworkDeviceDao.upsertAll() ← Room DB, preserves custom names
```

## Data Flow — Speed Limit

```
UI: SpeedLimitDialog → setClientSpeedLimit(mac, downKbps, upKbps)
    │
    └── RouterAdapter.setClientSpeedLimit()
            │
            ├── weshah-agent path:
            │   POST /api/devices/{mac}/speed
            │   → agent: tc HTB + nftables mark → persists across reboot
            │
            └── raw ubus fallback:
                returns RouterResult.Error(NOT_SUPPORTED)
                "Bandwidth control requires weshah-agent"
```
