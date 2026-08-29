package com.kenan.optishare.storage;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;

import androidx.annotation.RequiresApi;

import com.kenan.optishare.model.TransferItem;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Stores partial data privately, then publishes verified files into Download/OptiShare/<Category>. */
public final class DownloadStore {
    private static final long MIN_FREE_RESERVE = 64L * 1024 * 1024;
    private static final long STALE_PARTIAL_AGE_MS = 30L * 24L * 60L * 60L * 1000L;
    private final Context context;

    public DownloadStore(Context context) {
        this.context = context.getApplicationContext();
    }

    private File sessionDir(String sessionId) throws IOException {
        File root = new File(context.getFilesDir(), "partial/" + sanitize(sessionId));
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("Could not create partial directory");
        }
        return root;
    }

    public File partialFile(String sessionId, String fileId) throws IOException {
        return new File(sessionDir(sessionId), sanitize(fileId) + ".optishare-part");
    }

    private File verifiedMarker(String sessionId, String fileId) throws IOException {
        return new File(sessionDir(sessionId), sanitize(fileId) + ".verified");
    }

    public long partialLength(String sessionId, String fileId) throws IOException {
        long verified = verifiedLength(sessionId, fileId);
        if (verified >= 0) return verified;
        File file = partialFile(sessionId, fileId);
        return file.exists() ? file.length() : 0L;
    }

    public long verifiedLength(String sessionId, String fileId) throws IOException {
        File marker = verifiedMarker(sessionId, fileId);
        if (!marker.exists()) return -1L;
        try (BufferedReader reader = new BufferedReader(new FileReader(marker))) {
            String line = reader.readLine();
            return line == null ? -1L : Long.parseLong(line.trim());
        } catch (Exception corrupted) {
            marker.delete();
            return -1L;
        }
    }

    public boolean isVerified(String sessionId, String fileId, long expectedSize)
            throws IOException {
        return verifiedLength(sessionId, fileId) == expectedSize;
    }

    public FileOutputStream openPartial(String sessionId, String fileId, boolean append)
            throws IOException {
        if (verifiedLength(sessionId, fileId) >= 0) {
            throw new IOException("File already verified");
        }
        return new FileOutputStream(partialFile(sessionId, fileId), append);
    }

    /**
     * Fails early when a new/remaining incoming batch cannot fit with a reserve. Partials live in
     * app-private storage and are later copied into Downloads, so reserve enough headroom for the
     * copy/publish phase as well as Android's own storage needs.
     */
    public void ensureCapacity(long remainingBytes) throws IOException {
        if (remainingBytes < 0) throw new IOException("Invalid required storage size");
        long reserve = Math.max(MIN_FREE_RESERVE, Math.min(remainingBytes / 20, 512L * 1024 * 1024));
        long privateFree = availableBytes(context.getFilesDir());
        File external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        long publicFree = external == null ? privateFree : availableBytes(external);
        long required;
        if (Long.MAX_VALUE - remainingBytes < reserve) required = Long.MAX_VALUE;
        else required = remainingBytes + reserve;
        if (privateFree < required || publicFree < required) {
            throw new IOException("Not enough storage space. Need approximately "
                    + human(required) + " free before receiving this batch.");
        }
    }

    public Uri publishVerified(String sessionId, String fileId, String name, String mime,
                               TransferItem.Category category, String relativePath) throws IOException {
        File source = partialFile(sessionId, fileId);
        if (!source.exists()) throw new IOException("Partial file missing");
        long verifiedSize = source.length();
        String safeName = TransferItem.safeName(name);
        String folder = categoryFolder(category);
        if (mime != null && mime.toLowerCase(java.util.Locale.US).startsWith("text/")) folder = "Text";
        String safeRelative;
        try { safeRelative = TransferItem.safeRelativePath(relativePath); }
        catch (IllegalArgumentException invalid) { throw new IOException("Unsafe relative path", invalid); }
        if (safeRelative != null) {
            int slash = safeRelative.lastIndexOf('/');
            safeName = slash >= 0 ? safeRelative.substring(slash + 1) : safeRelative;
            String parent = slash >= 0 ? safeRelative.substring(0, slash) : "";
            folder = parent.isEmpty() ? "Folders" : "Folders/" + parent;
        }
        Uri published = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? publishMediaStore(source, safeName, mime, folder)
                : publishLegacy(source, safeName, folder);
        writeVerifiedMarker(sessionId, fileId, verifiedSize);
        if (!source.delete()) source.deleteOnExit();
        return published;
    }

    public Uri publishBrowserFile(File source, String name, String mime) throws IOException {
        if (source == null || !source.exists() || !source.isFile()) {
            throw new IOException("Browser upload temp file missing");
        }
        String safeName = TransferItem.safeName(name);
        String safeMime = mime == null || mime.trim().isEmpty()
                ? "application/octet-stream" : mime.trim();
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? publishMediaStore(source, safeName, safeMime, "Browser")
                : publishLegacy(source, safeName, "Browser");
    }

    private void writeVerifiedMarker(String sessionId, String fileId, long size)
            throws IOException {
        File marker = verifiedMarker(sessionId, fileId);
        File temp = new File(marker.getParentFile(), marker.getName() + ".tmp");
        try (FileWriter writer = new FileWriter(temp, false)) {
            writer.write(Long.toString(size));
            writer.flush();
        }
        if (marker.exists() && !marker.delete()) {
            throw new IOException("Could not replace verified marker");
        }
        if (!temp.renameTo(marker)) throw new IOException("Could not commit verified marker");
    }

    public void discard(String sessionId, String fileId) {
        try {
            File partial = partialFile(sessionId, fileId);
            if (partial.exists()) partial.delete();
            File marker = verifiedMarker(sessionId, fileId);
            if (marker.exists()) marker.delete();
        } catch (IOException ignored) { }
    }

    public void clearSession(String sessionId) {
        File dir = new File(context.getFilesDir(), "partial/" + sanitize(sessionId));
        deleteRecursive(dir);
    }

    /** Removes abandoned private partials only after a long grace period for offline resume. */
    public void pruneStalePartials() {
        File root = new File(context.getFilesDir(), "partial");
        File[] sessions = root.listFiles();
        if (sessions == null) return;
        long cutoff = System.currentTimeMillis() - STALE_PARTIAL_AGE_MS;
        for (File session : sessions) {
            if (session != null && session.isDirectory() && newestModified(session) < cutoff) {
                deleteRecursive(session);
            }
        }
    }

    private static long newestModified(File file) {
        long newest = Math.max(0L, file.lastModified());
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) newest = Math.max(newest, newestModified(child));
        }
        return newest;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private Uri publishMediaStore(File source, String name, String mime, String folder)
            throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/OptiShare/" + folder;
        String uniqueName = uniqueMediaStoreName(resolver, name, relativePath);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, uniqueName);
        values.put(MediaStore.Downloads.MIME_TYPE,
                mime == null ? "application/octet-stream" : mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Could not create download entry");
        boolean success = false;
        try (InputStream in = new FileInputStream(source);
             OutputStream out = resolver.openOutputStream(uri, "w")) {
            if (out == null) throw new IOException("Could not open destination");
            copy(in, out);
            success = true;
        } finally {
            if (!success) resolver.delete(uri, null, null);
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        int updated = resolver.update(uri, done, null, null);
        if (updated <= 0) {
            resolver.delete(uri, null, null);
            throw new IOException("Could not finalize download entry");
        }
        return uri;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private String uniqueMediaStoreName(ContentResolver resolver, String name, String relativePath) {
        if (!mediaStoreNameExists(resolver, name, relativePath)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (!mediaStoreNameExists(resolver, candidate, relativePath)) return candidate;
        }
        return System.currentTimeMillis() + "-" + name;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private boolean mediaStoreNameExists(ContentResolver resolver, String name, String relativePath) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Downloads._ID},
                    MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + "=?",
                    new String[]{name, relativePath}, null);
            return cursor != null && cursor.moveToFirst();
        } catch (RuntimeException ignored) {
            // If an OEM restricts the query, MediaStore still protects existing rows; publishing continues.
            return false;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    @SuppressWarnings("deprecation")
    private Uri publishLegacy(File source, String name, String folder) throws IOException {
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "OptiShare/" + folder);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create Download/OptiShare directory");
        }
        File target = uniqueFile(dir, name);
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        }
        return Uri.fromFile(target);
    }

    private static File uniqueFile(File dir, String name) {
        File first = new File(dir, name);
        if (!first.exists()) return first;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            File candidate = new File(dir, base + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, System.currentTimeMillis() + "-" + name);
    }

    private static String categoryFolder(TransferItem.Category category) {
        if (category == null) return "Other";
        switch (category) {
            case PHOTO: return "Photos";
            case VIDEO: return "Videos";
            case MUSIC: return "Music";
            case APP: return "Apps";
            case DOCUMENT: return "Documents";
            case ARCHIVE: return "Archives";
            default: return "Other";
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        out.flush();
    }

    private static long availableBytes(File path) throws IOException {
        try {
            StatFs stats = new StatFs(path.getAbsolutePath());
            return stats.getAvailableBytes();
        } catch (RuntimeException error) {
            throw new IOException("Could not determine available storage", error);
        }
    }

    private static String human(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.2f GB",
                    bytes / (1024.0 * 1024 * 1024));
        }
        return String.format(java.util.Locale.US, "%.1f MB",
                bytes / (1024.0 * 1024));
    }

    private static String sanitize(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }
}
