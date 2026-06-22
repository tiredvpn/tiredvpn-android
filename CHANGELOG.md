# Changelog

All notable changes to TiredVPN Android are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- **Share a server.** Long-press a server in the list to Share or Copy link - it serializes the full config (including the new shaper/ECH/IPv6/MTU/DNS settings) into a `tired://` link and hands it to the system share sheet or the clipboard.
- **Backup and restore configs.** Settings has a "Backup configs" entry that exports every saved server to a `tiredvpn-backup.json` file and opens the share sheet (with a warning that the file holds server secrets), and a "Restore configs" entry that picks a backup file and imports all servers from it. Restore accepts the JSON array backup, a single JSON object, or a file of `tired://` links, and is idempotent (servers keep their id, so re-importing updates in place instead of duplicating).

### Fixed

- **Import from clipboard failed with "doesn't contain tired:// URL" even when a link was present.** The clipboard text was matched with a strict `startsWith("tired://")` against the raw, untrimmed first clip item, so a leading newline, surrounding share text, or a link in a second clip item all broke detection. Import now trims, coerces every clip item to text, finds a `tired://` link anywhere in the content (case-insensitive), and falls back to JSON config. Parsing moved to a single tolerant `VpnConfig.fromUrl`/`extractTiredUrl` used by every import path.
- **Auto Fallback toggle did not stick.** The switch in Settings had no change handler, so toggling it never wrote back to the active server - on leaving and returning to Settings it reverted to the saved value, and the runtime command kept the old fallback behavior. The toggle now persists to the active server immediately (matching the other per-server switches).

## [1.1.0] - 2026-06-16

### Added

- **Exposed core client settings that the app previously could not reach.** The embedded core supports far more than the app surfaced; these are now configurable per server:
  - **Traffic Shaper** preset (`youtube_streaming`, `chrome_browsing`, `random_per_session`) with optional seed.
  - **ECH (Encrypted Client Hello)** - toggle, config (base64), and public name to hide the SNI.
  - **IPv6 endpoint** - separate IPv6 server address, prefer-IPv6 and IPv4-fallback toggles.
  - **QUIC SNI fragmentation** toggle for GFW-style SNI filtering.
  - **Port hopping** UI - the config and Kotlin logic already existed but had no controls (enable, port range, interval, strategy, seed).
  - **Custom MTU** and **custom DNS** overrides for troubleshooting (helps WebRTC/Meet).
  - **Full, corrected strategy list** - replaces the stale/partial picker. Adds QUIC Salamander, SSH/IMAP camouflage, the four Traffic Morph profiles, all Protocol Confusion variants, State Exhaustion, and Geneva (Russia/China/Iran), with values verified against the core's strategy IDs.
  - **Complete RTT-masking profiles** (7) - adds `beijing-baidu` and `tehran-aparat`.

### Fixed

- **Non-arm64 devices could not connect.** Every TUN connect extracted a standalone binary from assets, but only the arm64 build was bundled, so on armeabi-v7a and x86_64 the extraction threw and the connection aborted. The binary was dead weight anyway - the core runs via JNI from the per-ABI `libtiredvpn.so` and the extracted path was discarded. Removed the extraction (and the 13 MB committed binary); all ABIs now connect.
- **Google Meet (and other Google apps) failed to start calls under split tunneling** - in include/allowlist mode only the selected app was tunneled, but Google apps offload signaling, push and STUN/auth to Google Play Services, which stayed off-tunnel. Media and signaling then left from different external IPs and WebRTC ICE never completed. When a Google app is allowlisted, the tunnel now also includes `com.google.android.gms`, `com.google.android.gsf`, and `com.android.vending`.
- **Client settings were silently dropped on the embedded path.** The core's JNI argument parser recognized only ~11 flags with no default case, so QUIC, RTT masking, cover host (a `-cover`/`-cover-host` name mismatch), and fallback toggles never reached the core; proxy mode was also forced into TUN and lost its listen address. Fixed in the embedded core (tiredvpn v1.2.1 / v1.2.2), which now honors the full flag contract and logs unknown flags.

### Changed

- **Embedded Go VPN core updated to v1.2.2** (was ~v1.0.x). The native `libtiredvpn.so` is rebuilt from the latest core for all three ABIs. Highlights relevant to the mobile client:
  - **REALITY throughput fix** - ChaCha20 TLS framing plus per-request TCP, removing the bottleneck on the default strategy.
  - **Traffic Morph reliability** - larger TLS ClientHello fragments (2 -> 64 bytes) cut the segment count from ~750 to ~24, fixing reliable timeouts on high-latency mobile paths; `youtube_streaming` shaper is now the morph default.
  - **Traffic Shaper** - behavioural masking layer that decouples the DPI traffic shape from the TLS transport.
  - **New bypass strategies** - SSH and IMAP protocol camouflage, plus a capabilities probe with anti-probe dispatch so the client negotiates a working strategy faster.
  - **Salamander keyed-tag framing** and mux carrier budget recycling.
  - **Cleartext TLS fingerprints removed** from all strategies (core phase 3).
  - **Machine-readable strategy benchmarking** (`-benchmark-json`) feeding automatic strategy selection.

- **Dependency updates**
  - com.google.android.material: 1.13.0 -> 1.14.0
  - OkHttp: 5.3.2 -> 5.4.0
  - Gradle wrapper: 9.5.0 -> 9.5.1

  - Core security and stability fixes from v1.2.x: SSRF in the proxy path, ICMP nonce reuse across sessions, a goroutine leak, and several DPI strategy correctness bugs.

### Compatibility

- The Traffic Morph wire protocol is now v2 (server-to-client early ack). Morph strategies require a server running **tiredvpn v1.2.0 or newer**; older servers will desync on morph. The other strategies (REALITY, port-hop, stego, ws, raw) remain interoperable.

## [1.0.6] - 2026-05-15

### Fixed

- **VPN icon stays in status bar after disconnect** — Go goroutines from parallel connection attempts held dup'd `/dev/tun` file descriptors after `vpnInterface.close()`. Android kept the VPN system binding alive while any fd pointed to `/dev/tun`. Fixed by enumerating `/proc/self/fd` and force-closing all TUN fds via `ParcelFileDescriptor.adoptFd()`, repeated in a background thread to catch fds closed asynchronously by Go's shutdown sequence.

### Changed

- **Dependency updates**
  - Android Gradle Plugin: 9.2.0 → 9.2.1
  - OWASP Dependency Check: 12.2.1 → 12.2.2

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
