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

Install on your OpenWrt router to enable full bandwidth control:

```sh
scp weshah-agent/src/weshah-agent root@192.168.1.1:/usr/sbin/
scp weshah-agent/etc/init.d/weshah-agent root@192.168.1.1:/etc/init.d/
ssh root@192.168.1.1 "chmod +x /usr/sbin/weshah-agent /etc/init.d/weshah-agent && /etc/init.d/weshah-agent enable && /etc/init.d/weshah-agent start"
```

The app auto-detects weshah-agent at `http://<router-ip>:8765/weshah/`. Without the agent, device listing still works via ubus but bandwidth control is unavailable.

## Security

- Passwords are encrypted with AES-256-GCM in Android Keystore (hardware-backed when available)
- weshah-agent uses Bearer token authentication (auto-generated on first run)
- All MAC, IP, and speed parameters are validated before use
- Network Security Config restricts cleartext to local subnet IPs only
- weshah-agent validates all input before executing any system calls

## Android Limitations

See [ANDROID_LIMITATIONS.md](docs/ANDROID_LIMITATIONS.md) for details on ICMP restrictions and workarounds.
