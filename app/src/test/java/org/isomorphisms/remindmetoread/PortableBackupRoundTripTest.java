package org.isomorphisms.remindmetoread;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * Real BookStore SQLite and private file copy via Android shadows.
 * This does not stand in for physical C67/A1 installation acceptance.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class PortableBackupRoundTripTest {
    private static String sha(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = new FileInputStream(file)) {
            byte[] block = new byte[8192];
            int length;
            while ((length = stream.read(block)) != -1) digest.update(block, 0, length);
        }
        StringBuilder out = new StringBuilder();
        for (byte byteValue : digest.digest()) {
            out.append(Character.forDigit((byteValue >>> 4) & 15, 16));
            out.append(Character.forDigit(byteValue & 15, 16));
        }
        return out.toString();
    }

    private static File importedBook(Context context, String name, byte[] bytes) throws Exception {
        File dir = new File(context.getFilesDir(), "epubs");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("Cannot create private library");
        File file = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
        return file;
    }

    private static long sessionCount(BookStore store) {
        try (Cursor c = store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM sessions", null)) {
            assertTrue(c.moveToFirst());
            return c.getLong(0);
        }
    }

    private static long readEraCount(BookStore store) {
        try (Cursor c = store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM readera_positions", null)) {
            assertTrue(c.moveToFirst());
            return c.getLong(0);
        }
    }

    @Test public void completeLibrarySurvivesExportAndEmptyRestore() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        byte[] text = "Reader position: \u03bb \u03a3 and 0123456789".getBytes(StandardCharsets.UTF_8);
        byte[] epub = new byte[] {80, 75, 3, 4, 0, 7, -1, 27};
        File textFile = importedBook(context, "book-1.txt", text);
        File epubFile = importedBook(context, "book-2.epub", epub);
        String originalTextHash = sha(textFile);
        String originalEpubHash = sha(epubFile);
        long txtId, epubId, paperId;
        try (BookStore store = new BookStore(context)) {
            txtId = store.addText("Unicode Text", textFile.getAbsolutePath(), 34);
            epubId = store.addEpub("Novel EPUB", "Author", epubFile.getAbsolutePath());
            paperId = store.addPaper("Physical", "Author Two", 200);
            store.position(txtId, 0, 15, 20);
            store.position(epubId, 2, 123, 17);
            store.page(paperId, 47);
            store.addMark(txtId, 0, 15, 0, "Important line");
            store.addMark(epubId, 2, 120, 0, "Second chapter");
            store.addMark(paperId, 0, 0, 47, "Physical bookmark");
            store.recordForeground(txtId, 101L, 26_000L);
            store.recordForeground(epubId, 110L, 42_000L);

            ReadEraImporter.Record external = new ReadEraImporter.Record();
            external.sha1 = "a".repeat(40);
            external.title = "Other Reader Title";
            external.filename = "external.epub";
            external.rawPosition = "{\"ratio\":0.25,\"path\":\"chapter-two\"}";
            external.ratio = 0.25;
            external.lastReadMs = 123456L;
            store.readeraRecord(external);

            assertEquals(3, store.all().size());
            assertEquals(2, sessionCount(store));
            assertEquals(1, readEraCount(store));
        }
        File archive = new File(context.getCacheDir(), "entire-library-roundtrip.zip");
        if (archive.exists()) assertTrue(archive.delete());
        assertEquals(3, PortableBackup.export(context, Uri.fromFile(archive)));
        assertTrue(archive.isFile());
        assertTrue(archive.length() > text.length + epub.length);
        // Simulate a *different*, initially empty app installation. The
        // verified ZIP is external to the private files being cleared.
        assertTrue(textFile.delete());
        assertTrue(epubFile.delete());
        assertTrue(context.deleteDatabase("reading.sqlite"));
        assertEquals(3, PortableBackup.restore(context, Uri.fromFile(archive)));

        try (BookStore restored = new BookStore(context)) {
            assertEquals(3, restored.all().size());
            BookStore.Book txt = restored.get(txtId);
            BookStore.Book book = restored.get(epubId);
            BookStore.Book paper = restored.get(paperId);
            assertNotNull(txt);
            assertNotNull(book);
            assertNotNull(paper);
            assertEquals(15, txt.charOffset);
            assertEquals(20, txt.scrollY);
            assertEquals(2, book.chapter);
            assertEquals(123, book.charOffset);
            assertEquals(47, paper.currentPage);
            assertEquals(26L, txt.totalSeconds);
            assertEquals(42L, book.totalSeconds);
            assertEquals(1, restored.marks(txtId).size());
            assertEquals("Important line", restored.marks(txtId).get(0).label);
            assertEquals(1, restored.marks(epubId).size());
            assertEquals(1, restored.marks(paperId).size());
            assertEquals(2, sessionCount(restored));
            assertEquals(1, readEraCount(restored));
            BookStore.ReadEraPoint saved = restored.readeraByHash("a".repeat(40));
            assertNotNull(saved);
            assertEquals(0.25, saved.ratio, 0.0000001);
            assertEquals("{\"ratio\":0.25,\"path\":\"chapter-two\"}", saved.rawPosition);
            assertNull("SAF grants must not be transferred", restored.get(txtId).sourceUri.isEmpty() ? null : "unexpected");
            assertEquals(originalTextHash, sha(new File(txt.epubPath)));
            assertEquals(originalEpubHash, sha(new File(book.epubPath)));
        }

        try {
            PortableBackup.restore(context, Uri.fromFile(archive));
            fail("Restore into nonempty library must fail");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("not empty"));
        }
        try (BookStore restored = new BookStore(context)) {
            assertEquals(3, restored.all().size());
            assertEquals(2, sessionCount(restored));
        }
    }

    @Test public void missingImportedBookNeverProducesAcceptedCompleteBackup() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File fake = new File(new File(context.getFilesDir(), "epubs"), "lost.txt");
        try (BookStore store = new BookStore(context)) {
            store.addText("Missing private bytes", fake.getAbsolutePath(), 18);
        }
        File export = new File(context.getCacheDir(), "missing-book.zip");
        try {
            PortableBackup.export(context, Uri.fromFile(export));
            fail("Missing private file must prevent verified success");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("missing"));
        }
        try (BookStore store = new BookStore(context)) {
            assertEquals(1, store.all().size());
        }
    }
}
