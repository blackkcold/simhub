#!/usr/bin/env bash
set -euo pipefail

APK="${1:?usage: verify_android_release_signer.sh <apk> [apksigner]}"
APKSIGNER="${2:-${ANDROID_HOME:-}/build-tools/37.0.0/apksigner}"
EXPECTED="9cac47bd005bd526de8348b7ba9fd217c08f8af04589012ed653f5162cfde84a"

if [[ ! -x "$APKSIGNER" ]]; then
  echo "apksigner not found: $APKSIGNER" >&2
  exit 2
fi

ACTUAL="$("$APKSIGNER" verify --print-certs "$APK" | awk -F': ' '/Signer #1 certificate SHA-256 digest/ {print tolower($2); exit}')"

if [[ -z "$ACTUAL" ]]; then
  echo "Unable to read APK signer certificate digest." >&2
  exit 3
fi

if [[ "$ACTUAL" != "$EXPECTED" ]]; then
  echo "Unexpected Android release signer." >&2
  echo "expected: $EXPECTED" >&2
  echo "actual:   $ACTUAL" >&2
  exit 4
fi

echo "Android release signer verified: $ACTUAL"
