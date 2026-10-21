package org.isomorphisms.remindmetoread;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Date;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.List;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import android.content.SharedPreferences;

/** Native Android shell. No account, analytics, or access to other apps' reading data. */
public final class MainActivity extends Activity {
    private static final int OPEN_EPUB = 101;
    private static final int EXPORT_MARKERS = 102;
    private static final int PICK_BOOK_FOLDER = 103;
    private static final int PICK_BACKUP_FOLDER = 104;
    private static final int PICK_READBAK = 105;
    private static final int EXPORT_COMPLETE = 106;
    private static final int RESTORE_COMPLETE = 107;
    private boolean backupRunning;
    private boolean scanRunning;
    private String scanMessage = "";
    private BookStore store;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new BookStore(this);
        Reminder.channel(this);
        showHome();
        long last = getPreferences(MODE_PRIVATE).getLong("scan_last_ms", 0);
        if (System.currentTimeMillis() - last > 12L * 60 * 60 * 1000) scanSavedFolders(false);
    }

    @Override protected void onResume() {
        super.onResume();
        if (store != null) showHome();
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private TextView label(String value, float size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setPadding(0, px(6), 0, px(6));
        return text;
    }

    private Button button(String caption, Runnable command) {
        Button b = new Button(this);
        b.setText(caption);
        b.setAllCaps(false);
        b.setOnClickListener(v -> command.run());
        return b;
    }

    private void showHome() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(16), px(16), px(16), px(32));
        scroll.addView(root);
        setContentView(scroll);

        root.addView(label("Remind Me to Read", 27));
        root.addView(label("Local library · EPUB, TXT and physical books", 14));
        root.addView(button("Scan books and ReadEra savepoints", () -> pickFolder(false)));
        root.addView(button("Photograph a book or a stack", () ->
                startActivity(new Intent(this, PhotoBooksActivity.class))));
        root.addView(button("More: folders, backups, manual entry, settings", this::otherActions));
        if (!scanMessage.isEmpty()) root.addView(label(scanMessage, 14));
        root.addView(label("Your books", 22));

        List<BookStore.Book> books = store.all();
        if (books.isEmpty()) root.addView(label("Scan a books folder, photograph book spines, or import a ReadEra backup.", 15));
        for (BookStore.Book book : books) {
            String subtitle = book.isPaper()
                    ? "Paper · page " + book.currentPage + (book.totalPages == 0 ? " (total unknown)" : " / " + book.totalPages)
                    : book.isText()
                    ? "TXT · " + (PlainText.perMille(book.charOffset, book.contentLength) / 10.0) + "% read"
                    : "EPUB · chapter " + (book.chapter + 1);
            String duration = duration(book.totalSeconds);
            String last = book.lastReadAt == 0 ? "Not yet read"
                    : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(book.lastReadAt);
            root.addView(label(book.title, 19));
            if (!book.author.isEmpty()) root.addView(label(book.author, 13));
            root.addView(label(subtitle + " · " + duration + " read", 14));
            root.addView(label("Last read here: " + last +
                    (book.readingSince > 0 ? " · session running" : ""), 13));
            BookStore.ReadEraPoint external = store.readeraFor(book);
            if (external != null) {
                String when = external.lastReadMs == 0 ? "unknown date"
                    : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(external.lastReadMs);
                root.addView(label("ReadEra savepoint: ~" + Math.round(external.ratio * 1000) / 10.0 +
                        "% · last ReadEra activity " + when, 13));
            }
            root.addView(button(book.readingSince > 0 ? "Resume session" : "Read / log time",
                    () -> open(book.id)));
            View line = new View(this);
            line.setBackgroundColor(0xffdddddd);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, px(1));
            p.setMargins(0, px(10), 0, px(12));
            root.addView(line, p);
        }
        showUnmatchedReadEra(root);
    }

    private void showUnmatchedReadEra(LinearLayout root) {
        List<BookStore.ReadEraPoint> points = store.unmatchedReadEra();
        if (points.isEmpty()) return;
        root.addView(label("ReadEra savepoints — book file not found yet", 19));
        root.addView(label("Scan the folder containing those books. Saved positions match by SHA-1, not guessed titles.", 13));
        int shown = Math.min(20, points.size());
        for (int i = 0; i < shown; i++) {
            BookStore.ReadEraPoint point = points.get(i);
            root.addView(label(point.title + " · ~" +
                    Math.round(point.ratio * 1000) / 10.0 + "%", 14));
        }
        if (points.size() > shown) root.addView(label("And more saved ReadEra books…", 13));
    }

    private static String duration(long seconds) {
        long minutes = seconds / 60;
        return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + " min";
    }

    private void open(long id) {
        Intent i = new Intent(this, ReadActivity.class);
        i.putExtra("book_id", id);
        startActivity(i);
    }

    private void otherActions() {
        String[] options = {
                "Scan another book folder",
                "Scan ReadEra backup folder",
                "Import one ReadEra .bak",
                "Rescan authorized folders",
                "Import one EPUB or TXT",
                "Enter physical book manually",
                "Export progress to TXT",
                "Complete backup to ZIP (books, bookmarks, sessions)",
                "Restore complete ZIP (empty library only)",
                Reminder.enabled(this) ? "Change/disable daily reminder" : "Enable daily reminder",
                "Ask GPT-OSS (optional)"
        };
        new AlertDialog.Builder(this).setTitle("Reading library")
                .setItems(options, (dialog, selected) -> {
                    switch (selected) {
                        case 0: pickFolder(false); break;
                        case 1: pickFolder(true); break;
                        case 2: pickReadEraBackup(); break;
                        case 3: scanSavedFolders(true); break;
                        case 4: chooseEpub(); break;
                        case 5: addPaperDialog(); break;
                        case 6: exportMarkers(); break;
                        case 7: chooseCompleteBackup(); break;
                        case 8: chooseCompleteRestore(); break;
                        case 9: reminderDialog(); break;
                        case 10: gptDialog(); break;
                    }
                }).setNegativeButton("Close", null).show();
    }

    private void pickFolder(boolean backupsOnly) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, backupsOnly ? PICK_BACKUP_FOLDER : PICK_BOOK_FOLDER);
    }

    private void pickReadEraBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, PICK_READBAK);
    }

    private void acceptFolder(Uri uri, boolean backupsOnly) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            SharedPreferences preferences = getPreferences(MODE_PRIVATE);
            Set<String> stored = new HashSet<>(preferences.getStringSet(
                    backupsOnly ? "backups_trees" : "book_trees",
                    java.util.Collections.emptySet()));
            stored.add(uri.toString());
            preferences.edit().putStringSet(backupsOnly ? "backups_trees" : "book_trees",
                    stored).apply();
            scanSavedFolders(true);
        } catch (SecurityException ex) {
            new AlertDialog.Builder(this).setTitle("Folder permission unavailable")
                    .setMessage(ex.getMessage()).setPositiveButton("OK", null).show();
        }
    }

    private void scanSavedFolders(boolean explicit) {
        if (scanRunning || backupRunning) return;
        SharedPreferences p = getPreferences(MODE_PRIVATE);
        Set<String> books = new HashSet<>(p.getStringSet("book_trees",
                java.util.Collections.emptySet()));
        Set<String> backups = new HashSet<>(p.getStringSet("backups_trees",
                java.util.Collections.emptySet()));
        if (books.isEmpty() && backups.isEmpty()) {
            if (explicit) new AlertDialog.Builder(this).setMessage(
                    "Choose a books folder or a ReadEra backup folder first.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        scanRunning = true;
        scanMessage = "Scanning authorized folders…";
        showHome();
        new Thread(() -> {
            LibraryScanner.Summary total = new LibraryScanner.Summary();
            try (BookStore db = new BookStore(this)) {
                LibraryScanner.indexExisting(db);
                for (String uri : books) accumulate(total, LibraryScanner.scan(this, Uri.parse(uri), db, false));
                for (String uri : backups) {
                    if (!books.contains(uri))
                        accumulate(total, LibraryScanner.scan(this, Uri.parse(uri), db, true));
                }
                scanMessage = total.report();
            } catch (Exception ex) {
                scanMessage = "Scan stopped: " + ex.getMessage() + " · " + total.report();
            }
            getPreferences(MODE_PRIVATE).edit().putLong("scan_last_ms",
                    System.currentTimeMillis()).apply();
            runOnUiThread(() -> {
                scanRunning = false;
                if (!isFinishing() && !isDestroyed()) showHome();
            });
        }, "books-folder-scan").start();
    }

    private static void accumulate(LibraryScanner.Summary a, LibraryScanner.Summary b) {
        a.visited += b.visited;
        a.booksAdded += b.booksAdded;
        a.alreadyPresent += b.alreadyPresent;
        a.backupsRead += b.backupsRead;
        a.readeraPoints += b.readeraPoints;
        a.errors += b.errors;
    }

    private void importReadEraBackup(Uri uri) {
        scanMessage = "Scanning ReadEra backup…";
        showHome();
        new Thread(() -> {
            try (BookStore db = new BookStore(this)) {
                LibraryScanner.indexExisting(db);
                int n = LibraryScanner.importBackup(this, uri, db);
                scanMessage = n + " ReadEra savepoints found. Only exact file-hash matches are attached to books.";
            } catch (Exception e) {
                scanMessage = "ReadEra import failed: " + e.getMessage();
            }
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) showHome(); });
        }, "readera-backup-import").start();
    }

    private void chooseEpub() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/epub+zip", "text/plain", "application/octet-stream"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i, OPEN_EPUB);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == EXPORT_MARKERS) {
            exportTo(data.getData());
            return;
        }
        if (request == EXPORT_COMPLETE) {
            performCompleteBackup(data.getData(), false);
            return;
        }
        if (request == RESTORE_COMPLETE) {
            new AlertDialog.Builder(this).setTitle("Restore into an empty library only?")
                    .setMessage("Restore checks every book and database record before adding anything. " +
                            "It will REFUSE to overwrite existing reading data. " +
                            "Do not uninstall any older differently signed app until you have verified a complete export.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Validate and restore", (d, w) ->
                            performCompleteBackup(data.getData(), true)).show();
            return;
        }
        if (request == PICK_BOOK_FOLDER || request == PICK_BACKUP_FOLDER) {
            acceptFolder(data.getData(), request == PICK_BACKUP_FOLDER);
            return;
        }
        if (request == PICK_READBAK) {
            String picked = displayName(data.getData()).toLowerCase(Locale.ROOT);
            if (!picked.endsWith(".bak")) {
                new AlertDialog.Builder(this).setMessage("Choose a ReadEra .bak backup.")
                        .setPositiveButton("OK", null).show();
                return;
            }
            importReadEraBackup(data.getData());
            return;
        }
        if (request != OPEN_EPUB) return;
        Uri uri = data.getData();
        String displayName = displayName(uri);
        String mimeType = getContentResolver().getType(uri);
        boolean plain = displayName.toLowerCase(Locale.ROOT).endsWith(".txt") ||
                "text/plain".equalsIgnoreCase(mimeType);
        boolean epub = displayName.toLowerCase(Locale.ROOT).endsWith(".epub") ||
                "application/epub+zip".equalsIgnoreCase(mimeType);
        if (!plain && !epub) {
            new AlertDialog.Builder(this).setMessage("Choose a .txt or .epub document.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        Toast.makeText(this, "Importing " + (plain ? "TXT" : "EPUB") + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File target = null;
            try {
                File directory = new File(getFilesDir(), "epubs");
                if (!directory.exists() && !directory.mkdirs()) throw new Exception("Cannot create books folder");
                target = new File(directory, UUID.randomUUID() + (plain ? ".txt" : ".epub"));
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(target)) {
                    if (in == null) throw new Exception("Cannot read selected document");
                    byte[] buffer = new byte[8192];
                    long bytes = 0;
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        bytes += n;
                        if (bytes > (plain ? PlainText.MAX_BYTES : 100L * 1024 * 1024))
                            throw new Exception(plain ? "TXT exceeds 6 MiB" : "EPUB exceeds 100 MiB");
                        out.write(buffer, 0, n);
                    }
                }
                long bookId;
                if (plain) {
                    String words = PlainText.load(target);
                    String title = displayName.replaceFirst("(?i)\\.txt$", "");
                    bookId = store.addText(title, target.getAbsolutePath(), words.length());
                } else {
                    Epub.Info info = Epub.inspect(target);
                    bookId = store.addEpub(info.title, info.author, target.getAbsolutePath());
                }
                String sha = LibraryScanner.hash(target);
                store.source(bookId, uri.toString(), sha);
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) { showHome(); open(bookId); } });
            } catch (Exception ex) {
                if (target != null) target.delete();
                String message = ex.getMessage() == null ? "Cannot import this EPUB" : ex.getMessage();
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setMessage(message).setPositiveButton("OK", null).show());
            }
        }, "epub-import").start();
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) { }
        return "Untitled";
    }

    private void chooseCompleteBackup() {
        if (scanRunning || backupRunning) {
            Toast.makeText(this, "Another library operation is running", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, "remind-me-to-read-complete.zip");
        startActivityForResult(intent, EXPORT_COMPLETE);
    }

    private void chooseCompleteRestore() {
        if (scanRunning || backupRunning) {
            Toast.makeText(this, "Another library operation is running", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, RESTORE_COMPLETE);
    }

    private void performCompleteBackup(Uri uri, boolean restoring) {
        if (backupRunning || scanRunning) {
            Toast.makeText(this, "Another library operation is running", Toast.LENGTH_LONG).show();
            return;
        }
        backupRunning = true;
        scanMessage = restoring ? "Checking complete backup…" : "Writing and verifying complete ZIP backup…";
        showHome();
        new Thread(() -> {
            String message;
            try {
                int books = restoring ? PortableBackup.restore(this, uri)
                        : PortableBackup.export(this, uri);
                message = (restoring ? "Restored " : "Verified backup of ") + books +
                        " books, including bookmarks, positions, sessions and ReadEra records.";
            } catch (Exception ex) {
                message = (restoring ? "Restore refused: " : "Backup NOT verified: ") + ex.getMessage();
            }
            final String report = message;
            runOnUiThread(() -> {
                backupRunning = false;
                scanMessage = report;
                if (!isFinishing() && !isDestroyed()) showHome();
                new AlertDialog.Builder(this).setMessage(report).setPositiveButton("OK", null).show();
            });
        }, restoring ? "complete-restore" : "complete-export").start();
    }

    private void exportMarkers() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, "reading-progress.txt");
        startActivityForResult(i, EXPORT_MARKERS);
    }

    private void exportTo(Uri uri) {
        StringBuilder result = new StringBuilder("Remind Me to Read — reading progress\n");
        result.append("Exported: ").append(new Date()).append("\n\n");
        for (BookStore.Book b : store.all()) {
            result.append("Title: ").append(b.title).append("\n");
            result.append("Author: ").append(b.author).append("\n");
            if (b.isPaper()) {
                result.append("Physical pages: ").append(b.currentPage)
                        .append(" / ").append(b.totalPages).append("\n");
            } else if (b.isText()) {
                result.append("TXT character position: ").append(b.charOffset)
                        .append(" / ").append(b.contentLength).append("\n");
                result.append("Progress: ").append(
                        PlainText.perMille(b.charOffset, b.contentLength) / 10.0).append("%\n");
            } else {
                result.append("EPUB chapter: ").append(b.chapter + 1)
                        .append("\nCharacter position in chapter: ").append(b.charOffset).append("\n");
            }
            result.append("Last read: ").append(b.lastReadAt == 0
                    ? "never" : new Date(b.lastReadAt).toString()).append("\n");
            result.append("Last read (epoch milliseconds): ")
                    .append(b.lastReadAt).append("\n");
            result.append("Total reading seconds: ").append(b.totalSeconds).append("\n");
            for (BookStore.Mark mark : store.marks(b.id)) {
                result.append("Bookmark: ").append(mark.label)
                        .append(" | chapter=").append(mark.chapter + 1)
                        .append(" | character=").append(mark.charOffset)
                        .append(" | paper-page=").append(mark.page)
                        .append(" | saved-ms=").append(mark.savedAt).append("\n");
            }
            result.append("\n");
        }
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new Exception("Destination is unavailable");
            out.write(result.toString().getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "Progress TXT saved", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Export failed").setMessage(e.getMessage())
                    .setPositiveButton("OK", null).show();
        }
    }

    private EditText field(String hint) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setHint(hint);
        return edit;
    }

    private void addPaperDialog() {
        LinearLayout fields = new LinearLayout(this);
        fields.setPadding(px(18), 0, px(18), 0);
        fields.setOrientation(LinearLayout.VERTICAL);
        EditText title = field("Title");
        EditText author = field("Author (optional)");
        EditText pages = field("Number of pages");
        pages.setInputType(InputType.TYPE_CLASS_NUMBER);
        fields.addView(title);
        fields.addView(author);
        fields.addView(pages);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Physical book").setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(w -> {
                    String name = title.getText().toString().trim();
                    int total;
                    try { total = Integer.parseInt(pages.getText().toString().trim()); }
                    catch (NumberFormatException e) {
                        pages.setError("Enter a positive number"); return;
                    }
                    if (name.isEmpty()) { title.setError("Title required"); return; }
                    if (total < 1) { pages.setError("Enter a positive number"); return; }
                    long id = store.addPaper(name, author.getText().toString(), total);
                    dialog.dismiss();
                    showHome();
                    open(id);
                }));
        dialog.show();
    }

    private void reminderDialog() {
        if (Reminder.enabled(this)) {
            new AlertDialog.Builder(this).setTitle("Daily reminder")
                    .setMessage("Change its time or turn it off?")
                    .setPositiveButton("Change time", (d, w) -> pickReminder())
                    .setNegativeButton("Disable", (d, w) -> {
                        Reminder.disable(this); showHome();
                    }).setNeutralButton("Cancel", null).show();
        } else pickReminder();
    }

    private void pickReminder() {
        android.content.SharedPreferences p = getSharedPreferences("reading-settings", MODE_PRIVATE);
        int hour = p.getInt("hour", 20);
        int minute = p.getInt("minute", 0);
        new TimePickerDialog(this, (view, h, m) -> {
            Reminder.enable(this, h, m);
            if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 103);
            }
            showHome();
        }, hour, minute, false).show();
    }

    private void gptDialog() {
        BookStore.Book book = store.all().stream().findFirst().orElse(null);
        if (book == null) {
            Toast.makeText(this, "Add a book first", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout fields = new LinearLayout(this);
        fields.setPadding(px(18), 0, px(18), 0);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.addView(label("Sends only title, author, progress, and reading time to your configured HTTPS server. Never sends book text. A separate GPT-OSS host is required.", 14));
        EditText endpoint = field("HTTPS chat-completions endpoint");
        endpoint.setText(getPreferences(MODE_PRIVATE).getString("gpt-endpoint", ""));
        EditText apiKey = field("API key (optional, not saved)");
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(endpoint);
        fields.addView(apiKey);
        new AlertDialog.Builder(this).setTitle("GPT-OSS reading nudge")
                .setView(fields).setNegativeButton("Cancel", null)
                .setPositiveButton("Send", (d, w) -> {
                    String url = endpoint.getText().toString().trim();
                    getPreferences(MODE_PRIVATE).edit().putString("gpt-endpoint", url).apply();
                    new Thread(() -> {
                        try {
                            String answer = GptOss.suggest(url, apiKey.getText().toString(), book);
                            runOnUiThread(() -> new AlertDialog.Builder(this)
                                    .setTitle("GPT-OSS").setMessage(answer)
                                    .setPositiveButton("Close", null).show());
                        } catch (Exception ex) {
                            runOnUiThread(() -> new AlertDialog.Builder(this)
                                    .setTitle("GPT-OSS unavailable")
                                    .setMessage(ex.getMessage()).setPositiveButton("OK", null).show());
                        }
                    }, "gpt-oss-request").start();
                }).show();
    }

    @Override protected void onDestroy() {
        if (store != null) store.close();
        super.onDestroy();
    }
}
