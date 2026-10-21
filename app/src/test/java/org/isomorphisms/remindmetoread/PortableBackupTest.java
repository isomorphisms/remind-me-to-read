package org.isomorphisms.remindmetoread;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public final class PortableBackupTest {
    private static final String EMPTY = "{\"books\":[],\"bookmarks\":[],\"sessions\":[],\"readera_positions\":[]}";

    @Test public void entryNamesRejectTraversalAndAmbiguousPaths() {
        assertTrue(PortableBackup.allowedName("books/12.epub"));
        assertTrue(PortableBackup.allowedName("books/9.txt"));
        assertFalse(PortableBackup.allowedName("books/../12.epub"));
        assertFalse(PortableBackup.allowedName("books//12.epub"));
        assertFalse(PortableBackup.allowedName("books/01.epub"));
        assertFalse(PortableBackup.allowedName("books/12.epub/extra"));
        assertFalse(PortableBackup.allowedName("/etc/passwd"));
        assertFalse(PortableBackup.allowedName("books/12.EPUB"));
        assertFalse(PortableBackup.allowedName("anything.txt"));
    }

    private static String digest(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder();
        for (byte b : hash) {
            out.append(Character.forDigit((b >>> 4) & 15, 16));
            out.append(Character.forDigit(b & 15, 16));
        }
        return out.toString();
    }

    private static void member(ZipOutputStream zip, String name, byte[] data) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static File fixture(String db, boolean corruptHash, boolean badMember,
                                boolean omitManifestFile) throws Exception {
        File archive = File.createTempFile("rmr-fixture", ".zip");
        byte[] database = db.getBytes(StandardCharsets.UTF_8);
        String expected = corruptHash ? "0".repeat(64) : digest(database);
        JSONArray files = new JSONArray();
        if (!omitManifestFile) {
            files.put(new JSONObject().put("name", "database.json")
                    .put("bytes", database.length).put("sha256", expected));
        }
        JSONObject manifest = new JSONObject().put("format", "org.isomorphisms.remindmetoread.backup")
                .put("version", 1).put("files", files);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            member(zip, "database.json", database);
            if (badMember) member(zip, "books/../attack.txt", new byte[] {42});
            member(zip, "manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
        }
        return archive;
    }

    private static void check(File archive, boolean expectedPass) throws Exception {
        File stage = new File(archive.getParentFile(), archive.getName() + ".stage");
        assertTrue(stage.mkdir());
        try {
            if (expectedPass) PortableBackup.verifyArchiveForTest(archive, stage);
            else {
                try {
                    PortableBackup.verifyArchiveForTest(archive, stage);
                    fail("Hostile/incomplete archive accepted");
                } catch (Exception expected) {
                    assertNotNull(expected);
                }
            }
        } finally {
            File[] children = stage.listFiles();
            if (children != null) for (File child : children) child.delete();
            stage.delete();
            archive.delete();
        }
    }

    @Test public void emptyLibraryRoundTripEnvelopeIsValid() throws Exception {
        check(fixture(EMPTY, false, false, false), true);
    }

    @Test public void corruptedHashFailsClosed() throws Exception {
        check(fixture(EMPTY, true, false, false), false);
    }

    @Test public void missingFileFromManifestFailsClosed() throws Exception {
        check(fixture(EMPTY, false, false, true), false);
    }

    @Test public void unexpectedMemberFailsClosed() throws Exception {
        check(fixture(EMPTY, false, true, false), false);
    }

    @Test public void invalidDatabaseSchemaFailsClosed() throws Exception {
        check(fixture("{\"books\":[],\"sessions\":[]}", false, false, false), false);
    }

    @Test public void bookRecordWithoutMatchingBytesFailsClosed() throws Exception {
        JSONObject record = new JSONObject()
            .put("id", 1).put("title", "Test").put("author", "")
            .put("kind", 2).put("epub_path", "books/1.txt")
            .put("source_uri", JSONObject.NULL).put("source_sha1", JSONObject.NULL)
            .put("chapter", 0).put("scroll_y", 0).put("char_offset", 7)
            .put("content_length", 10).put("current_page", 0)
            .put("total_pages", 0).put("added_ms", 1).put("last_read_ms", 2)
            .put("total_seconds", 6).put("reading_since_ms", 0);
        String database = new JSONObject()
            .put("books", new JSONArray().put(record))
            .put("bookmarks", new JSONArray()).put("sessions", new JSONArray())
            .put("readera_positions", new JSONArray()).toString();
        check(fixture(database, false, false, false), false);
    }

    @Test public void truncatedZipFailsClosed() throws Exception {
        File archive = fixture(EMPTY, false, false, false);
        byte[] bytes = java.nio.file.Files.readAllBytes(archive.toPath());
        java.nio.file.Files.write(archive.toPath(), java.util.Arrays.copyOf(bytes, bytes.length - 12));
        check(archive, false);
    }
}
