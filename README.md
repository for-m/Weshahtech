# WESHAH Network Manager — وشاح لإدارة الشبكات

Professional Android network management application for WESHAH routers and standard OpenWrt devices.

## Features

- **Real-time device discovery** — ARP cache + TCP/ICMP probing, no fake data
- **Router management** — Connect to OpenWrt/WESHAH routers via weshah-agent REST API or raw ubus
- **Bandwidth control** — Speed limits persist on the router (tc + nftables HTB), survive app close/reboot
- **Internet blocking** — Block WAN access per device while preserving LAN; persists via UCI firewall rules
- **Subscriber management** — Bind devices to subscribers with speed profiles and expiry
- **Network tools** — Ping, DNS lookup, port scan
- **Security** — Credentials stored in Android Keystore (AES-256-GCM), never as plain text

## Architecture

```
app/                    ← Android application module (navigation, DI entry point)
core/                   ← Shared models, utils (no Android dependencies)
domain/                 ← Repository interfaces
router-api/             ← RouterAdapter interface (decouples UI from router type)
router-openwrt/         ← OpenWrt adapter + weshah-agent client + credential manager
network-discovery/      ← ARP cache reader + TCP/ICMP scanner + device fingerprinter
data/                   ← Room database, repository implementations
ui/                     ← Compose screens, ViewModels
weshah-agent/           ← Lightweight OpenWrt shell agent (REST API on port 8765)
```

See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed module structure.

## Building

Requirements: Android Studio Iguana+, JDK 17, Android SDK 35.

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

## weshah-agent Setup

Install the package on your OpenWrt router (check compatibility first, nothing is written by the check):

```sh
scp weshah-agent/preflight/weshah-preflight.sh root@192.168.1.1:/tmp/ && ssh root@192.168.1.1 sh /tmp/weshah-preflight.sh
scp weshah-agent_2.1.0-1_all.ipk root@192.168.1.1:/tmp/ && ssh root@192.168.1.1 'opkg install /tmp/weshah-agent_2.1.0-1_all.ipk'
```

The app logs in with the router's own username/password at `http://<router-ip>:8765/api/v1/auth/login` and receives the agent token (the token itself is in `/etc/weshah-agent.conf`, root only). Details and API:
[docs/OPENWRT_INTEGRATION.md](docs/OPENWRT_INTEGRATION.md), procedure and rollback:
[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md). Builds and tests: the `Weshah CI` workflow
(`.github/workflows/weshah-ci.yml`) builds the debug APK and runs the agent against the real
OpenWrt 18.06.9 userland.

## Security

- Passwords are encrypted with AES-256-GCM in Android Keystore (hardware-backed when available)
- weshah-agent uses Bearer token authentication (auto-generated on first run)
- All MAC, IP, and speed parameters are validated before use
- Network Security Config restricts cleartext to local subnet IPs only
- weshah-agent validates all input before executing any system calls

## Android Limitations

See [ANDROID_LIMITATIONS.md](docs/ANDROID_LIMITATIONS.md) for details on ICMP restrictions and workarounds.
