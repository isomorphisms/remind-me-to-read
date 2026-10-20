package org.isomorphisms.remindmetoread;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/** All reading records remain on-device. Session rows preserve the source of totals. */
public final class BookStore extends SQLiteOpenHelper {
    public static final int EPUB = 0;
    public static final int PAPER = 1;

    public static final class Book {
        public long id, addedAt, lastReadAt, totalSeconds, readingSince;
        public String title, author, epubPath;
        public int kind, chapter, scrollY, currentPage, totalPages;
        public boolean isPaper() { return kind == PAPER; }
    }

    public BookStore(Context context) {
        super(context, "reading.sqlite", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL, author TEXT NOT NULL, kind INTEGER NOT NULL," +
                "epub_path TEXT, chapter INTEGER NOT NULL DEFAULT 0," +
                "scroll_y INTEGER NOT NULL DEFAULT 0, current_page INTEGER NOT NULL DEFAULT 0," +
                "total_pages INTEGER NOT NULL DEFAULT 0," +
                "added_ms INTEGER NOT NULL, last_read_ms INTEGER NOT NULL DEFAULT 0," +
                "total_seconds INTEGER NOT NULL DEFAULT 0," +
                "reading_since_ms INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "book_id INTEGER NOT NULL REFERENCES books(id)," +
                "started_ms INTEGER NOT NULL, ended_ms INTEGER NOT NULL," +
                "seconds INTEGER NOT NULL CHECK(seconds > 0))");
        db.execSQL("CREATE INDEX sessions_book ON sessions(book_id, started_ms)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("No database upgrade defined");
    }

    public synchronized long addEpub(String title, String author, String privatePath) {
        ContentValues values = initial(title, author, EPUB);
        values.put("epub_path", privatePath);
        return getWritableDatabase().insertOrThrow("books", null, values);
    }

    public synchronized long addPaper(String title, String author, int pages) {
        ContentValues values = initial(title, author, PAPER);
        values.put("total_pages", Math.max(0, pages));
        return getWritableDatabase().insertOrThrow("books", null, values);
    }

    private ContentValues initial(String title, String author, int kind) {
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

    public synchronized void bookmarkEpub(long id, int chapter, int scrollY) {
        ContentValues values = new ContentValues();
        values.put("chapter", Math.max(0, chapter));
        values.put("scroll_y", Math.max(0, scrollY));
        getWritableDatabase().update("books", values, "id=? AND kind=0",
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

    /** EPUB sessions use monotonic elapsed time and are committed on pause. */
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
