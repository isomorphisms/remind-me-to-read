package org.isomorphisms.remindmetoread;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Plain text reader with deterministic decoding; preserves character-offset bookmarks. */
public final class PlainText {
    public static final int MAX_BYTES = 6 * 1024 * 1024;
    private PlainText() {}

    public static String load(File file) throws IOException {
        if (file.length() > MAX_BYTES) throw new IOException("TXT exceeds 6 MiB limit");
        byte[] bytes;
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (out.size() + n > MAX_BYTES) throw new IOException("TXT exceeds 6 MiB limit");
                out.write(buf, 0, n);
            }
            bytes = out.toByteArray();
        }
        return decode(bytes);
    }

    static String decode(byte[] bytes) throws IOException {
        String result;
        if (bytes.length >= 3 &&
                (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb &&
                (bytes[2] & 0xff) == 0xbf) {
            result = strict(bytes, 3, StandardCharsets.UTF_8);
        } else if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe) {
            result = strict(bytes, 2, StandardCharsets.UTF_16LE);
        } else if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff) {
            result = strict(bytes, 2, StandardCharsets.UTF_16BE);
        } else {
            try {
                result = strict(bytes, 0, StandardCharsets.UTF_8);
            } catch (CharacterCodingException badUtf8) {
                // Common legacy plain-text ebooks. Exact encoding is not otherwise detectable.
                result = strict(bytes, 0, Charset.forName("windows-1252"));
            }
        }
        if (result.indexOf('\0') >= 0) throw new IOException("TXT appears to be a binary file");
        return result.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String strict(byte[] bytes, int offset, Charset charset) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
    }

    /** Character-offset progress is independent of font and viewport size. */
    public static int perMille(int offset, int length) {
        if (length <= 0) return 0;
        long clamped = Math.max(0, Math.min((long) offset, (long) length));
        return (int) (clamped * 1000 / length);
    }

    public static int offsetAt(int perMille, int length) {
        if (length <= 0) return 0;
        return (int) (Math.max(0L, Math.min(1000L, perMille)) * length / 1000);
    }
}
