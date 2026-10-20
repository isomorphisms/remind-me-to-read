# Remind Me to Read

A local-first Android reading tracker for EPUBs and physical books. No account and no telemetry.

## What works in the first Android implementation

- Import user-selected DRM-free EPUB documents via Android's document picker; make private copies for reading after reboot.
- Parse EPUB container, OPF metadata, and spine; show chapter text within the app. Previous/next chapter, scroll position and manual bookmarks persist.
- Automatically count time spent in the foreground **inside this EPUB reader**. Store start/end/duration in SQLite and show last-read date and total time.
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

The EPUB display is intentionally **text-first**, not a full EPUB3 engine: CSS, image-heavy books, fixed layouts, DRM, footnotes, and some malformed XHTML are not supported. Imported EPUBs are limited to 100 MiB; single decompressed chapters to 4 MiB. The reader avoids executing embedded scripts or loading external book resources.

For paper books, Stop reading is manual: the application cannot know when you physically put down the book. Durations are recorded on Stop. EPUB reading time counts only while its Activity is foregrounded and rounds down to whole seconds.

Notifications require Android's runtime notification permission on Android 13+. Inexact alarms can be delayed by system power management; no exact-alarm privilege is requested.

GPT-OSS weights are **not bundled on Android**. In particular, gpt-oss-20b does not fit the intended low-RAM phones. Host it separately and supply an HTTPS API endpoint; tracking and reminders always work without any model or network connection. Review the receiver's data handling before connecting a third-party host.

## Next qualification

- Physical-device tests on MIRO C67: import, resume, notifications, battery mode, screen lock, process death.
- EPUB corpus tests for differing package paths and real-world XHTML, and proper reflow/illustrations if desired.
- Editing/correcting sessions, backup/export and book deletion.
- Integration adapters for other EPUB readers, only where they expose supported progress data.
