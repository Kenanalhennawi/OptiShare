package com.kenan.optishare.storage;

import com.kenan.optishare.model.TransferItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FolderTransferQueue {
    private static List<TransferItem> pending = Collections.emptyList();
    private FolderTransferQueue() {}

    public static synchronized void set(List<TransferItem> items) {
        pending = new ArrayList<>(items);
    }

    public static synchronized List<TransferItem> takeIfMatches(int count) {
        if (pending.size() != count) return Collections.emptyList();
        List<TransferItem> result = new ArrayList<>(pending);
        pending = Collections.emptyList();
        return result;
    }

    public static synchronized void clear() {
        pending = Collections.emptyList();
    }
}
