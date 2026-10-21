# Reading library upgrade, signing and recovery contract

Owner: `isomorphisms/remind-me-to-read` PR #1 / #3.
Producer: [Flexible Pipes #238](https://github.com/isomorphisms/flexible-pipes/issues/238).
Independent acceptance: [AI-CI #306](https://github.com/isomorphisms/ai-ci/issues/306).

## Never uninstall first

Two already-delivered builds have **different** signer certificate SHA-256 values:

- `b4e20ee121fa69d1aa9af2da95eec9f8452c3fbd2b3ffa6b3dc3fa7a17cf37a3`
- `939a9b16b7004b82cdcca59cd5337b93ca681ceacd328ff78f0429227f651fd9`

Android blocks in-place `adb install -r` when a candidate APK's signing lineage
does not match the installed app. A new development signer does **not**
magically upgrade either existing app. The report-only `reading-progress.txt`
export does not contain EPUB/TXT bytes, sessions or reconstructible database rows.

**Do not uninstall or clear storage on C67/A1 until an actual complete backup
of that particular installation has been exported, independently opened, and
saved in two external places.** Stop if an export command fails. Losing either
previous private signing key does not grant permission to delete the old data.

## Complete local ZIP (new builds)

More → **Complete backup to ZIP (books, bookmarks, sessions)** opens Android's
Create Document picker. Save the ZIP outside the app's private storage (e.g.
SD card or a user-controlled document provider). The app writes, closes,
reopens and independently verifies every entry, SHA-256 hash, row schema
and relationship. **Only the displayed verified-success result qualifies.**
If the operation reports failure, treat the chosen document as incomplete.
No data is uploaded.

The archive includes copies of *every* privately imported EPUB and TXT,
physical-book records, original/ReadEra metadata, reading positions, all
bookmarks, completed sessions, totals and open paper-session timestamps.
It preserves both matching and unmatched ReadEra observations. Persisted
Android folder-access permissions, temporary camera files, app reminders and
network settings are not in this **library** backup; reauthorize SAF folders
after migrating. Source document URIs are intentionally cleared on restore
because permission grants cannot be transferred between installations.

More → **Restore complete ZIP (empty library only)** validates first, then
copies book files and inserts database rows in a SQL transaction. It refuses
to restore into a nonempty library, including one containing only unmatched
ReadEra records. A partial/error case must not erase existing books.

Version 1 format uses `manifest.json` (SHA-256 per payload, size and format
version), `database.json` (the four reading tables) and one
`books/ID.epub` or `books/ID.txt` member for every digital book. Entries
are allowlisted; traversal, duplicates, missing bytes, corrupt digests,
unrecognized schema or orphan marks/sessions fail. Current bounded profile:
1 GiB archive/uncompressed payload total, 100 MiB per book, 16 MiB database
JSON and 10,000 books. If exceeded, the export **fails rather than silently
omitting data**. The target must have sufficient temporary storage for restore.

An **old** installed app that lacks this ZIP feature cannot use its
report-only TXT export to migrate book data.

## Rescue from a previously installed debug-key APK

For existing Android 14 **debuggable** builds only, `adb run-as` can read
the package's private databases and copied book files without root. This may
work even if the previous signing key is no longer available, but it must be
verified **while the old APK is still installed**.

On a trusted computer with ADB and Python 3:

```sh
git checkout fix/stable-signer-portable-restore
bash ci/legacy-rescue.sh C67_ADB_SERIAL ./c67-reading-rescue.tar
bash ci/legacy-rescue.sh A1_ADB_SERIAL ./a1-reading-rescue.tar
sha256sum ./c67-reading-rescue.tar ./a1-reading-rescue.tar
```

Use the actual ADB serials. Run one device at a time and keep the resulting
archives private; the files contain books and reading history. The rescue script
force-stops the installed application, copies `databases/`, `files/` and
`shared_prefs/` with `run-as`, then checks SQLite records and referenced
private EPUB/TXT bytes. It **never uninstalls** the application.

If `run-as` fails (for example, if the installed APK was not debuggable),
**stop**. Do not assume the TXT report, an Android cloud backup
(`allowBackup=false`) or changing the certificate can recover private data.

Once the old archive is **verified in two separate external copies**, a
migration to a differently signed development APK is possible by an explicitly
approved manual uninstall/reinstall and restoring the original
`databases/` and `files/` on the newly installed debuggable app through
`adb shell -T run-as org.isomorphisms.remindmetoread tar -xf -`.
This operation is intentionally **not automated**: it is destructive if
mistakenly run against a populated library, and the file/SQLite schema must
remain compatible. Keep the original archives permanently until the migrated
reader has been opened and checked on the physical device. Reauthorize scanned
folder access afterward.

If the previous keystore **is** recoverable, prefer an old-key-signed bridge
APK with the new complete ZIP export and make an ordinary same-cert update,
then export/verify before changing the signing key.

## Permanent protected development signer

A developer must create **one** PKCS#12 signing key, back it up securely
offline, and configure a GitHub Actions environment
`protected-dev-signing` with restricted branches (`main` and
`feature/reading-tracker-mvp`) and appropriate review controls.

Environment secrets (never commit values):

| Secret | Purpose |
| --- | --- |
| `RMR_DEV_KEYSTORE_B64` | Base64 contents of one long-lived PKCS#12 file |
| `RMR_DEV_KEYSTORE_PASSWORD` | Store password |
| `RMR_DEV_KEY_ALIAS` | Permanent signing alias |
| `RMR_DEV_KEY_PASSWORD` | Key password |
| `RMR_DEV_CERT_SHA256` | Expected signer certificate digest, lowercase hex |

The stable variant is `:app:assembleStableDev`. It inherits debug
testability/`run-as` but never substitutes the Gradle runner's transient
debug keystore. The signing job fails when any secret is missing. The
`ci/verify-signer.sh` gate checks exactly two ABI-specific APKs, the one
expected certificate, and records APK SHA-256, source SHA and ABI before
publishing. Ordinary `assembleDebug` outputs must **not** be delivered
as updateable user builds.

Key creation, secret provisioning, certificate comparison across two separate
runs, and keeping offline copies are **human/secure-environment actions**
and are not completed by adding this configuration. Never put a base64 key
in a PR, issue, chat, artifact or log.

## Physical same-key upgrade acceptance

After the protected signer is actually provisioned, seed each physical phone
with EPUB + TXT + paper books, nonzero saved positions, bookmarks and completed
reading sessions under a baseline APK **signed with that same key**.

Then, on the host:

```sh
bash ci/device-update.sh C67 C67_ADB_SERIAL app-arm64-v8a-stableDev.apk CERT_SHA256
bash ci/device-update.sh A1 A1_ADB_SERIAL app-armeabi-v7a-stableDev.apk CERT_SHA256
```

The script reads the **installed** APK's actual signer, verifies its exact
match against the candidate signer before installation, captures the app's
private book files/SQLite rows, performs only `adb install -r`, captures
them again, and compares all logical content. It refuses a foreign-key
upgrade, missing `run-as`, absent seed records or a data mismatch. It never
uninstalls or clears an app. Raw private archives are temporary and deleted
at script exit. Run/commit/certificate, APK identity and actual device results
must be retained in independent AI-CI receipts, not inferred from unit tests.

**Status:** The repair branch provides code and scripts. It does not certify
the protected secrets, prior-device migration, successful CI, C67/A1 execution
or acceptance. PR #1 must remain open until independent receipts exist.
