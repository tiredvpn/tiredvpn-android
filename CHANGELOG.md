# Changelog

All notable changes to TiredVPN Android are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/).

## [1.0.5] - 2026-05-05

### Changed

- **Dependency updates**
  - Android Gradle Plugin: 9.1.0 → 9.2.0
  - Gradle wrapper: 9.4.1 → 9.5.0
  - OWASP Dependency Check: 9.1.0 → 12.2.1
  - Robolectric: 4.14.1 → 4.16.1
  - aquasecurity/trivy-action: 0.35.0 → 0.36.0
  - softprops/action-gh-release: 2 → 3

### Docs

- Replaced em-dash double-hyphens with single hyphens in README prose for cleaner rendering.

## [1.0.4] - 2026-04-10

### Fixed

- **App crash on startup under R8 minification** — `androidx.startup.InitializationProvider` failed to instantiate `WorkDatabase` because R8 obfuscated the class name, breaking Room's `Class.forName(...)` lookup. Added keep rules for `androidx.work`, `androidx.room`, and `androidx.startup` so release builds survive minification.

## [1.0.3] - 2026-04-09

### Added

- **OWASP Dependency Check** — scans Gradle dependencies against NVD for known CVEs
- **30 new unit tests** — VpnConfig (JSON round-trip, validation), VpnState (sealed class), CountryDetector (emoji flags, country mapping), UpdateConfig (data class defaults)
- Total test suite: 37 tests (was 7)

### Fixed

- Fixed `PortHopper` sequential strategy starting outside port range
- Replaced deprecated `Build.CPU_ABI` with `Build.SUPPORTED_ABIS`
- Replaced deprecated `stopForeground(Boolean)` with `STOP_FOREGROUND_REMOVE`
- Pinned `trivy-action` from `@master` to `@v0.35.0` (supply chain hardening)
- Eliminated all Node.js 20 deprecation warnings in CI

## [1.0.2] - 2026-04-08

### Changed

- Upgraded OkHttp from 4.12.0 to 5.3.2
- Upgraded AndroidX Lifecycle from 2.7.0 to 2.10.0
- Added unit tests step to CI pipeline
- Release workflow now gates on passing tests and lint before building APK

## [1.0.0] - 2026-04-03

### Added

- **Go VPN core embedded via JNI** — libtired.so compiled as a C shared library for arm64-v8a, armeabi-v7a, and x86_64
- **20+ DPI bypass strategies** from the Go core with automatic selection and mid-session fallback
- **Smart auto-reconnect** — survives airplane mode, Wi-Fi/mobile switches, and device sleep; WorkManager watchdog restarts the tunnel if killed
- **Split tunneling** — per-app routing: choose which apps go through the tunnel
- **Port hopping** — dynamically switches server ports to evade port-based blocking
- **Persistent VPN notification** — foreground service with real-time status, latency, and data counters
- **Auto-update system** — checks for new APKs, downloads and prompts installation
- **Deep link & QR import** — configure servers via `tired://` URI scheme or QR code scan
- **ADB control** — connect, disconnect, and import configs via broadcast intents (Android TV, headless setups)
- **Boot auto-start** — optional connection on boot, including direct boot before unlock
- **Android TV support** — leanback launcher, D-pad navigation, banner icon
- **Material Design UI** — server list, settings, log viewer, split tunneling picker, onboarding wizard
- **Minimum Android 7.0** (API 24), target Android 13 (API 33)
