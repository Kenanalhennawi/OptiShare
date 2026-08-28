package com.kenan.optishare.transfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.database.Cursor;
import android.content.res.AssetFileDescriptor;
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
import java.security.MessageDigest;
import java.security.KeyPair;
import java.security.SecureRandom;
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
    public static final String EXTRA_PROTOCOL = "pc_protocol";
    public static final String EXTRA_URIS = "pc_uris";

    private static final String CHANNEL = "optishare_pc_transfer";
    private static final int NOTIFICATION_ID = 2203;
    private static final int BUFFER = 1024 * 1024;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Socket activeSocket;
    private RoutePerformanceStore routeStore;

    @Override public void onCreate() {
        super.onCreate();
        routeStore = new RoutePerformanceStore(this);
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
        int protocol = intent.getIntExtra(EXTRA_PROTOCOL, 1);
        ArrayList<String> rawUris = intent.getStringArrayListExtra(EXTRA_URIS);
        startForeground(NOTIFICATION_ID, notification("Connecting to Windows PC", 0, false));
        executor.execute(() -> send(host, port, token, protocol, rawUris));
        return START_NOT_STICKY;
    }

    private void send(String host, int port, String token, int protocol, List<String> rawUris) {
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
                if (protocol >= 2) {
                    sendSecure(in, out, token, items, total, started);
                    return;
                }
                out.write(PcWire.MAGIC);
                PcWire.writeString(out, token, 512);
                out.writeInt(items.size());
                out.flush();

                int welcome = in.readUnsignedByte();
                if (welcome != 1) throw new IllegalStateException("Windows receiver declined the session");

                long batchDone = 0L;
                byte[] buffer = new byte[BUFFER];
                for (Item item : items) {
                    if (!running.get()) throw new InterruptedException("Transfer cancelled");
                    PcWire.writeString(out, item.name, 4096);
                    PcWire.writeString(out, item.relativePath == null ? "" : item.relativePath, 8192);
                    PcWire.writeString(out, item.mime, 1024);
                    out.writeLong(item.size);
                    out.flush();

                    int accepted = in.readUnsignedByte();
                    if (accepted != 1) throw new IllegalStateException("Windows declined " + item.name);

                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    long fileDone = 0L;
                    long fileStarted = System.nanoTime();
                    InputStream raw = getContentResolver().openInputStream(item.uri);
                    if (raw == null) throw new IllegalStateException("Cannot open " + item.name);
                    try (InputStream source = new BufferedInputStream(raw, BUFFER)) {
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
                out.writeInt(PcWire.COMPLETION_MARKER);
                out.flush();
                if (in.readUnsignedByte() != 1) throw new IllegalStateException("Windows did not confirm completion");

                long durationMs = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
                double average = total <= 0 ? 0d : total / Math.max(0.001, durationMs / 1000d);
                routeStore.recordSuccess(RoutePerformanceStore.ROUTE_PC, average);
                broadcast("completed", "Saved on Windows • SHA-256 verified", 100, 0d,
                        total, total, 0L, average, durationMs, 0, items.size(), total);
                updateNotification("Transfer complete", 100);
            }
        } catch (Exception error) {
            routeStore.recordFailure(RoutePerformanceStore.ROUTE_PC);
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

    private void sendSecure(DataInputStream in, DataOutputStream out, String token,
                            List<Item> items, long total, long started) throws Exception {
        out.write(PcSecureWire.MAGIC);
        PcWire.writeString(out, token, 512);
        KeyPair keyPair = PcSecureWire.createEphemeralKeyPair();
        byte[] clientPublic = keyPair.getPublic().getEncoded();
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        out.writeInt(clientPublic.length);
        out.write(clientPublic);
        out.write(salt);
        out.flush();

        int serverKeyLength = in.readInt();
        if (serverKeyLength < 64 || serverKeyLength > 512) throw new java.security.GeneralSecurityException("Invalid Windows secure key");
        byte[] serverPublic = new byte[serverKeyLength];
        in.readFully(serverPublic);
        byte[] shared = PcSecureWire.sharedSecret(keyPair, serverPublic);
        byte[] key = PcSecureWire.deriveKey(shared, salt);
        java.util.Arrays.fill(shared, (byte) 0);
        String code = PcSecureWire.securityCode(clientPublic, serverPublic, salt);
        String approvalKey = "pc-v2:" + code + ":" + System.nanoTime();
        IncomingApproval.begin(approvalKey, "Windows security code: " + code,
                "Confirm that Windows shows exactly " + code + ".\nDecline if any digit differs.");
        boolean accepted = IncomingApproval.await(approvalKey, 120_000L);

        SecureIo secure = new SecureIo(in, out, key);
        secure.write(accepted ? "ACCEPT".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
                : "DECLINE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        if (!accepted) throw new java.security.GeneralSecurityException("Windows security code was declined on Android");
        if (!"ACCEPT".equals(new String(secure.read(), java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new java.security.GeneralSecurityException("Secure session declined on Windows");
        }
        secure.write(java.nio.ByteBuffer.allocate(4).putInt(items.size()).array());

        long batchDone = 0L;
        byte[] buffer = new byte[BUFFER];
        for (Item item : items) {
            if (!running.get()) throw new InterruptedException("Transfer cancelled");
            secure.write(PcWire.metadataVector(item.name, item.relativePath == null ? "" : item.relativePath, item.mime, item.size));
            if (!isOk(secure.read())) throw new IllegalStateException("Windows declined " + item.name);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long fileDone = 0L;
            long fileStarted = System.nanoTime();
            InputStream raw = getContentResolver().openInputStream(item.uri);
            if (raw == null) throw new IllegalStateException("Cannot open " + item.name);
            try (InputStream source = new BufferedInputStream(raw, BUFFER)) {
                while (fileDone < item.size) {
                    if (!running.get()) throw new InterruptedException("Transfer cancelled");
                    int want = (int) Math.min(buffer.length, item.size - fileDone);
                    int n = source.read(buffer, 0, want);
                    if (n < 0) break;
                    if (n == 0) continue;
                    secure.write(java.util.Arrays.copyOf(buffer, n));
                    digest.update(buffer, 0, n);
                    fileDone += n;
                    long allDone = batchDone + fileDone;
                    double seconds = Math.max(0.001, (System.nanoTime() - fileStarted) / 1_000_000_000.0);
                    double speed = fileDone / seconds;
                    int progress = total <= 0 ? 0 : (int) Math.min(100L, allDone * 100L / total);
                    long eta = speed <= 0 ? 0 : Math.max(0L, Math.round((total - allDone) / speed));
                    broadcast("progress", "Encrypted to Windows • " + item.name, progress, speed,
                            allDone, total, eta, 0d, 0L, 0, 0, 0L);
                    updateNotification("Encrypted • " + item.name, progress);
                }
            }
            if (fileDone != item.size) throw new IllegalStateException("Source ended early: " + item.name);
            secure.write(digest.digest());
            if (!isOk(secure.read())) throw new IllegalStateException("Windows verification failed: " + item.name);
            batchDone += item.size;
        }
        secure.write(java.nio.ByteBuffer.allocate(4).putInt(PcWire.COMPLETION_MARKER).array());
        if (!isOk(secure.read())) throw new IllegalStateException("Windows did not confirm secure completion");
        java.util.Arrays.fill(key, (byte) 0);
        long durationMs = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
        double average = total <= 0 ? 0d : total / Math.max(0.001, durationMs / 1000d);
        routeStore.recordSuccess(RoutePerformanceStore.ROUTE_PC, average);
        broadcast("completed", "Encrypted transfer saved on Windows • SHA-256 verified", 100, 0d,
                total, total, 0L, average, durationMs, 0, items.size(), total);
        updateNotification("Encrypted transfer complete", 100);
    }

    private static boolean isOk(byte[] value) { return value.length == 1 && value[0] == 1; }

    private static final class SecureIo {
        private final DataInputStream in;
        private final DataOutputStream out;
        private final byte[] key;
        private long sent;
        private long received;
        SecureIo(DataInputStream in, DataOutputStream out, byte[] key) { this.in = in; this.out = out; this.key = key; }
        void write(byte[] plaintext) throws Exception {
            byte[] record = PcSecureWire.encrypt(key, sent++, true, plaintext);
            out.writeInt(record.length);
            out.write(record);
            out.flush();
        }
        byte[] read() throws Exception {
            int length = in.readInt();
            if (length < 30 || length > PcSecureWire.MAX_RECORD_BYTES) throw new java.security.GeneralSecurityException("Invalid Windows secure record length");
            byte[] record = new byte[length];
            in.readFully(record);
            return PcSecureWire.decrypt(key, received++, false, record);
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
        long size = -1L;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = TransferItem.safeName(cursor.getString(nameIndex));
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        if (size < 0L) {
            AssetFileDescriptor descriptor = null;
            try {
                descriptor = getContentResolver().openAssetFileDescriptor(uri, "r");
                if (descriptor != null && descriptor.getLength() >= 0L) size = descriptor.getLength();
            } catch (Exception ignored) {
            } finally {
                if (descriptor != null) try { descriptor.close(); } catch (Exception ignored) { }
            }
        }
        if (size < 0L) {
            throw new IllegalArgumentException("Could not determine file size for Windows transfer: " + name);
        }
        String mime = getContentResolver().getType(uri);
        if (mime == null || mime.trim().isEmpty()) mime = "application/octet-stream";
        return new Item(uri, name, mime, size, null);
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
        intent.putExtra(TransferService.EXTRA_ROUTE, RoutePerformanceStore.ROUTE_PC);
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
