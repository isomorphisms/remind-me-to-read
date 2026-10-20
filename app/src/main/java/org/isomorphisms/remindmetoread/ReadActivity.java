package org.isomorphisms.remindmetoread;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.Layout;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Reader with stable character-offset progress and independent manual page markers. */
public final class ReadActivity extends Activity {
    private BookStore store;
    private BookStore.Book book;
    private ScrollView scroll;
    private TextView content, location;
    private SeekBar progress;
    private List<String> chapters = new ArrayList<>();
    private String documentText = "";
    private int chapter, savedOffset, legacyScrollY, requestId;
    private boolean restoring = true, loaded;
    private long foregroundSinceMs, foregroundSinceElapsed;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable deferredPositionSave = this::remember;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new BookStore(this);
        book = store.get(getIntent().getLongExtra("book_id", -1));
        if (book == null) { finish(); return; }
        if (book.isPaper()) showPaper();
        else {
            chapter = book.chapter;
            savedOffset = book.charOffset;
            legacyScrollY = book.scrollY;
            showDigital();
            if (book.isText()) loadText();
            else loadEpubSpine();
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
        result.setPadding(px(8), px(8), px(8), px(8));
        return result;
    }

    private void showPaper() {
        book = store.get(book.id);
        LinearLayout root = column();
        ScrollView outer = new ScrollView(this);
        outer.addView(root);
        setContentView(outer);
        root.addView(text(book.title, 24));
        root.addView(button("Edit title, author and page count", this::editPhysicalBook));
        root.addView(text("Physical book · " + book.totalSeconds / 60 + " minutes recorded", 15));
        root.addView(text("Current page: " + book.currentPage + (book.totalPages == 0 ? " (total pages not set)" : " of " + book.totalPages), 16));

        EditText page = new EditText(this);
        page.setSingleLine(true);
        page.setInputType(InputType.TYPE_CLASS_NUMBER);
        page.setHint("Current page");
        page.setText(Integer.toString(book.currentPage));
        root.addView(page);
        root.addView(button("Save current page", () -> {
            try {
                int next = Integer.parseInt(page.getText().toString().trim());
                if (next < 0 || next > book.totalPages) {
                    page.setError("Enter 0 to " + book.totalPages);
                    return;
                }
                store.page(book.id, next);
                showPaper();
            } catch (NumberFormatException e) {
                page.setError("Enter a page number");
            }
        }));
        root.addView(button("★ Bookmark this page", () -> {
            savePaperPage(page);
            BookStore.Book current = store.get(book.id);
            store.addMark(book.id, 0, 0, current.currentPage, "Page " + current.currentPage);
            Toast.makeText(this, "Bookmark saved", Toast.LENGTH_SHORT).show();
        }));
        root.addView(button("View bookmarks", this::showMarks));
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

    private void editPhysicalBook() {
        BookStore.Book current = store.get(book.id);
        if (current == null) return;
        LinearLayout form = column();
        EditText title = new EditText(this);
        title.setSingleLine(true);
        title.setHint("Book title");
        title.setText(current.title);
        EditText author = new EditText(this);
        author.setSingleLine(true);
        author.setHint("Author (optional)");
        author.setText(current.author);
        EditText total = new EditText(this);
        total.setInputType(InputType.TYPE_CLASS_NUMBER);
        total.setSingleLine(true);
        total.setHint("Total pages (0 if unknown)");
        total.setText(Integer.toString(current.totalPages));
        form.addView(title);
        form.addView(author);
        form.addView(total);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Physical book details")
                .setView(form).setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(w -> {
                    String name = title.getText().toString().trim();
                    if (name.isEmpty()) { title.setError("Title required"); return; }
                    int pages;
                    try { pages = Integer.parseInt(total.getText().toString().trim()); }
                    catch (NumberFormatException ex) { total.setError("Enter a page count"); return; }
                    if (pages < 0) { total.setError("Pages cannot be negative"); return; }
                    store.changePaper(current.id, name, author.getText().toString(), pages);
                    dialog.dismiss();
                    showPaper();
                }));
        dialog.show();
    }

    private void savePaperPage(EditText page) {
        try {
            int current = Integer.parseInt(page.getText().toString().trim());
            if (current >= 0 && current <= book.totalPages) store.page(book.id, current);
        } catch (NumberFormatException ignored) { }
    }

    private void showDigital() {
        LinearLayout root = column();
        setContentView(root);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = text(book.title, 21);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(button("★", this::addDigitalMark));
        top.addView(button("Marks", this::showMarks));
        root.addView(top);

        location = text("Loading…", 13);
        root.addView(location);
        BookStore.ReadEraPoint readera = store.readeraFor(book);
        if (readera != null && readera.ratio > 0) {
            root.addView(button("ReadEra savepoint: ~" +
                    Math.round(readera.ratio * 1000) / 10.0 + "% · resume", () -> {
                new AlertDialog.Builder(this).setTitle("Use ReadEra savepoint?")
                    .setMessage("ReadEra's position is imported from its backup. " +
                            "This reader estimates the text location from its percentage; " +
                            "it may differ from ReadEra's exact page. " +
                            "Your current position will be replaced only if you confirm.")
                    .setPositiveButton("Estimate location", (d, w) -> useReadEraPosition(readera))
                    .setNegativeButton("Cancel", null).show();
            }));
        }

        if (!book.isText()) {
            LinearLayout controls = new LinearLayout(this);
            controls.setOrientation(LinearLayout.HORIZONTAL);
            controls.addView(button("◀ Previous chapter", () -> switchChapter(chapter - 1)),
                    new LinearLayout.LayoutParams(0, -2, 1));
            controls.addView(button("Next chapter ▶", () -> switchChapter(chapter + 1)),
                    new LinearLayout.LayoutParams(0, -2, 1));
            root.addView(controls);
        }

        scroll = new ScrollView(this);
        content = text("Loading document…", 18);
        content.setTextIsSelectable(true);
        content.setLineSpacing(px(4), 1.08f);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        progress = new SeekBar(this);
        progress.setMax(1000);
        progress.setContentDescription("Reading position in this document or chapter");
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (loaded) jumpTo(PlainText.offsetAt(seekBar.getProgress(), documentText.length()));
            }
        });
        root.addView(progress);

        scroll.setOnScrollChangeListener((v, sx, sy, ox, oy) -> {
            if (!loaded || restoring) return;
            showProgress();
            handler.removeCallbacks(deferredPositionSave);
            handler.postDelayed(deferredPositionSave, 1500);
        });
        root.addView(button("Save reading position", () -> {
            remember();
            Toast.makeText(this, "Position saved", Toast.LENGTH_SHORT).show();
        }));
    }

    private void loadEpubSpine() {
        File file = new File(book.epubPath);
        new Thread(() -> {
            try {
                Epub.Info info = Epub.inspect(file);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    chapters = info.chapters;
                    chapter = Math.min(Math.max(0, chapter), chapters.size() - 1);
                    loadEpubChapter();
                });
            } catch (Exception ex) {
                error(ex);
            }
        }, "epub-spine").start();
    }

    private void loadText() {
        ++requestId;
        int expected = requestId;
        new Thread(() -> {
            try {
                String words = PlainText.load(new File(book.epubPath));
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || requestId != expected) return;
                    display(words, expected);
                });
            } catch (Exception ex) { error(ex); }
        }, "txt-reader").start();
    }

    private void switchChapter(int next) {
        if (book.isText() || next < 0 || next >= chapters.size()) return;
        remember();
        chapter = next;
        savedOffset = 0;
        legacyScrollY = 0;
        loadEpubChapter();
    }

    private void loadEpubChapter() {
        if (chapters.isEmpty()) return;
        loaded = false;
        restoring = true;
        documentText = "";
        int request = ++requestId;
        int expectedChapter = chapter;
        location.setText("Chapter " + (chapter + 1) + " of " + chapters.size() + " — loading…");
        content.setText("Loading chapter…");
        new Thread(() -> {
            try {
                String words = Epub.chapterText(new File(book.epubPath), chapters.get(expectedChapter));
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || requestId != request) return;
                    display(words, request);
                });
            } catch (Exception ex) { error(ex); }
        }, "epub-chapter").start();
    }

    private void useReadEraPosition(BookStore.ReadEraPoint position) {
        if (!Double.isFinite(position.ratio) || position.ratio < 0 || position.ratio > 1) return;
        if (book.isText()) {
            if (loaded) jumpTo((int) Math.round(position.ratio * documentText.length()));
            return;
        }
        if (chapters.isEmpty()) {
            Toast.makeText(this, "EPUB still loading", Toast.LENGTH_SHORT).show();
            return;
        }
        // EPUB's CSS and renderer-dependent page numbers differ from our text-only reader.
        // Approximate the location by fraction of decoded text, never claim exact restoration.
        List<String> paths = new ArrayList<>(chapters);
        location.setText("Estimating ReadEra EPUB location…");
        new Thread(() -> {
            try {
                long[] lengths = new long[paths.size()];
                long total = 0;
                File file = new File(book.epubPath);
                for (int i = 0; i < paths.size(); i++) {
                    lengths[i] = Epub.chapterText(file, paths.get(i)).length();
                    total += lengths[i];
                }
                if (total <= 0) throw new java.io.IOException("EPUB has no text to position");
                long positionInAllText = Math.max(0, Math.min(total, Math.round(position.ratio * total)));
                long prefix = 0;
                int index = paths.size() - 1;
                for (int i = 0; i < paths.size(); i++) {
                    if (positionInAllText < prefix + lengths[i]) { index = i; break; }
                    prefix += lengths[i];
                }
                int targetChapter = index;
                int offset = (int) Math.max(0, Math.min(lengths[index], positionInAllText - prefix));
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    // Snapshot current EPUB position before switching chapters.
                    remember();
                    chapter = targetChapter;
                    savedOffset = offset;
                    legacyScrollY = 0;
                    loadEpubChapter();
                });
            } catch (Exception exception) {
                error(exception);
            }
        }, "readera-epub-position").start();
    }

    private void display(String words, int request) {
        restoring = true;
        loaded = false;
        documentText = words;
        content.setText(words);
        scroll.scrollTo(0, 0);
        int targetOffset = Math.max(0, Math.min(savedOffset, words.length()));
        int oldPixels = legacyScrollY;
        scroll.post(() -> {
            if (isDestroyed() || isFinishing() || request != requestId) return;
            Layout layout = content.getLayout();
            if (layout != null) {
                int targetY = (targetOffset == 0 && oldPixels > 0)
                        ? oldPixels : layout.getLineTop(layout.getLineForOffset(targetOffset));
                scroll.scrollTo(0, Math.max(0, targetY));
            }
            savedOffset = targetOffset;
            legacyScrollY = 0;
            loaded = true;
            restoring = false;
            showProgress();
        });
    }

    private int currentOffset() {
        if (!loaded || content == null || scroll == null) return savedOffset;
        Layout layout = content.getLayout();
        if (layout == null || documentText.isEmpty()) return 0;
        int maxY = Math.max(0, content.getHeight() - scroll.getHeight());
        // For a short document fitting on one screen, an explicit seek still marks progress.
        if (maxY == 0) return Math.max(0, Math.min(savedOffset, documentText.length()));
        if (scroll.getScrollY() >= maxY - px(2)) return documentText.length();
        int line = layout.getLineForVertical(Math.max(0, scroll.getScrollY()));
        return Math.min(documentText.length(), layout.getLineStart(line));
    }

    private void showProgress() {
        if (!loaded) return;
        int position = currentOffset();
        int perMille = PlainText.perMille(position, documentText.length());
        int viewHeight = Math.max(1, scroll.getHeight());
        int pages = Math.max(1, (content.getHeight() + viewHeight - 1) / viewHeight);
        int page = Math.min(pages, 1 + scroll.getScrollY() / viewHeight);
        String heading = book.isText()
                ? "TXT · " + (perMille / 10.0) + "%"
                : "Chapter " + (chapter + 1) + "/" + chapters.size() +
                    " · " + (perMille / 10.0) + "% of chapter";
        location.setText(heading + " · screen page ~" + page + "/" + pages);
        progress.setProgress(perMille);
    }

    private void jumpTo(int offset) {
        if (!loaded) return;
        restoring = true;
        savedOffset = Math.max(0, Math.min(offset, documentText.length()));
        int request = requestId;
        scroll.post(() -> {
            if (isDestroyed() || isFinishing() || request != requestId) return;
            Layout layout = content.getLayout();
            if (layout != null) {
                int targetY = layout.getLineTop(layout.getLineForOffset(savedOffset));
                scroll.scrollTo(0, Math.max(0, targetY));
            }
            restoring = false;
            showProgress();
            remember();
        });
    }

    private void remember() {
        if (book == null || !book.isDigital() || !loaded || restoring) return;
        savedOffset = currentOffset();
        store.position(book.id, chapter, savedOffset, Math.max(0, scroll.getScrollY()));
    }

    private void addDigitalMark() {
        if (!loaded) return;
        int offset = currentOffset();
        for (BookStore.Mark mark : store.marks(book.id)) {
            if (mark.chapter == chapter && mark.charOffset == offset) {
                store.deleteMark(book.id, mark.id);
                Toast.makeText(this, "Bookmark removed", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        String label = book.isText()
                ? "TXT " + (PlainText.perMille(offset, documentText.length()) / 10.0) + "%"
                : "Chapter " + (chapter + 1) + " · " +
                    (PlainText.perMille(offset, documentText.length()) / 10.0) + "%";
        store.addMark(book.id, chapter, offset, 0, label);
        remember();
        Toast.makeText(this, "Bookmark saved", Toast.LENGTH_SHORT).show();
    }

    private void showMarks() {
        List<BookStore.Mark> marks = store.marks(book.id);
        if (marks.isEmpty()) {
            Toast.makeText(this, "No bookmarks yet", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[marks.size()];
        for (int i = 0; i < marks.size(); i++) names[i] = marks.get(i).label;
        new AlertDialog.Builder(this).setTitle("Bookmarks")
                .setItems(names, (dialog, which) -> navigate(marks.get(which)))
                .setNeutralButton("Delete bookmark…", (dialog, which) -> deleteMarkDialog())
                .setNegativeButton("Close", null).show();
    }

    private void navigate(BookStore.Mark mark) {
        if (book.isPaper()) {
            store.page(book.id, mark.page);
            showPaper();
        } else if (!book.isText() && chapter != mark.chapter) {
            remember();
            chapter = mark.chapter;
            savedOffset = mark.charOffset;
            legacyScrollY = 0;
            loadEpubChapter();
        } else {
            jumpTo(mark.charOffset);
        }
    }

    private void deleteMarkDialog() {
        List<BookStore.Mark> marks = store.marks(book.id);
        String[] labels = new String[marks.size()];
        for (int i = 0; i < marks.size(); i++) labels[i] = marks.get(i).label;
        new AlertDialog.Builder(this).setTitle("Remove which bookmark?")
                .setItems(labels, (dialog, which) -> {
                    store.deleteMark(book.id, marks.get(which).id);
                    Toast.makeText(this, "Bookmark removed", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("Cancel", null).show();
    }

    private void error(Exception exception) {
        String message = exception.getMessage() == null ? "Could not read document" : exception.getMessage();
        runOnUiThread(() -> {
            if (!isDestroyed() && !isFinishing())
                new AlertDialog.Builder(this).setTitle("Reading error").setMessage(message)
                        .setPositiveButton("OK", null).show();
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (book != null && book.isDigital() && foregroundSinceElapsed == 0) {
            foregroundSinceMs = System.currentTimeMillis();
            foregroundSinceElapsed = SystemClock.elapsedRealtime();
        }
    }

    @Override protected void onPause() {
        handler.removeCallbacks(deferredPositionSave);
        if (book != null && book.isDigital()) {
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
        ++requestId;
        handler.removeCallbacks(deferredPositionSave);
        if (store != null) store.close();
        super.onDestroy();
    }
}
