# Remind Me to Read

A local-first Android reading tracker for EPUB, TXT and physical books. No account and no telemetry.

## What works in the first Android implementation

- Import user-selected DRM-free EPUB and plain-text TXT documents via Android's document picker; make private copies for reading after reboot.
- TXT supports UTF-8, UTF-8/UTF-16 BOMs, and legacy Windows-1252 fallback (6 MiB input limit); reading progress uses a stable character offset, not a screen-pixel position.
- Parse EPUB container, OPF metadata, and spine; show chapter text within the app. Previous/next chapter and auto-saved reading position persist.
- ReadEra / Google Play Books-inspired marks: quick ★ bookmark, multiple saved marks to jump to or remove, document/chapter progress slider and approximate screen-page indicator. Saved marks never replace the last-read position.
- Export all reading markers, timestamps, durations and progress as a human-readable .txt file chosen via Android's document picker.
- Automatically count time spent in the foreground **inside this EPUB/TXT reader**. Store start/end/duration in SQLite and show last-read date and total time.
- Add physical books with title, author, page count, and current-page bookmark. Explicit **Start reading / Stop reading** sessions survive screen lock, app backgrounding, and process restart.
- Opt into daily, approximately timed Android notifications for unfinished books. Skip the notification if the selected book was already read today. Reminders are **off by default**.
- Optional **gpt-oss-20b** prompt to a user-configured OpenAI-compatible HTTPS chat-completions server. This only runs after tapping **Send**, and submits metadata/progress/time, never EPUB content. No provider credentials are saved.

## Build

Use JDK 17 and Android SDK API 35. With Gradle 8.9 installed:

    gradle :app:testDebugUnitTest :app:assembleDebug

Install the result on Android 8 or later:

    adb install -r app/build/outputs/apk/debug/app-debug.apk

GitHub Actions runs tests/build and uploads a **standalone .apk** artifact on pushes and PRs. There is no wrapper JAR in the first revision; CI installs its pinned Gradle version.

## Important boundaries

Android's app sandbox does not permit reading another app's private EPUB bookmarks or reading-session history. This implementation measures its own reader sessions; importing an EPUB file does not recover how much time the user spent in an unrelated reader. Future integrations can use an explicitly supported export or integration from that reader, never pretend notification access gives reading history.

The EPUB display is intentionally **text-first**, not a full EPUB3 engine: CSS, image-heavy books, fixed layouts, DRM, footnotes, and some malformed XHTML are not supported. Imported EPUBs are limited to 100 MiB; single decompressed chapters to 4 MiB. Plain-text files are limited to 6 MiB and rendered in memory; large files may need a chunked renderer. The reader avoids executing embedded scripts or loading external book resources.

For paper books, Stop reading is manual: the application cannot know when you physically put down the book. Durations are recorded on Stop. EPUB/TXT reading time counts only while its Activity is foregrounded and rounds down to whole seconds.

Notifications require Android's runtime notification permission on Android 13+. Inexact alarms can be delayed by system power management; no exact-alarm privilege is requested.

GPT-OSS weights are **not bundled on Android**. In particular, gpt-oss-20b does not fit the intended low-RAM phones. Host it separately and supply an HTTPS API endpoint; tracking and reminders always work without any model or network connection. Review the receiver's data handling before connecting a third-party host.


## Folder scanning and ReadEra savepoints (0.3.0)

The home screen leads with **Scan books and ReadEra savepoints**. Select a folder through Android's system picker (books, SD card subfolder, or shared-storage area), granting access once. Subsequent launches rescan approved folders approximately every 12 hours. **More → Rescan authorized folders** forces a scan. Multiple folders can be added. Per-folder limits: 20,000 files visited, 1,000 books added, 20 ReadEra backups. The scanner reports skipped/unreadable items.

Scan EPUB, TXT and BAK, deduplicating EPUB/TXT by exact SHA-1 of document bytes (and URI for repeat scans). Books are copied to the app's private library. No unrestricted Android storage permission.

ReadEra's private live database is isolated by Android 14; use its exported backup:

1. In ReadEra choose **Settings → Backup & Restore → Create a backup**.
2. Choose **More → Scan ReadEra backup folder** and select the backup folder (often Internal storage / ReadEra / BackUps), or **More → Import one ReadEra .bak**.
3. Scan the folder containing corresponding EPUB/TXT documents.
4. The application matches saved positions to actual files by SHA-1 (not by title guesses). Matched books show ReadEra last-read time and approximate percent, while unmatched records are retained in the library.
5. In the reader, tap the **ReadEra savepoint** button to explicitly estimate where to resume.

ReadEra backup files are ZIP files holding library.json. The parser extracts document hash, original serialized position, and last-read time; keeps raw location information for possible future accurate XPath-based anchoring. EPUB percentage mapping is **approximate**, because this text-only renderer has different pagination and does not yet interpret ReadEra XPath precisely. An external timestamp is not a duration, so no reading minutes are invented. Highlight/note conversion is not yet included.

## Photograph one physical book or a stack

Tap **Photograph a book or a stack**. Take a full-sized picture with the phone camera or select a gallery image. Bundled Latin-script Google ML Kit OCR runs entirely on the device and tries four rotations to find vertical spine titles. It proposes separate candidate titles; edit the multiline list to remove mistaken publisher names and fragments before confirming. One image can add multiple books. Nothing is sent to a remote OCR or GPT server.

Photo-added books start with an unknown page count: open one and choose **Edit title, author and page count** to fill in the metadata. Manual physical-book entry remains under **More**. Bundled OCR increases APK size substantially; physical C67 OCR accuracy, real backup format variations and external acceptance are still pending.

## Next qualification

- Physical-device tests on MIRO C67: import, resume, notifications, battery mode, screen lock, process death.
- EPUB corpus tests for differing package paths and real-world XHTML, and proper reflow/illustrations if desired.
- Multi-bookmark and exact character-offset restore testing on the C67; exported TXT marker round trips.
- Reference behavior and limitations: [Reader markers comparison](docs/reader-reference.md).
- Editing/correcting sessions, backup/export and book deletion.
- Integration adapters for other EPUB readers, only where they expose supported progress data.

## Signing, complete ZIP backups and safe upgrades (repair branch)

See [Safe upgrades, signed builds, legacy app rescue and C67/A1 acceptance](docs/safe-upgrades.md).

The human-readable TXT progress report is not a complete data backup. New builds
also expose a validated **complete ZIP backup** with imported EPUB/TXT bytes,
books, positions, bookmarks, reading sessions and ReadEra records, plus
**restore to an empty library only**. Do not uninstall a differently signed
old APK until an independently verified complete data export exists.

Android CI no longer distributes runner-signed debug APKs. Protected
`stableDev` builds require one permanent signing keystore in secured
environment secrets. The certificate/ABI publishing gate and physical-device
upgrade tests are separate from ordinary JVM/Android compilation. Successful
unit tests alone do not qualify a safe update.

