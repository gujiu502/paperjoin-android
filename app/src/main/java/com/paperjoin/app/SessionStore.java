package com.paperjoin.app;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class SessionStore {
    private final File root;
    private final AtomicFile file;
    SessionStore(Context context) {
        root = context.getFilesDir();
        file = new AtomicFile(new File(root, "session.json"));
    }
    List<PageItem> read() throws Exception {
        List<PageItem> pages = new ArrayList<>();
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return pages;
        JSONArray data = new JSONArray(new String(file.readFully(), StandardCharsets.UTF_8));
        String prefix = root.getCanonicalPath() + File.separator;
        for (int i = 0; i < data.length(); i++) {
            JSONObject j = data.getJSONObject(i);
            PageItem p = new PageItem();
            p.id = j.getString("id");
            p.sourcePath = j.getString("path");
            p.sourceName = j.getString("name");
            p.sourcePage = j.getInt("page");
            p.isPdf = j.getBoolean("pdf");
            p.rotation = ((j.getInt("rotation") % 360) + 360) % 360;
            p.width = (float) j.getDouble("width");
            p.height = (float) j.getDouble("height");
            p.thumbnailPath = j.getString("thumb");
            if (!new File(p.sourcePath).getCanonicalPath().startsWith(prefix)
                    || !new File(p.thumbnailPath).getCanonicalPath().startsWith(prefix))
                throw new IllegalStateException("工作區路徑無效");
            if (!new File(p.sourcePath).isFile()) throw new IllegalStateException("工作區來源檔案遺失");
            pages.add(p);
        }
        return pages;
    }
    void write(List<PageItem> pages) throws Exception {
        JSONArray data = new JSONArray();
        for (PageItem p : pages) data.put(new JSONObject()
            .put("id", p.id).put("path", p.sourcePath).put("name", p.sourceName)
            .put("page", p.sourcePage).put("pdf", p.isPdf).put("rotation", p.rotation)
            .put("width", p.width).put("height", p.height).put("thumb", p.thumbnailPath));
        FileOutputStream out = file.startWrite();
        try {
            out.write(data.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (Exception e) {
            file.failWrite(out);
            throw e;
        }
    }
}
