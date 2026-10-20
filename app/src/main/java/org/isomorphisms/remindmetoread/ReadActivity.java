package org.isomorphisms.remindmetoread;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** EPUB foreground sessions are automatic; physical sessions are explicitly started/stopped. */
public final class ReadActivity extends Activity {
    private BookStore store;
    private BookStore.Book book;
    private ScrollView scroll;
    private TextView content;
    private TextView location;
    private List<String> chapters = new ArrayList<>();
    private int chapter, savedScrollY, readRequest;
    private long foregroundSinceMs, foregroundSinceElapsed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new BookStore(this);
        book = store.get(getIntent().getLongExtra("book_id", -1));
        if (book == null) { finish(); return; }
        if (book.isPaper()) showPaper();
        else {
            chapter = book.chapter;
            savedScrollY = book.scrollY;
            showEpub();
            loadSpine();
        }
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, float size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(px(8), px(8), px(8), px(8));
        return view;
    }

    private Button button(String caption, Runnable command) {
        Button b = new Button(this);
        b.setText(caption);
        b.setAllCaps(false);
        b.setOnClickListener(v -> command.run());
        return b;
    }

    private LinearLayout column() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        result.setPadding(px(12), px(12), px(12), px(12));
        return result;
    }

    private void showPaper() {
        book = store.get(book.id);
        LinearLayout root = column();
        ScrollView outer = new ScrollView(this);
        outer.addView(root);
        setContentView(outer);
        root.addView(text(book.title, 24));
        root.addView(text("Physical book · " + book.totalSeconds / 60 + " minutes recorded", 15));
        root.addView(text("Current page: " + book.currentPage + " of " + book.totalPages, 16));

        EditText page = new EditText(this);
        page.setSingleLine(true);
        page.setInputType(InputType.TYPE_CLASS_NUMBER);
        page.setHint("Current page");
        page.setText(Integer.toString(book.currentPage));
        root.addView(page);
        root.addView(button("Save page / bookmark", () -> {
            try {
                int next = Integer.parseInt(page.getText().toString().trim());
                if (next < 0 || next > book.totalPages) {
                    page.setError("Enter 0 to " + book.totalPages);
                    return;
                }
                store.page(book.id, next);
                Toast.makeText(this, "Page saved", Toast.LENGTH_SHORT).show();
                showPaper();
            } catch (NumberFormatException e) {
                page.setError("Enter a page number");
            }
        }));
        boolean running = book.readingSince > 0;
        root.addView(text(running
                ? "Session running since " + android.text.format.DateFormat.format("MMM d, h:mm a", book.readingSince)
                : "Start a session before reading. Stop it when finished, even if the phone was locked.", 15));
        root.addView(button(running ? "Stop reading and save time" : "Start reading", () -> {
            if (running) store.stopPaper(book.id);
            else store.startPaper(book.id);
            showPaper();
        }));
        root.addView(button("Back to library", this::finish));
    }

    private void showEpub() {
        LinearLayout root = column();
        setContentView(root);
        root.addView(text(book.title, 22));
        location = text("Loading EPUB…", 14);
        root.addView(location);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button previous = button("◀ Previous", () -> switchChapter(chapter - 1));
        Button next = button("Next ▶", () -> switchChapter(chapter + 1));
        controls.addView(previous, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(controls);

        scroll = new ScrollView(this);
        content = text("Reading file…", 19);
        content.setTextIsSelectable(true);
        content.setLineSpacing(px(4), 1.08f);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        root.addView(button("Save bookmark", () -> {
            remember();
            Toast.makeText(this, "Bookmark saved", Toast.LENGTH_SHORT).show();
        }));
    }

    private void loadSpine() {
        File file = new File(book.epubPath);
        new Thread(() -> {
            try {
                Epub.Info info = Epub.inspect(file);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    chapters = info.chapters;
                    chapter = Math.min(Math.max(0, chapter), chapters.size() - 1);
                    loadChapter();
                });
            } catch (Exception ex) {
                error(ex);
            }
        }, "epub-spine").start();
    }

    private void switchChapter(int next) {
        if (next < 0 || next >= chapters.size()) return;
        remember();
        chapter = next;
        savedScrollY = 0;
        store.bookmarkEpub(book.id, chapter, 0);
        loadChapter();
    }

    private void loadChapter() {
        if (chapters.isEmpty()) return;
        int request = ++readRequest;
        int expectedChapter = chapter;
        int offset = savedScrollY;
        location.setText("Chapter " + (chapter + 1) + " / " + chapters.size());
        content.setText("Loading chapter…");
        scroll.scrollTo(0, 0);
        new Thread(() -> {
            try {
                String words = Epub.chapterText(new File(book.epubPath), chapters.get(expectedChapter));
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || readRequest != request) return;
                    content.setText(words);
                    scroll.post(() -> {
                        if (readRequest == request) scroll.scrollTo(0, offset);
                    });
                });
            } catch (Exception ex) {
                error(ex);
            }
        }, "epub-chapter").start();
    }

    private void remember() {
        if (book != null && !book.isPaper() && scroll != null && !chapters.isEmpty()) {
            savedScrollY = Math.max(0, scroll.getScrollY());
            store.bookmarkEpub(book.id, chapter, savedScrollY);
        }
    }

    private void error(Exception exception) {
        String message = exception.getMessage() == null ? "Could not open EPUB" : exception.getMessage();
        runOnUiThread(() -> {
            if (!isDestroyed() && !isFinishing())
                new AlertDialog.Builder(this).setTitle("EPUB error").setMessage(message)
                        .setPositiveButton("OK", null).show();
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (book != null && !book.isPaper() && foregroundSinceElapsed == 0) {
            foregroundSinceMs = System.currentTimeMillis();
            foregroundSinceElapsed = SystemClock.elapsedRealtime();
        }
    }

    @Override protected void onPause() {
        if (book != null && !book.isPaper()) {
            remember();
            if (foregroundSinceElapsed != 0) {
                long elapsed = SystemClock.elapsedRealtime() - foregroundSinceElapsed;
                store.recordForeground(book.id, foregroundSinceMs, elapsed);
                foregroundSinceElapsed = 0;
            }
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        ++readRequest;
        if (store != null) store.close();
        super.onDestroy();
    }
}
