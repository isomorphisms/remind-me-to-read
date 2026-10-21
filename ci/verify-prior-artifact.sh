#!/usr/bin/env bash
# Gate distribution on two independently built APK pairs with the SAME signer.
# Run ONLY from the protected signing job. No private key is read by this script.
set -euo pipefail

prior_id="${RMR_PREVIOUS_SIGNED_ARTIFACT_ID:?Protect and set the prior signed candidate artifact ID}"
current_run="${GITHUB_RUN_ID:?Must run in GitHub Actions}"
current_sha="${GITHUB_SHA:?Must bind exact source commit}"
repo="${GITHUB_REPOSITORY:?Must bind repository}"
expected="${RMR_DEV_CERT_SHA256:?Missing protected signer certificate digest}"
current_dir="${1:?current APK directory}"
current_receipt="${2:?current signing receipt}"

[[ "$repo" == "isomorphisms/remind-me-to-read" ]] || {
    echo "Wrong GitHub source repository" >&2; exit 1;
}
[[ "$prior_id" =~ ^[1-9][0-9]*$ ]] || { echo "Invalid prior artifact ID" >&2; exit 1; }
command -v gh >/dev/null
command -v jq >/dev/null
command -v unzip >/dev/null

metadata="$(gh api "repos/$repo/actions/artifacts/$prior_id")"
[[ "$(jq -er '.name' <<< "$metadata")" == "remind-me-to-read-stable-dev-candidate" ]] || {
    echo "Previous artifact is not a protected signing candidate" >&2; exit 1;
}
[[ "$(jq -er '.expired' <<< "$metadata")" == "false" ]] || {
    echo "Previous signing artifact expired" >&2; exit 1;
}
prior_run="$(jq -er '.workflow_run.id' <<< "$metadata")"
[[ "$prior_run" =~ ^[1-9][0-9]*$ && "$prior_run" != "$current_run" ]] || {
    echo "Missing/different prior CI run required" >&2; exit 1;
}
(( prior_run < current_run )) || { echo "Prior build must predate current build" >&2; exit 1; }
prior_meta="$(gh api "repos/$repo/actions/runs/$prior_run")"
prior_sha="$(jq -er '.head_sha' <<< "$prior_meta")"
prior_branch="$(jq -er '.head_branch' <<< "$prior_meta")"
prior_path="$(jq -er '.path' <<< "$prior_meta")"
[[ "$prior_sha" =~ ^[0-9a-f]{40}$ ]] || { echo "Invalid prior source SHA" >&2; exit 1; }
[[ "$prior_branch" == "main" || "$prior_branch" == "feature/reading-tracker-mvp" ]] || {
    echo "Candidate did not come from a protected signing branch" >&2; exit 1;
}
[[ "$prior_path" == ".github/workflows/android.yml" ]] || {
    echo "Candidate did not come from approved Android signing workflow" >&2; exit 1;
}
[[ "$(jq -er '.event' <<< "$prior_meta")" == "push" ]] || {
    echo "Candidate was not a protected push build" >&2; exit 1;
}
[[ "$(jq -er '.head_repository.full_name' <<< "$prior_meta")" == "$repo" ]] || {
    echo "Candidate belongs to another source repository" >&2; exit 1;
}
[[ "$(jq -er '.workflow_run.head_sha' <<< "$metadata")" == "$prior_sha" ]] || {
    echo "Artifact/run source identity mismatch" >&2; exit 1;
}

temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT
gh api "repos/$repo/actions/artifacts/$prior_id/zip" > "$temporary/archive.zip"
[[ -s "$temporary/archive.zip" ]] || { echo "Prior artifact download is empty" >&2; exit 1; }
mkdir -p "$temporary/previous" "$temporary/apks"
unzip -q "$temporary/archive.zip" -d "$temporary/previous"
mapfile -d '' -t candidates < <(
    find "$temporary/previous" -type f -name '*-stableDev.apk' -print0
)
[[ ${#candidates[@]} -eq 2 ]] || {
    echo "Prior candidate lacks both exact-ABI APKs" >&2; exit 1;
}
for source in "${candidates[@]}"; do
    dest="$temporary/apks/$(basename "$source")"
    [[ ! -e "$dest" ]] || { echo "Duplicate APK filename" >&2; exit 1; }
    cp -- "$source" "$dest"
done
mapfile -d '' -t receipts < <(
    find "$temporary/previous" -type f -name signing-receipt.txt -print0
)
[[ ${#receipts[@]} -eq 1 ]] || {
    echo "Prior candidate must have one original CI receipt" >&2; exit 1;
}

# Check actual cryptographic signatures, not only text descriptions.
bash ci/verify-signer.sh "$temporary/apks" "$temporary/recomputed.txt"
python3 ci/check-continuity.py "${receipts[0]}" "$current_receipt" "$expected" \
    --previous-dir "$temporary/apks" --current-dir "$current_dir" \
    --previous-run "$prior_run" --current-run "$current_run" \
    --previous-source "$prior_sha" --current-source "$current_sha"
printf 'PASS protected signer continuity: earlier_run=%s current_run=%s\n' \
    "$prior_run" "$current_run"
