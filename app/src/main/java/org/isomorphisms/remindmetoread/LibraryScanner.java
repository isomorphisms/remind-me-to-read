package org.isomorphisms.remindmetoread;

import android.content.Context;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Bounded SAF folder scan. Never uses unrestricted Android storage access. */
public final class LibraryScanner {
    public static final class Summary {
        public int visited, booksAdded, alreadyPresent, backupsRead, readeraPoints, errors;
        public String report() {
            return booksAdded + " new books, " + alreadyPresent + " already present, " +
                    readeraPoints + " ReadEra savepoints (" + backupsRead + " backups), " +
                    errors + " skipped or unreadable. Examined " + visited + " files.";
        }
    }

    private static final int MAX_FILES = 20_000;
    private static final int MAX_BOOKS_PER_SCAN = 1_000;
    private static final int MAX_BACKUPS_PER_SCAN = 20;
    private static final int MAX_DEPTH = 24;
    private LibraryScanner() {}

    private static final class Node {
        final DocumentFile file;
        final int depth;
        Node(DocumentFile file, int depth) { this.file = file; this.depth = depth; }
    }

    public static Summary scan(Context context, Uri tree, BookStore store, boolean backupsOnly) throws IOException {
        DocumentFile root = DocumentFile.fromTreeUri(context, tree);
        if (root == null || !root.isDirectory()) throw new IOException("Cannot read selected directory");
        Summary s = new Summary();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Set<String> visitedFolders = new HashSet<>();
        queue.add(new Node(root, 0));
        while (!queue.isEmpty() && s.visited < MAX_FILES) {
            Node node = queue.removeFirst();
            if (!visitedFolders.add(node.file.getUri().toString())) continue;
            DocumentFile[] children;
            try { children = node.file.listFiles(); }
            catch (Exception e) { s.errors++; continue; }
            for (DocumentFile child : children) {
                if (s.visited >= MAX_FILES) break;
                if (child.isDirectory()) {
                    if (node.depth < MAX_DEPTH) queue.addLast(new Node(child, node.depth + 1));
                    continue;
                }
                if (!child.isFile()) continue;
                s.visited++;
                String name = child.getName();
                if (name == null) continue;
                String extension = name.toLowerCase(java.util.Locale.ROOT);
                try {
                    if (extension.endsWith(".bak")) {
                        // Backups can be large. Each parsed JSON entry is separately bounded.
                        if (s.backupsRead < MAX_BACKUPS_PER_SCAN) {
                            s.readeraPoints += importBackup(context, child.getUri(), store);
                            s.backupsRead++;
                        }
                    } else if (!backupsOnly && (extension.endsWith(".epub") || extension.endsWith(".txt"))
                            && s.booksAdded < MAX_BOOKS_PER_SCAN) {
                        importBook(context, child.getUri(), name, child.length(), store, s);
                    }
                } catch (Exception e) {
                    s.errors++;
                }
            }
        }
        if (s.visited >= MAX_FILES) s.errors++;
        return s;
    }

    public static int importBackup(Context context, Uri source, BookStore store) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(source)) {
            if (input == null) throw new IOException("Could not read ReadEra backup");
            int imported = 0;
            for (ReadEraImporter.Record record : ReadEraImporter.read(input)) {
                store.readeraRecord(record);
                imported++;
            }
            return imported;
        }
    }

    private static void importBook(Context context, Uri source, String name, long size,
                                   BookStore store, Summary s) throws Exception {
        String sourceId = source.toString();
        if (store.bySourceUri(sourceId) != null) {
            s.alreadyPresent++;
            return;
        }
        boolean txt = name.toLowerCase(java.util.Locale.ROOT).endsWith(".txt");
        long limit = txt ? PlainText.MAX_BYTES : 100L * 1024 * 1024;
        if (size > limit) throw new IOException("Book exceeds scan size limit");
        File dir = new File(context.getFilesDir(), "epubs");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create books directory");
        File privateFile = new File(dir, UUID.randomUUID() + (txt ? ".txt" : ".epub"));
        try {
            String sha = copyHash(context, source, privateFile, limit);
            BookStore.Book known = store.bySha1(sha);
            if (known != null) {
                store.source(known.id, sourceId, sha);
                s.alreadyPresent++;
                return;
            }
            long bookId;
            if (txt) {
                String words = PlainText.load(privateFile);
                String title = name.replaceFirst("(?i)\\.txt$", "");
                bookId = store.addText(title, privateFile.getAbsolutePath(), words.length());
            } else {
                Epub.Info info = Epub.inspect(privateFile);
                bookId = store.addEpub(info.title, info.author, privateFile.getAbsolutePath());
            }
            store.source(bookId, sourceId, sha);
            privateFile = null; // ownership transferred to private book library
            s.booksAdded++;
        } finally {
            if (privateFile != null) privateFile.delete();
        }
    }

    public static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[16384];
            int read;
            while ((read = in.read(bytes)) != -1) digest.update(bytes, 0, read);
        }
        return hex(digest.digest());
    }

    public static String copyHash(Context context, Uri uri, File target, long limit) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("Cannot open selected file");
            byte[] bytes = new byte[16384];
            int n;
            long size = 0;
            while ((n = in.read(bytes)) != -1) {
                size += n;
                if (size > limit) throw new IOException("Document exceeds import limit");
                digest.update(bytes, 0, n);
                out.write(bytes, 0, n);
            }
        }
        return hex(digest.digest());
    }

    public static void indexExisting(BookStore store) {
        for (BookStore.Book book : store.all()) {
            if (!book.isDigital() || !book.sourceSha1.isEmpty()) continue;
            File file = new File(book.epubPath);
            if (!file.isFile()) continue;
            try { store.source(book.id, "", hash(file)); }
            catch (Exception ignored) { }
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(Character.forDigit((b >>> 4) & 15, 16))
                                .append(Character.forDigit(b & 15, 16));
        return out.toString();
    }
}
