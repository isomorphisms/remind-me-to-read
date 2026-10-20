package org.isomorphisms.remindmetoread;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public final class ReadEraImporterTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef01234567";
    private static final String SAVEPOINTS =
            "{\"docs\":[" +
                "{\"data\":{\"doc_sha1\":\"" + HASH + "\"," +
                  "\"doc_title\":\"A book\", \"doc_position\":\"{\\\"ratio\\\":0.375,\\\"page\\\":23,\\\"xPath\\\":\\\"/body/p[4]\\\"}\"," +
                  "\"doc_last_read_time\":1700000000000}, \"links\":[{\"file_name\":\"a.epub\"}]}," +
                "{\"data\":{\"doc_sha1\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"," +
                  "\"doc_title\":\"Deleted\", \"doc_delete_time\":1700000000000}}," +
                "{\"data\":{\"doc_title\":\"No hash\", \"doc_position\":\"{\\\"ratio\\\":0.1}\"}}" +
            "]}";

    @Test public void parsesProgressAndIgnoresDeletedOrUnmatchedDocs() throws Exception {
        List<ReadEraImporter.Record> records = ReadEraImporter.parse(SAVEPOINTS);
        assertEquals(1, records.size());
        ReadEraImporter.Record r = records.get(0);
        assertEquals(HASH, r.sha1);
        assertEquals("A book", r.title);
        assertEquals("a.epub", r.filename);
        assertEquals(0.375, r.ratio, 1e-12);
        assertEquals(1700000000000L, r.lastReadMs);
        assertTrue(r.rawPosition.contains("xPath"));
    }

    @Test public void backupIsZipContainingLibraryJson() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("meta.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("library.json"));
            zip.write(SAVEPOINTS.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        List<ReadEraImporter.Record> docs = ReadEraImporter.read(
                new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(1, docs.size());
        assertEquals(HASH, docs.get(0).sha1);
    }

    @Test public void percentRatioAndSecondTimestampsAreNormalized() throws Exception {
        String text = "{\"docs\":[{\"data\":{\"doc_sha1\":\"" + HASH + "\"," +
                "\"doc_position\":\"{\\\"ratio\\\":25}\",\"doc_last_read_time\":1700000000}}]}";
        ReadEraImporter.Record r = ReadEraImporter.parse(text).get(0);
        assertEquals(0.25, r.ratio, 1e-12);
        assertEquals(1700000000000L, r.lastReadMs);
    }

    @Test public void refusesInvalidBackup() throws Exception {
        try {
            ReadEraImporter.read(new ByteArrayInputStream(new byte[0]));
            fail("Missing library.json must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("library.json"));
        }
    }
}
