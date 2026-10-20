# Reader markers: ReadEra / Google Play Books comparison

Sources reviewed 2026-10-20:

- ReadEra Google Play: https://play.google.com/store/apps/details?id=org.readera
- ReadEra documentation: https://readera.org/
- Google Play Books Android bookmarks/notes: https://support.google.com/googleplay/answer/3165868?co=GENIE.Platform%3DAndroid&hl=en

ReadEra supports TXT, EPUB, automatically saved reading position, manually created page bookmarks, a page pointer/progress line, and page history. Its documentation describes a quick bookmark gesture at the upper-right corner.

Google Play Books also uses the upper-right bookmark gesture. It can export bookmarks/notes/highlights to Google Drive as a document; these exports are not guaranteed to provide elapsed reading time or live reading position.

## Our corresponding behavior

| Feature | Remind Me to Read (current PR) |
|---|---|
| Plain-text TXT | Private local import, UTF-8/BOM/legacy Windows-1252 decoding |
| EPUB | Private import, text-first chapter viewer |
| Last position | Automatic save after scrolling and when backgrounded |
| Stable marker | UTF-16 offset into decoded text, per book/chapter, plus legacy pixel fallback |
| Quick bookmark | Star action, stored separately from the last position |
| Saved marks | Review/jump/delete, multiple per book |
| Progress line | Seekbar (0 to 100%, per TXT file or EPUB chapter) |
| Page pointer | **Approximate screenful count** from text layout and viewport height; varies with fonts and device |
| Physical books | Exact manually entered page number, manually timed sessions, bookmarks |
| Portable record | Export a human-readable \`.txt\` with positions, bookmarks, timestamps, accumulated time |
| Reflow | Stable character offsets avoid assuming that an old pixel scroll height is a durable position |

No proprietary ReadEra or Google Play Books account database is accessed. Android application isolation prevents reading their private bookmarks or time data. An optional future importer must use explicitly provided exports and document the provenance and limits; no notification permission grants access to reading positions.

## Follow-up tests

- MIRO C67 (Android 14) screen lock / process death / font-size and orientation changes.
- Verify import of UTF-8 and UTF-16 books, markers, export, and navigating over very long TXT documents.
- Stress test on low-memory devices; the current reader uses an in-memory text view with 6 MiB TXT cap.
- Add true font-aware pagination if users need stable print-like page numbers; the display currently labels its screenful estimate.
