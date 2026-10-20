package org.isomorphisms.remindmetoread;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/** Local reading history. Saved position and deliberate bookmarks are independent. */
public final class BookStore extends SQLiteOpenHelper {
    public static final int EPUB = 0;
    public static final int PAPER = 1;
    public static final int TEXT = 2;

    public static final class Book {
        public long id, addedAt, lastReadAt, totalSeconds, readingSince;
        public String title, author, epubPath;
        public int kind, chapter, scrollY, charOffset, contentLength, currentPage, totalPages;
        public boolean isPaper() { return kind == PAPER; }
        public boolean isText() { return kind == TEXT; }
        public boolean isDigital() { return kind != PAPER; }
    }

    public static final class Mark {
        public long id, bookId, savedAt;
        public int chapter, charOffset, page;
        public String label;
    }

    public BookStore(Context context) {
        super(context, "reading.sqlite", null, 2);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL, author TEXT NOT NULL, kind INTEGER NOT NULL," +
                "epub_path TEXT, chapter INTEGER NOT NULL DEFAULT 0," +
                "scroll_y INTEGER NOT NULL DEFAULT 0, char_offset INTEGER NOT NULL DEFAULT 0," +
                "content_length INTEGER NOT NULL DEFAULT 0," +
                "current_page INTEGER NOT NULL DEFAULT 0, total_pages INTEGER NOT NULL DEFAULT 0," +
                "added_ms INTEGER NOT NULL, last_read_ms INTEGER NOT NULL DEFAULT 0," +
                "total_seconds INTEGER NOT NULL DEFAULT 0, reading_since_ms INTEGER NOT NULL DEFAULT 0)");
        createSessions(db);
        createMarks(db);
    }

    private static void createSessions(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "book_id INTEGER NOT NULL REFERENCES books(id)," +
                "started_ms INTEGER NOT NULL, ended_ms INTEGER NOT NULL," +
                "seconds INTEGER NOT NULL CHECK(seconds > 0))");
        db.execSQL("CREATE INDEX sessions_book ON sessions(book_id, started_ms)");
    }

    private static void createMarks(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE bookmarks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "book_id INTEGER NOT NULL REFERENCES books(id)," +
                "chapter INTEGER NOT NULL DEFAULT 0," +
                "char_offset INTEGER NOT NULL DEFAULT 0," +
                "page INTEGER NOT NULL DEFAULT 0," +
                "label TEXT NOT NULL DEFAULT ''," +
                "saved_ms INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX bookmarks_book ON bookmarks(book_id, saved_ms DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion >= 2) {
            // Preserve existing books, reading sessions, time totals and EPUB scroll positions.
            db.execSQL("ALTER TABLE books ADD COLUMN char_offset INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE books ADD COLUMN content_length INTEGER NOT NULL DEFAULT 0");
            createMarks(db);
        } else {
            throw new IllegalStateException("Unsupported reading database upgrade: " + oldVersion);
        }
    }

    public synchronized long addEpub(String title, String author, String privatePath) {
        ContentValues values = initial(title, author, EPUB);
        values.put("epub_path", privatePath);
        return getWritableDatabase().insertOrThrow("books", null, values);
    }

    public synchronized long addText(String title, String privatePath, int characterCount) {
        ContentValues values = initial(title, "", TEXT);
        values.put("epub_path", privatePath); // existing private-document path column
        values.put("content_length", Math.max(0, characterCount));
        return getWritableDatabase().insertOrThrow("books", null, values);
    }

    public synchronized long addPaper(String title, String author, int pages) {
        ContentValues values = initial(title, author, PAPER);
        values.put("total_pages", Math.max(0, pages));
        return getWritableDatabase().insertOrThrow("books", null, values);
    }

    private static ContentValues initial(String title, String author, int kind) {
        ContentValues values = new ContentValues();
        values.put("title", title.trim());
        values.put("author", author.trim());
        values.put("kind", kind);
        values.put("added_ms", System.currentTimeMillis());
        return values;
    }

    public synchronized List<Book> all() {
        ArrayList<Book> books = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM books ORDER BY CASE WHEN last_read_ms = 0 THEN 0 ELSE 1 END DESC, last_read_ms DESC, added_ms DESC", null)) {
            while (c.moveToNext()) books.add(from(c));
        }
        return books;
    }

    public synchronized Book get(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM books WHERE id=?",
                new String[]{Long.toString(id)})) {
            return c.moveToFirst() ? from(c) : null;
        }
    }

    private static Book from(Cursor c) {
        Book b = new Book();
        b.id = number(c, "id");
        b.title = word(c, "title");
        b.author = word(c, "author");
        b.kind = (int) number(c, "kind");
        b.epubPath = word(c, "epub_path");
        b.chapter = (int) number(c, "chapter");
        b.scrollY = (int) number(c, "scroll_y");
        b.charOffset = (int) number(c, "char_offset");
        b.contentLength = (int) number(c, "content_length");
        b.currentPage = (int) number(c, "current_page");
        b.totalPages = (int) number(c, "total_pages");
        b.addedAt = number(c, "added_ms");
        b.lastReadAt = number(c, "last_read_ms");
        b.totalSeconds = number(c, "total_seconds");
        b.readingSince = number(c, "reading_since_ms");
        return b;
    }

    private static long number(Cursor c, String col) {
        return c.getLong(c.getColumnIndexOrThrow(col));
    }
    private static String word(Cursor c, String col) {
        String value = c.getString(c.getColumnIndexOrThrow(col));
        return value == null ? "" : value;
    }

    /** Stable position is a UTF-16 character offset, independent of rendered font/viewport. */
    public synchronized void position(long id, int chapter, int offset, int fallbackScrollY) {
        ContentValues values = new ContentValues();
        values.put("chapter", Math.max(0, chapter));
        values.put("char_offset", Math.max(0, offset));
        values.put("scroll_y", Math.max(0, fallbackScrollY));
        getWritableDatabase().update("books", values, "id=? AND kind!=1",
                new String[]{Long.toString(id)});
    }

    public synchronized void page(long id, int newPage) {
        Book book = get(id);
        if (book == null || !book.isPaper()) return;
        ContentValues values = new ContentValues();
        values.put("current_page", Math.max(0, Math.min(book.totalPages, newPage)));
        getWritableDatabase().update("books", values, "id=? AND kind=1",
                new String[]{Long.toString(id)});
    }

    public synchronized long addMark(long bookId, int chapter, int offset, int page, String label) {
        if (get(bookId) == null) throw new IllegalArgumentException("Unknown book");
        ContentValues values = new ContentValues();
        values.put("book_id", bookId);
        values.put("chapter", Math.max(0, chapter));
        values.put("char_offset", Math.max(0, offset));
        values.put("page", Math.max(0, page));
        values.put("label", label == null ? "" : label);
        values.put("saved_ms", System.currentTimeMillis());
        return getWritableDatabase().insertOrThrow("bookmarks", null, values);
    }

    public synchronized List<Mark> marks(long bookId) {
        ArrayList<Mark> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM bookmarks WHERE book_id=? ORDER BY saved_ms DESC, id DESC",
                new String[]{Long.toString(bookId)})) {
            while (c.moveToNext()) {
                Mark m = new Mark();
                m.id = number(c, "id");
                m.bookId = number(c, "book_id");
                m.chapter = (int) number(c, "chapter");
                m.charOffset = (int) number(c, "char_offset");
                m.page = (int) number(c, "page");
                m.savedAt = number(c, "saved_ms");
                m.label = word(c, "label");
                result.add(m);
            }
        }
        return result;
    }

    public synchronized void deleteMark(long bookId, long markId) {
        getWritableDatabase().delete("bookmarks", "book_id=? AND id=?",
                new String[]{Long.toString(bookId), Long.toString(markId)});
    }

    /** EPUB/TXT foreground sessions use monotonic elapsed time and commit on pause. */
    public synchronized void recordForeground(long id, long startedWallMs, long elapsedMs) {
        long seconds = Math.max(0, elapsedMs / 1000);
        if (seconds == 0) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            addSession(db, id, startedWallMs, System.currentTimeMillis(), seconds);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Physical sessions survive backgrounding, lock-screen, and process restart. */
    public synchronized void startPaper(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE books SET reading_since_ms=? WHERE id=? AND kind=1 AND reading_since_ms=0",
                new Object[]{System.currentTimeMillis(), id});
    }

    public synchronized void stopPaper(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Book b = get(id);
            if (b != null && b.isPaper() && b.readingSince > 0) {
                long now = System.currentTimeMillis();
                long seconds = Math.max(0, (now - b.readingSince) / 1000);
                db.execSQL("UPDATE books SET reading_since_ms=0 WHERE id=?", new Object[]{id});
                if (seconds > 0) addSession(db, id, b.readingSince, now, seconds);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void addSession(SQLiteDatabase db, long id, long start, long end, long seconds) {
        ContentValues row = new ContentValues();
        row.put("book_id", id);
        row.put("started_ms", start);
        row.put("ended_ms", end);
        row.put("seconds", seconds);
        db.insertOrThrow("sessions", null, row);
        db.execSQL("UPDATE books SET total_seconds=total_seconds+?, last_read_ms=? WHERE id=?",
                new Object[]{seconds, end, id});
    }
}
