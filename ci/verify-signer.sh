#!/usr/bin/env bash
# Fail-closed policy: only stableDev APKs using the same protected signer may be published.
set -euo pipefail
dir="${1:?expected signed APK output directory}"
expected="${RMR_DEV_CERT_SHA256:?expected permanent development certificate SHA-256}"
expected="$(printf '%s' "$expected" | tr '[:upper:]' '[:lower:]' | tr -d ':')"
[[ "$expected" =~ ^[0-9a-f]{64}$ ]] || { echo "Invalid expected certificate digest" >&2; exit 1; }
if [[ -n "${ANDROID_HOME:-}" && -x "${ANDROID_HOME}/build-tools/35.0.0/apksigner" ]]; then
    apksigner="${ANDROID_HOME}/build-tools/35.0.0/apksigner"
else
    apksigner="$(command -v apksigner)"
fi
[[ -x "$apksigner" ]] || { echo "apksigner unavailable" >&2; exit 1; }
shopt -s nullglob
apks=("$dir"/*-stableDev.apk)
[[ ${#apks[@]} -eq 2 ]] || { echo "Expected exactly two signed ABI-specific APKs" >&2; exit 1; }
found_arm64=0
found_arm32=0
receipt="${2:-signing-receipt.txt}"
: > "$receipt"
for apk in "${apks[@]}"; do
    if [[ "$apk" == *arm64-v8a* ]]; then
        ((found_arm64+=1))
        abi="arm64-v8a"
    elif [[ "$apk" == *armeabi-v7a* ]]; then
        ((found_arm32+=1))
        abi="armeabi-v7a"
    else
        echo "Unexpected universal or unknown-ABI APK: $apk" >&2
        exit 1
    fi
    verifier_output="$("$apksigner" verify --verbose --print-certs "$apk")"
    digest="$(printf '%s\n' "$verifier_output" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | tr '[:upper:]' '[:lower:]')"
    [[ "$digest" == "$expected" ]] || {
        echo "Certificate mismatch ($abi); signed APK publication refused" >&2
        exit 1
    }
    number_of_signers="$(printf '%s\n' "$verifier_output" | grep -c '^Signer #[0-9]* certificate SHA-256 digest:' || true)"
    [[ "$number_of_signers" -eq 1 ]] || { echo "Unexpected signer count" >&2; exit 1; }
    sha="$(sha256sum "$apk" | awk '{print $1}')"
    printf 'source_sha=%s\tabi=%s\tfile=%s\tapk_sha256=%s\tcert_sha256=%s\n' \
      "${GITHUB_SHA:-local}" "$abi" "$(basename "$apk")" "$sha" "$digest" >> "$receipt"
done
[[ "$found_arm64" -eq 1 && "$found_arm32" -eq 1 ]] ||
    { echo "Both C67 and A1 ABI builds required" >&2; exit 1; }
echo "Both stableDev APKs have the expected same protected signer."
