package org.isomorphisms.remindmetoread;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicitly invoked OpenAI-compatible HTTPS endpoint for an independently hosted gpt-oss-20b. */
public final class GptOss {
    private GptOss() {}

    public static String suggest(String endpoint, String key, BookStore.Book book) throws Exception {
        URL url = new URL(endpoint);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()
                || url.getUserInfo() != null)
            throw new IllegalArgumentException("Supply an HTTPS /v1/chat/completions URL");
        String facts = "Book: " + book.title + "\nAuthor: " + book.author +
                "\nType: " + (book.isPaper() ? "physical" : book.isText() ? "TXT" : "EPUB") +
                "\nPosition: " + (book.isPaper() ? "page " + book.currentPage +
                " of " + book.totalPages : book.isText()
                ? (PlainText.perMille(book.charOffset, book.contentLength) / 10.0) +
                  "% (" + book.charOffset + "/" + book.contentLength + " characters)"
                : "chapter " + (book.chapter + 1) + ", character " + book.charOffset) +
                "\nMinutes read: " + (book.totalSeconds / 60) +
                "\nLast read (Unix milliseconds, 0 if never): " + book.lastReadAt;
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content",
                "Write one practical, nonjudgmental reading reminder in at most 45 words. " +
                "Do not invent facts. Do not demand more data or quote book text."));
        messages.put(new JSONObject().put("role", "user").put("content", facts));
        JSONObject request = new JSONObject().put("model", "gpt-oss-20b")
                .put("messages", messages).put("max_tokens", 180);
        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        if (key != null && !key.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + key);
        connection.setDoOutput(true);
        try {
            try (OutputStream stream = connection.getOutputStream()) { stream.write(body); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300)
                throw new IllegalStateException("Server returned HTTP " + status);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (bytes.size() + n > 64 * 1024)
                        throw new IllegalStateException("Response exceeds 64 KiB");
                    bytes.write(buffer, 0, n);
                }
                JSONObject response = new JSONObject(bytes.toString("UTF-8"));
                String answer = response.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim();
                if (answer.isEmpty()) throw new IllegalStateException("Model returned no message");
                return answer;
            }
        } finally {
            connection.disconnect();
        }
    }
}
