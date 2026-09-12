package com.paperjoin.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.test.InstrumentationTestCase;

import androidx.exifinterface.media.ExifInterface;

import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Run with connectedDebugAndroidTest; exercises real PDFBox and Android PdfRenderer. */
public class PdfEngineTest extends InstrumentationTestCase {
    public void testMergeRotateCompressAndRejectInvalidInput() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        PdfEngine engine = new PdfEngine(context);
        File input = new File(context.getCacheDir(), "engine-test.pdf");
        Bitmap noise = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888);
        noise.setHasAlpha(false);
        int[] row = new int[1600];
        Random random = new Random(42);
        for (int y = 0; y < 1200; y++) {
            for (int x = 0; x < row.length; x++) row[x] = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256));
            noise.setPixels(row, 0, row.length, 0, y, row.length, 1);
        }
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = LosslessFactory.createFromImage(doc, noise);
            PDFormXObject form = new PDFormXObject(doc);
            form.setResources(new PDResources());
            form.setBBox(new PDRectangle(160, 120));
            form.getResources().put(COSName.getPDFName("Photo"), image);
            try (java.io.OutputStream out = form.getCOSObject().createOutputStream()) {
                out.write("q 160 0 0 120 0 0 cm /Photo Do Q".getBytes(StandardCharsets.US_ASCII));
            }
            for (int i = 0; i < 2; i++) {
                PDPage page = new PDPage(new PDRectangle(612, 792));
                if (i == 0) page.setRotation(90);
                doc.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                    stream.beginText();
                    stream.setFont(PDType1Font.HELVETICA, 26);
                    stream.newLineAtOffset(60, 700);
                    stream.showText(i == 0 ? "FIRST PAGE" : "SECOND PAGE");
                    stream.endText();
                    stream.drawForm(form);
                }
            }
            doc.save(input);
        } finally { noise.recycle(); }
        File png = new File(context.getCacheDir(), "engine-test.png");
        Bitmap transparent = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888);
        new Canvas(transparent).drawColor(Color.TRANSPARENT);
        Paint paint = new Paint();
        paint.setColor(Color.BLUE);
        new Canvas(transparent).drawRect(0, 0, 100, 100, paint);
        try (FileOutputStream out = new FileOutputStream(png)) { transparent.compress(Bitmap.CompressFormat.PNG, 100, out); }
        transparent.recycle();

        List<PageItem> pdf = engine.importUri(Uri.fromFile(input));
        assertEquals(2, pdf.size());
        assertEquals(792f, pdf.get(0).width, .01f);
        assertEquals(612f, pdf.get(0).height, .01f);
        PageItem picture = engine.importUri(Uri.fromFile(png)).get(0);
        assertEquals(400f, picture.width, .01f);
        assertEquals(200f, picture.height, .01f);
        picture.rotation = 90;
        Bitmap preview = engine.render(picture, 300);
        assertEquals(150, preview.getWidth());
        assertEquals(300, preview.getHeight());
        preview.recycle();
        PageItem originalFirst = pdf.get(0).copy();
        pdf.get(0).rotation = 180;
        List<PageItem> order = Arrays.asList(pdf.get(1), picture, pdf.get(0), originalFirst);
        List<Integer> progress = new ArrayList<>();
        File original = engine.export(order, 0, (done, total) -> { assertEquals(4, total); progress.add(done); });
        File compressed = engine.export(order, 2, null);
        assertEquals(Arrays.asList(0, 1, 2, 3, 4), progress);
        assertTrue("Embedded photos must actually shrink without rasterizing text", compressed.length() < original.length() / 2);
        for (File output : Arrays.asList(original, compressed)) {
            try (PDDocument doc = PDDocument.load(output)) {
                assertEquals(4, doc.getNumberOfPages());
                assertEquals(0, doc.getPage(0).getRotation());
                assertEquals(90, doc.getPage(1).getRotation());
                assertEquals(270, doc.getPage(2).getRotation());
                assertEquals(90, doc.getPage(3).getRotation());
                PDFTextStripper text = new PDFTextStripper();
                text.setStartPage(1); text.setEndPage(1);
                assertTrue(text.getText(doc).contains("SECOND PAGE"));
                for (int page = 3; page <= 4; page++) {
                    text.setStartPage(page); text.setEndPage(page);
                    String actual = text.getText(doc);
                    // Rotation can make the stripper insert line breaks between selectable glyphs.
                    assertEquals("Selectable text on page " + page + ": " + actual,
                            "FIRSTPAGE", actual.replaceAll("\\s+", ""));
                }
                PDResources res = doc.getPage(1).getResources();
                PDImageXObject img = (PDImageXObject) res.getXObject(res.getXObjectNames().iterator().next());
                assertNotNull("PNG transparency must survive", img.getSoftMask());
            }
        }
        try (PDDocument doc = PDDocument.load(input)) {
            PDFTextStripper text = new PDFTextStripper();
            text.setStartPage(1); text.setEndPage(1);
            String actual = text.getText(doc);
            assertEquals("Original selectable text: " + actual, "FIRSTPAGE", actual.replaceAll("\\s+", ""));
        }

        File invalid = new File(context.getCacheDir(), "invalid.pdf");
        try (FileOutputStream out = new FileOutputStream(invalid)) { out.write("%PDF-1.7 broken".getBytes(StandardCharsets.US_ASCII)); }
        int sourceCount = new File(context.getFilesDir(), "sources").list().length;
        int thumbnailCount = new File(context.getFilesDir(), "thumbnails").list().length;
        try { engine.importUri(Uri.fromFile(invalid)); fail("Corrupt PDF accepted"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().matches("(?s).*[\\u3400-\\u9fff].*")); }
        assertEquals(sourceCount, new File(context.getFilesDir(), "sources").list().length);
        assertEquals(thumbnailCount, new File(context.getFilesDir(), "thumbnails").list().length);

        File encrypted = new File(context.getCacheDir(), "encrypted.pdf");
        try (PDDocument doc = PDDocument.load(input)) {
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-password", "user-password", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(encrypted);
        }
        try { engine.importUri(Uri.fromFile(encrypted)); fail("Encrypted PDF accepted"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("加密")); }
        PageItem missing = new PageItem(); missing.sourcePath = "/missing-source.png";
        int exportCount = new File(context.getFilesDir(), "exports").list().length;
        try { engine.export(Arrays.asList(pdf.get(0), missing), 0, null); fail("Partial PDF exported"); }
        catch (java.io.IOException expected) { }
        assertEquals(exportCount, new File(context.getFilesDir(), "exports").list().length);
    }

    public void testAllExifOrientationsMatchExport() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        PdfEngine engine = new PdfEngine(context);
        Bitmap colored = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(colored);
        Paint paint = new Paint();
        int[] colors = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW};
        for (int i = 0; i < 4; i++) {
            paint.setColor(colors[i]);
            int x = i % 2 * 60, y = i / 2 * 40;
            canvas.drawRect(x, y, x + 60, y + 40, paint);
        }
        for (int orientation = 1; orientation <= 8; orientation++) {
            File jpeg = new File(context.getCacheDir(), "exif-" + orientation + ".jpg");
            try (FileOutputStream out = new FileOutputStream(jpeg)) { colored.compress(Bitmap.CompressFormat.JPEG, 100, out); }
            ExifInterface exif = new ExifInterface(jpeg.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(orientation));
            exif.saveAttributes();
            PageItem source = engine.importUri(Uri.fromFile(jpeg)).get(0);
            source.rotation = 90;
            Bitmap expected = engine.render(source, 240);
            File pdf = engine.export(Arrays.asList(source), 0, null);
            PageItem exported = new PageItem();
            exported.sourcePath = pdf.getAbsolutePath(); exported.isPdf = true;
            Bitmap actual = engine.render(exported, 240);
            assertEquals("EXIF " + orientation + " aspect", (double) expected.getWidth() / expected.getHeight(),
                    (double) actual.getWidth() / actual.getHeight(), .03);
            for (float x : new float[]{.25f, .75f}) for (float y : new float[]{.25f, .75f}) {
                int a = expected.getPixel((int) (expected.getWidth() * x), (int) (expected.getHeight() * y));
                int b = actual.getPixel((int) (actual.getWidth() * x), (int) (actual.getHeight() * y));
                assertTrue("EXIF " + orientation + " color", Math.abs(Color.red(a) - Color.red(b)) < 35
                        && Math.abs(Color.green(a) - Color.green(b)) < 35 && Math.abs(Color.blue(a) - Color.blue(b)) < 35);
            }
            expected.recycle(); actual.recycle();
        }
        colored.recycle();
    }
}
