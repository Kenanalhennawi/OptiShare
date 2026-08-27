from pathlib import Path


def replace(path, old, new, label):
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise RuntimeError(f"missing {label} in {path}")
    p.write_text(s.replace(old, new, 1))


replace(
    "app/build.gradle",
    "    implementation 'androidx.core:core:1.16.0'\n",
    "    implementation 'androidx.core:core:1.16.0'\n    implementation 'androidx.documentfile:documentfile:1.0.1'\n",
    "documentfile dependency",
)

# TransferItem: optional safe relative path, old constructors retained.
p = Path("app/src/main/java/com/kenan/optishare/model/TransferItem.java")
s = p.read_text()
s = s.replace(
    "    private final Category category;\n",
    "    private final Category category;\n    private final String relativePath;\n",
    1,
)
s = s.replace(
    """    public TransferItem(Uri uri, String name, String mimeType, long size, Category category) {
        this(UUID.randomUUID().toString(), uri, name, mimeType, size, category);
    }

    public TransferItem(String id, Uri uri, String name, String mimeType, long size, Category category) {
""",
    """    public TransferItem(Uri uri, String name, String mimeType, long size, Category category) {
        this(UUID.randomUUID().toString(), uri, name, mimeType, size, category, null);
    }

    public TransferItem(Uri uri, String name, String mimeType, long size, Category category,
                        String relativePath) {
        this(UUID.randomUUID().toString(), uri, name, mimeType, size, category, relativePath);
    }

    public TransferItem(String id, Uri uri, String name, String mimeType, long size,
                        Category category) {
        this(id, uri, name, mimeType, size, category, null);
    }

    public TransferItem(String id, Uri uri, String name, String mimeType, long size,
                        Category category, String relativePath) {
""",
    1,
)
s = s.replace(
    """        this.size = size;
        this.category = category == null ? Category.OTHER : category;
    }
""",
    """        this.size = size;
        this.category = category == null ? Category.OTHER : category;
        this.relativePath = safeRelativePath(relativePath);
    }
""",
    1,
)
s = s.replace(
    "    public Category getCategory() { return category; }\n",
    "    public Category getCategory() { return category; }\n    public String getRelativePath() { return relativePath; }\n",
    1,
)
anchor = "    public static String normalizedExtension(String name) {"
helper = """    public static String safeRelativePath(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String normalized = raw.replace('\\\\', '/').trim();
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        String[] parts = normalized.split("/");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty() || ".".equals(value) || "..".equals(value)) {
                throw new IllegalArgumentException("Invalid relative path");
            }
            String safe = safeName(value);
            if (safe.length() > 255) safe = safe.substring(0, 255);
            if (out.length() > 0) out.append('/');
            out.append(safe);
            if (out.length() > 2048) throw new IllegalArgumentException("Relative path too long");
        }
        return out.length() == 0 ? null : out.toString();
    }

"""
if anchor not in s:
    raise RuntimeError("missing relative path anchor")
p.write_text(s.replace(anchor, helper + anchor, 1))

# BatchManifest: preserve relative path.
p = Path("app/src/main/java/com/kenan/optishare/protocol/BatchManifest.java")
s = p.read_text()
s = s.replace(
    "        public final TransferItem.Category category;\n        public final byte[] sha256;\n",
    "        public final TransferItem.Category category;\n        public final String relativePath;\n        public final byte[] sha256;\n",
    1,
)
s = s.replace(
    """        public Entry(String id, String name, String mime, long size,
                     TransferItem.Category category, byte[] sha256) {
""",
    """        public Entry(String id, String name, String mime, long size,
                     TransferItem.Category category, byte[] sha256) {
            this(id, name, mime, size, category, null, sha256);
        }

        public Entry(String id, String name, String mime, long size,
                     TransferItem.Category category, String relativePath, byte[] sha256) {
""",
    1,
)
s = s.replace(
    """            this.category = category == null ? TransferItem.Category.OTHER : category;
            this.sha256 = sha256.clone();
""",
    """            this.category = category == null ? TransferItem.Category.OTHER : category;
            this.relativePath = TransferItem.safeRelativePath(relativePath);
            this.sha256 = sha256.clone();
""",
    1,
)
p.write_text(s)

# Protocol v4 because authenticated manifest format changes.
p = Path("app/src/main/java/com/kenan/optishare/protocol/SessionWire.java")
s = p.read_text()
s = s.replace("public static final int VERSION = 3;", "public static final int VERSION = 4;", 1)
s = s.replace(
    """            out.writeLong(e.size);
            out.writeInt(e.category.ordinal());
            out.writeInt(e.sha256.length);""",
    """            out.writeLong(e.size);
            out.writeInt(e.category.ordinal());
            out.writeUTF(e.relativePath == null ? "" : e.relativePath);
            out.writeInt(e.sha256.length);""",
    1,
)
s = s.replace(
    """            int categoryIndex = in.readInt();
            int hashLength = in.readInt();""",
    """            int categoryIndex = in.readInt();
            String relativePath = in.readUTF();
            if (relativePath.isEmpty()) relativePath = null;
            int hashLength = in.readInt();""",
    1,
)
s = s.replace(
    "entries.add(new BatchManifest.Entry(id, name, mime, size, category, hash));",
    "entries.add(new BatchManifest.Entry(id, name, mime, size, category, relativePath, hash));",
    1,
)
p.write_text(s)

replace(
    "app/src/main/java/com/kenan/optishare/transfer/TransferEngine.java",
    """                        Uri published = downloadStore.publishVerified(sessionId, fileId,
                                entry.name, entry.mime, entry.category);""",
    """                        Uri published = downloadStore.publishVerified(sessionId, fileId,
                                entry.name, entry.mime, entry.category, entry.relativePath);""",
    "publish relative path",
)
replace(
    "app/src/main/java/com/kenan/optishare/transfer/TransferEngine.java",
    """            entries.add(new BatchManifest.Entry(item.getId(), item.getName(),
                    item.getMimeType(), item.getSize(), item.getCategory(), digest.digest()));""",
    """            entries.add(new BatchManifest.Entry(item.getId(), item.getName(),
                    item.getMimeType(), item.getSize(), item.getCategory(),
                    item.getRelativePath(), digest.digest()));""",
    "manifest relative path",
)

# Pending sender session persists relative path.
p = Path("app/src/main/java/com/kenan/optishare/transfer/SenderSessionStore.java")
s = p.read_text()
s = s.replace(
    """            o.put("category", entry.category.name());
            o.put("sha256", Base64.encodeToString(entry.sha256, Base64.NO_WRAP));""",
    """            o.put("category", entry.category.name());
            o.put("relativePath", entry.relativePath == null ? JSONObject.NULL : entry.relativePath);
            o.put("sha256", Base64.encodeToString(entry.sha256, Base64.NO_WRAP));""",
    1,
)
s = s.replace(
    """                byte[] sha = Base64.decode(o.getString("sha256"), Base64.NO_WRAP);
                if (sha.length != 32) throw new IllegalStateException("Invalid saved SHA-256");
                items.add(new TransferItem(id, uri, name, mime, size, category));
                entries.add(new BatchManifest.Entry(id, name, mime, size, category, sha));""",
    """                String relativePath = o.isNull("relativePath")
                        ? null : o.optString("relativePath", null);
                byte[] sha = Base64.decode(o.getString("sha256"), Base64.NO_WRAP);
                if (sha.length != 32) throw new IllegalStateException("Invalid saved SHA-256");
                items.add(new TransferItem(id, uri, name, mime, size, category, relativePath));
                entries.add(new BatchManifest.Entry(id, name, mime, size, category,
                        relativePath, sha));""",
    1,
)
p.write_text(s)

# DownloadStore publishes folder entries under Download/OptiShare/Folders/<relative parent>.
p = Path("app/src/main/java/com/kenan/optishare/storage/DownloadStore.java")
s = p.read_text()
s = s.replace(
    """    public Uri publishVerified(String sessionId, String fileId, String name, String mime,
                               TransferItem.Category category) throws IOException {""",
    """    public Uri publishVerified(String sessionId, String fileId, String name, String mime,
                               TransferItem.Category category, String relativePath) throws IOException {""",
    1,
)
s = s.replace(
    """        String safeName = TransferItem.safeName(name);
        String folder = categoryFolder(category);""",
    """        String safeName = TransferItem.safeName(name);
        String folder = categoryFolder(category);
        String safeRelative;
        try { safeRelative = TransferItem.safeRelativePath(relativePath); }
        catch (IllegalArgumentException invalid) { throw new IOException("Unsafe relative path", invalid); }
        if (safeRelative != null) {
            int slash = safeRelative.lastIndexOf('/');
            safeName = slash >= 0 ? safeRelative.substring(slash + 1) : safeRelative;
            String parent = slash >= 0 ? safeRelative.substring(0, slash) : "";
            folder = parent.isEmpty() ? "Folders" : "Folders/" + parent;
        }""",
    1,
)
p.write_text(s)

# SAF folder collector.
Path("app/src/main/java/com/kenan/optishare/storage/FolderSelection.java").write_text(
    r'''package com.kenan.optishare.storage;

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
'''
)

# Process-local handoff retaining relative paths between selection and service start.
Path("app/src/main/java/com/kenan/optishare/storage/FolderTransferQueue.java").write_text(
    r'''package com.kenan.optishare.storage;

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
'''
)

# V2Activity: folder picker and tile.
p = Path("app/src/main/java/com/kenan/optishare/V2Activity.java")
s = p.read_text()
s = s.replace(
    "import com.kenan.optishare.storage.MediaRepository;\n",
    "import com.kenan.optishare.storage.MediaRepository;\nimport com.kenan.optishare.storage.FolderSelection;\nimport com.kenan.optishare.storage.FolderTransferQueue;\n",
    1,
)
marker = "    private final ActivityResultLauncher<ScanOptions> qrScanner =\n"
launcher = """    private final ActivityResultLauncher<Intent> folderPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null
                        || result.getData().getData() == null) return;
                Uri tree = result.getData().getData();
                int flags = result.getData().getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try { getContentResolver().takePersistableUriPermission(
                        tree, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (Exception ignored) { }
                try {
                    List<com.kenan.optishare.model.TransferItem> files = FolderSelection.collect(this, tree);
                    selected.clear();
                    for (com.kenan.optishare.model.TransferItem item : files) selected.add(item.getUri());
                    FolderTransferQueue.set(files);
                    showSendSelection();
                    showMessage("Folder ready", files.size()
                            + " files selected with folder structure preserved.");
                } catch (Exception error) {
                    showMessage("Folder could not be opened", error.getMessage());
                }
            });

"""
if marker not in s:
    raise RuntimeError("missing folder launcher anchor")
s = s.replace(marker, launcher + marker, 1)
old_row = """                category("A","Apps",Color.rgb(53,203,165),v -> openExternal("application/vnd.android.package-archive")),
                category("≡","Documents",Color.rgb(55,143,255),v -> openExternal("application/*")),
                category("…","Other",Color.rgb(122,140,166),v -> openExternal("*/*")));"""
new_row = """                category("A","Apps",Color.rgb(53,203,165),v -> openExternal("application/vnd.android.package-archive")),
                category("≡","Documents",Color.rgb(55,143,255),v -> openExternal("application/*")),
                category("▤","Folder",Color.rgb(122,140,166),v -> openFolder())));"""
if old_row not in s:
    raise RuntimeError("missing folder tile anchor")
s = s.replace(old_row, new_row, 1)
anchor = "    private void openExternal(String mime){"
helper = """    private void openFolder(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                |Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                |Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        folderPicker.launch(intent);
    }

"""
if anchor not in s:
    raise RuntimeError("missing openFolder anchor")
s = s.replace(anchor, helper + anchor, 1)
p.write_text(s)

# TransferService consumes rich folder metadata if present.
p = Path("app/src/main/java/com/kenan/optishare/transfer/TransferService.java")
s = p.read_text()
s = s.replace(
    "import com.kenan.optishare.storage.FileClassifier;\n",
    "import com.kenan.optishare.storage.FileClassifier;\nimport com.kenan.optishare.storage.FolderTransferQueue;\n",
    1,
)
old = """                activeItems = resolveItems(rawUris);
                TransferEngine engine = new TransferEngine(this);"""
new = """                List<TransferItem> folderItems = FolderTransferQueue.takeIfMatches(rawUris.size());
                activeItems = folderItems.isEmpty() ? resolveItems(rawUris) : folderItems;
                TransferEngine engine = new TransferEngine(this);"""
if old not in s:
    raise RuntimeError("missing service folder queue anchor")
p.write_text(s.replace(old, new, 1))
