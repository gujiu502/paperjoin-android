package com.paperjoin.app;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import androidx.exifinterface.media.ExifInterface;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.cos.COSStream;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Local file processing. Call from a worker thread; returned bitmaps belong to the caller. */
public final class PdfEngine {
    private static final int MAX_PAGES = 500;
    private static final long MAX_FILE_BYTES = 250L * 1024 * 1024;
    private final Context context;

    public interface Progress { void update(int done, int total); }

    public PdfEngine(Context context) {
        this.context = context.getApplicationContext();
        PDFBoxResourceLoader.init(this.context);
    }

    public List<PageItem> importUri(Uri uri) throws Exception {
        File folder = directory("sources");
        File source = new File(folder, UUID.randomUUID().toString());
        List<PageItem> pages = new ArrayList<>();
        List<File> thumbnails = new ArrayList<>();
        boolean complete = false;
        try {
            String name = displayName(uri);
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(source)) {
                if (in == null) throw new IOException("無法讀取所選檔案。");
                byte[] buffer = new byte[64 * 1024];
                long count = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("已取消加入檔案。");
                    count += read;
                    if (count > MAX_FILE_BYTES) throw new IOException("單一檔案上限為 250 MB，請先分割檔案。");
                    out.write(buffer, 0, read);
                }
                if (count == 0) throw new IOException("這個檔案是空的。");
            }
            if (isPdf(source)) {
                // Validate with the same parser used by export before accepting any pages.
                try (PDDocument doc = loadPdf(source)) {
                    if (doc.getNumberOfPages() < 1 || doc.getNumberOfPages() > MAX_PAGES)
                        throw new IOException("PDF 必須包含 1 至 500 頁。");
                }
                try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY);
                     PdfRenderer renderer = new PdfRenderer(fd)) {
                    if (renderer.getPageCount() > MAX_PAGES) throw new IOException("每個 PDF 最多支援 500 頁。");
                    for (int i = 0; i < renderer.getPageCount(); i++) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("已取消加入檔案。");
                        PageItem item = item(source, name, i, true);
                        try (PdfRenderer.Page page = renderer.openPage(i)) {
                            validateSize(page.getWidth(), page.getHeight(), false);
                            item.width = page.getWidth();
                            item.height = page.getHeight();
                            Bitmap preview = renderPdfPage(page, 360);
                            saveThumbnail(item, preview, thumbnails);
                        }
                        pages.add(item);
                    }
                }
            } else {
                BitmapFactory.Options size = imageSize(source);
                PageItem item = item(source, name, 0, false);
                int orientation = orientation(source);
                boolean swap = orientation >= 5 && orientation <= 8;
                item.width = swap ? size.outHeight : size.outWidth;
                item.height = swap ? size.outWidth : size.outHeight;
                saveThumbnail(item, render(item, 360), thumbnails);
                pages.add(item);
            }
            complete = true;
            return pages;
        } catch (SecurityException e) {
            throw new IOException("無法開啟檔案；檔案可能已加密，或讀取權限已失效。", e);
        } catch (OutOfMemoryError e) {
            throw new IOException("檔案過大，記憶體不足；請選擇較小的檔案。", e);
        } catch (Exception e) {
            throw readable(e, "無法加入檔案；請確認它是有效且未加密的 PDF、JPG、PNG 或 WebP。");
        } finally {
            if (!complete) {
                source.delete();
                for (File thumbnail : thumbnails) thumbnail.delete();
            }
        }
    }

    public Bitmap render(PageItem item, int maxEdge) throws Exception {
        if (item == null || item.sourcePath == null) throw new IOException("找不到頁面來源。");
        if (maxEdge < 1 || maxEdge > 4096) throw new IOException("預覽尺寸必須介於 1 至 4096 像素。");
        int rotation = rotation(item.rotation);
        File file = new File(item.sourcePath);
        Bitmap bitmap;
        if (item.isPdf) {
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer renderer = new PdfRenderer(fd)) {
                if (item.sourcePage < 0 || item.sourcePage >= renderer.getPageCount())
                    throw new IOException("PDF 頁碼無效。");
                try (PdfRenderer.Page page = renderer.openPage(item.sourcePage)) {
                    bitmap = renderPdfPage(page, maxEdge);
                }
            }
            Matrix matrix = new Matrix();
            matrix.setRotate(rotation);
            return transform(bitmap, matrix);
        }
        bitmap = decodeImage(file, maxEdge);
        Matrix matrix = exifMatrix(orientation(file));
        matrix.postRotate(rotation);
        return transform(bitmap, matrix);
    }

    public File export(List<PageItem> pages, int compression, Progress progress) throws Exception {
        if (pages == null || pages.isEmpty()) throw new IOException("請先加入至少一頁。");
        if (pages.size() > MAX_PAGES) throw new IOException("每次最多匯出 500 頁。");
        if (compression < 0 || compression > 2) throw new IOException("壓縮模式無效。");
        File exports = directory("exports");
        String id = "合併_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        File temp = new File(exports, id + ".tmp");
        File result = new File(exports, id + ".pdf");
        Map<String, PDDocument> sources = new LinkedHashMap<>();
        Set<COSDictionary> resourcesSeen = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<COSStream, PDImageXObject> imagesSeen = new IdentityHashMap<>();
        boolean complete = false;
        try (PDDocument output = new PDDocument(memory())) {
            output.getDocumentInformation().setCreator("PaperJoin");
            if (progress != null) progress.update(0, pages.size());
            for (int i = 0; i < pages.size(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("已取消匯出。");
                PageItem item = pages.get(i);
                if (item == null || item.sourcePath == null) throw new IOException("第 " + (i + 1) + " 頁缺少來源檔案。");
                int angle = rotation(item.rotation);
                PDPage page;
                if (item.isPdf) {
                    PDDocument source = sources.get(item.sourcePath);
                    if (source == null) {
                        source = loadPdf(new File(item.sourcePath));
                        sources.put(item.sourcePath, source);
                    }
                    if (item.sourcePage < 0 || item.sourcePage >= source.getNumberOfPages())
                        throw new IOException("第 " + (i + 1) + " 頁的 PDF 頁碼無效。");
                    PDPage original = source.getPage(item.sourcePage);
                    validateSize(original.getCropBox().getWidth(), original.getCropBox().getHeight(), false);
                    page = output.importPage(original);
                    page.setResources(original.getResources());
                    page.setRotation((original.getRotation() + angle) % 360);
                    if (compression != 0) compressResources(output, page.getResources(), compression, resourcesSeen, imagesSeen, 0);
                } else {
                    page = imagePage(output, new File(item.sourcePath), compression);
                    page.setRotation(angle);
                    output.addPage(page);
                }
                if (progress != null) progress.update(i + 1, pages.size());
            }
            output.save(temp);
            if (temp.length() == 0 || !temp.renameTo(result)) throw new IOException("無法儲存 PDF，請確認儲存空間充足。");
            complete = true;
            return result;
        } catch (OutOfMemoryError e) {
            throw new IOException("記憶體不足；請減少頁數或改用壓縮模式。", e);
        } catch (Exception e) {
            throw readable(e, "匯出失敗；請確認來源檔案完整，並檢查儲存空間。");
        } finally {
            for (PDDocument source : sources.values()) {
                try { source.close(); } catch (IOException ignored) { }
            }
            temp.delete();
            if (!complete) result.delete();
        }
    }

    private PDPage imagePage(PDDocument output, File file, int compression) throws IOException {
        BitmapFactory.Options size = imageSize(file);
        int orientation = orientation(file);
        boolean swap = orientation >= 5 && orientation <= 8;
        float width = swap ? size.outHeight : size.outWidth;
        float height = swap ? size.outWidth : size.outHeight;
        float scale = 842f / Math.max(width, height);
        width *= scale;
        height *= scale;
        PDPage page = new PDPage(new PDRectangle(width, height));
        PDImageXObject image;
        if (compression == 0 && "image/jpeg".equals(size.outMimeType)) {
            try (InputStream in = new FileInputStream(file)) { image = JPEGFactory.createFromStream(output, in); }
        } else {
            if (compression == 0 && (long) size.outWidth * size.outHeight > Math.min(16_000_000L, Runtime.getRuntime().maxMemory() / 20))
                throw new IOException("原畫質圖片過大，請選擇平衡或較小檔案模式。");
            Bitmap bitmap = decodeImage(file, compression == 0 ? Math.max(size.outWidth, size.outHeight) : edge(compression));
            try {
                image = compression == 0 || bitmap.hasAlpha()
                        ? LosslessFactory.createFromImage(output, bitmap)
                        : JPEGFactory.createFromImage(output, bitmap, quality(compression));
            } finally { bitmap.recycle(); }
            if (compression != 0 && "image/jpeg".equals(size.outMimeType) && image.getCOSObject().getLength() >= file.length()) {
                try (InputStream in = new FileInputStream(file)) { image = JPEGFactory.createFromStream(output, in); }
            }
        }
        // EXIF is applied as a PDF transform, preserving original JPEG bytes even when mirrored.
        float[] m;
        switch (orientation) {
            case 2: m = new float[]{-width, 0, 0, height, width, 0}; break;
            case 3: m = new float[]{-width, 0, 0, -height, width, height}; break;
            case 4: m = new float[]{width, 0, 0, -height, 0, height}; break;
            case 5: m = new float[]{0, -height, -width, 0, width, height}; break;
            case 6: m = new float[]{0, -height, width, 0, 0, height}; break;
            case 7: m = new float[]{0, height, width, 0, 0, 0}; break;
            case 8: m = new float[]{0, height, -width, 0, width, 0}; break;
            default: m = new float[]{width, 0, 0, height, 0, 0};
        }
        try (PDPageContentStream stream = new PDPageContentStream(output, page)) {
            stream.drawImage(image, new com.tom_roush.pdfbox.util.Matrix(m[0], m[1], m[2], m[3], m[4], m[5]));
        }
        return page;
    }

    private void compressResources(PDDocument output, PDResources resources, int mode,
                                   Set<COSDictionary> seen, Map<COSStream, PDImageXObject> images, int depth) throws IOException {
        if (resources == null || !seen.add(resources.getCOSObject())) return;
        if (depth > 32) throw new IOException("PDF 圖像結構過於複雜，請改用原畫質匯出。");
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDFormXObject) {
                compressResources(output, ((PDFormXObject) object).getResources(), mode, seen, images, depth + 1);
            } else if (object instanceof PDImageXObject) {
                PDImageXObject image = (PDImageXObject) object;
                PDImageXObject replacement = images.get(image.getCOSObject());
                if (replacement == null) {
                    replacement = compressImage(output, image, mode);
                    images.put(image.getCOSObject(), replacement);
                }
                if (replacement != image) resources.put(name, replacement);
            }
        }
    }

    private PDImageXObject compressImage(PDDocument output, PDImageXObject image, int mode) throws IOException {
        // ponytail: preserve masks, special color spaces and huge rasters; add tiled decoding if needed.
        if (image.isStencil() || image.getBitsPerComponent() != 8
                || image.getCOSObject().containsKey(COSName.SMASK) || image.getCOSObject().containsKey(COSName.MASK)
                || image.getWidth() <= 0 || image.getHeight() <= 0
                || (long) image.getWidth() * image.getHeight() > 40_000_000L
                || !(image.getColorSpace() instanceof PDDeviceRGB || image.getColorSpace() instanceof PDDeviceGray)) return image;
        int sample = Math.max(1, (int) Math.ceil((double) Math.max(image.getWidth(), image.getHeight()) / edge(mode)));
        Bitmap bitmap = image.getImage(null, sample);
        if (bitmap == null) throw new IOException("PDF 中的圖像無法解碼，請改用原畫質匯出。");
        try {
            bitmap.setHasAlpha(false); // masks were excluded above, so this raster is opaque.
            PDImageXObject candidate = JPEGFactory.createFromImage(output, bitmap, quality(mode));
            return candidate.getCOSObject().getLength() < image.getCOSObject().getLength() ? candidate : image;
        } finally { bitmap.recycle(); }
    }

    private PDDocument loadPdf(File file) throws IOException {
        PDDocument doc;
        try { doc = PDDocument.load(file, memory()); }
        catch (InvalidPasswordException e) { throw new IOException("此 PDF 已加密，請先解除密碼後再加入。", e); }
        if (doc.isEncrypted()) {
            doc.close();
            throw new IOException("此 PDF 已加密，請先解除密碼後再加入。");
        }
        return doc;
    }

    private MemoryUsageSetting memory() { return MemoryUsageSetting.setupTempFileOnly().setTempDir(context.getCacheDir()); }
    private static int edge(int mode) { return mode == 1 ? 2400 : 1280; }
    private static float quality(int mode) { return mode == 1 ? .82f : .58f; }

    private File directory(String name) throws IOException {
        File dir = new File(context.getFilesDir(), name);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("無法建立儲存資料夾，請檢查可用空間。");
        return dir;
    }

    private String displayName(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
            } catch (RuntimeException ignored) { }
        }
        String name = uri.getLastPathSegment();
        return name == null ? "未命名檔案" : name;
    }

    private static boolean isPdf(File file) throws IOException {
        byte[] header = new byte[1024];
        try (InputStream in = new FileInputStream(file)) {
            int read = in.read(header);
            return read >= 5 && new String(header, 0, read, StandardCharsets.ISO_8859_1).contains("%PDF-");
        }
    }

    private static PageItem item(File source, String name, int index, boolean pdf) {
        PageItem item = new PageItem();
        item.sourcePath = source.getAbsolutePath();
        item.sourceName = name;
        item.sourcePage = index;
        item.isPdf = pdf;
        return item;
    }

    private void saveThumbnail(PageItem item, Bitmap bitmap, List<File> created) throws IOException {
        File file = new File(directory("thumbnails"), item.id + ".png");
        created.add(file);
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("無法建立頁面預覽。");
        } finally { bitmap.recycle(); }
        item.thumbnailPath = file.getAbsolutePath();
    }

    private static Bitmap renderPdfPage(PdfRenderer.Page page, int maxEdge) throws IOException {
        validateSize(page.getWidth(), page.getHeight(), false);
        float scale = (float) maxEdge / Math.max(page.getWidth(), page.getHeight());
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(page.getWidth() * scale)),
                Math.max(1, Math.round(page.getHeight() * scale)), Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        try { page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); }
        catch (RuntimeException e) { bitmap.recycle(); throw e; }
        return bitmap;
    }

    private static BitmapFactory.Options imageSize(File file) throws IOException {
        BitmapFactory.Options size = new BitmapFactory.Options();
        size.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), size);
        if (!("image/jpeg".equals(size.outMimeType) || "image/png".equals(size.outMimeType) || "image/webp".equals(size.outMimeType)))
            throw new IOException("不支援這個檔案，請選擇 PDF、JPG、PNG 或 WebP。");
        validateSize(size.outWidth, size.outHeight, true);
        return size;
    }

    private static void validateSize(float width, float height, boolean image) throws IOException {
        if (Float.isNaN(width) || Float.isNaN(height) || width < 1 || height < 1
                || width > 50_000 || height > 50_000 || (image && (double) width * height > 60_000_000))
            throw new IOException("頁面尺寸無效或過大，請先縮小原始檔案。");
    }

    private static Bitmap decodeImage(File file, int maxEdge) throws IOException {
        BitmapFactory.Options size = imageSize(file);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(size.outWidth, size.outHeight) / (options.inSampleSize * 2) >= maxEdge) options.inSampleSize *= 2;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) throw new IOException("圖像已損壞，無法讀取。");
        int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longest > maxEdge) {
            float scale = (float) maxEdge / longest;
            Bitmap smaller = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            if (smaller != bitmap) bitmap.recycle();
            bitmap = smaller;
        }
        return bitmap;
    }

    private static int orientation(File file) throws IOException {
        return new ExifInterface(file.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
    }

    private static Matrix exifMatrix(int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case 2: matrix.setScale(-1, 1); break;
            case 3: matrix.setRotate(180); break;
            case 4: matrix.setScale(1, -1); break;
            case 5: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case 6: matrix.setRotate(90); break;
            case 7: matrix.setRotate(270); matrix.postScale(-1, 1); break;
            case 8: matrix.setRotate(270); break;
            default: break;
        }
        return matrix;
    }

    private static Bitmap transform(Bitmap bitmap, Matrix matrix) {
        if (matrix.isIdentity()) return bitmap;
        Bitmap result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (result != bitmap) bitmap.recycle();
        return result;
    }

    private static int rotation(int angle) throws IOException {
        if (angle % 90 != 0) throw new IOException("旋轉角度必須是 90 度的倍數。");
        return ((angle % 360) + 360) % 360;
    }

    private static IOException readable(Exception e, String fallback) {
        String message = e.getMessage();
        if (e instanceof IOException && message != null && message.matches("(?s).*[\\u3400-\\u9fff].*")) return (IOException) e;
        return new IOException(fallback, e);
    }
}
