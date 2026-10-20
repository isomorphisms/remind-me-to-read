package org.isomorphisms.remindmetoread;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** ReadEra .bak is a ZIP with library.json. Retains raw location for future exact anchoring. */
public final class ReadEraImporter {
    private static final int MAX_JSON_BYTES = 32 * 1024 * 1024;
    private static final int MAX_DOCS = 100_000;
    private ReadEraImporter() { }

    public static final class Record {
        public String sha1, title, filename, rawPosition;
        public double ratio;
        public long lastReadMs;
    }

    public static List<Record> read(InputStream source) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(source)) {
            int entries = 0;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 200) throw new IOException("Too many ReadEra backup entries");
                if (entry.isDirectory() || !entry.getName().equals("library.json")) {
                    zip.closeEntry();
                    continue;
                }
                byte[] json = limited(zip);
                return parse(new String(json, StandardCharsets.UTF_8));
            }
        }
        throw new IOException("Not a recognized ReadEra backup: library.json missing");
    }

    private static byte[] limited(InputStream source) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = source.read(buffer)) != -1) {
                if (out.size() + n > MAX_JSON_BYTES)
                    throw new IOException("ReadEra library.json exceeds 32 MiB");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    public static List<Record> parse(String json) throws IOException {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray docs = root.optJSONArray("docs");
            if (docs == null) throw new IOException("ReadEra backup has no docs");
            if (docs.length() > MAX_DOCS) throw new IOException("Too many documents in backup");
            List<Record> result = new ArrayList<>();
            for (int i = 0; i < docs.length(); i++) {
                JSONObject doc = docs.optJSONObject(i);
                if (doc == null) continue;
                JSONObject data = doc.optJSONObject("data");
                if (data == null) continue;
                Object deletion = data.opt("doc_delete_time");
                if (deletion instanceof Number && ((Number) deletion).longValue() > 0) continue;
                if (deletion instanceof String && !((String) deletion).isEmpty()
                        && !((String) deletion).equals("0")) continue;
                String sha = data.optString("doc_sha1", "").trim().toLowerCase(Locale.ROOT);
                if (!sha.matches("[0-9a-f]{40}")) continue;
                Record r = new Record();
                r.sha1 = sha;
                r.title = data.optString("doc_title", "").trim();
                if (r.title.isEmpty()) r.title = data.optString("doc_file_name_title", "").trim();
                if (r.title.isEmpty()) r.title = "Untitled ReadEra book";
                JSONArray links = doc.optJSONArray("links");
                JSONObject link = links != null && links.length() > 0 ? links.optJSONObject(0) : null;
                r.filename = link == null ? "" : link.optString("file_name", "");
                Object raw = data.opt("doc_position");
                r.rawPosition = raw == null ? "" : raw.toString();
                JSONObject position;
                if (raw instanceof JSONObject) position = (JSONObject) raw;
                else {
                    try { position = new JSONObject(r.rawPosition); }
                    catch (Exception invalid) { position = null; }
                }
                double ratio = position == null ? 0 : position.optDouble("ratio", 0);
                if (!Double.isFinite(ratio) || ratio < 0) ratio = 0;
                if (ratio > 1 && ratio <= 100) ratio /= 100.0;
                r.ratio = Math.min(1, ratio);
                r.lastReadMs = normalizedTime(data.optLong("doc_last_read_time", 0));
                result.add(r);
            }
            return result;
        } catch (org.json.JSONException ex) {
            throw new IOException("Invalid ReadEra library.json", ex);
        }
    }

    static long normalizedTime(long time) {
        // Some exports use Unix seconds, others Unix milliseconds; never infer durations.
        if (time < 1_000_000_000L) return 0;
        return time < 100_000_000_000L ? time * 1000L : time;
    }
}
