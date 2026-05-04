# TiredVPN Android

[![CI](https://github.com/tiredvpn/tiredvpn-android/actions/workflows/ci.yml/badge.svg)](https://github.com/tiredvpn/tiredvpn-android/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/tiredvpn/tiredvpn-android)](https://github.com/tiredvpn/tiredvpn-android/releases/latest)
[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-7.0%2B-green.svg)](https://developer.android.com/about/versions/nougat)

![TiredVPN](img/github.png)

Android client for TiredVPN - a DPI-resistant VPN designed to operate reliably in censored network environments.

**Related repositories:** [tiredvpn/tiredvpn](https://github.com/tiredvpn/tiredvpn) — Go server and CLI client

## What is it

TiredVPN Android is the mobile client for the [TiredVPN](https://github.com/tiredvpn/tiredvpn) tunnel system. It embeds the Go VPN core as a native shared library (via JNI) and wraps it in a full-featured Android VPN service. The app automatically selects the best bypass strategy for the current network conditions, reconnects after disruptions, and keeps the tunnel alive in the background.

## Features

- **Multiple DPI bypass strategies** - the Go core ships with 20+ strategies (REALITY, QUIC Salamander, HTTP/2 Stego, WebSocket Padded, Traffic Morphing, Protocol Confusion, and more). The client rotates through them automatically when one is blocked.
- **Smart auto-reconnect** - survives airplane mode toggles, network switches (Wi-Fi / mobile), and device sleep. A WorkManager-based watchdog ensures the tunnel restarts if the service is killed.
- **Persistent VPN notification** - foreground service with real-time status, latency, and data counters.
- **Split tunneling** - per-app routing: choose which apps go through the tunnel and which use the direct connection.
- **Port hopping** - dynamically switches server ports to evade port-based blocking.
- **Auto-update system** - checks for new versions, downloads APKs, and prompts for installation.
- **Deep link & QR import** - configure servers via `tired://` URI scheme or QR code scan.
- **ADB control** - connect, disconnect, and import configs via broadcast intents (useful for Android TV and headless setups).
- **Boot auto-start** - optionally connects on device boot, including direct boot (before unlock).
- **Android TV support** - leanback launcher category, D-pad navigation, banner icon.
- **Material Design UI** - server list, settings, log viewer, split tunneling picker, onboarding wizard.

## Requirements

| Requirement       | Minimum            |
|-------------------|--------------------|
| Android version   | 7.0 (API 24)      |
| Target SDK        | 33 (Android 13)   |
| Architectures     | `arm64-v8a`, `armeabi-v7a`, `x86_64` |

The APK includes native libraries for all three architectures. Most modern phones use `arm64-v8a`.

## Project Structure

```
app/src/main/java/com/tiredvpn/android/
├── TiredVpnApp.kt              # Application class
├── native/                     # JNI bridge to Go VPN core
│   ├── TiredVpnNative.kt       # JNI function declarations
│   ├── NativeProcess.kt        # Native process lifecycle
│   └── TiredVpnProcess.kt      # Process abstraction
├── vpn/                        # VPN service layer
│   ├── TiredVpnService.kt      # Android VpnService implementation
│   ├── ConnectionManager.kt    # Connection state machine
│   ├── ServerRepository.kt     # Server config persistence
│   ├── VpnConfig.kt            # Configuration model
│   ├── VpnState.kt             # State enum
│   └── VpnWatchdogWorker.kt    # WorkManager keepalive
├── ui/                         # Activities
│   ├── MainActivity.kt         # Dashboard (connect/disconnect)
│   ├── ServerConfigActivity.kt # Add/edit server
│   ├── ServerListActivity.kt   # Server picker
│   ├── SettingsActivity.kt     # App settings
│   ├── SplitTunnelingActivity.kt
│   ├── LogViewerActivity.kt    # Real-time log viewer
│   ├── WelcomeActivity.kt      # Onboarding wizard
│   └── AboutActivity.kt
├── porthopping/                # Port hopping logic
├── receiver/                   # Broadcast receivers
│   ├── BootReceiver.kt         # Auto-start on boot
│   ├── AirplaneModeReceiver.kt # Reconnect after airplane mode
│   ├── ConfigImportReceiver.kt # ADB config import
│   └── VpnControlReceiver.kt   # ADB connect/disconnect
├── update/                     # Auto-update system
│   ├── UpdateManager.kt
│   ├── VersionChecker.kt
│   ├── ApkDownloader.kt
│   ├── ApkInstaller.kt
│   └── UpdateWorker.kt
└── util/                       # Helpers
    ├── BatteryOptimizationHelper.kt
    ├── CountryDetector.kt
    ├── FileLogger.kt
    ├── PingManager.kt
    └── TvUtils.kt
```

## Building from Source

### Prerequisites

- **Android Studio** Hedgehog (2023.1) or newer
- **Android NDK** 27.x (install via SDK Manager)
- **Go** 1.24 or newer
- **JDK** 17

### Step 1: Build the Go native library

Use the provided script — it clones the [Go VPN core](https://github.com/tiredvpn/tiredvpn) automatically, cross-compiles for all three architectures, and places the `.so` files in the right directories:

```bash
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/27.2.12479018
./scripts/build-jni.sh
```

If you already have the Go core checked out locally:

```bash
./scripts/build-jni.sh --core-dir /path/to/tiredvpn
```

The script outputs `libtiredvpn.so` into `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/`.

### Step 2: Build the APK

```bash
./gradlew assembleDebug
```

The output APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

For a release build with ProGuard minification:

```bash
./gradlew assembleRelease
```

## Configuration

### Via the app UI

1. Open the app and tap **Add Server**.
2. Enter the server address, port, and client secret.
3. Tap **Save** and connect.

### Via deep link

Share a `tired://` URI. The app registers an intent filter for the `tired` scheme and opens the server configuration screen with pre-filled fields.

### Via ADB (useful for Android TV)

```bash
# Import a server config
adb shell am broadcast -a com.tiredvpn.IMPORT_CONFIG \
  --es host "vpn.example.com" \
  --es port "995" \
  --es secret "base64secret"

# Connect
adb shell am broadcast -a com.tiredvpn.ACTION_CONNECT

# Disconnect
adb shell am broadcast -a com.tiredvpn.ACTION_DISCONNECT
```

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| AndroidX Core KTX | 1.12.0 | Kotlin extensions for Android |
| Material Components | 1.11.0 | UI components and theming |
| AndroidX Lifecycle | 2.7.0 | ViewModel and lifecycle-aware components |
| Kotlin Coroutines | 1.7.3 | Asynchronous programming |
| AndroidX WorkManager | 2.9.0 | VPN watchdog and update scheduler |
| OkHttp | 4.12.0 | Update checking and APK downloads |
| ConstraintLayout | 2.1.4 | Responsive layouts |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on submitting patches, running tests, and code style.

## License

This project is licensed under the GNU Affero General Public License v3.0. See [LICENSE](LICENSE) for details.
