#!/usr/bin/env bash
# CI fixture only: prove the configured stableDev Gradle variant actually signs
# both ABI APKs and the finished-apk verifier reads the expected certificate.
# Generated key is disposable, untrusted for device delivery and NEVER uploaded.
set -euo pipefail
sandbox="$(mktemp -d)"
trap 'rm -rf "$sandbox"' EXIT
export RMR_DEV_KEYSTORE_FILE="$sandbox/throwaway-signing.p12"
export RMR_DEV_KEYSTORE_PASSWORD="untrusted-ci-sample-only"
export RMR_DEV_KEY_ALIAS="throwaway"
export RMR_DEV_KEY_PASSWORD="$RMR_DEV_KEYSTORE_PASSWORD"
umask 077
keytool -genkeypair -noprompt -alias "$RMR_DEV_KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 1 -storetype PKCS12 \
    -keystore "$RMR_DEV_KEYSTORE_FILE" \
    -storepass "$RMR_DEV_KEYSTORE_PASSWORD" \
    -keypass "$RMR_DEV_KEY_PASSWORD" \
    -dname "CN=Untrusted RMR CI Fixture,OU=Tests,O=Local,C=US" >/dev/null
export RMR_DEV_CERT_SHA256="$(
    keytool -list -v -alias "$RMR_DEV_KEY_ALIAS" -storetype PKCS12 \
        -keystore "$RMR_DEV_KEYSTORE_FILE" \
        -storepass "$RMR_DEV_KEYSTORE_PASSWORD" |
    sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | head -n1 |
    tr -d ':\r' | tr '[:upper:]' '[:lower:]'
)"
[[ "$RMR_DEV_CERT_SHA256" =~ ^[0-9a-f]{64}$ ]] || {
    echo "Could not derive disposable certificate digest" >&2; exit 1;
}
gradle --no-daemon :app:assembleStableDev
bash ci/verify-signer.sh app/build/outputs/apk/stableDev "$sandbox/fixture-receipt.tsv"
echo "PASS temporary, deliberately UNTRUSTED dual-ABI APK signer smoke"
echo "These fixture APKs are not qualified and must not be distributed."
