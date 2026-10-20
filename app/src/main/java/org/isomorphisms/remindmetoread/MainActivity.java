package org.isomorphisms.remindmetoread;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
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
import java.io.InputStream;
import java.text.DateFormat;
import java.util.List;
import java.util.UUID;

/** Native Android shell. No account, analytics, or access to other apps' reading data. */
public final class MainActivity extends Activity {
    private static final int OPEN_EPUB = 101;
    private BookStore store;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new BookStore(this);
        Reminder.channel(this);
        showHome();
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
        root.addView(label("Local reading history — EPUB and paper", 14));
        root.addView(button("Import an EPUB", this::chooseEpub));
        root.addView(button("Add a physical book", this::addPaperDialog));
        boolean enabled = Reminder.enabled(this);
        root.addView(button(enabled ? "Daily reminder: on (change or disable)" :
                "Enable a daily reading reminder", this::reminderDialog));
        root.addView(button("Ask GPT-OSS (optional)", this::gptDialog));
        root.addView(label("Your books", 22));

        List<BookStore.Book> books = store.all();
        if (books.isEmpty()) root.addView(label("Import an EPUB or add a paper book to begin.", 15));
        for (BookStore.Book book : books) {
            String subtitle = book.isPaper()
                    ? "Paper · page " + book.currentPage + " / " + book.totalPages
                    : "EPUB · chapter " + (book.chapter + 1);
            String duration = duration(book.totalSeconds);
            String last = book.lastReadAt == 0 ? "Not yet read"
                    : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(book.lastReadAt);
            root.addView(label(book.title, 19));
            if (!book.author.isEmpty()) root.addView(label(book.author, 13));
            root.addView(label(subtitle + " · " + duration + " read", 14));
            root.addView(label("Last read: " + last +
                    (book.readingSince > 0 ? " · session running" : ""), 13));
            root.addView(button(book.readingSince > 0 ? "Resume session" : "Read / log time",
                    () -> open(book.id)));
            View line = new View(this);
            line.setBackgroundColor(0xffdddddd);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, px(1));
            p.setMargins(0, px(10), 0, px(12));
            root.addView(line, p);
        }
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

    private void chooseEpub() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/epub+zip", "application/octet-stream"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i, OPEN_EPUB);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != OPEN_EPUB || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        Toast.makeText(this, "Importing EPUB…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File target = null;
            try {
                File directory = new File(getFilesDir(), "epubs");
                if (!directory.exists() && !directory.mkdirs()) throw new Exception("Cannot create books folder");
                target = new File(directory, UUID.randomUUID() + ".epub");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(target)) {
                    if (in == null) throw new Exception("Cannot read selected document");
                    byte[] buffer = new byte[8192];
                    long bytes = 0;
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        bytes += n;
                        if (bytes > 100L * 1024 * 1024) throw new Exception("EPUB exceeds 100 MiB");
                        out.write(buffer, 0, n);
                    }
                }
                Epub.Info info = Epub.inspect(target);
                long bookId = store.addEpub(info.title, info.author, target.getAbsolutePath());
                runOnUiThread(() -> { showHome(); open(bookId); });
            } catch (Exception ex) {
                if (target != null) target.delete();
                String message = ex.getMessage() == null ? "Cannot import this EPUB" : ex.getMessage();
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setMessage(message).setPositiveButton("OK", null).show());
            }
        }, "epub-import").start();
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
