package com.paperjoin.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(15, 16, 20), PANEL = Color.rgb(23, 25, 31),
        SURFACE = Color.rgb(31, 33, 42), LINE = Color.rgb(49, 52, 64),
        INK = Color.rgb(247, 248, 250), MUTED = Color.rgb(164, 170, 185),
        ACCENT = Color.rgb(94, 106, 210), SOFT = Color.rgb(177, 185, 255);
    private static final int PICK = 10, SAVE = 11;
    private static final long MULTI_SELECT_HOLD_MS = 2500;
    final ArrayList<PageItem> pages = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getAllocationByteCount(); }
    };
    private PdfEngine engine;
    private SessionStore session;
    private PagesAdapter adapter;
    private RecyclerView grid;
    private ItemTouchHelper touchHelper;
    private LinearLayout root, content;
    private View empty, progressArea;
    private TextView count, selectionLabel, progressText, summary, lastExport;
    private Button selectButton, rotateButton, autoButton, deleteButton, exportButton, shareButton;
    private final ArrayList<View> controls = new ArrayList<>();
    private EditText fileName;
    private int compression = 1;
    private boolean busy;
    private String status = "", outputName = "合頁文件";
    private File pendingExport;
    private String exportDetail = "";
    private int previewGeneration;
    private PageItem heldPage;
    private float holdX, holdY;
    private boolean consumeHoldTouch;
    private final Runnable selectHeldPage = () -> {
        if (heldPage == null || busy) return;
        PageItem page = heldPage;
        cancelImageHold();
        // End the native long-press drag before turning this stationary hold into selection.
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        try { super.dispatchTouchEvent(cancel); }
        finally { cancel.recycle(); }
        consumeHoldTouch = true;
        selected.add(page.id);
        adapter.notifyDataSetChanged();
        refresh();
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    };

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (consumeHoldTouch && action != MotionEvent.ACTION_DOWN) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) consumeHoldTouch = false;
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            consumeHoldTouch = false;
            cancelImageHold();
            if (!busy && grid != null) {
                int[] location = new int[2];
                grid.getLocationOnScreen(location);
                float x = event.getRawX() - location[0], y = event.getRawY() - location[1];
                View card = x >= 0 && y >= 0 && x < grid.getWidth() && y < grid.getHeight()
                        ? grid.findChildViewUnder(x, y) : null;
                if (card != null) {
                    PageHolder holder = (PageHolder) grid.getChildViewHolder(card);
                    holder.image.getLocationOnScreen(location);
                    x = event.getRawX() - location[0];
                    y = event.getRawY() - location[1];
                    int position = holder.getBindingAdapterPosition();
                    if (position >= 0 && x >= 0 && y >= 0 && x < holder.image.getWidth() && y < holder.image.getHeight()) {
                        heldPage = pages.get(position);
                        holdX = event.getRawX();
                        holdY = event.getRawY();
                        root.postDelayed(selectHeldPage, MULTI_SELECT_HOLD_MS);
                    }
                }
            }
        } else if (action == MotionEvent.ACTION_MOVE && heldPage != null) {
            float dx = event.getRawX() - holdX, dy = event.getRawY() - holdY;
            int slop = ViewConfiguration.get(this).getScaledTouchSlop();
            if (dx * dx + dy * dy > slop * slop) cancelImageHold();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
            cancelImageHold();
        }
        return super.dispatchTouchEvent(event);
    }

    private void cancelImageHold() {
        if (root != null) root.removeCallbacks(selectHeldPage);
        heldPage = null;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        engine = new PdfEngine(this);
        session = new SessionStore(this);
        if (state != null) {
            compression = state.getInt("compression", 1);
            outputName = state.getString("filename", "合頁文件");
            String pending = state.getString("pending");
            if (pending != null && new File(pending).isFile()) pendingExport = new File(pending);
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        try { pages.addAll(session.read()); }
        catch (Exception e) { error("無法還原上次的工作區", e); }
        buildUi();
        handleShare(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!busy) handleShare(intent);
        else toast("處理完成後，請再次分享檔案到合頁");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        rememberName();
        state.putInt("compression", compression);
        state.putString("filename", outputName);
        if (pendingExport != null) state.putString("pending", pendingExport.getAbsolutePath());
        super.onSaveInstanceState(state);
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        rememberName();
        buildUi();
    }

    @Override protected void onDestroy() {
        cancelImageHold();
        worker.shutdown();
        super.onDestroy();
    }

    @Override protected void onPause() {
        cancelImageHold();
        consumeHoldTouch = false;
        super.onPause();
    }

    private void buildUi() {
        cancelImageHold();
        controls.clear();
        fileName = null;
        exportButton = null;
        shareButton = null;
        summary = null;
        lastExport = null;
        root = column();
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(10) + insets.getSystemWindowInsetTop(),
                dp(20) + insets.getSystemWindowInsetRight(), dp(12) + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        root.setPadding(dp(20), dp(10), dp(20), dp(12));
        setContentView(root);

        LinearLayout header = row();
        ImageView mark = new ImageView(this);
        mark.setImageResource(com.paperjoin.app.R.drawable.ic_launcher);
        header.addView(mark, lp(40, 40));
        LinearLayout brand = column();
        brand.setPadding(dp(10), 0, 0, 0);
        brand.addView(label("合頁", 23, INK, true));
        brand.addView(label("PAPERJOIN  /  PDF 工作室", 10, MUTED, false));
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView badge = label("●  離線處理", 11, MUTED, false);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackground(shape(PANEL, 6, LINE));
        header.addView(badge);
        root.addView(header);
        root.addView(space(8));

        content = row();
        content.setGravity(Gravity.TOP);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout workspace = column();
        content.addView(workspace, new LinearLayout.LayoutParams(0, -1, 1));

        LinearLayout titleRow = row();
        titleRow.addView(label("你的文件，一次整理好。", 20, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button add = button("＋ 加入檔案", true, v -> pickFiles());
        controls.add(add);
        titleRow.addView(add, lp(116, 44));
        workspace.addView(titleRow);
        count = label("", 12, MUTED, false);
        count.setPadding(0, dp(6), 0, dp(8));
        workspace.addView(count);

        HorizontalScrollView toolbarScroll = new HorizontalScrollView(this);
        toolbarScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout toolbar = row();
        selectButton = tool("全選", v -> toggleSelect());
        rotateButton = tool("↻ 旋轉", v -> rotateTargets());
        autoButton = tool("✧ 自動旋轉", v -> autoRotate());
        deleteButton = tool("移除", v -> deleteSelected());
        toolbar.addView(selectButton, lp(64, 44));
        toolbar.addView(gap(6));
        toolbar.addView(rotateButton, lp(84, 44));
        toolbar.addView(gap(6));
        toolbar.addView(autoButton, lp(108, 44));
        toolbar.addView(gap(6));
        toolbar.addView(deleteButton, lp(64, 44));
        toolbarScroll.addView(toolbar);
        workspace.addView(toolbarScroll);
        selectionLabel = label("", 11, MUTED, false);
        selectionLabel.setPadding(0, dp(6), 0, dp(6));
        workspace.addView(selectionLabel);

        FrameLayout pageArea = new FrameLayout(this);
        workspace.addView(pageArea, new LinearLayout.LayoutParams(-1, 0, 1));
        grid = new RecyclerView(this);
        grid.setClipToPadding(false);
        grid.setPadding(0, 0, 0, dp(8));
        int screen = getResources().getConfiguration().screenWidthDp;
        boolean wide = screen >= 720;
        int available = screen - 40 - (wide ? 256 : 0);
        grid.setLayoutManager(new GridLayoutManager(this, Math.max(2, available / 174)));
        adapter = new PagesAdapter();
        grid.setAdapter(adapter);
        touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0) {
            @Override public boolean isLongPressDragEnabled() { return !busy; }
            @Override public int getMovementFlags(RecyclerView rv, RecyclerView.ViewHolder vh) {
                return busy ? 0 : super.getMovementFlags(rv, vh);
            }
            @Override public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder from, RecyclerView.ViewHolder to) {
                int a = from.getBindingAdapterPosition(), b = to.getBindingAdapterPosition();
                if (busy || a < 0 || b < 0) return false;
                pages.add(b, pages.remove(a));
                adapter.notifyItemMoved(a, b);
                pendingExport = null;
                return true;
            }
            @Override public void onSwiped(RecyclerView.ViewHolder vh, int direction) {}
            @Override public void clearView(RecyclerView rv, RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                // Cancellation may detach the dragged card during a layout pass.
                rv.post(() -> rv.getAdapter().notifyDataSetChanged());
                persist();
            }
        });
        touchHelper.attachToRecyclerView(grid);
        pageArea.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout emptyBox = column();
        emptyBox.setGravity(Gravity.CENTER);
        emptyBox.setPadding(dp(16), dp(12), dp(16), dp(12));
        emptyBox.setBackground(shape(PANEL, 12, LINE));
        TextView paper = label("▤  ＋  ▧", 38, SOFT, false);
        paper.setGravity(Gravity.CENTER);
        emptyBox.addView(paper);
        TextView emptyTitle = label("把零散的頁面，合成一份。", 17, INK, true);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(0, dp(12), 0, dp(8));
        emptyBox.addView(emptyTitle);
        TextView emptySub = label("加入 PDF 或圖片即可開始\n支援多選 · 頁面預覽 · 自由排序", 12, MUTED, false);
        emptySub.setGravity(Gravity.CENTER);
        emptySub.setLineSpacing(dp(4), 1);
        emptyBox.addView(emptySub);
        empty = emptyBox;
        pageArea.addView(emptyBox, new FrameLayout.LayoutParams(-1, -1));

        if (wide) {
            content.addView(gap(20));
            LinearLayout sidebar = column();
            ScrollView settingsScroll = new ScrollView(this);
            settingsScroll.setFillViewport(true);
            LinearLayout settings = settingsPanel();
            settings.removeView(exportButton);
            settings.removeView(shareButton);
            settings.removeView(lastExport);
            settingsScroll.addView(settings);
            sidebar.addView(settingsScroll, new LinearLayout.LayoutParams(-1, 0, 1));
            sidebar.addView(space(10));
            sidebar.addView(exportButton, lp(-1, 48));
            sidebar.addView(space(6));
            sidebar.addView(shareButton, lp(-1, 44));
            sidebar.addView(lastExport);
            content.addView(sidebar, lp(236, -1));
        } else {
            Button settings = button("匯出設定與壓縮  →", true, v -> showSettings());
            controls.add(settings);
            root.addView(space(8));
            root.addView(settings, lp(-1, 48));
        }

        LinearLayout progress = row();
        progress.setPadding(0, dp(8), 0, 0);
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(SOFT));
        progress.addView(spinner, lp(20, 20));
        progressText = label(status, 12, MUTED, false);
        progressText.setPadding(dp(8), 0, 0, 0);
        progress.addView(progressText, new LinearLayout.LayoutParams(0, -2, 1));
        progressArea = progress;
        root.addView(progress);
        refresh();
    }

    private LinearLayout settingsPanel() {
        LinearLayout panel = column();
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.setBackground(shape(PANEL, 12, LINE));
        panel.addView(label("準備匯出", 17, INK, true));
        summary = label("", 12, MUTED, false);
        summary.setPadding(0, dp(6), 0, dp(16));
        panel.addView(summary);
        panel.addView(label("檔案名稱", 11, MUTED, false));
        fileName = new EditText(this);
        fileName.setSingleLine(true);
        fileName.setTextSize(13);
        fileName.setTextColor(INK);
        fileName.setHintTextColor(MUTED);
        fileName.setHint("合頁文件");
        fileName.setText(outputName);
        fileName.setPadding(dp(10), 0, dp(10), 0);
        fileName.setBackground(shape(BG, 8, LINE));
        fileName.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams nameParams = lp(-1, 44);
        nameParams.topMargin = dp(6);
        panel.addView(fileName, nameParams);
        controls.add(fileName);
        panel.addView(space(16));
        panel.addView(label("PDF 壓縮", 11, MUTED, false));
        panel.addView(space(8));
        String[] titles = {"原始品質", "平衡壓縮", "最小檔案"};
        Button modeButton = button(titles[compression] + "  ⌄", false, null);
        modeButton.setTextColor(SOFT);
        modeButton.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("PDF 壓縮")
            .setSingleChoiceItems(titles, compression, (dialog, mode) -> {
                compression = mode;
                pendingExport = null;
                modeButton.setText(titles[mode] + "  ⌄");
                dialog.dismiss();
            }).setNegativeButton("取消", null).show());
        controls.add(modeButton);
        panel.addView(modeButton, lp(-1, 44));
        TextView note = label("文字保持清晰、可選取。壓縮效果依來源圖片而定。", 10, MUTED, false);
        note.setLineSpacing(dp(2), 1);
        note.setPadding(0, dp(4), 0, dp(12));
        panel.addView(note);
        exportButton = button("合併並儲存 PDF  ↓", true, v -> export(false));
        panel.addView(exportButton, lp(-1, 48));
        panel.addView(space(8));
        shareButton = button("合併並分享", false, v -> export(true));
        panel.addView(shareButton, lp(-1, 44));
        lastExport = label(exportDetail, 11, SOFT, false);
        lastExport.setPadding(0, dp(10), 0, 0);
        panel.addView(lastExport);
        return panel;
    }

    private void showSettings() {
        Dialog dialog = new Dialog(this);
        dialog.setTitle("匯出 PDF");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(settingsPanel());
        dialog.setContentView(scroll);
        dialog.setOnDismissListener(d -> { rememberName(); refresh(); });
        dialog.show();
        dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(400)), -2);
        refresh();
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "image/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, PICK); }
        catch (Exception e) { error("無法開啟檔案選擇器", e); }
    }

    private void handleShare(Intent intent) {
        if (intent == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) uris.add(uri);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> shared = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (shared != null) uris.addAll(shared);
        }
        if (!uris.isEmpty()) {
            intent.setAction(Intent.ACTION_MAIN);
            importFiles(uris);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        if (request == PICK) {
            ArrayList<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            importFiles(uris);
        } else if (request == SAVE && data.getData() != null && pendingExport != null) {
            saveExport(data.getData(), pendingExport);
        }
    }

    private void importFiles(List<Uri> uris) {
        if (busy || uris.isEmpty()) return;
        setBusy(true, "正在加入檔案…");
        worker.execute(() -> {
            List<PageItem> added = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            for (int i = 0; i < uris.size(); i++) {
                final int current = i + 1;
                ui(() -> setProgress("加入檔案 " + current + " / " + uris.size()));
                try { added.addAll(engine.importUri(uris.get(i))); }
                catch (Exception e) { errors.add("第 " + current + " 個檔案：" + message(e)); }
            }
            ui(() -> {
                pages.addAll(added);
                changed();
                setBusy(false, "");
                if (!errors.isEmpty()) new AlertDialog.Builder(this).setTitle("部分檔案未加入")
                    .setMessage("已加入 " + added.size() + " 頁。\n\n" + TextUtils.join("\n", errors))
                    .setPositiveButton("知道了", null).show();
                else toast("已加入 " + added.size() + " 頁");
            });
        });
    }

    private void toggleSelect() {
        if (selected.size() == pages.size()) selected.clear();
        else for (PageItem p : pages) selected.add(p.id);
        adapter.notifyDataSetChanged();
        refresh();
    }

    private List<PageItem> targets() {
        List<PageItem> result = new ArrayList<>();
        for (PageItem p : pages) if (selected.isEmpty() || selected.contains(p.id)) result.add(p);
        return result;
    }

    private void rotateTargets() {
        for (PageItem p : targets()) p.rotation = (p.rotation + 90) % 360;
        changed();
    }

    private void deleteSelected() {
        int size = selected.size();
        if (size == 0) return;
        new AlertDialog.Builder(this).setTitle("移除 " + size + " 頁？")
            .setMessage("只會從目前工作區移除，來源檔案仍會保留。")
            .setNegativeButton("取消", null).setPositiveButton("移除", (d, w) -> {
                for (int i = pages.size() - 1; i >= 0; i--)
                    if (selected.contains(pages.get(i).id)) pages.remove(i);
                selected.clear();
                changed();
            }).show();
    }

    private void autoRotate() {
        List<PageItem> work = targets();
        if (work.isEmpty() || busy) return;
        setBusy(true, "正在判讀文字方向…");
        worker.execute(() -> {
            int rotated = 0, uncertain = 0;
            List<String> errors = new ArrayList<>();
            List<Integer> corrections = new ArrayList<>();
            try (AutoRotate detector = new AutoRotate()) {
                for (int i = 0; i < work.size(); i++) {
                    final int current = i + 1;
                    ui(() -> setProgress("判讀方向 " + current + " / " + work.size()));
                    int correction = 0;
                    try {
                        AutoRotate.Result result = detector.detect(work.get(i).copy(), engine);
                        if (result.confident) {
                            correction = result.clockwiseDegrees;
                            if (correction != 0) rotated++;
                        } else uncertain++;
                    } catch (Exception e) { uncertain++; errors.add(message(e)); }
                    corrections.add(correction);
                }
            } catch (Exception e) { errors.add(message(e)); }
            final int done = rotated, skipped = uncertain;
            ui(() -> {
                for (int i = 0; i < corrections.size(); i++)
                    work.get(i).rotation = (work.get(i).rotation + corrections.get(i)) % 360;
                changed();
                setBusy(false, "");
                String detail = "已轉正 " + done + " 頁。";
                if (skipped > 0) detail += "\n" + skipped + " 頁文字不足或方向不明，保留原方向。";
                if (!errors.isEmpty()) detail += "\n\n" + errors.get(0);
                new AlertDialog.Builder(this).setTitle("自動旋轉完成").setMessage(detail)
                    .setPositiveButton("檢查預覽", null).show();
            });
        });
    }

    private void export(boolean share) {
        if (busy || pages.isEmpty()) return;
        rememberName();
        List<PageItem> snapshot = new ArrayList<>();
        for (PageItem p : pages) snapshot.add(p.copy());
        final int mode = compression;
        setBusy(true, "正在合併 PDF…");
        worker.execute(() -> {
            try {
                File result = engine.export(snapshot, mode, (done, total) ->
                    ui(() -> setProgress("合併頁面 " + done + " / " + total)));
                ui(() -> {
                    pendingExport = result;
                    exportDetail = "已產生 " + snapshot.size() + " 頁 · " + size(result.length());
                    setBusy(false, "");
                    if (share) shareExport(result); else chooseDestination();
                });
            } catch (Exception e) { ui(() -> { setBusy(false, ""); error("PDF 匯出失敗", e); }); }
        });
    }

    private void rememberName() {
        if (fileName != null) outputName = fileName.getText().toString().trim();
        if (outputName.isEmpty()) outputName = "合頁文件";
    }

    private String safeName() {
        String name = outputName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) name = name.substring(0, name.length() - 4);
        if (name.length() > 100) name = name.substring(0, 100);
        return name + ".pdf";
    }

    private void chooseDestination() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/pdf")
            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, safeName());
        try { startActivityForResult(intent, SAVE); }
        catch (Exception e) { error("無法開啟儲存位置", e); }
    }

    private void saveExport(Uri destination, File prepared) {
        setBusy(true, "正在儲存 PDF…");
        worker.execute(() -> {
            try (FileInputStream in = new FileInputStream(prepared);
                 OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
                if (out == null) throw new IllegalStateException("無法寫入選取的位置");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                out.flush();
            } catch (Exception e) {
                ui(() -> {
                    setBusy(false, "");
                    new AlertDialog.Builder(this).setTitle("儲存未完成")
                        .setMessage(message(e) + "\n完整 PDF 仍保留在 App 內，可重選位置儲存；目標位置可能留下未完成的檔案。")
                        .setNegativeButton("稍後", null).setPositiveButton("重選位置", (d, w) -> chooseDestination()).show();
                });
                return;
            }
            ui(() -> {
                exportDetail = "已儲存 · " + size(prepared.length());
                setBusy(false, "");
                new AlertDialog.Builder(this).setTitle("PDF 已儲存")
                    .setMessage(safeName() + "\n" + pages.size() + " 頁 · " + size(prepared.length()))
                    .setNegativeButton("完成", null).setNeutralButton("分享", (d, w) -> shareExport(prepared))
                    .setPositiveButton("開啟", (d, w) -> {
                        try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(destination, "application/pdf")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
                        catch (Exception e) { toast("裝置沒有 PDF 閱讀器，仍可在檔案中找到匯出的 PDF"); }
                    }).show();
            });
        });
    }

    private void shareExport(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file, safeName());
            Intent share = new Intent(Intent.ACTION_SEND).setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newRawUri("PDF", uri));
            startActivity(Intent.createChooser(share, "分享 PDF"));
        } catch (Exception e) { error("無法分享 PDF", e); }
    }

    private void changed() {
        pendingExport = null;
        exportDetail = "";
        persist();
        adapter.notifyDataSetChanged();
        refresh();
    }

    private void persist() {
        try { session.write(pages); }
        catch (Exception e) { error("無法儲存工作區", e); }
    }

    private void setBusy(boolean value, String text) {
        if (value) cancelImageHold();
        busy = value;
        status = text;
        if (value) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refresh();
    }

    private void setProgress(String text) { status = text; progressText.setText(text); }

    private void refresh() {
        HashSet<String> files = new HashSet<>();
        long bytes = 0;
        for (PageItem p : pages) if (files.add(p.sourcePath)) bytes += new File(p.sourcePath).length();
        count.setText(pages.isEmpty() ? "PDF、JPG、PNG、WebP · 可一次選取多個檔案" :
            pages.size() + " 頁  ·  " + files.size() + " 個來源  ·  " + size(bytes));
        selectionLabel.setText(selected.isEmpty() ? "長按拖拉排序 · 按住圖片 2.5 秒多選 · 點圖放大" :
            "已選 " + selected.size() + " 頁 · 點圖繼續多選 · 旋轉只套用選取頁面");
        selectButton.setText(!pages.isEmpty() && selected.size() == pages.size() ? "取消" : "全選");
        empty.setVisibility(pages.isEmpty() ? View.VISIBLE : View.GONE);
        grid.setVisibility(pages.isEmpty() ? View.GONE : View.VISIBLE);
        for (View control : controls) enable(control, !busy);
        enable(selectButton, !busy && !pages.isEmpty());
        enable(rotateButton, !busy && !pages.isEmpty());
        enable(autoButton, !busy && !pages.isEmpty());
        enable(deleteButton, !busy && !selected.isEmpty());
        if (exportButton != null) enable(exportButton, !busy && !pages.isEmpty());
        if (shareButton != null) enable(shareButton, !busy && !pages.isEmpty());
        if (summary != null) summary.setText(pages.size() + " 頁合成 1 份 PDF");
        if (lastExport != null) lastExport.setText(exportDetail);
        progressArea.setVisibility(busy ? View.VISIBLE : View.GONE);
        progressText.setText(status);
    }

    private void preview(int index) {
        if (busy || index < 0 || index >= pages.size()) return;
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar);
        LinearLayout layout = column();
        layout.setBackgroundColor(BG);
        layout.setPadding(dp(16), dp(12), dp(16), dp(12));
        final int[] current = {index};
        TextView title = label("", 15, INK, true);
        LinearLayout top = row();
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(button("完成", false, v -> dialog.dismiss()), lp(70, 44));
        layout.addView(top);
        TextView hint = label("雙指縮放 · 雙點放大 · 拖動查看", 11, MUTED, false);
        hint.setPadding(0, dp(8), 0, dp(8));
        layout.addView(hint);
        PaperPreview image = new PaperPreview(true);
        layout.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout nav = row();
        nav.setGravity(Gravity.CENTER);
        final Runnable[] load = new Runnable[1];
        load[0] = () -> {
            PageItem item = pages.get(current[0]);
            title.setText((current[0] + 1) + " / " + pages.size() + "  ·  " + item.sourceName);
            image.setBitmap(null, 0);
            int generation = ++previewGeneration;
            PageItem snapshot = item.copy();
            worker.execute(() -> {
                try {
                    Bitmap bitmap = engine.render(snapshot, 1800);
                    ui(() -> {
                        if (dialog.isShowing() && generation == previewGeneration) image.setBitmap(bitmap, 0);
                        else bitmap.recycle();
                    });
                } catch (Exception e) { ui(() -> error("無法顯示頁面", e)); }
            });
        };
        nav.addView(button("← 上一頁", false, v -> { if (current[0] > 0) { current[0]--; load[0].run(); } }), lp(100, 48));
        nav.addView(gap(8));
        nav.addView(button("↻ 旋轉", true, v -> {
            PageItem p = pages.get(current[0]);
            p.rotation = (p.rotation + 90) % 360;
            changed();
            load[0].run();
        }), lp(100, 48));
        nav.addView(gap(8));
        nav.addView(button("下一頁 →", false, v -> { if (current[0] < pages.size() - 1) { current[0]++; load[0].run(); } }), lp(100, 48));
        layout.addView(space(8));
        layout.addView(nav);
        dialog.setContentView(layout);
        dialog.setOnDismissListener(d -> { previewGeneration++; image.setBitmap(null, 0); });
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
        load[0].run();
    }

    private final class PagesAdapter extends RecyclerView.Adapter<PageHolder> {
        @Override public PageHolder onCreateViewHolder(ViewGroup parent, int type) { return new PageHolder(); }
        @Override public int getItemCount() { return pages.size(); }
        @Override public void onBindViewHolder(PageHolder h, int position) {
            PageItem p = pages.get(position);
            h.card.setBackground(shape(PANEL, 10, selected.contains(p.id) ? ACCENT : LINE));
            h.number.setText(String.format(Locale.ROOT, "%02d", position + 1));
            h.check.setText(selected.contains(p.id) ? "✓" : "○");
            h.check.setTextColor(selected.contains(p.id) ? SOFT : MUTED);
            h.check.setContentDescription("選取第 " + (position + 1) + " 頁");
            h.name.setText(p.sourceName);
            h.meta.setText((p.isPdf ? "PDF · 第 " + (p.sourcePage + 1) + " 頁" : "圖片") +
                (p.rotation == 0 ? "" : " · " + p.rotation + "°"));
            Bitmap bitmap = thumbs.get(p.thumbnailPath);
            if (bitmap == null) {
                bitmap = BitmapFactory.decodeFile(p.thumbnailPath);
                if (bitmap != null) thumbs.put(p.thumbnailPath, bitmap);
            }
            h.image.setBitmap(bitmap, p.rotation);
            h.image.setContentDescription("預覽第 " + (position + 1) + " 頁，" + p.sourceName);
            h.image.setOnClickListener(v -> {
                if (busy) return;
                if (selected.isEmpty()) preview(h.getBindingAdapterPosition());
                else h.check.performClick();
            });
            h.check.setOnClickListener(v -> {
                if (busy) return;
                if (!selected.remove(p.id)) selected.add(p.id);
                notifyItemChanged(h.getBindingAdapterPosition());
                refresh();
            });
            h.rotate.setContentDescription("順時針旋轉第 " + (position + 1) + " 頁");
            h.rotate.setOnClickListener(v -> {
                if (!busy) { p.rotation = (p.rotation + 90) % 360; changed(); }
            });
            h.more.setOnClickListener(v -> {
                if (busy) return;
                new AlertDialog.Builder(MainActivity.this).setTitle("第 " + (h.getBindingAdapterPosition() + 1) + " 頁")
                    .setItems(new String[]{"移到最前", "移到最後", "選取 / 取消選取"}, (d, which) -> {
                        if (which == 0) { pages.remove(p); pages.add(0, p); changed(); }
                        else if (which == 1) { pages.remove(p); pages.add(p); changed(); }
                        else { if (!selected.remove(p.id)) selected.add(p.id); notifyDataSetChanged(); refresh(); }
                    }).show();
            });
        }
    }

    private final class PageHolder extends RecyclerView.ViewHolder {
        final LinearLayout card;
        final TextView number, name, meta;
        final Button check, rotate, more;
        final PaperPreview image;
        PageHolder() {
            super(new LinearLayout(MainActivity.this));
            card = (LinearLayout) itemView;
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(8), 0, dp(8), dp(4));
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(-1, -2);
            params.setMargins(0, 0, dp(8), dp(10));
            card.setLayoutParams(params);
            LinearLayout top = row();
            number = label("", 12, MUTED, true);
            top.addView(number, new LinearLayout.LayoutParams(0, dp(38), 1));
            number.setGravity(Gravity.CENTER_VERTICAL);
            TextView drag = label("⠿", 19, MUTED, false);
            drag.setGravity(Gravity.CENTER);
            drag.setContentDescription("長按拖拉排序");
            drag.setFocusable(true);
            drag.setOnLongClickListener(v -> {
                if (!busy) touchHelper.startDrag(this);
                return true;
            });
            top.addView(drag, lp(40, 44));
            check = button("○", false, null);
            check.setBackgroundColor(Color.TRANSPARENT);
            top.addView(check, lp(44, 44));
            card.addView(top);
            image = new PaperPreview(false);
            image.setOnLongClickListener(v -> {
                if (!busy) touchHelper.startDrag(this);
                return true;
            });
            image.setBackground(shape(Color.rgb(222, 224, 231), 5, Color.TRANSPARENT));
            card.addView(image, lp(-1, getResources().getConfiguration().screenHeightDp < 600 ? 105 : 160));
            name = label("", 12, INK, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(dp(2), dp(10), 0, dp(2));
            card.addView(name);
            LinearLayout bottom = row();
            meta = label("", 10, MUTED, false);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            bottom.addView(meta, new LinearLayout.LayoutParams(0, dp(44), 1));
            meta.setGravity(Gravity.CENTER_VERTICAL);
            rotate = button("↻", false, null);
            rotate.setTextSize(21);
            rotate.setBackgroundColor(Color.TRANSPARENT);
            more = button("⋯", false, null);
            more.setTextSize(20);
            more.setContentDescription("頁面排序選項");
            more.setBackgroundColor(Color.TRANSPARENT);
            drag.setOnClickListener(v -> more.performClick());
            bottom.addView(rotate, lp(44, 44));
            bottom.addView(more, lp(44, 44));
            card.addView(bottom);
        }
    }

    private final class PaperPreview extends View {
        private Bitmap bitmap;
        private int angle;
        private final boolean zoomable;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private float zoom = 1, panX, panY;
        private final ScaleGestureDetector scale;
        private final GestureDetector gestures;
        PaperPreview(boolean zoomable) {
            super(MainActivity.this);
            this.zoomable = zoomable;
            setFocusable(true);
            scale = new ScaleGestureDetector(MainActivity.this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    zoom = Math.max(1, Math.min(6, zoom * detector.getScaleFactor()));
                    if (zoom == 1) panX = panY = 0;
                    invalidate();
                    return true;
                }
            });
            gestures = new GestureDetector(MainActivity.this, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onDoubleTap(MotionEvent e) { zoom = zoom > 1 ? 1 : 2.5f; panX = panY = 0; invalidate(); return true; }
                @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                    if (zoom > 1) { panX -= dx; panY -= dy; invalidate(); }
                    return true;
                }
                @Override public boolean onSingleTapConfirmed(MotionEvent e) { performClick(); return true; }
            });
        }
        void setBitmap(Bitmap next, int degrees) {
            // Full-size preview owns its bitmap; thumbnail bitmaps belong to the bounded cache.
            if (zoomable && bitmap != null && bitmap != next) bitmap.recycle();
            bitmap = next;
            angle = degrees;
            zoom = 1;
            panX = panY = 0;
            invalidate();
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!zoomable) return super.onTouchEvent(event);
            scale.onTouchEvent(event);
            gestures.onTouchEvent(event);
            return true;
        }
        @Override public boolean performClick() { return super.performClick(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap == null || bitmap.isRecycled()) return;
            boolean sideways = angle % 180 != 0;
            float width = sideways ? bitmap.getHeight() : bitmap.getWidth();
            float height = sideways ? bitmap.getWidth() : bitmap.getHeight();
            float fit = Math.min((getWidth() - dp(16)) / width, (getHeight() - dp(16)) / height);
            canvas.save();
            canvas.translate(getWidth() / 2f + panX, getHeight() / 2f + panY);
            canvas.scale(fit * zoom, fit * zoom);
            canvas.rotate(angle);
            canvas.drawBitmap(bitmap, -bitmap.getWidth() / 2f, -bitmap.getHeight() / 2f, paint);
            canvas.restore();
        }
    }

    @Override public void onBackPressed() {
        if (busy) { toast("正在處理文件，請稍候"); return; }
        super.onBackPressed();
    }
    private void ui(Runnable action) { runOnUiThread(() -> { if (!isDestroyed() && !isFinishing()) action.run(); }); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private void error(String title, Exception e) {
        android.util.Log.e("PaperJoin", title, e);
        new AlertDialog.Builder(this).setTitle(title).setMessage(message(e)).setPositiveButton("知道了", null).show();
    }
    private void enable(View view, boolean value) { view.setEnabled(value); view.setAlpha(value ? 1 : .38f); }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout.LayoutParams lp(int width, int height) { return new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height)); }
    private View gap(int width) { View v = new View(this); v.setLayoutParams(lp(width, 1)); return v; }
    private View space(int height) { View v = new View(this); v.setLayoutParams(lp(1, height)); return v; }
    private TextView label(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }
    private GradientDrawable shape(int color, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        if (border != Color.TRANSPARENT) drawable.setStroke(dp(1), border);
        return drawable;
    }
    private Button button(String text, boolean primary, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setAllCaps(false); b.setText(text); b.setTextSize(12); b.setTextColor(INK);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setStateListAnimator(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), shape(primary ? ACCENT : SURFACE, 8, primary ? Color.TRANSPARENT : LINE), null));
        b.setOnClickListener(listener);
        return b;
    }
    private Button tool(String text, View.OnClickListener listener) { return button(text, false, listener); }
    private static String size(long bytes) {
        return bytes < 1024 * 1024 ? String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0) :
            String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }
}
