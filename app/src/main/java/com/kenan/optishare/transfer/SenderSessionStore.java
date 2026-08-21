package com.kenan.optishare.transfer;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import com.kenan.optishare.model.TransferItem;
import com.kenan.optishare.protocol.BatchManifest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/** Persists one active outbound session so process recreation can reuse the same IDs/hashes and peer. */
public final class SenderSessionStore {
    public static final class Pending {
        public final String host;
        public final String peerAddress;
        public final List<TransferItem> items;
        public final BatchManifest manifest;

        Pending(String host, String peerAddress, List<TransferItem> items, BatchManifest manifest) {
            this.host = host;
            this.peerAddress = peerAddress;
            this.items = items;
            this.manifest = manifest;
        }
    }

    private final File file;

    public SenderSessionStore(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "sessions");
        if (!dir.exists()) dir.mkdirs();
        file = new File(dir, "pending_sender.json");
    }

    public synchronized void save(String host, List<TransferItem> items, BatchManifest manifest) throws Exception {
        save(host, null, items, manifest);
    }

    public synchronized void save(String host, String peerAddress, List<TransferItem> items, BatchManifest manifest) throws Exception {
        if (host == null || items == null || manifest == null || items.size() != manifest.getEntries().size()) {
            throw new IllegalArgumentException("Invalid sender session");
        }
        JSONObject root = new JSONObject();
        root.put("host", host);
        root.put("peerAddress", peerAddress == null ? JSONObject.NULL : peerAddress);
        root.put("sessionId", manifest.getSessionId());
        root.put("createdAt", manifest.getCreatedAt());
        JSONArray array = new JSONArray();
        for (int i = 0; i < items.size(); i++) {
            TransferItem item = items.get(i);
            BatchManifest.Entry entry = manifest.getEntries().get(i);
            JSONObject o = new JSONObject();
            o.put("id", entry.id);
            o.put("uri", item.getUri().toString());
            o.put("name", entry.name);
            o.put("mime", entry.mime);
            o.put("size", entry.size);
            o.put("category", entry.category.name());
            o.put("sha256", Base64.encodeToString(entry.sha256, Base64.NO_WRAP));
            array.put(o);
        }
        root.put("files", array);
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileWriter writer = new FileWriter(temp, false)) {
            writer.write(root.toString());
            writer.flush();
        }
        if (file.exists() && !file.delete()) throw new IllegalStateException("Could not replace pending session");
        if (!temp.renameTo(file)) throw new IllegalStateException("Could not commit pending session");
    }

    public synchronized Pending load() {
        if (!file.exists()) return null;
        try {
            StringBuilder raw = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) raw.append(line);
            }
            JSONObject root = new JSONObject(raw.toString());
            String host = root.getString("host");
            String peerAddress = root.isNull("peerAddress") ? null : root.optString("peerAddress", null);
            String sessionId = root.getString("sessionId");
            long createdAt = root.getLong("createdAt");
            JSONArray array = root.getJSONArray("files");
            List<TransferItem> items = new ArrayList<>();
            List<BatchManifest.Entry> entries = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                String id = o.getString("id");
                Uri uri = Uri.parse(o.getString("uri"));
                String name = o.getString("name");
                String mime = o.getString("mime");
                long size = o.getLong("size");
                TransferItem.Category category;
                try { category = TransferItem.Category.valueOf(o.getString("category")); }
                catch (Exception ignored) { category = TransferItem.Category.OTHER; }
                byte[] sha = Base64.decode(o.getString("sha256"), Base64.NO_WRAP);
                items.add(new TransferItem(id, uri, name, mime, size, category));
                entries.add(new BatchManifest.Entry(id, name, mime, size, category, sha));
            }
            return new Pending(host, peerAddress, items, new BatchManifest(sessionId, createdAt, entries));
        } catch (Exception corrupted) {
            clear();
            return null;
        }
    }

    public synchronized boolean exists() { return file.exists() && file.length() > 0; }

    public synchronized void clear() {
        if (file.exists()) file.delete();
    }
}
