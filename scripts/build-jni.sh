#!/usr/bin/env bash
set -euo pipefail

# Build TiredVPN Go core as shared libraries for Android architectures.
#
# Usage:
#   ./scripts/build-jni.sh [--core-dir /path/to/tiredvpn-core] [--output-dir /path/to/jniLibs]
#
# Environment:
#   ANDROID_NDK_HOME — path to Android NDK (required)
#
# Defaults:
#   --core-dir   /tmp/tiredvpn-core (will clone if missing)
#   --output-dir app/src/main/jniLibs

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

CORE_DIR="/tmp/tiredvpn-core"
OUTPUT_DIR="app/src/main/jniLibs"
CORE_REPO="https://github.com/tiredvpn/tiredvpn.git"
# Empty by default: the real version is read from the core checkout below so it
# can never drift away from the code that actually gets compiled.
VERSION=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --core-dir)  CORE_DIR="$2"; shift 2 ;;
    --output-dir) OUTPUT_DIR="$2"; shift 2 ;;
    --core-repo) CORE_REPO="$2"; shift 2 ;;
    --version)   VERSION="$2"; shift 2 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

# Convert OUTPUT_DIR to absolute path if relative
if [[ ! "$OUTPUT_DIR" =~ ^/ ]]; then
  OUTPUT_DIR="$REPO_ROOT/$OUTPUT_DIR"
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "ERROR: ANDROID_NDK_HOME is not set"
  exit 1
fi

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ ! -d "$TOOLCHAIN" ]]; then
  echo "ERROR: NDK toolchain not found at $TOOLCHAIN"
  exit 1
fi

# Clone core repo if directory does not exist
if [[ ! -d "$CORE_DIR" ]]; then
  echo "==> Cloning tiredvpn core into $CORE_DIR"
  git clone --depth 1 "$CORE_REPO" "$CORE_DIR"
fi

if [[ -z "$VERSION" ]]; then
  if [[ ! -f "$CORE_DIR/VERSION" ]]; then
    echo "ERROR: $CORE_DIR/VERSION not found and --version was not given"
    exit 1
  fi
  VERSION="$(tr -d '[:space:]' < "$CORE_DIR/VERSION")-android-jni"
fi

if [[ -z "$VERSION" || "$VERSION" == "-android-jni" ]]; then
  echo "ERROR: could not determine core version from $CORE_DIR/VERSION"
  exit 1
fi

echo "==> Core version: $VERSION"

ARCHITECTURES="arm64 arm x86_64"

for arch in $ARCHITECTURES; do
  unset GOARM
  case "$arch" in
    arm64)
      export GOARCH=arm64
      CC_PREFIX="aarch64-linux-android"
      JNI_DIR="arm64-v8a"
      ;;
    arm)
      export GOARCH=arm
      export GOARM=7
      CC_PREFIX="armv7a-linux-androideabi"
      JNI_DIR="armeabi-v7a"
      ;;
    x86_64)
      export GOARCH=amd64
      CC_PREFIX="x86_64-linux-android"
      JNI_DIR="x86_64"
      ;;
  esac

  export GOOS=android
  export CGO_ENABLED=1
  export CC="${TOOLCHAIN}/${CC_PREFIX}24-clang"
  # 16KB page alignment is required by Android 15+ (and the default on Android 16).
  export CGO_LDFLAGS="-Wl,-z,max-page-size=16384"

  OUT="$OUTPUT_DIR/$JNI_DIR"
  mkdir -p "$OUT"

  echo "==> Building libtiredvpn.so for $arch ($JNI_DIR), version=$VERSION"
  (cd "$CORE_DIR" && go build -buildmode=c-shared \
    -ldflags "-s -w -X main.version=${VERSION}" \
    -trimpath \
    -o "$OUT/libtiredvpn.so" \
    ./cmd/tiredvpn/)

  # Remove the generated C header — not needed at runtime
  rm -f "$OUT/libtiredvpn.h"

  echo "    -> $OUT/libtiredvpn.so"
done

echo "==> JNI build complete"
