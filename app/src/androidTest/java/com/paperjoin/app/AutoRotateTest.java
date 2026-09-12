package com.paperjoin.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.test.AndroidTestCase;

import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.util.Matrix;

import java.io.File;
import java.io.FileOutputStream;

/** Run on a device: am instrument -w -e class com.paperjoin.app.AutoRotateTest ... */
@SuppressWarnings("deprecation")
public final class AutoRotateTest extends AndroidTestCase {
    public void testPdfAndScannedOrientation() throws Exception {
        PdfEngine engine = new PdfEngine(getContext());
        try (AutoRotate detector = new AutoRotate()) {
            // Text matrices use counterclockwise PDF coordinates, including inherited /Rotate.
            for (int textAngle : new int[] {0, 90, 180, 270}) {
                for (int inheritedRotation : new int[] {0, 90, 180, 270}) {
                    File source = File.createTempFile("orientation-", ".pdf", getContext().getCacheDir());
                    try {
                        try (PDDocument document = new PDDocument()) {
                            PDPage pdfPage = new PDPage(new PDRectangle(900, 900));
                            document.addPage(pdfPage);
                            document.getPages().getCOSObject().setInt(COSName.ROTATE, inheritedRotation);
                            try (PDPageContentStream content = new PDPageContentStream(document, pdfPage)) {
                                content.beginText();
                                content.setFont(PDType1Font.HELVETICA, 18);
                                content.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(textAngle), 450, 450));
                                content.showText("Document direction needs to remain correct.");
                                content.endText();
                            }
                            document.save(source);
                        }
                        PageItem page = page(source, true);
                        page.rotation = 90;
                        AutoRotate.Result result = detector.detect(page, engine);
                        assertTrue("PDF direction should be confident", result.confident);
                        assertEquals("PDF text=" + textAngle + " inherited=" + inheritedRotation,
                                (textAngle - inheritedRotation - 90 + 720) % 360, result.clockwiseDegrees);
                    } finally {
                        source.delete();
                    }
                }
            }

            Bitmap upright = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(upright);
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            paint.setTextSize(42);
            String[] text = {
                    "DOCUMENT ORIENTATION CHECK",
                    "Please keep this paragraph facing upwards.",
                    "The quick brown fox jumps over the lazy dog.",
                    "Invoice number 20260911, paid in full.",
                    "All pages should remain in the correct order.",
                    "Preview the document before saving your PDF."
            };
            for (int i = 0; i < text.length; i++) canvas.drawText(text[i], 70, 150 + i * 90, paint);
            try {
                for (int angle : new int[] {0, 90, 180, 270}) {
                    File source = saveImage(upright, angle);
                    try {
                        AutoRotate.Result result = detector.detect(page(source, false), engine);
                        assertTrue("Scan " + angle + " should be confident: " + result.reason, result.confident);
                        assertEquals("Scan " + angle, (360 - angle) % 360, result.clockwiseDegrees);
                    } finally {
                        source.delete();
                    }
                }

                // No text layer: the embedded scan is upside down and /Rotate adds 90 degrees.
                File scan = File.createTempFile("scan-only-", ".pdf", getContext().getCacheDir());
                try {
                    try (PDDocument document = new PDDocument()) {
                        PDPage pdfPage = new PDPage(new PDRectangle(600, 800));
                        pdfPage.setRotation(90);
                        document.addPage(pdfPage);
                        try (PDPageContentStream content = new PDPageContentStream(document, pdfPage)) {
                            content.drawImage(LosslessFactory.createFromImage(document, upright),
                                    new Matrix(-600, 0, 0, -800, 600, 800));
                        }
                        document.save(scan);
                    }
                    AutoRotate.Result result = detector.detect(page(scan, true), engine);
                    assertTrue("Image-only PDF should use OCR", result.confident);
                    assertEquals("Embedded 180 plus PDF 90 needs a clockwise 90 correction", 90,
                            result.clockwiseDegrees);
                } finally {
                    scan.delete();
                }

                canvas.drawColor(Color.WHITE);
                paint.setTextSize(54);
                String[] chinese = {
                        "文件自動旋轉方向辨識測試",
                        "請確認每一頁的文字保持正確方向",
                        "合併之前可以拖曳排列所有文件頁面",
                        "預覽圖片完成以後再儲存新的文件",
                        "這份中文掃描文件只在裝置內處理",
                        "所有檔案皆應維持原本的閱讀順序"
                };
                for (int i = 0; i < chinese.length; i++)
                    canvas.drawText(chinese[i], 70, 150 + i * 100, paint);
                File chineseScan = saveImage(upright, 180);
                try {
                    AutoRotate.Result result = detector.detect(page(chineseScan, false), engine);
                    assertTrue("Chinese scan should be confident: " + result.reason, result.confident);
                    assertEquals("Upside-down Chinese scan", 180, result.clockwiseDegrees);
                } finally {
                    chineseScan.delete();
                }
            } finally {
                upright.recycle();
            }

            Bitmap blank = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888);
            blank.eraseColor(Color.WHITE);
            File source = saveImage(blank, 0);
            blank.recycle();
            try {
                PageItem page = page(source, false);
                page.rotation = 90;
                AutoRotate.Result result = detector.detect(page, engine);
                assertFalse("Blank landscape page must not be guessed", result.confident);
                assertEquals(0, result.clockwiseDegrees);
            } finally {
                source.delete();
            }
        }
    }

    private PageItem page(File source, boolean pdf) {
        PageItem page = new PageItem();
        page.sourcePath = source.getAbsolutePath();
        page.sourceName = source.getName();
        page.isPdf = pdf;
        return page;
    }

    private File saveImage(Bitmap original, int degrees) throws Exception {
        android.graphics.Matrix rotation = new android.graphics.Matrix();
        rotation.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(original, 0, 0, original.getWidth(), original.getHeight(), rotation, true);
        File source = File.createTempFile("orientation-", ".png", getContext().getCacheDir());
        try (FileOutputStream output = new FileOutputStream(source)) {
            if (!rotated.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new Exception("PNG encoding failed");
        } finally {
            if (rotated != original) rotated.recycle();
        }
        return source;
    }
}
