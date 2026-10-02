#!/usr/bin/env bash
set -euo pipefail

# End-to-end check of the core guard in app/build.gradle.kts.
#
# Runs Gradle in a throwaway copy of the repository with fake libraries and
# fake stamps, and asserts that native packaging is refused whenever the
# core cannot be traced, and allowed when the stamp matches. Fake .so files
# are enough: the guard decides before anything is compiled or linked.
#
# Usage: ./scripts/test-core-guard.sh

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

(cd "$REPO_ROOT" && git ls-files -z | rsync -a --from0 --files-from=- ./ "$WORK/")
[[ -f "$REPO_ROOT/local.properties" ]] && cp "$REPO_ROOT/local.properties" "$WORK/"
cd "$WORK"

JNI=app/src/main/jniLibs
ABIS="arm64-v8a armeabi-v7a x86_64"
FAILURES=0

fake_libs() {
  rm -rf "$JNI"
  for abi in $ABIS; do
    mkdir -p "$JNI/$abi"
    head -c 4096 /dev/urandom > "$JNI/$abi/libtiredvpn.so"
  done
}

write_stamp() {
  {
    echo "revision=git:0000000000000000000000000000000000000000"
    echo "version=0.0.0-test"
    for abi in $ABIS; do
      echo "$abi=$(sha256sum "$JNI/$abi/libtiredvpn.so" | cut -d' ' -f1)"
    done
  } > "$JNI/.core-revision"
}

# expect <name> <pass|fail> <text the output must contain> -- <gradle args...>
expect() {
  local name="$1" want="$2" needle="$3"
  shift 4
  local out rc=0
  out="$(./gradlew --console=plain "$@" 2>&1)" || rc=$?
  local got=pass; [[ $rc -ne 0 ]] && got=fail
  if [[ "$got" == "$want" ]] && grep -qF -- "$needle" <<<"$out"; then
    echo "ok   - $name"
  else
    echo "FAIL - $name (expected $want with \"$needle\", got $got)"
    grep -E "No verified|packaging core|not a tiredvpn|unverified|BUILD" <<<"$out" | sed 's/^/       /' | head -n 8
    FAILURES=$((FAILURES + 1))
  fi
}

rm -rf "$JNI"
expect "no libraries at all: packaging refused" fail "No verified Go core" -- :app:mergeDebugNativeLibs
expect "no libraries at all: the asset still builds, for unit tests" pass "BUILD SUCCESSFUL" -- :app:coreRevisionAsset
grep -q "^unverified:" app/build/generated/coreRevision/assets/core-revision.txt \
  && echo "ok   - the asset says unverified" \
  || { echo "FAIL - the asset does not say unverified"; FAILURES=$((FAILURES + 1)); }

fake_libs
expect "libraries without a stamp: packaging refused" fail "no .core-revision stamp" -- :app:mergeDebugNativeLibs

write_stamp
expect "libraries with a matching stamp: packaged" pass "packaging core git:0000000000000000000000000000000000000000" -- :app:mergeDebugNativeLibs
expect "matching stamp: asset written" pass "BUILD SUCCESSFUL" -- :app:coreRevisionAsset
grep -q "^revision=git:0000000000000000000000000000000000000000$" app/build/generated/coreRevision/assets/core-revision.txt \
  && echo "ok   - the asset names the revision" \
  || { echo "FAIL - the asset does not name the revision"; FAILURES=$((FAILURES + 1)); }

head -c 4096 /dev/urandom > "$JNI/x86_64/libtiredvpn.so"
expect "one library replaced after the stamp: packaging refused" fail "not the file the stamp describes" -- :app:mergeDebugNativeLibs

expect "named core dir that is not a checkout: refused" fail "is not a tiredvpn core checkout" -- :app:mergeDebugNativeLibs -PtiredvpnCoreDir="$WORK/no-such-core"

if [[ $FAILURES -ne 0 ]]; then
  echo "$FAILURES check(s) failed"
  exit 1
fi
echo "all checks passed"
