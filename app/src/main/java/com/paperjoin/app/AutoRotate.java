package com.paperjoin.app;

import android.graphics.Bitmap;
import android.graphics.Matrix;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Detects an additional clockwise correction. Call from a background executor. */
public final class AutoRotate implements AutoCloseable {
    private TextRecognizer latin;
    private TextRecognizer chinese;

    public static final class Result {
        public final int clockwiseDegrees;
        public final boolean confident;
        public final String reason;

        Result(int degrees, boolean confident, String reason) {
            this.clockwiseDegrees = degrees;
            this.confident = confident;
            this.reason = reason;
        }
    }

    public AutoRotate() {}

    public Result detect(PageItem page, PdfEngine engine) throws Exception {
        if (page.isPdf) {
            try {
                Result result = detectPdfText(page);
                if (result.confident) return result;
            } catch (IOException ignored) {
                // A missing font mapping can prevent extraction; the rendered page still works.
            }
        }
        Bitmap original = engine.render(page, 1600);
        try {
            if (latin == null) latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            Result result = detectImage(original, latin);
            if (result.confident) return result;
            if (chinese == null) {
                chinese = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            }
            return detectImage(original, chinese);
        } finally {
            original.recycle();
        }
    }

    private Result detectPdfText(PageItem page) throws IOException {
        File source = new File(page.sourcePath);
        try (PDDocument document = PDDocument.load(source,
                MemoryUsageSetting.setupTempFileOnly().setTempDir(source.getParentFile()))) {
            final double[] weights = new double[4];
            final int[] counts = new int[4];
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override protected void processTextPosition(TextPosition text) {
                    int count = lettersAndDigits(text.getUnicode());
                    if (count == 0) return;
                    com.tom_roush.pdfbox.util.Matrix matrix = text.getTextMatrix();
                    double angle = Math.toDegrees(Math.atan2(matrix.getShearY(), matrix.getScaleX()));
                    int quarter = (int) Math.round(angle / 90.0);
                    if (Math.abs(angle - quarter * 90.0) > 15) return;
                    int index = ((quarter % 4) + 4) % 4;
                    counts[index] += count;
                    weights[index] += count * Math.min(24, Math.max(6, text.getHeightDir()));
                }
            };
            stripper.setStartPage(page.sourcePage + 1);
            stripper.setEndPage(page.sourcePage + 1);
            stripper.getText(document);
            int best = bestIndex(weights);
            double total = weights[0] + weights[1] + weights[2] + weights[3];
            if (counts[best] >= 24 && weights[best] >= total * 0.8) {
                // PDF baselines use y-up (counterclockwise); /Rotate and preview use clockwise.
                int correction = normalize(best * 90
                        - document.getPage(page.sourcePage).getRotation() - page.rotation);
                return new Result(correction, true, "依文字方向判斷");
            }
            return uncertain();
        }
    }

    private Result detectImage(Bitmap original, TextRecognizer recognizer) throws Exception {
        double[] scores = new double[4];
        int[] characters = new int[4];
        int[] lines = new int[4];
        for (int i = 0; i < 4; i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Bitmap candidate;
            if (i == 0) {
                candidate = original.copy(Bitmap.Config.ARGB_8888, false);
            } else {
                Matrix rotation = new Matrix();
                rotation.postRotate(i * 90);
                candidate = Bitmap.createBitmap(original, 0, 0, original.getWidth(),
                        original.getHeight(), rotation, true);
            }
            if (candidate == null) throw new IOException("無法建立方向辨識預覽");
            Task<Text> operation = null;
            try {
                operation = recognizer.process(InputImage.fromBitmap(candidate, 0));
                Text text = Tasks.await(operation, 15, TimeUnit.SECONDS);
                for (Text.TextBlock block : text.getTextBlocks()) {
                    for (Text.Line line : block.getLines()) {
                        // OCR also recognizes sideways/upside-down lines: only upright evidence counts.
                        if (Math.abs(line.getAngle()) > 18) continue;
                        int lineCharacters = 0;
                        double lineScore = 0;
                        for (Text.Element element : line.getElements()) {
                            float confidence = element.getConfidence();
                            if (confidence < 0.45f || Math.abs(element.getAngle()) > 18) continue;
                            int count = lettersAndDigits(element.getText());
                            lineCharacters += count;
                            lineScore += count * confidence * confidence;
                        }
                        if (lineCharacters >= 3) {
                            characters[i] += lineCharacters;
                            scores[i] += lineScore;
                            lines[i]++;
                        }
                    }
                }
            } finally {
                if (operation == null || operation.isComplete()) {
                    candidate.recycle();
                } else {
                    // A timed-out native recognition task may still be reading its bitmap.
                    Bitmap pending = candidate;
                    operation.addOnCompleteListener(Runnable::run, ignored -> pending.recycle());
                }
            }
        }
        int best = bestIndex(scores);
        double second = 0;
        for (int i = 0; i < 4; i++) if (i != best) second = Math.max(second, scores[i]);
        // ponytail: sparse/vertical/ambiguous text stays unchanged; manual rotation handles those pages.
        if (characters[best] < 24 || lines[best] < 2 || scores[best] < 14
                || scores[best] < second * 1.35 + 3) return uncertain();
        return new Result(best * 90, true, "依離線文字辨識判斷");
    }

    private static int lettersAndDigits(String text) {
        if (text == null) return 0;
        int count = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) count++;
            i += Character.charCount(cp);
        }
        return count;
    }

    private static int bestIndex(double[] scores) {
        int best = 0;
        for (int i = 1; i < scores.length; i++) if (scores[i] > scores[best]) best = i;
        return best;
    }

    private static int normalize(int degrees) {
        return ((degrees % 360) + 360) % 360;
    }

    private static Result uncertain() {
        return new Result(0, false, "文字不足或方向不明，保留原方向");
    }

    @Override public void close() {
        if (latin != null) latin.close();
        if (chinese != null) chinese.close();
        latin = null;
        chinese = null;
    }
}
