package org.isomorphisms.remindmetoread;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** EPUB container/OPF reader. No network requests, JavaScript, or external entities. */
public final class Epub {
    private static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CHAPTER_BYTES = 4 * 1024 * 1024;
    private static final int MAX_CHAPTERS = 512;
    private Epub() {}

    public static final class Info {
        public final String title;
        public final String author;
        public final List<String> chapters;

        Info(String title, String author, List<String> chapters) {
            this.title = title;
            this.author = author;
            this.chapters = Collections.unmodifiableList(new ArrayList<>(chapters));
        }
    }

    public static Info inspect(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            Document container = xml(read(zip, "META-INF/container.xml", MAX_METADATA_BYTES));
            NodeList rootFiles = container.getElementsByTagNameNS("*", "rootfile");
            if (rootFiles.getLength() == 0) throw new IOException("EPUB container has no rootfile");
            String opfPath = ((Element) rootFiles.item(0)).getAttribute("full-path");
            if (!safePath(opfPath)) throw new IOException("Unsafe EPUB package path");
            Document opf = xml(read(zip, opfPath, MAX_METADATA_BYTES));
            String title = firstText(opf, "title");
            if (title.isEmpty()) title = file.getName().replaceFirst("(?i)\\.epub$", "");
            String author = firstText(opf, "creator");

            Map<String, String> items = new HashMap<>();
            NodeList manifest = opf.getElementsByTagNameNS("*", "item");
            for (int i = 0; i < manifest.getLength(); i++) {
                Element item = (Element) manifest.item(i);
                String type = item.getAttribute("media-type");
                if (!type.equals("application/xhtml+xml") && !type.equals("text/html")) continue;
                String href = item.getAttribute("href");
                String path = resolve(opfPath, href);
                if (path != null) items.put(item.getAttribute("id"), path);
            }
            NodeList spine = opf.getElementsByTagNameNS("*", "itemref");
            List<String> chapters = new ArrayList<>();
            for (int i = 0; i < spine.getLength() && chapters.size() < MAX_CHAPTERS; i++) {
                Element ref = (Element) spine.item(i);
                String path = items.get(ref.getAttribute("idref"));
                if (path != null && zip.getEntry(path) != null) chapters.add(path);
            }
            if (chapters.isEmpty()) throw new IOException("EPUB has no readable XHTML chapters");
            return new Info(title, author, chapters);
        }
    }

    /** Plain-text reader deliberately strips scripts and active EPUB content. */
    public static String chapterText(File file, String chapterPath) throws IOException {
        if (!safePath(chapterPath)) throw new IOException("Unsafe chapter path");
        try (ZipFile zip = new ZipFile(file)) {
            Document doc = xml(read(zip, chapterPath, MAX_CHAPTER_BYTES));
            NodeList bodies = doc.getElementsByTagNameNS("*", "body");
            Node root = bodies.getLength() > 0 ? bodies.item(0) : doc.getDocumentElement();
            StringBuilder text = new StringBuilder();
            appendText(root, text);
            return text.toString().replace('\u00a0', ' ')
                    .replaceAll("[ \\t]*\\n[ \\t]*", "\n")
                    .replaceAll("\\n{3,}", "\n\n").trim();
        }
    }

    private static void appendText(Node node, StringBuilder out) throws IOException {
        if (out.length() > 750_000) throw new IOException("Chapter too large to display");
        if (node.getNodeType() == Node.TEXT_NODE) {
            out.append(node.getNodeValue().replaceAll("\\s+", " "));
            return;
        }
        if (node.getNodeType() != Node.ELEMENT_NODE && node.getNodeType() != Node.DOCUMENT_NODE) return;
        String tag = node.getLocalName();
        if (tag == null) tag = node.getNodeName();
        if (tag.equalsIgnoreCase("script") || tag.equalsIgnoreCase("style")
                || tag.equalsIgnoreCase("noscript")) return;
        boolean block = tag.matches("(?i)p|div|section|article|h[1-6]|li|blockquote|pre|ul|ol");
        if (block || tag.equalsIgnoreCase("br")) out.append('\n');
        Node child = node.getFirstChild();
        while (child != null) {
            appendText(child, out);
            child = child.getNextSibling();
        }
        if (block) out.append('\n');
    }

    private static String firstText(Document doc, String name) {
        NodeList values = doc.getElementsByTagNameNS("*", name);
        return values.getLength() == 0 ? "" : values.item(0).getTextContent().trim();
    }

    private static boolean safePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0) return false;
        for (String part : path.split("/")) if (part.equals("..") || part.equals(".")) return false;
        return true;
    }

    private static String resolve(String opfPath, String href) {
        try {
            URI base = new URI("file:/" + opfPath);
            URI target = base.resolve(href);
            if (!"file".equals(target.getScheme()) || target.getRawQuery() != null) return null;
            String path = target.getPath();
            if (path == null || !path.startsWith("/")) return null;
            path = path.substring(1);
            return safePath(path) ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] read(ZipFile zip, String path, int maxBytes) throws IOException {
        if (!safePath(path)) throw new IOException("Unsafe archive path");
        ZipEntry entry = zip.getEntry(path);
        if (entry == null || entry.isDirectory()) throw new IOException("Missing EPUB entry: " + path);
        if (entry.getSize() > maxBytes) throw new IOException("EPUB entry exceeds limit: " + path);
        try (InputStream in = zip.getInputStream(entry);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > maxBytes) throw new IOException("EPUB entry exceeds limit: " + path);
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static Document xml(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) ->
                    new InputSource(new java.io.StringReader("")));
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new IOException("Invalid EPUB XML/XHTML", e);
        }
    }
}
