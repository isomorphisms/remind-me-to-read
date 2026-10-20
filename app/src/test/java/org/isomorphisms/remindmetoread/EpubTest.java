package org.isomorphisms.remindmetoread;

import static org.junit.Assert.*;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public final class EpubTest {
    private static final String CONTAINER =
            "<?xml version=\"1.0\"?><container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
            "<rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>";
    private static final String PACKAGE =
            "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\"" +
            " xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            "<metadata><dc:title>Sample Book</dc:title><dc:creator>Example Author</dc:creator></metadata>" +
            "<manifest><item id=\"one\" href=\"chapter1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
            "<item id=\"two\" href=\"chapter2.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>" +
            "<spine><itemref idref=\"one\"/><itemref idref=\"two\"/></spine></package>";
    private static final String CHAPTER =
            "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\">" +
            "<body><h1>A Heading</h1><p>Hello <b>reader</b>.</p>" +
            "<script>dangerous()</script><p>Next paragraph.</p></body></html>";

    private static void entry(ZipOutputStream zip, String path, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private File book(String opf, String chapter) throws Exception {
        File file = File.createTempFile("book", ".epub");
        file.deleteOnExit();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            entry(zip, "META-INF/container.xml", CONTAINER);
            entry(zip, "OPS/book.opf", opf);
            entry(zip, "OPS/chapter1.xhtml", chapter);
            entry(zip, "OPS/chapter2.xhtml", CHAPTER);
        }
        return file;
    }

    @Test public void inspectAndReadSpineInOrder() throws Exception {
        File file = book(PACKAGE, CHAPTER);
        Epub.Info info = Epub.inspect(file);
        assertEquals("Sample Book", info.title);
        assertEquals("Example Author", info.author);
        assertEquals(2, info.chapters.size());
        assertEquals("OPS/chapter1.xhtml", info.chapters.get(0));
        assertEquals("OPS/chapter2.xhtml", info.chapters.get(1));
        String contents = Epub.chapterText(file, info.chapters.get(0));
        assertTrue(contents.contains("A Heading"));
        assertTrue(contents.contains("Hello reader."));
        assertTrue(contents.contains("Next paragraph."));
        assertFalse(contents.contains("dangerous()"));
    }

    @Test public void rejectsTraversal() throws Exception {
        try {
            Epub.chapterText(book(PACKAGE, CHAPTER), "../outside.xhtml");
            fail("Must reject traversal");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("Unsafe"));
        }
    }

    @Test public void rejectsUnreasonableChapter() throws Exception {
        String huge = "<?xml version=\"1.0\"?><html><body><p>" +
                "x".repeat(4 * 1024 * 1024) + "</p></body></html>";
        File file = book(PACKAGE, huge);
        try {
            Epub.chapterText(file, "OPS/chapter1.xhtml");
            fail("Must reject oversized decompressed entry");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("exceeds limit"));
        }
    }

    @Test public void rejectsMissingSpine() throws Exception {
        File file = book(PACKAGE.replace("idref=\"one\"", "idref=\"missing\"")
                .replace("idref=\"two\"", "idref=\"missing\""), CHAPTER);
        try {
            Epub.inspect(file);
            fail("Spine must have readable chapters");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("no readable"));
        }
    }
}
