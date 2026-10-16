# Android Network Limitations

## ICMP Ping (Raw Sockets)

**Problem:** True ICMP ping requires `CAP_NET_RAW`. Android does not grant this to normal apps.

**What `InetAddress.isReachable()` actually does on Android:**
- Without root: falls back to TCP echo on port 7 (RFC 862)
- Most modern hosts do NOT respond to port 7
- Result: `isReachable()` returns `false` even when the host is online

**Our workaround:**
1. Call `InetAddress.isReachable(2000)` first
2. If false, probe TCP ports 80 and 443 with a 1-second timeout
3. Report timing and "ICMP blocked" note when TCP succeeds

**Code location:** `ui/src/main/kotlin/com/weshah/ui/tools/ToolsScreen.kt` — `ToolsViewModel.ping()`

## Traceroute — NOT IMPLEMENTED

Traceroute requires sending IP packets with TTL=1,2,3,... and reading ICMP Time Exceeded replies. Both sending custom TTL packets and reading ICMP requires `CAP_NET_RAW`.

**Not available without root.** The Tools screen shows an explicit "NOT IMPLEMENTED" notice.

## ARP Cache Reading

Reading `/proc/net/arp` works on all Android versions without root. This is the primary discovery method for devices already seen by the router's ARP table.

## Network Discovery Without Root

The scanner uses three methods in order:
1. ARP cache (`/proc/net/arp`) — instant, no permissions
2. `InetAddress.isReachable()` — TCP echo fallback
3. TCP port probing — connects to common ports (22, 80, 443, 554, 8080, etc.)

**Permission required for accurate subnet detection on Android 13+:** `NEARBY_WIFI_DEVICES` (no location permission needed with `neverForLocation` flag).

## Wi-Fi Scan Results

The `WifiManager.getScanResults()` API is throttled by Android OS:
- Android 9+: once per 2 minutes per app in the foreground, 30 minutes in background
- Results may be stale

The app uses `ConnectivityManager.getLinkProperties()` to get the current subnet without triggering a scan.
