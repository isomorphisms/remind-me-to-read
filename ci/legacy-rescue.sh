#!/usr/bin/env bash
# Export a STILL-INSTALLED prior debug build. This never uninstalls or changes the app.
# Usage: bash ci/legacy-rescue.sh SERIAL ./private-reading-rescue.tar
set -euo pipefail
serial="${1:?adb serial}"
output="${2:?backup destination .tar}"
package=org.isomorphisms.remindmetoread
[[ ! -e "$output" ]] || { echo "Refusing to overwrite an existing rescue archive" >&2; exit 1; }
command -v adb >/dev/null
command -v python3 >/dev/null
adb -s "$serial" shell pm path "$package" | grep -q '^package:' ||
    { echo "Reading app is not installed; no private data was exported" >&2; exit 1; }
temporary="$output.partial.$$"
trap 'rm -f "$temporary"' EXIT
adb -s "$serial" shell am force-stop "$package" >/dev/null
adb -s "$serial" exec-out run-as "$package" tar -cf - databases files shared_prefs > "$temporary"
[[ -s "$temporary" ]] || { echo "No backup bytes exported" >&2; exit 1; }
python3 ci/device-state.py --verify-legacy "$temporary"
mv "$temporary" "$output"
sha256sum "$output"
echo "Verified copy saved. Keep TWO external copies. Original app remains installed and untouched."
