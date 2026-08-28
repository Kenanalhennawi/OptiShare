package com.kenan.optishare.transfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.OpenableColumns;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.kenan.optishare.R;
import com.kenan.optishare.V2Activity;
import com.kenan.optishare.model.TransferItem;
import com.kenan.optishare.storage.FolderTransferQueue;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground sender for the local Windows Companion route. This route is token-authenticated local TCP, not app-to-app E2E. */
public final class PcTransferService extends Service {
    public static final String ACTION_SEND_PC = "com.kenan.optishare.action.SEND_PC";
    public static final String ACTION_STOP_PC = "com.kenan.optishare.action.STOP_PC";
    public static final String EXTRA_HOST = "pc_host";
    public static final String EXTRA_PORT = "pc_port";
    public static final String EXTRA_TOKEN = "pc_token";
    public static final String EXTRA_URIS = "pc_uris";

    private static final String CHANNEL = "optishare_pc_transfer";
    private static final int NOTIFICATION_ID = 2203;
    private static final byte[] MAGIC = "OPTISHARE-PC-1\n".getBytes(StandardCharsets.US_ASCII);
    private static final int BUFFER = 1024 * 1024;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Socket activeSocket;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP_PC.equals(action)) {
            stopTransfer();
            return START_NOT_STICKY;
        }
        if (!ACTION_SEND_PC.equals(action) || running.getAndSet(true)) return START_NOT_STICKY;

        String host = intent.getStringExtra(EXTRA_HOST);
        int port = intent.getIntExtra(EXTRA_PORT, 49890);
        String token = intent.getStringExtra(EXTRA_TOKEN);
        ArrayList<String> rawUris = intent.getStringArrayListExtra(EXTRA_URIS);
        startForeground(NOTIFICATION_ID, notification("Connecting to Windows PC", 0, false));
        executor.execute(() -> send(host, port, token, rawUris));
        return START_NOT_STICKY;
    }

    private void send(String host, int port, String token, List<String> rawUris) {
        long started = System.nanoTime();
        try {
            if (host == null || host.trim().isEmpty()) throw new IllegalArgumentException("Missing PC host");
            if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid PC port");
            if (token == null || token.length() < 16) throw new IllegalArgumentException("Invalid PC session token");
            List<Uri> uris = new ArrayList<>();
            if (rawUris != null) for (String value : rawUris) if (value != null) uris.add(Uri.parse(value));
            if (uris.isEmpty()) throw new IllegalArgumentException("No files selected");

            Map<String, TransferItem> rich = richItems();
            List<Item> items = new ArrayList<>();
            long total = 0L;
            for (Uri uri : uris) {
                Item item = item(uri, rich.get(uri.toString()));
                items.add(item);
                if (item.size > 0 && Long.MAX_VALUE - total > item.size) total += item.size;
            }

            Socket socket = new Socket();
            activeSocket = socket;
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(4 * 1024 * 1024);
            socket.connect(new InetSocketAddress(host, port), 10_000);

            try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BUFFER));
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BUFFER))) {
                out.write(MAGIC);
                writeString(out, token, 512);
                out.writeInt(items.size());
                out.flush();

                int welcome = in.readUnsignedByte();
                if (welcome != 1) throw new IllegalStateException("Windows receiver declined the session");

                long batchDone = 0L;
                byte[] buffer = new byte[BUFFER];
                for (Item item : items) {
                    if (!running.get()) throw new InterruptedException("Transfer cancelled");
                    writeString(out, item.name, 4096);
                    writeString(out, item.relativePath == null ? "" : item.relativePath, 8192);
                    writeString(out, item.mime, 1024);
                    out.writeLong(item.size);
                    out.flush();

                    int accepted = in.readUnsignedByte();
                    if (accepted != 1) throw new IllegalStateException("Windows declined " + item.name);

                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    long fileDone = 0L;
                    long fileStarted = System.nanoTime();
                    try (InputStream source = new BufferedInputStream(getContentResolver().openInputStream(item.uri), BUFFER)) {
                        if (source == null) throw new IllegalStateException("Cannot open " + item.name);
                        while (fileDone < item.size) {
                            if (!running.get()) throw new InterruptedException("Transfer cancelled");
                            int want = (int) Math.min(buffer.length, item.size - fileDone);
                            int n = source.read(buffer, 0, want);
                            if (n < 0) break;
                            if (n == 0) continue;
                            out.write(buffer, 0, n);
                            digest.update(buffer, 0, n);
                            fileDone += n;
                            long allDone = batchDone + fileDone;
                            double seconds = Math.max(0.001, (System.nanoTime() - fileStarted) / 1_000_000_000.0);
                            double speed = fileDone / seconds;
                            int progress = total <= 0 ? 0 : (int) Math.min(100L, allDone * 100L / total);
                            long eta = speed <= 0 ? 0 : Math.max(0L, Math.round((total - allDone) / speed));
                            broadcast("progress", "Sending to Windows • " + item.name,
                                    progress, speed, allDone, total, eta, 0d, 0L, 0, 0, 0L);
                            updateNotification("Sending " + item.name, progress);
                        }
                    }
                    if (fileDone != item.size) throw new IllegalStateException("Source ended early: " + item.name);
                    out.write(digest.digest());
                    out.flush();
                    int verified = in.readUnsignedByte();
                    if (verified != 1) throw new IllegalStateException("Windows verification failed: " + item.name);
                    batchDone += item.size;
                    broadcast("file_done", item.name + " verified on Windows", total <= 0 ? 0 : (int) Math.min(100L, batchDone * 100L / total),
                            0d, batchDone, total, 0L, 0d, 0L, 0, 0, 0L);
                }
                out.writeInt(0x0F7152E2);
                out.flush();
                if (in.readUnsignedByte() != 1) throw new IllegalStateException("Windows did not confirm completion");

                long durationMs = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
                double average = total <= 0 ? 0d : total / Math.max(0.001, durationMs / 1000d);
                broadcast("completed", "Saved on Windows • SHA-256 verified", 100, 0d,
                        total, total, 0L, average, durationMs, 0, items.size(), total);
                updateNotification("Transfer complete", 100);
            }
        } catch (Exception error) {
            broadcast("error", safe(error), 0, 0d, 0L, 0L, 0L, 0d, 0L, 0, 0, 0L);
        } finally {
            running.set(false);
            Socket socket = activeSocket;
            activeSocket = null;
            if (socket != null) try { socket.close(); } catch (Exception ignored) { }
            stopForeground(false);
            stopSelf();
        }
    }

    private Map<String, TransferItem> richItems() {
        Map<String, TransferItem> result = new HashMap<>();
        for (TransferItem item : FolderTransferQueue.snapshot()) {
            if (item != null && item.getUri() != null) result.put(item.getUri().toString(), item);
        }
        return result;
    }

    private Item item(Uri uri, TransferItem rich) {
        if (rich != null) {
            return new Item(uri, TransferItem.safeName(rich.getName()), rich.getMimeType(),
                    rich.getSize(), rich.getRelativePath());
        }
        String name = "file.bin";
        long size = 0L;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = TransferItem.safeName(cursor.getString(nameIndex));
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = Math.max(0L, cursor.getLong(sizeIndex));
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        String mime = getContentResolver().getType(uri);
        if (mime == null || mime.trim().isEmpty()) mime = "application/octet-stream";
        return new Item(uri, name, mime, size, null);
    }

    private static void writeString(DataOutputStream out, String value, int maxBytes) throws Exception {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw new IllegalArgumentException("Metadata too long");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private void broadcast(String event, String message, int progress, double speed,
                           long done, long total, long etaSeconds, double averageSpeed,
                           long durationMs, int reconnects, int fileCount, long totalBytes) {
        Intent intent = new Intent(TransferService.ACTION_EVENT).setPackage(getPackageName());
        intent.putExtra(TransferService.EXTRA_EVENT, event);
        intent.putExtra(TransferService.EXTRA_MESSAGE, message);
        intent.putExtra(TransferService.EXTRA_PROGRESS, progress);
        intent.putExtra(TransferService.EXTRA_SPEED, speed);
        intent.putExtra(TransferService.EXTRA_DONE, done);
        intent.putExtra(TransferService.EXTRA_TOTAL, total);
        intent.putExtra(TransferService.EXTRA_ETA_SECONDS, etaSeconds);
        intent.putExtra(TransferService.EXTRA_AVG_SPEED, averageSpeed);
        intent.putExtra(TransferService.EXTRA_DURATION_MS, durationMs);
        intent.putExtra(TransferService.EXTRA_RECONNECTS, reconnects);
        intent.putExtra(TransferService.EXTRA_FILE_COUNT, fileCount);
        intent.putExtra(TransferService.EXTRA_TOTAL_BYTES, totalBytes);
        intent.putExtra(TransferService.EXTRA_ROUTE, "pc-local");
        sendBroadcast(intent);
    }

    private void stopTransfer() {
        running.set(false);
        Socket socket = activeSocket;
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
        stopForeground(true);
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "OptiShare PC transfers", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private Notification notification(String text, int progress, boolean ongoing) {
        Intent open = new Intent(this, V2Activity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 2203, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_optishare)
                .setContentTitle("OptiShare → Windows")
                .setContentText(text)
                .setContentIntent(pending)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing);
        if (progress > 0 && progress < 100) builder.setProgress(100, progress, false);
        return builder.build();
    }

    private void updateNotification(String text, int progress) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(text, progress, progress < 100));
    }

    private static String safe(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? (error == null ? "Unknown PC transfer error" : error.getClass().getSimpleName())
                : message;
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private static final class Item {
        final Uri uri;
        final String name;
        final String mime;
        final long size;
        final String relativePath;
        Item(Uri uri, String name, String mime, long size, String relativePath) {
            this.uri = uri;
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.relativePath = relativePath;
        }
    }
}
