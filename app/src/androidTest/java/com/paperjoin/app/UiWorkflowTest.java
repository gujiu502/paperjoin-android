package com.paperjoin.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.util.AtomicFile;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Exercises batch import and page controls, captures only samples, and restores the user's session. */
@SuppressWarnings("deprecation")
public final class UiWorkflowTest extends InstrumentationTestCase {
    public void testBatchImportRotateAndPersist() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        AtomicFile session = new AtomicFile(new File(context.getFilesDir(), "session.json"));
        AtomicFile backup = new AtomicFile(new File(context.getFilesDir(), "qa-session-backup.json"));
        if (backup.getBaseFile().exists() || new File(backup.getBaseFile() + ".bak").exists()) {
            // A prior interrupted run must be recovered before taking another backup.
            byte[] interrupted = backup.readFully();
            writeAtomic(session, interrupted);
            assertTrue(Arrays.equals(interrupted, session.readFully()));
            backup.delete();
        }
        int originalCount = new SessionStore(context).read().size();
        byte[] original = session.getBaseFile().exists() ? session.readFully() : "[]".getBytes(StandardCharsets.UTF_8);
        writeAtomic(backup, original);
        MainActivity[] running = new MainActivity[1];
        try {
            new SessionStore(context).write(Collections.emptyList());
            PDFBoxResourceLoader.init(context);
            File pdf = samplePdf(context);
            File scan = sampleScan(context);
            MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(
                    new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            running[0] = activity;
            assertEquals(0, snapshot(activity).size());

            ClipData batch = ClipData.newRawUri("PaperJoin sample documents", Uri.fromFile(pdf));
            batch.addItem(new ClipData.Item(Uri.fromFile(scan)));
            Intent result = new Intent();
            result.setClipData(batch);
            getInstrumentation().runOnMainSync(() -> activity.onActivityResult(10, Activity.RESULT_OK, result));
            long deadline = SystemClock.elapsedRealtime() + 30_000;
            while (snapshot(activity).size() != 3 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
            getInstrumentation().waitForIdleSync();
            List<PageItem> imported = snapshot(activity);
            assertEquals("Batch picker result should append every PDF page and image", 3, imported.size());
            assertTrue(imported.get(0).isPdf);
            assertTrue(imported.get(1).isPdf);
            assertFalse(imported.get(2).isPdf);
            assertEquals(0, imported.get(0).sourcePage);
            assertEquals(1, imported.get(1).sourcePage);
            assertEquals(imported.get(0).sourcePath, imported.get(1).sourcePath);
            for (PageItem page : imported) assertTrue("Preview thumbnail exists", new File(page.thumbnailPath).length() > 0);

            File legacyBackup = new File(session.getBaseFile() + ".bak");
            assertFalse("Completed session write leaves no legacy backup", legacyBackup.exists());
            assertTrue("Simulate an interrupted AtomicFile write", session.getBaseFile().renameTo(legacyBackup));
            List<PageItem> recovered = new SessionStore(context).read();
            assertEquals("Legacy backup recovers all imported pages", 3, recovered.size());
            for (int index = 0; index < imported.size(); index++) assertEquals(imported.get(index).id, recovered.get(index).id);
            assertTrue("Recovered session is back at its primary path", session.getBaseFile().isFile());

            dragFirstTwo(activity);
            assertOrder(context, activity, imported.get(1).id, imported.get(0).id, imported.get(2).id);
            dragFirstTwo(activity);
            assertOrder(context, activity, imported.get(0).id, imported.get(1).id, imported.get(2).id);

            clickRotation(activity, 1);
            assertEquals(90, snapshot(activity).get(0).rotation);
            List<PageItem> saved = new SessionStore(context).read();
            assertEquals(3, saved.size());
            assertEquals(imported.get(0).id, saved.get(0).id);
            assertEquals("Page control rotation is persisted", 90, saved.get(0).rotation);
            assertEquals(0, saved.get(1).rotation);
            assertEquals(0, saved.get(2).rotation);
            for (int i = 0; i < 3; i++) clickRotation(activity, 1);
            for (int i = 0; i < 2; i++) clickRotation(activity, 3);
            saved = new SessionStore(context).read();
            assertEquals(0, saved.get(0).rotation);
            assertEquals(180, saved.get(2).rotation);

            SystemClock.sleep(3000);
            screenshot(context, "workspace.png");
            getInstrumentation().runOnMainSync(() -> {
                View preview = described(activity.getWindow().getDecorView(), "預覽第 1 頁，01_Project_SAMPLE.pdf");
                assertNotNull("First page can open its full preview", preview);
                assertTrue(preview.performClick());
            });
            SystemClock.sleep(2000);
            screenshot(context, "preview.png");
            getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        } finally {
            try {
                if (running[0] != null) {
                    getInstrumentation().runOnMainSync(() -> running[0].finish());
                    getInstrumentation().waitForIdleSync();
                }
            } finally {
                writeAtomic(session, original);
                assertTrue("User session metadata restored byte for byte", Arrays.equals(original, session.readFully()));
                assertEquals("Original page count restored", originalCount, new SessionStore(context).read().size());
                backup.delete(); // Keep recovery metadata if any restore step above fails.
            }
        }
    }

    private void writeAtomic(AtomicFile file, byte[] data) throws IOException {
        FileOutputStream output = file.startWrite();
        try {
            output.write(data);
            output.getFD().sync();
            file.finishWrite(output);
        } catch (IOException e) {
            file.failWrite(output);
            throw e;
        }
    }

    private void screenshot(Context context, String name) throws Exception {
        getInstrumentation().waitForIdleSync();
        File folder = context.getExternalFilesDir("qa");
        assertNotNull("External app screenshot directory is available", folder);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        Bitmap bitmap = getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("Capture the actual Android screen", bitmap);
        try (FileOutputStream output = new FileOutputStream(new File(folder, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }

    private List<PageItem> snapshot(MainActivity activity) {
        List<PageItem> copy = new ArrayList<>();
        getInstrumentation().runOnMainSync(() -> {
            for (PageItem page : activity.pages) copy.add(page.copy());
        });
        return copy;
    }

    private void dragFirstTwo(MainActivity activity) {
        SystemClock.sleep(400);
        getInstrumentation().waitForIdleSync();
        float[] points = new float[4];
        getInstrumentation().runOnMainSync(() -> {
            for (int page = 0; page < 2; page++) {
                View preview = described(activity.getWindow().getDecorView(), "預覽第 " + (page + 1) + " 頁，"
                        + activity.pages.get(page).sourceName);
                assertNotNull("Page preview is visible for dragging", preview);
                View handle = described((View) preview.getParent(), "長按拖拉排序");
                assertNotNull("Page has a drag handle", handle);
                int[] location = new int[2];
                handle.getLocationOnScreen(location);
                assertTrue("Drag handle is laid out", handle.getWidth() > 0 && handle.getHeight() > 0);
                points[page * 2] = location[0] + handle.getWidth() / 2f;
                points[page * 2 + 1] = location[1] + handle.getHeight() / 2f;
            }
        });
        // Cross the destination card edge so ItemTouchHelper chooses it as a drop target.
        points[2] += Math.signum(points[2] - points[0]) * 12;
        points[3] += Math.signum(points[3] - points[1]) * 12;
        long down = SystemClock.uptimeMillis();
        pointer(down, MotionEvent.ACTION_DOWN, points[0], points[1]);
        try {
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 200);
            for (int step = 1; step <= 12; step++) {
                float fraction = step / 12f;
                pointer(down, MotionEvent.ACTION_MOVE, points[0] + (points[2] - points[0]) * fraction,
                        points[1] + (points[3] - points[1]) * fraction);
                SystemClock.sleep(40);
            }
        } finally {
            pointer(down, MotionEvent.ACTION_UP, points[2], points[3]);
        }
        SystemClock.sleep(600); // ItemTouchHelper persists in clearView after its settling animation.
        getInstrumentation().waitForIdleSync();
    }

    private void pointer(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { getInstrumentation().sendPointerSync(event); }
        finally { event.recycle(); }
    }

    private void assertOrder(Context context, MainActivity activity, String... ids) throws Exception {
        List<PageItem> current = snapshot(activity);
        List<PageItem> persisted = new SessionStore(context).read();
        assertEquals(ids.length, current.size());
        assertEquals(ids.length, persisted.size());
        for (int index = 0; index < ids.length; index++) {
            assertEquals("Dragged page order " + index, ids[index], current.get(index).id);
            assertEquals("Persisted dragged order " + index, ids[index], persisted.get(index).id);
        }
    }

    private void clickRotation(MainActivity activity, int page) {
        getInstrumentation().waitForIdleSync();
        getInstrumentation().runOnMainSync(() -> {
            View button = described(activity.getWindow().getDecorView(), "順時針旋轉第 " + page + " 頁");
            assertNotNull("Page " + page + " has an accessible rotation control", button);
            assertTrue(button.isEnabled());
            assertTrue(button.performClick());
        });
    }

    private View described(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = described(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private File samplePdf(Context context) throws Exception {
        File file = new File(context.getCacheDir(), "01_Project_SAMPLE.pdf");
        try (PDDocument document = new PDDocument()) {
            String[][] sections = {
                    {"01  Gather your pages", "Bring PDFs and images into one workspace.",
                     "02  Put everything in order", "Preview, rotate, and arrange each page.",
                     "03  Create one clear document", "Choose your compression and export locally."},
                    {"01  Preview every page", "Check that every document is easy to read.",
                     "02  Check orientation", "Turn sideways scans into readable pages.",
                     "03  Save your finished PDF", "Keep selectable text and share one file."}
            };
            for (int pageIndex = 0; pageIndex < 2; pageIndex++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.setNonStrokingColor(94, 106, 210);
                    stream.addRect(0, 828, 595, 14); stream.fill();
                    text(stream, 48, 785, 12, true, "PAPERJOIN  /  SAMPLE");
                    text(stream, 48, 725, 32, true, pageIndex == 0 ? "Project overview" : "Before you export");
                    text(stream, 48, 694, 13, false, "A small guide to a more organized document.");
                    for (int section = 0; section < 3; section++) {
                        float top = 610 - section * 135;
                        stream.setNonStrokingColor(241, 242, 250);
                        stream.addRect(48, top - 70, 499, 105); stream.fill();
                        text(stream, 65, top, 18, true, sections[pageIndex][section * 2]);
                        text(stream, 65, top - 28, 12, false, sections[pageIndex][section * 2 + 1]);
                    }
                    text(stream, 48, 100, 11, false, "DEMO DOCUMENT - No customer or private information.");
                    text(stream, 48, 65, 11, true, "PAPERJOIN                                              " + (pageIndex + 1) + " / 2");
                }
            }
            document.save(file);
        }
        return file;
    }

    private void text(PDPageContentStream stream, float x, float y, float size, boolean bold, String value) throws Exception {
        stream.setNonStrokingColor(34, 39, 54);
        stream.beginText();
        stream.setFont(bold ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA, size);
        stream.newLineAtOffset(x, y);
        stream.showText(value);
        stream.endText();
    }

    private File sampleScan(Context context) throws Exception {
        File file = new File(context.getCacheDir(), "02_Scan_DEMO.jpg");
        Bitmap bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        canvas.rotate(180, 600, 800);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(94, 106, 210));
        canvas.drawRect(0, 0, 1200, 22, paint);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        paint.setTextSize(32);
        canvas.drawText("PAPERJOIN / DEMO SCAN", 80, 120, paint);
        paint.setColor(Color.rgb(34, 39, 54));
        paint.setTextSize(64);
        canvas.drawText("A fresh perspective.", 80, 250, paint);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(35);
        String[] lines = {"This sample page was scanned upside down.",
                "Automatic rotation helps put it right.", "Preview every page before exporting.",
                "Arrange your documents in the reading order.", "All processing stays on your device.",
                "DEMO ONLY - No customer information."};
        for (int i = 0; i < lines.length; i++) canvas.drawText(lines[i], 80, 380 + i * 100, paint);
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output));
        } finally { bitmap.recycle(); }
        return file;
    }
}
