package com.kenan.optishare.storage;

import android.content.Context;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import androidx.documentfile.provider.DocumentFile;

import com.kenan.optishare.model.TransferItem;

import java.util.ArrayList;
import java.util.List;

public final class FolderSelection {
    private FolderSelection() {}

    public static List<TransferItem> collect(Context context, Uri treeUri) {
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null || !root.exists() || !root.isDirectory()) {
            throw new IllegalArgumentException("Selected folder is unavailable");
        }
        String rootName = clean(root.getName(), "Folder");
        List<TransferItem> out = new ArrayList<>();
        walk(root, rootName, out);
        if (out.isEmpty()) throw new IllegalArgumentException("Selected folder contains no readable files");
        if (out.size() > 10_000) throw new IllegalArgumentException("Folder contains more than 10,000 files");
        return out;
    }

    private static void walk(DocumentFile dir, String relative, List<TransferItem> out) {
        for (DocumentFile child : dir.listFiles()) {
            if (out.size() > 10_000) return;
            String name = clean(child.getName(), child.isDirectory() ? "Folder" : "file.bin");
            String path = relative + "/" + name;
            if (child.isDirectory()) {
                walk(child, path, out);
            } else if (child.isFile() && child.canRead()) {
                long size = Math.max(0L, child.length());
                String mime = child.getType();
                if (mime == null) mime = guessMime(name);
                TransferItem.Category category = FileClassifier.classify(name, mime);
                out.add(new TransferItem(child.getUri(), name, mime, size, category, path));
            }
        }
    }

    private static String clean(String value, String fallback) {
        String candidate = value == null || value.trim().isEmpty() ? fallback : value.trim();
        return TransferItem.safeName(candidate);
    }

    private static String guessMime(String name) {
        String ext = TransferItem.normalizedExtension(name);
        String value = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return value == null ? "application/octet-stream" : value;
    }
}
