package org.isomorphisms.remindmetoread;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Explicit, offline, lossless version-3 library backup (not the report-only TXT export).
 * Import is additive only to an EMPTY library; existing data is never overwritten.
 * All ZIP members are allowlisted and hashed before any database mutation.
 */
public final class PortableBackup {
    private PortableBackup() {}
    private static final int FORMAT = 1;
    private static final String MAGIC = "org.isomorphisms.remindmetoread.backup";
    private static final long MAX_ARCHIVE = 1024L * 1024 * 1024;
    private static final long MAX_BOOK = 100L * 1024 * 1024;
    private static final long MAX_JSON = 16L * 1024 * 1024;
    private static final long MAX_MANIFEST = 1024 * 1024;
    private static final int MAX_BOOKS = 10000;
    private static final int MAX_ROWS = 100000;
    private static final int MAX_ENTRIES = MAX_BOOKS + 2;
    private static final String[] TABLES = {"books", "bookmarks", "sessions", "readera_positions"};
    private static final String[][] COLUMNS = {
        {"id", "title", "author", "kind", "epub_path", "source_uri", "source_sha1",
         "chapter", "scroll_y", "char_offset", "content_length", "current_page",
         "total_pages", "added_ms", "last_read_ms", "total_seconds", "reading_since_ms"},
        {"id", "book_id", "chapter", "char_offset", "page", "label", "saved_ms"},
        {"id", "book_id", "started_ms", "ended_ms", "seconds"},
        {"sha1", "title", "filename", "ratio", "last_read_ms", "raw_position", "imported_ms"}
    };

    private static final class EntryInfo {
        final String name, sha;
        final long bytes;
        EntryInfo(String name, String sha, long bytes) {
            this.name = name; this.sha = sha; this.bytes = bytes;
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("name", name).put("sha256", sha).put("bytes", bytes);
        }
    }

    private static final class Archive {
        JSONObject database;
        final Map<String, File> stagedBooks = new HashMap<>();
    }

    /** Names are logical, not filesystem paths supplied by the archive. */
    static boolean allowedName(String name) {
        return "manifest.json".equals(name) || "database.json".equals(name) ||
                (name != null && name.matches("books/[1-9][0-9]{0,17}\\.(epub|txt)"));
    }

    private static String bookEntry(long id, int kind) throws IOException {
        if (id <= 0 || id > 999999999999999999L || (kind != BookStore.EPUB && kind != BookStore.TEXT))
            throw new IOException("Invalid digital book identity");
        return "books/" + id + (kind == BookStore.TEXT ? ".txt" : ".epub");
    }

    private static JSONObject snapshot(SQLiteDatabase db) throws Exception {
        JSONObject result = new JSONObject();
        db.beginTransactionNonExclusive();
        try {
            for (int i = 0; i < TABLES.length; i++) {
                JSONArray rows = new JSONArray();
                String select = "SELECT " + String.join(",", COLUMNS[i]) + " FROM " + TABLES[i];
                try (Cursor c = db.rawQuery(select, null)) {
                    while (c.moveToNext()) {
                        if (rows.length() >= MAX_ROWS || (i == 0 && rows.length() >= MAX_BOOKS))
                            throw new IOException("Too many rows for bounded backup");
                        JSONObject row = new JSONObject();
                        for (int col = 0; col < COLUMNS[i].length; col++) {
                            switch (c.getType(col)) {
                                case Cursor.FIELD_TYPE_NULL: row.put(COLUMNS[i][col], JSONObject.NULL); break;
                                case Cursor.FIELD_TYPE_INTEGER: row.put(COLUMNS[i][col], c.getLong(col)); break;
                                case Cursor.FIELD_TYPE_FLOAT: row.put(COLUMNS[i][col], c.getDouble(col)); break;
                                case Cursor.FIELD_TYPE_STRING: row.put(COLUMNS[i][col], c.getString(col)); break;
                                default: throw new IOException("Unexpected binary database column");
                            }
                        }
                        rows.put(row);
                    }
                }
                result.put(TABLES[i], rows);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return result;
    }

    private static String sha256(byte[] digest) {
        StringBuilder s = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            s.append(Character.forDigit((b >>> 4) & 15, 16));
            s.append(Character.forDigit(b & 15, 16));
        }
        return s.toString();
    }

    private static EntryInfo writeEntry(ZipOutputStream zip, String name, InputStream source,
                                        long limit) throws Exception {
        if (!allowedName(name) || "manifest.json".equals(name))
            throw new IOException("Unapproved backup entry");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        zip.putNextEntry(new ZipEntry(name));
        long size = 0;
        byte[] buffer = new byte[16384];
        int n;
        while ((n = source.read(buffer)) != -1) {
            size += n;
            if (size > limit) throw new IOException("Backup entry exceeds size limit: " + name);
            digest.update(buffer, 0, n);
            zip.write(buffer, 0, n);
        }
        zip.closeEntry();
        return new EntryInfo(name, sha256(digest.digest()), size);
    }

    private static JSONArray bookRows(JSONObject database) throws Exception {
        return database.getJSONArray("books");
    }

    private static File appBook(Context context, String path) throws IOException {
        File root = new File(context.getFilesDir(), "epubs").getCanonicalFile();
        File file = new File(path).getCanonicalFile();
        if (!file.getPath().startsWith(root.getPath() + File.separator) || !file.isFile())
            throw new IOException("Imported book is missing from private library; export is incomplete");
        return file;
    }

    /** Writes a complete archive, then independently reads and verifies the chosen destination. */
    public static int export(Context context, Uri destination) throws Exception {
        JSONObject database;
        try (BookStore store = new BookStore(context)) {
            database = snapshot(store.getReadableDatabase());
        }
        JSONArray books = bookRows(database);
        JSONArray listed = new JSONArray();
        long total = 0;
        try (OutputStream raw = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (raw == null) throw new IOException("Cannot write backup destination");
            try (ZipOutputStream zip = new ZipOutputStream(raw)) {
                for (int i = 0; i < books.length(); i++) {
                    JSONObject row = books.getJSONObject(i);
                    int kind = row.getInt("kind");
                    if (kind == BookStore.PAPER) continue;
                    String logical = bookEntry(row.getLong("id"), kind);
                    File file = appBook(context, row.getString("epub_path"));
                    try (InputStream in = new FileInputStream(file)) {
                        EntryInfo saved = writeEntry(zip, logical, in, MAX_BOOK);
                        listed.put(saved.json());
                        total += saved.bytes;
                        if (total > MAX_ARCHIVE) throw new IOException("Library exceeds backup limit");
                    }
                    // Private paths cannot be transferred to another installation.
                    row.put("epub_path", logical);
                }
                byte[] dbBytes = database.toString().getBytes(StandardCharsets.UTF_8);
                if (dbBytes.length > MAX_JSON) throw new IOException("Library database exceeds backup limit");
                EntryInfo dbEntry = writeEntry(zip, "database.json",
                        new ByteArrayInputStream(dbBytes), MAX_JSON);
                listed.put(dbEntry.json());
                JSONObject manifest = new JSONObject()
                        .put("format", MAGIC).put("version", FORMAT).put("files", listed);
                byte[] manifestBytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
                if (manifestBytes.length > MAX_MANIFEST) throw new IOException("Backup manifest is too large");
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(manifestBytes);
                zip.closeEntry();
            }
        }
        // A closed stream is not proof that SAF persisted the bytes.
        File local = copyUri(context, destination);
        File stage = makeStage(context);
        try {
            Archive verified = inspect(local, stage);
            return verified.database.getJSONArray("books").length();
        } finally {
            deleteTree(stage);
            local.delete();
        }
    }

    private static File copyUri(Context context, Uri source) throws Exception {
        File zip = File.createTempFile("rmr-backup-", ".zip", context.getCacheDir());
        boolean okay = false;
        try (InputStream in = context.getContentResolver().openInputStream(source);
             OutputStream out = new FileOutputStream(zip)) {
            if (in == null) throw new IOException("Cannot read backup");
            byte[] buffer = new byte[16384];
            long size = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                size += n;
                if (size > MAX_ARCHIVE) throw new IOException("Backup archive exceeds 1 GiB limit");
                out.write(buffer, 0, n);
            }
            okay = true;
            return zip;
        } finally {
            if (!okay) zip.delete();
        }
    }

    private static File makeStage(Context context) throws IOException {
        File root = new File(context.getCacheDir(), "rmr-restore-" + UUID.randomUUID());
        if (!root.mkdir()) throw new IOException("Could not stage backup safely");
        return root;
    }

    private static byte[] readSmall(ZipFile zip, ZipEntry entry, long max) throws Exception {
        try (InputStream in = zip.getInputStream(entry);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long size = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                size += n;
                if (size > max) throw new IOException("Backup metadata exceeds size limit");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static Archive inspect(File source, File stage) throws Exception {
        Archive archive = new Archive();
        try (ZipFile zip = new ZipFile(source)) {
            Map<String, ZipEntry> found = new HashMap<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!allowedName(name) || entry.isDirectory() || found.put(name, entry) != null)
                    throw new IOException("Unsafe, unexpected, or duplicate backup entry: " + name);
                if (found.size() > MAX_ENTRIES) throw new IOException("Backup has too many entries");
            }
            if (!found.containsKey("manifest.json") || !found.containsKey("database.json"))
                throw new IOException("Backup metadata is missing");
            JSONObject manifest = new JSONObject(new String(
                    readSmall(zip, found.get("manifest.json"), MAX_MANIFEST), StandardCharsets.UTF_8));
            if (!MAGIC.equals(manifest.getString("format")) || manifest.getInt("version") != FORMAT ||
                    manifest.length() != 3) throw new IOException("Unsupported backup format/version");
            JSONArray files = manifest.getJSONArray("files");
            if (files.length() != found.size() - 1) throw new IOException("Backup file list is incomplete");
            Set<String> listed = new HashSet<>();
            long total = 0;
            for (int i = 0; i < files.length(); i++) {
                JSONObject record = files.getJSONObject(i);
                if (record.length() != 3) throw new IOException("Unknown manifest property");
                String name = record.getString("name");
                long length = record.getLong("bytes");
                String expected = record.getString("sha256");
                if (!allowedName(name) || "manifest.json".equals(name) ||
                        !listed.add(name) || length < 0 || length > ("database.json".equals(name) ? MAX_JSON : MAX_BOOK) ||
                        !expected.matches("[0-9a-f]{64}") || !found.containsKey(name))
                    throw new IOException("Invalid backup file manifest: " + name);
                total += length;
                if (total > MAX_ARCHIVE) throw new IOException("Extracted library exceeds size limit");
                File staged = null;
                if (name.startsWith("books/")) {
                    staged = new File(stage, name.substring("books/".length()));
                    if (!staged.createNewFile()) throw new IOException("Duplicate staged book");
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long bytes = 0;
                try (InputStream in = zip.getInputStream(found.get(name));
                     OutputStream out = staged == null ? new ByteArrayOutputStream() : new FileOutputStream(staged)) {
                    byte[] buffer = new byte[16384];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        bytes += n;
                        if (bytes > length) throw new IOException("Entry exceeds declared length");
                        digest.update(buffer, 0, n);
                        out.write(buffer, 0, n);
                    }
                }
                if (bytes != length || !expected.equals(sha256(digest.digest())))
                    throw new IOException("Corrupt or incomplete backup entry: " + name);
                if (staged != null) archive.stagedBooks.put(name, staged);
            }
            if (!listed.contains("database.json"))
                throw new IOException("Database missing from manifest");
            archive.database = new JSONObject(new String(
                    readSmall(zip, found.get("database.json"), MAX_JSON), StandardCharsets.UTF_8));
            validateDatabase(archive.database, archive.stagedBooks.keySet());
        }
        return archive;
    }

    private static void validateDatabase(JSONObject database, Set<String> files) throws Exception {
        if (database.length() != TABLES.length)
            throw new IOException("Unknown database schema");
        Set<Long> ids = new HashSet<>();
        Set<String> requiredFiles = new HashSet<>();
        for (int i = 0; i < TABLES.length; i++) {
            JSONArray rows = database.getJSONArray(TABLES[i]);
            if (rows.length() > MAX_ROWS || (i == 0 && rows.length() > MAX_BOOKS))
                throw new IOException("Database has too many rows");
            for (int j = 0; j < rows.length(); j++) {
                JSONObject row = rows.getJSONObject(j);
                if (row.length() != COLUMNS[i].length)
                    throw new IOException("Database column count mismatch");
                for (String col : COLUMNS[i]) if (!row.has(col))
                    throw new IOException("Database column missing: " + col);
                if (i == 0) {
                    long id = row.getLong("id");
                    int kind = row.getInt("kind");
                    if (id <= 0 || !ids.add(id)) throw new IOException("Duplicate/invalid book id");
                    if (kind == BookStore.PAPER) {
                        if (!row.isNull("epub_path")) throw new IOException("Paper book has file");
                    } else {
                        String expected = bookEntry(id, kind);
                        if (!expected.equals(row.getString("epub_path")) || !requiredFiles.add(expected))
                            throw new IOException("Book file mapping is invalid");
                    }
                }
            }
        }
        if (!requiredFiles.equals(files))
            throw new IOException("Book files do not match library records");
        for (int table : new int[] {1, 2}) {
            JSONArray rows = database.getJSONArray(TABLES[table]);
            for (int i = 0; i < rows.length(); i++) {
                if (!ids.contains(rows.getJSONObject(i).getLong("book_id")))
                    throw new IOException("Orphan bookmark or reading session");
            }
        }
    }

    /** Pure-file validation entrypoint for independent hostile ZIP fixtures. */
    static void verifyArchiveForTest(File zip, File stagingDirectory) throws Exception {
        inspect(zip, stagingDirectory);
    }

    private static boolean blank(SQLiteDatabase db) {
        for (String table : TABLES) {
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
                if (c.moveToFirst() && c.getLong(0) != 0) return false;
            }
        }
        return true;
    }

    private static ContentValues values(JSONObject row, String[] columns) throws Exception {
        ContentValues result = new ContentValues();
        for (String col : columns) {
            Object value = row.get(col);
            if (value == JSONObject.NULL) result.putNull(col);
            else if (value instanceof String) result.put(col, (String)value);
            else if (value instanceof Integer) result.put(col, (Integer)value);
            else if (value instanceof Long) result.put(col, (Long)value);
            else if (value instanceof Double) result.put(col, (Double)value);
            else if (value instanceof Number) result.put(col, ((Number)value).longValue());
            else throw new IOException("Unrecognized database value type");
        }
        return result;
    }

    private static String fileSha(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return sha256(digest.digest());
    }

    private static void copyFile(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
    }

    /**
     * Strictly restore to an empty installation. It never deletes or updates an old
     * book, session, mark or ReadEra record; collision/validation rolls back SQL.
     */
    public static int restore(Context context, Uri source) throws Exception {
        try (BookStore store = new BookStore(context)) {
            if (!blank(store.getReadableDatabase()))
                throw new IOException("Library is not empty; back up existing books before switching installs");
        }
        File local = copyUri(context, source);
        File stage = null;
        try {
            stage = makeStage(context);
            Archive archive = inspect(local, stage);
            File root = new File(context.getFilesDir(), "epubs");
            if (!root.isDirectory() && !root.mkdirs())
                throw new IOException("Cannot create private book directory");
            List<File> newFiles = new ArrayList<>();
            boolean committed = false;
            try (BookStore store = new BookStore(context)) {
                SQLiteDatabase db = store.getWritableDatabase();
                db.beginTransaction();
                try {
                    if (!blank(db)) throw new IOException("Library changed during restore; nothing overwritten");
                    for (int t = 0; t < TABLES.length; t++) {
                        JSONArray rows = archive.database.getJSONArray(TABLES[t]);
                        for (int i = 0; i < rows.length(); i++) {
                            JSONObject row = rows.getJSONObject(i);
                            ContentValues fields = values(row, COLUMNS[t]);
                            if (t == 0) {
                                int kind = row.getInt("kind");
                                if (kind != BookStore.PAPER) {
                                    String logical = bookEntry(row.getLong("id"), kind);
                                    File target = new File(root, UUID.randomUUID() +
                                            (kind == BookStore.TEXT ? ".txt" : ".epub"));
                                    if (!target.createNewFile())
                                        throw new IOException("Cannot create unique private book");
                                    newFiles.add(target); // track even a partially copied file for rollback
                                    File stagedFile = archive.stagedBooks.get(logical);
                                    copyFile(stagedFile, target);
                                    if (!fileSha(stagedFile).equals(fileSha(target)))
                                        throw new IOException("Private book copy failed integrity verification");
                                    fields.put("epub_path", target.getAbsolutePath());
                                }
                                // SAF permissions from another app installation are not portable.
                                fields.putNull("source_uri");
                            }
                            if (db.insertOrThrow(TABLES[t], null, fields) < 0)
                                throw new IOException("Cannot restore " + TABLES[t]);
                        }
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                committed = true;
            } finally {
                if (!committed) for (File file : newFiles) file.delete();
            }
            return archive.database.getJSONArray("books").length();
        } finally {
            if (stage != null) deleteTree(stage);
            local.delete();
        }
    }

    private static void deleteTree(File root) {
        if (root == null || !root.exists()) return;
        File[] children = root.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        root.delete();
    }
}
