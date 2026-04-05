# Contributing to TiredVPN Android

## Reporting Issues

- Use [GitHub Issues](https://github.com/tiredvpn/tiredvpn-android/issues) for bugs and feature requests
- Include: Android version, device model, steps to reproduce
- For security issues, see [SECURITY.md](SECURITY.md)

## Development Setup

### Prerequisites
- Android Studio (latest stable)
- Android SDK 34+, NDK 27.x
- Go 1.24+ (for building native JNI library)

### Build
```bash
git clone https://github.com/tiredvpn/tiredvpn-android.git
cd tiredvpn-android
# Build JNI native library first (see README for details)
./gradlew assembleDebug
```

## Pull Requests

1. Fork the repository
2. Create a feature branch (`git checkout -b feat/my-feature`)
3. Test on a physical device if possible
4. Submit a PR with a clear description

## Code Style

- Kotlin: follow standard Kotlin conventions
- Use meaningful commit messages: `feat:`, `fix:`, `refactor:`, `test:`, `docs:`

## Code of Conduct

By participating, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).
