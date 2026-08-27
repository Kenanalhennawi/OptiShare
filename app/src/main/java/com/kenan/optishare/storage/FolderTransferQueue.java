package com.kenan.optishare.storage;

import com.kenan.optishare.model.TransferItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Holds rich metadata for selections whose URI alone is not enough (folders/text). */
public final class FolderTransferQueue {
    private static final List<TransferItem> pending = new ArrayList<>();
    private FolderTransferQueue() {}

    public static synchronized void set(List<TransferItem> items) {
        pending.clear();
        if (items != null) pending.addAll(items);
    }

    public static synchronized void addAll(List<TransferItem> items) {
        if (items == null) return;
        for (TransferItem item : items) add(item);
    }

    public static synchronized void add(TransferItem item) {
        if (item == null || item.getUri() == null) return;
        String uri = item.getUri().toString();
        for (int i = pending.size() - 1; i >= 0; i--) {
            TransferItem existing = pending.get(i);
            if (existing.getUri() != null && uri.equals(existing.getUri().toString())) pending.remove(i);
        }
        pending.add(item);
    }

    public static synchronized List<TransferItem> takeAll() {
        if (pending.isEmpty()) return Collections.emptyList();
        List<TransferItem> result = new ArrayList<>(pending);
        pending.clear();
        return result;
    }

    public static synchronized void clear() { pending.clear(); }
}
