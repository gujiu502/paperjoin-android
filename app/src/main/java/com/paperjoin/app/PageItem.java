package com.paperjoin.app;

import java.util.UUID;

/** One independently reorderable page. Rotation is clockwise relative to the original preview. */
public final class PageItem {
    public String id = UUID.randomUUID().toString();
    public String sourcePath;
    public String sourceName;
    public int sourcePage;
    public boolean isPdf;
    public int rotation;
    public float width;
    public float height;
    public String thumbnailPath;

    public PageItem() {}

    public PageItem copy() {
        PageItem p = new PageItem();
        p.id = id;
        p.sourcePath = sourcePath;
        p.sourceName = sourceName;
        p.sourcePage = sourcePage;
        p.isPdf = isPdf;
        p.rotation = rotation;
        p.width = width;
        p.height = height;
        p.thumbnailPath = thumbnailPath;
        return p;
    }
}
