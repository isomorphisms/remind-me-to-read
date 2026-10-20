package org.isomorphisms.remindmetoread;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Photo of one book or a stack: four-angle offline OCR, then explicit per-title confirmation. */
public final class PhotoBooksActivity extends Activity {
    private static final int CAMERA = 41, GALLERY = 42;
    private static final int MAX_SIDE = 1800;
    private static final int MAX_CANDIDATES = 40;
    private static final int MAX_IMAGE_BYTES = 24 * 1024 * 1024;
    private Uri cameraOutput;
    private File cameraFile;
    private LinearLayout panel;
    private TextView status;
    private EditText recognized;
    private Bitmap sourceBitmap;
    private TextRecognizer recognizer;
    private final Map<String, String> candidates = new LinkedHashMap<>();

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            String uri = saved.getString("camera_output");
            if (uri != null) cameraOutput = Uri.parse(uri);
        }
        showChoices();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (cameraOutput != null) state.putString("camera_output", cameraOutput.toString());
        super.onSaveInstanceState(state);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int size) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setPadding(dp(6), dp(7), dp(6), dp(7));
        return t;
    }

    private Button button(String title, Runnable action) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(title);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void showChoices() {
        ScrollView scroll = new ScrollView(this);
        panel = new LinearLayout(this);
        panel.setPadding(dp(12), dp(16), dp(12), dp(24));
        panel.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(panel);
        setContentView(scroll);
        panel.addView(text("Photograph physical books", 24));
        panel.addView(text("Point the camera at one cover or several book spines in a stack. " +
                "The app reads possible titles on your phone. Remove mistakes and confirm before adding.", 15));
        panel.addView(button("Take a photo of books", this::takePhoto));
        panel.addView(button("Use a photo already on the phone", this::choosePhoto));
        status = text("Nothing is uploaded. OCR runs locally; no server or account is needed.", 14);
        panel.addView(status);
    }

    private void takePhoto() {
        try {
            File folder = new File(getCacheDir(), "photos");
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create photo folder");
            cameraFile = new File(folder, UUID.randomUUID() + ".jpg");
            cameraOutput = FileProvider.getUriForFile(this,
                    getPackageName() + ".files", cameraFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutput);
            intent.setClipData(ClipData.newUri(getContentResolver(), "Books", cameraOutput));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (intent.resolveActivity(getPackageManager()) == null) {
                status.setText("No camera application is available. Choose a photo instead.");
                return;
            }
            startActivityForResult(intent, CAMERA);
        } catch (Exception ex) {
            status.setText("Camera unavailable: " + ex.getMessage());
        }
    }

    private void choosePhoto() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, GALLERY);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK) return;
        if (request == CAMERA) {
            if (cameraOutput != null) readPhoto(cameraOutput);
        } else if (request == GALLERY && data != null && data.getData() != null) {
            readPhoto(data.getData());
        }
    }

    private void readPhoto(Uri photo) {
        status.setText("Reading text from photograph, including rotated book spines…");
        if (panel.getChildCount() > 5) panel.removeViews(5, panel.getChildCount() - 5);
        // Decode at low resolution off the UI thread to respect low-RAM devices.
        new Thread(() -> {
            try {
                Bitmap bitmap = sampleBitmap(photo);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) { bitmap.recycle(); return; }
                    sourceBitmap = bitmap;
                    candidates.clear();
                    recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                    recognizeRotation(0);
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Could not read photo: " + e.getMessage()));
            }
        }, "book-photo-decode").start();
    }

    private Bitmap sampleBitmap(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Photo unavailable");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            throw new IOException("Unrecognized image format");
        long pixels = (long) bounds.outWidth * bounds.outHeight;
        if (pixels > 120_000_000L) throw new IOException("Photo exceeds image safety limit");
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2;
        BitmapFactory.Options reduced = new BitmapFactory.Options();
        reduced.inSampleSize = sample;
        reduced.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Photo unavailable");
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, reduced);
            if (bitmap == null) throw new IOException("Failed to decode image");
            return bitmap;
        } catch (OutOfMemoryError error) {
            throw new IOException("Not enough memory to decode photo", error);
        }
    }

    private void recognizeRotation(int quarterTurn) {
        if (sourceBitmap == null || recognizer == null || isDestroyed()) return;
        status.setText("Reading book spines: orientation " + ((quarterTurn + 1) * 90) + " / 360…");
        Bitmap bitmap = sourceBitmap;
        if (quarterTurn != 0) {
            Matrix transform = new Matrix();
            transform.postRotate(quarterTurn * 90);
            bitmap = Bitmap.createBitmap(sourceBitmap, 0, 0,
                    sourceBitmap.getWidth(), sourceBitmap.getHeight(), transform, true);
        }
        final Bitmap transformed = bitmap;
        InputImage input = InputImage.fromBitmap(bitmap, 0);
        recognizer.process(input).addOnSuccessListener(result -> {
            for (Text.TextBlock block : result.getTextBlocks()) {
                String candidate = block.getText().replace('\n', ' ').trim()
                        .replaceAll("\\s+", " ");
                if (candidate.length() < 3 || candidate.length() > 120) continue;
                String key = candidate.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
                if (key.length() > 2 && candidates.size() < MAX_CANDIDATES)
                    candidates.putIfAbsent(key, candidate);
            }
            finishAngle(quarterTurn, transformed);
        }).addOnFailureListener(error -> finishAngle(quarterTurn, transformed));
    }

    private void finishAngle(int angle, Bitmap transformed) {
        if (transformed != sourceBitmap && !transformed.isRecycled()) transformed.recycle();
        if (angle < 3) {
            recognizeRotation(angle + 1);
        } else {
            if (recognizer != null) { recognizer.close(); recognizer = null; }
            if (sourceBitmap != null) { sourceBitmap.recycle(); sourceBitmap = null; }
            showConfirmation();
        }
    }

    private void showConfirmation() {
        if (isFinishing() || isDestroyed()) return;
        status.setText(candidates.size() + " possible titles detected. " +
                "These are OCR guesses, not verified book identities.");
        recognized = new EditText(this);
        recognized.setHint("One book title per line");
        recognized.setMinLines(5);
        recognized.setMaxLines(16);
        recognized.setSingleLine(false);
        StringBuilder guess = new StringBuilder();
        for (String text : candidates.values()) guess.append(text).append('\n');
        recognized.setText(guess);
        panel.addView(text("Edit this list. Keep one title per line; remove publisher names, noise, or duplicates. " +
                "If nothing was found, type the titles from the photo yourself.", 14));
        panel.addView(recognized);
        panel.addView(button("Add confirmed physical books", this::addConfirmed));
    }

    private void addConfirmed() {
        if (recognized == null) return;
        String[] lines = recognized.getText().toString().split("\\r?\\n");
        if (lines.length > 100) {
            status.setText("Too many lines; please review fewer than 100 titles.");
            return;
        }
        int added = 0, skipped = 0;
        try (BookStore store = new BookStore(this)) {
            for (String line : lines) {
                String title = line.trim();
                if (title.length() < 2 || title.length() > 160 || store.hasPaperTitle(title)) {
                    skipped++;
                    continue;
                }
                store.addPaper(title, "", 0); // Unknown page count until confirmed by the user.
                added++;
            }
        }
        new AlertDialog.Builder(this).setTitle("Books added")
                .setMessage(added + " physical books added. " + skipped +
                        " duplicates or invalid lines skipped. Edit their titles, authors, and page counts individually.")
                .setPositiveButton("Return to library", (d, w) -> finish()).show();
    }

    @Override protected void onDestroy() {
        if (recognizer != null) { recognizer.close(); recognizer = null; }
        if (sourceBitmap != null && !sourceBitmap.isRecycled()) sourceBitmap.recycle();
        super.onDestroy();
    }
}
