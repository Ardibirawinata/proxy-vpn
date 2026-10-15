# Proxy VPN

A lightweight Android app that routes **all of your device's TCP traffic** through a
**SOCKS5** or **HTTP (CONNECT)** proxy, using Android's built-in `VpnService`.

No root, no native libraries, no third-party dependencies — just plain Kotlin.

> 🇮🇩 **Ringkasan:** Aplikasi Android untuk mengalirkan seluruh koneksi internet HP lewat
> proxy SOCKS5 / HTTP. Isi host, port, (opsional) username & password, lalu tekan **Connect**.
> Bisa juga dinyalakan/dimatikan dari tile di panel notifikasi.

---

## Features

- **SOCKS5 and HTTP proxy** support, with optional username/password authentication
- **Device-wide** — every app's TCP traffic goes through the proxy, not just the browser
- **DNS through the proxy** — domain names are resolved on the proxy side, so your local
  DNS doesn't leak
- **IPv6 leak protection** — IPv6 traffic is captured and dropped so apps fall back to IPv4
  through the proxy
- **Proxy test on connect** — shows your public (exit) IP once connected
- **Quick Settings tile** — toggle the VPN from the notification panel; long-press the tile
  to open the app
- **Live reconnect** — change settings while connected and tap **Save** to reconnect
  without turning the VPN off
- Status in the app, tile and a persistent notification
  (Disconnected / Connecting / Connected / Paused / Proxy error)

## Requirements

| | |
|---|---|
| Android | 7.0 (API 24) or newer |
| Proxy | A working SOCKS5 or HTTP proxy that supports `CONNECT` |

## Installation

### Option 1 — Download the APK

If an APK is available on the [Releases](../../releases) page, download it to your phone
and install it (you may need to allow *Install unknown apps*).

### Option 2 — Build from source

You need **JDK 17** and the **Android SDK** (Android Studio includes both).

```bash
git clone https://github.com/Ardibirawinata/proxy-vpn.git
cd proxy-vpn
./gradlew assembleRelease
```

On Windows use `gradlew.bat assembleRelease`.

The APK is created at `app/build/outputs/apk/release/app-release.apk`.

> The release build is signed with the debug key so it can be installed directly.
> Use your own signing key before publishing it to a store.

Alternatively, open the project in Android Studio and press **Run**.

## How to use

1. Open **Proxy VPN**.
2. Pick the proxy type: **SOCKS5** or **HTTP / HTTPS (CONNECT)**.
3. Enter the **host** (e.g. `proxy.example.com` or `1.2.3.4` — without `http://`) and **port**.
4. If your proxy needs a login, turn on **Use username & password** and fill them in.
5. Tap **Connect** and accept Android's VPN permission prompt (first time only).
6. When the status shows **Connected**, all traffic goes through the proxy.

**Quick Settings tile:** pull down the notification panel, edit the tiles, and add
**Proxy VPN**. Tap it to connect/disconnect.

## How it works

```
 Apps ──► TUN interface ──► local ServerSocket ──► SOCKS5 / HTTP proxy ──► Internet
           (VpnService)       (kernel handles TCP)
```

1. The app creates a TUN interface that captures all IPv4 and IPv6 traffic.
2. **TCP** packets are rewritten (NAT) to a local `ServerSocket`, so the Android kernel
   handles the TCP protocol itself — no user-space TCP stack needed.
3. For each accepted connection, the app opens a connection to the original destination
   **through the proxy** and relays data in both directions.
4. **DNS** queries are answered locally with fake IPs from `198.18.0.0/15`. When an app
   connects to one of those IPs, the real domain name is sent to the proxy, which does the
   real DNS lookup.
5. **UDP** (other than DNS) and **IPv6** are dropped; apps automatically fall back to
   TCP / IPv4.

### Limitations

- UDP traffic (e.g. QUIC, some games, voice/video calls) is not proxied.
- IPv6-only destinations are not reachable.
- Fragmented IP packets are not supported.

## Project structure

```
app/src/main/java/com/kimi/proxyvpn/
├── MainActivity.kt       # UI: proxy form, connect/disconnect, status
├── ProxyVpnService.kt    # VpnService: TUN loop, TCP NAT, fake DNS, relay
├── ProxyClient.kt        # Minimal SOCKS5 and HTTP CONNECT client + proxy test
├── ProxyConfig.kt        # Proxy settings (saved in SharedPreferences)
├── ProxyTileService.kt   # Quick Settings tile
└── VpnState.kt           # Shared connection state for UI, tile and notification
```

## Privacy

- Settings (including the proxy password) are stored only on your device, in the app's
  private storage. Backups are disabled.
- The app has no analytics or tracking. The only outside request it makes itself is to
  `api.ipify.org` (through your proxy) to show your exit IP.

## Contributing

Issues and pull requests are welcome. Please describe the proxy type and Android version
when reporting a bug.
