package org.isomorphisms.remindmetoread;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class PlainTextTest {
    @Test public void readsUtf8WithoutDroppingParagraphs() throws Exception {
        byte[] bytes = "α β\r\nFirst line\rSecond line\n".getBytes(StandardCharsets.UTF_8);
        assertEquals("α β\nFirst line\nSecond line\n", PlainText.decode(bytes));
    }

    @Test public void acceptsUtf8Bom() throws Exception {
        assertEquals("book", PlainText.decode(new byte[]{
                (byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'b', 'o', 'o', 'k'}));
    }

    @Test public void readsUtf16LittleEndianBom() throws Exception {
        assertEquals("abc", PlainText.decode(new byte[]{
                (byte) 0xff, (byte) 0xfe, 'a', 0, 'b', 0, 'c', 0}));
    }

    @Test public void readsUtf16BigEndianBom() throws Exception {
        assertEquals("abc", PlainText.decode(new byte[]{
                (byte) 0xfe, (byte) 0xff, 0, 'a', 0, 'b', 0, 'c'}));
    }

    @Test public void handlesLegacyWindows1252Ebooks() throws Exception {
        assertEquals("“hello”", PlainText.decode(new byte[]{
                (byte) 0x93, 'h', 'e', 'l', 'l', 'o', (byte) 0x94}));
    }

    @Test public void rejectsBinary() throws Exception {
        try {
            PlainText.decode(new byte[]{'a', 0, 'b'});
            fail("Binary must be refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("binary"));
        }
    }

    @Test public void positionIsStableUnderReflow() {
        assertEquals(500, PlainText.perMille(500, 1000));
        assertEquals(25, PlainText.offsetAt(250, 100));
        assertEquals(0, PlainText.perMille(-10, 100));
        assertEquals(1000, PlainText.perMille(900, 100));
        assertEquals(100, PlainText.offsetAt(1200, 100));
        assertEquals(0, PlainText.perMille(1, 0));
        assertEquals(0, PlainText.offsetAt(500, 0));
        assertEquals(1000, PlainText.perMille(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test public void largeFilesAreRejectedBeforeRead() throws Exception {
        File file = File.createTempFile("overlarge", ".txt");
        try {
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.setLength(PlainText.MAX_BYTES + 1L);
            }
            try {
                PlainText.load(file);
                fail("Size limit not enforced");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("6 MiB"));
            }
        } finally {
            file.delete();
        }
    }
}
