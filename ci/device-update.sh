#!/usr/bin/env bash
# Runs on host with adb, apksigner, python3. Never uninstalls or clears an app.
# Usage: bash ci/device-update.sh A1|C67 SERIAL candidate-stableDev.apk EXPECTED_CERT_SHA256
set -euo pipefail
device="${1:?A1 or C67}"
serial="${2:?adb device serial}"
candidate="${3:?candidate stableDev APK}"
expected="${4:?protected signer SHA-256 fingerprint}"
package=org.isomorphisms.remindmetoread
expected="$(printf '%s' "$expected" | tr -d ':' | tr '[:upper:]' '[:lower:]')"
[[ "$expected" =~ ^[0-9a-f]{64}$ ]] || { echo "Invalid signing fingerprint" >&2; exit 1; }
[[ -f "$candidate" ]] || { echo "No APK: $candidate" >&2; exit 1; }

case "$device" in
    A1) abi="armeabi-v7a" ;;
    C67) abi="arm64-v8a" ;;
    *) echo "Expected A1 or C67" >&2; exit 1 ;;
esac
[[ "$candidate" == *"$abi"* ]] || { echo "Candidate APK does not match $device ABI" >&2; exit 1; }
if [[ -n "${ANDROID_HOME:-}" && -x "${ANDROID_HOME}/build-tools/35.0.0/apksigner" ]]; then
    apksigner="${ANDROID_HOME}/build-tools/35.0.0/apksigner"
else
    apksigner="$(command -v apksigner)"
fi

cert() {
    "$apksigner" verify --print-certs "$1" |
        sed -n 's/^Signer #1 certificate SHA-256 digest: //p' |
        tr '[:upper:]' '[:lower:]'
}
[[ "$(cert "$candidate")" == "$expected" ]] ||
    { echo "Refusing candidate with unknown development signer" >&2; exit 1; }

actual_abis="$(adb -s "$serial" shell getprop ro.product.cpu.abilist | tr -d '\r')"
case "$device" in
    A1) [[ "$actual_abis" == *"armeabi-v7a"* && "$actual_abis" != *"arm64-v8a"* ]] ;;
    C67) [[ "$actual_abis" == *"arm64-v8a"* ]] ;;
esac || { echo "Device CPU ABI does not match $device" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
installed="$(adb -s "$serial" shell pm path "$package" | tr -d '\r' | sed -n 's/^package://p' | grep '/base.apk$' | head -n1)"
[[ -n "$installed" ]] || { echo "No existing installed APK; not an update test" >&2; exit 1; }
adb -s "$serial" exec-out cat "$installed" > "$work/installed.apk"
[[ "$(cert "$work/installed.apk")" == "$expected" ]] || {
    echo "Existing installation has a DIFFERENT certificate. Do not uninstall. Export and verify its full data first." >&2
    exit 1
}

# The already installed app must be a debuggable build so run-as can read it.
# A host-side Python verifier requires EPUB, TXT, paper, positions, marks, sessions.
adb -s "$serial" shell am force-stop "$package" >/dev/null
adb -s "$serial" exec-out run-as "$package" tar -cf - databases files > "$work/before.tar"
python3 ci/device-state.py "$work/before.tar"

# Never pass -d, -t or any uninstall/data-clearing flag.
adb -s "$serial" install -r "$candidate"
adb -s "$serial" shell am force-stop "$package" >/dev/null
adb -s "$serial" exec-out run-as "$package" tar -cf - databases files > "$work/after.tar"
python3 ci/device-state.py "$work/before.tar" "$work/after.tar"
printf 'device=%s\tabi=%s\tserial=%s\tcandidate_sha256=%s\tcert_sha256=%s\tresult=PASS\n' \
    "$device" "$abi" "$serial" "$(sha256sum "$candidate" | cut -d ' ' -f1)" "$expected"
