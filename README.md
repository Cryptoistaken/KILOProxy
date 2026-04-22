# ProxyTunnel — Ad-Free Super Proxy Clone

A free, open Android app that tunnels all your device traffic through an HTTP or SOCKS5 proxy
server. No ads, no tracking, no root required.

---

## Features

- **HTTP CONNECT** proxy support
- **SOCKS5** proxy support (with optional username/password auth)
- Save and manage **multiple proxy profiles**
- **No root required** — uses Android's VpnService API
- Live **bytes sent / received** stats
- Dark theme UI
- Zero ads

---

## How to Build

### Requirements
- Android Studio Hedgehog (2023.1) or newer
- JDK 17 (bundled with Android Studio)
- Android SDK 34

### Steps

1. **Open project**
   - Launch Android Studio
   - File → Open → select this `ProxyTunnel` folder
   - Wait for Gradle sync to complete

2. **Build & Run**
   - Connect your Android phone via USB (enable USB debugging in Developer Options)
   - Click the green **Run ▶** button
   - Android will ask for **VPN permission** on first launch — tap **Allow**

3. **Build APK for sharing with friends**
   - Build → Build Bundle(s) / APK(s) → Build APK(s)
   - APK will be at: `app/build/outputs/apk/debug/app-debug.apk`
   - Copy to your friends' phones and install (they need "Install from unknown sources" enabled)

---

## How to Use

1. Tap the **+** button to add a proxy profile
2. Enter:
   - **Name** — anything you like (e.g. "Work Proxy")
   - **Host** — IP address or hostname of your proxy server
   - **Port** — proxy port number
   - **Type** — HTTP or SOCKS5
   - **Auth** — toggle on if your proxy needs a username/password
3. Tap **Save Profile**
4. Select the profile by tapping its radio button
5. Tap **Connect**
6. All your device's internet traffic now goes through the proxy

---

## Architecture

```
App traffic
    ↓
TUN virtual network interface (Android VpnService)
    ↓
ProxyVpnService.java  ← reads raw IP packets
    ↓
Socks5Handler.java    ← SOCKS5 handshake
HttpConnectHandler.java ← HTTP CONNECT handshake
    ↓
Your proxy server
    ↓
Internet
```

### Key files

| File | Description |
|------|-------------|
| `ProxyVpnService.java` | Core VPN service, creates TUN interface, routes traffic |
| `Socks5Handler.java` | SOCKS5 v5 handshake implementation |
| `HttpConnectHandler.java` | HTTP CONNECT tunnel implementation |
| `ProfileManager.java` | Saves/loads proxy profiles to SharedPreferences |
| `MainActivity.java` | Main screen |
| `AddProxyActivity.java` | Add/edit proxy profile screen |

---

## Upgrading to Production-Grade Packet Routing

The included `packetLoop()` in `ProxyVpnService` handles basic TCP forwarding.
For full protocol support (TCP state machine, UDP, DNS-over-proxy), replace it
with the **tun2socks** library:

1. Add to `build.gradle`:
   ```
   implementation 'io.github.xjasonlyu:tun2socks-android:2.5.0'
   ```
2. Replace the `packetLoop()` call with tun2socks initialization,
   passing it your proxy address and the VPN file descriptor.

---

## Legal

This app is for personal use — accessing your own company/college network,
bypassing ISP restrictions, or using public proxies. Do not use to
circumvent laws in your jurisdiction.
