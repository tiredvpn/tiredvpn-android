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
#   --core-dir   a fresh clone of the core's default branch in a new temp
#                directory, made for this run only
#   --output-dir app/src/main/jniLibs
#
# Writes <output-dir>/.core-revision: the core revision and a SHA-256 per
# library. Gradle packages native code only when that stamp matches the files.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

CORE_DIR=""
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

# No --core-dir: clone into a directory of our own. The old default was a
# fixed /tmp/tiredvpn-core that was cloned once and then reused as it was, so
# every later build quietly packaged whatever that first clone had been.
if [[ -z "$CORE_DIR" ]]; then
  CORE_DIR="$(mktemp -d)/tiredvpn"
  echo "==> No --core-dir given, cloning $CORE_REPO into $CORE_DIR"
  git clone --depth 1 "$CORE_REPO" "$CORE_DIR"
elif [[ ! -d "$CORE_DIR" ]]; then
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

# Same format as coreRevision() in app/build.gradle.kts.
if HEAD="$(git -C "$CORE_DIR" rev-parse HEAD 2>/dev/null)"; then
  if [[ -n "$(git -C "$CORE_DIR" status --porcelain 2>/dev/null)" ]]; then
    REVISION="git:${HEAD}-dirty"
  else
    REVISION="git:${HEAD}"
  fi
else
  REVISION="version:$(tr -d '[:space:]' < "$CORE_DIR/VERSION")"
fi

STAMP="$OUTPUT_DIR/.core-revision"
{
  echo "revision=$REVISION"
  echo "version=$VERSION"
  for abi in arm64-v8a armeabi-v7a x86_64; do
    echo "$abi=$(sha256sum "$OUTPUT_DIR/$abi/libtiredvpn.so" | cut -d' ' -f1)"
  done
} > "$STAMP"

echo "==> Core $REVISION recorded in $STAMP"
echo "==> JNI build complete"
