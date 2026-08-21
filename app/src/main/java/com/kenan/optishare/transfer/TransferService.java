package com.kenan.optishare.transfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.OpenableColumns;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.kenan.optishare.R;
import com.kenan.optishare.V2Activity;
import com.kenan.optishare.model.TransferItem;
import com.kenan.optishare.protocol.BatchManifest;
import com.kenan.optishare.storage.FileClassifier;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground service so large transfers survive screen-off and Activity recreation. */
public final class TransferService extends Service {
    public static final String ACTION_START_RECEIVER = "com.kenan.optishare.action.START_RECEIVER";
    public static final String ACTION_SEND = "com.kenan.optishare.action.SEND";
    public static final String ACTION_STOP = "com.kenan.optishare.action.STOP";
    public static final String ACTION_EVENT = "com.kenan.optishare.TRANSFER_EVENT";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_URIS = "uris";
    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_SPEED = "speed";
    public static final String EXTRA_SESSION = "session";
    public static final int PORT = 49888;

    private static final String CHANNEL = "optishare_transfers";
    private static final int NOTIFICATION_ID = 2200;
    private static final int MAX_SOCKET_RETRIES = 8;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket serverSocket;
    private volatile Socket activeSocket;
    private volatile BatchManifest activeManifest;
    private volatile List<TransferItem> activeItems;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopTransfer();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification("OptiShare", "Preparing secure transfer…", 0, false));
        if (ACTION_START_RECEIVER.equals(action)) startReceiver();
        else if (ACTION_SEND.equals(action)) startSender(intent);
        return START_STICKY;
    }

    private void startReceiver() {
        if (!running.compareAndSet(false, true)) return;
        updateNotification("Ready to receive", "Waiting for a nearby OptiShare sender", 0, false);
        broadcast("receiver_ready", "Waiting for sender…", 0, 0, null);
        executor.execute(() -> {
            try (ServerSocket server = new ServerSocket()) {
                serverSocket = server;
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(PORT));
                while (running.get()) {
                    Socket socket = server.accept();
                    activeSocket = socket;
                    broadcast("connected", "Sender connected securely", 0, 0, null);
                    receiveOne(socket);
                    activeSocket = null;
                }
            } catch (Throwable t) {
                if (running.get()) broadcast("error", safe(t), 0, 0, null);
            } finally {
                running.set(false);
                stopSelf();
            }
        });
    }

    private void receiveOne(Socket socket) throws Exception {
        TransferEngine engine = new TransferEngine(this);
        engine.receive(socket, new TransferEngine.Listener() {
            @Override public void onSecurityCode(String code) {
                broadcast("security", "Security code: " + formatCode(code), 0, 0, null);
            }
            @Override public void onIncomingBatch(BatchManifest manifest) {
                activeManifest = manifest;
                broadcast("incoming", manifest.getEntries().size() + " files • " + formatBytes(manifest.totalBytes()), 0, 0, manifest.getSessionId());
            }
            @Override public boolean acceptIncomingBatch(BatchManifest manifest) {
                // Receiver mode is explicitly user-initiated; accepting the next secured batch is the expected action.
                return running.get();
            }
            @Override public void onProgress(String sessionId, String fileId, String fileName, long done, long total, long batchDone, long batchTotal, double bytesPerSecond) {
                int p = batchTotal <= 0 ? 0 : (int) Math.min(100, batchDone * 100L / batchTotal);
                String message = "Receiving " + fileName + " • " + p + "% • " + formatSpeed(bytesPerSecond);
                updateNotification("Receiving files", message, p, true);
                broadcast("progress", message, p, bytesPerSecond, sessionId);
            }
            @Override public void onFileCompleted(String sessionId, String fileId, Uri publishedUri) {
                broadcast("file_done", "Saved to Download/OptiShare", 0, 0, sessionId);
            }
            @Override public void onCompleted(String sessionId) {
                updateNotification("Transfer complete", "Files saved to Download/OptiShare", 100, false);
                broadcast("completed", "Transfer complete ✓", 100, 0, sessionId);
            }
            @Override public void onError(String sessionId, Throwable error, boolean resumable) {
                broadcast(resumable ? "reconnecting" : "error", resumable ? "Connection interrupted — ready to resume" : safe(error), 0, 0, sessionId);
            }
        });
    }

    private void startSender(Intent intent) {
        String host = intent.getStringExtra(EXTRA_HOST);
        ArrayList<String> rawUris = intent.getStringArrayListExtra(EXTRA_URIS);
        if (host == null || rawUris == null || rawUris.isEmpty()) {
            broadcast("error", "Missing receiver or files", 0, 0, null);
            stopSelf();
            return;
        }
        if (!running.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                activeItems = resolveItems(rawUris);
                TransferEngine engine = new TransferEngine(this);
                activeManifest = engine.buildManifest(activeItems);
                int attempt = 0;
                while (running.get() && attempt < MAX_SOCKET_RETRIES) {
                    attempt++;
                    try {
                        if (attempt > 1) {
                            broadcast("reconnecting", "Reconnecting… attempt " + attempt, 0, 0, activeManifest.getSessionId());
                            Thread.sleep(Math.min(5000, 700L * attempt));
                        }
                        Socket socket = new Socket();
                        activeSocket = socket;
                        socket.connect(new InetSocketAddress(host, PORT), 8000);
                        sendOne(engine, socket);
                        running.set(false);
                        stopSelf();
                        return;
                    } catch (Throwable transferError) {
                        closeSocket();
                        if (attempt >= MAX_SOCKET_RETRIES) throw transferError;
                    }
                }
            } catch (Throwable t) {
                broadcast("error", safe(t), 0, 0, activeManifest == null ? null : activeManifest.getSessionId());
                updateNotification("Transfer failed", safe(t), 0, false);
            } finally {
                running.set(false);
                closeSocket();
                stopSelf();
            }
        });
    }

    private void sendOne(TransferEngine engine, Socket socket) throws Exception {
        engine.send(socket, activeManifest, activeItems, new TransferEngine.Listener() {
            @Override public void onSecurityCode(String code) {
                broadcast("security", "Security code: " + formatCode(code), 0, 0, activeManifest.getSessionId());
            }
            @Override public void onIncomingBatch(BatchManifest manifest) { }
            @Override public boolean acceptIncomingBatch(BatchManifest manifest) { return true; }
            @Override public void onProgress(String sessionId, String fileId, String fileName, long done, long total, long batchDone, long batchTotal, double bytesPerSecond) {
                int p = batchTotal <= 0 ? 0 : (int) Math.min(100, batchDone * 100L / batchTotal);
                String message = "Sending " + fileName + " • " + p + "% • " + formatSpeed(bytesPerSecond);
                updateNotification("Sending files", message, p, true);
                broadcast("progress", message, p, bytesPerSecond, sessionId);
            }
            @Override public void onFileCompleted(String sessionId, String fileId, Uri publishedUri) { }
            @Override public void onCompleted(String sessionId) {
                updateNotification("Transfer complete", "All files sent successfully", 100, false);
                broadcast("completed", "All files sent ✓", 100, 0, sessionId);
            }
            @Override public void onError(String sessionId, Throwable error, boolean resumable) {
                broadcast("reconnecting", "Connection interrupted — resuming automatically", 0, 0, sessionId);
            }
        });
    }

    private List<TransferItem> resolveItems(List<String> rawUris) throws Exception {
        List<TransferItem> result = new ArrayList<>();
        ContentResolver resolver = getContentResolver();
        for (String raw : rawUris) {
            Uri uri = Uri.parse(raw);
            String name = "file.bin";
            long size = -1;
            Cursor cursor = null;
            try {
                cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = TransferItem.safeName(cursor.getString(nameIndex));
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
                }
            } finally {
                if (cursor != null) cursor.close();
            }
            String mime = resolver.getType(uri);
            if (size < 0) size = measure(uri);
            result.add(new TransferItem(uri, name, mime, size, FileClassifier.classify(mime, name)));
        }
        return result;
    }

    private long measure(Uri uri) throws Exception {
        long total = 0;
        byte[] buffer = new byte[256 * 1024];
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("Cannot open selected file");
            int n;
            while ((n = in.read(buffer)) != -1) total += n;
        }
        return total;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "File transfers", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Active OptiShare local file transfers");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private Notification notification(String title, String text, int progress, boolean ongoingProgress) {
        Intent open = new Intent(this, V2Activity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Intent stop = new Intent(this, TransferService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_optishare)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pending)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoingProgress)
                .addAction(0, "Cancel", stopPending);
        if (ongoingProgress) builder.setProgress(100, progress, false);
        return builder.build();
    }

    private void updateNotification(String title, String text, int progress, boolean ongoing) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification(title, text, progress, ongoing));
    }

    private void broadcast(String event, String message, int progress, double speed, String session) {
        Intent intent = new Intent(ACTION_EVENT);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_EVENT, event);
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_PROGRESS, progress);
        intent.putExtra(EXTRA_SPEED, speed);
        intent.putExtra(EXTRA_SESSION, session);
        sendBroadcast(intent);
    }

    private void stopTransfer() {
        running.set(false);
        closeSocket();
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) { }
        stopForeground(true);
        stopSelf();
    }

    private void closeSocket() {
        try { if (activeSocket != null) activeSocket.close(); } catch (Exception ignored) { }
        activeSocket = null;
    }

    @Override public void onDestroy() {
        stopTransfer();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private static String safe(Throwable t) {
        String message = t == null ? null : t.getMessage();
        return message == null || message.trim().isEmpty() ? (t == null ? "Unknown error" : t.getClass().getSimpleName()) : message;
    }

    private static String formatCode(String code) {
        if (code == null || code.length() != 6) return code;
        return code.substring(0, 3) + " " + code.substring(3);
    }

    private static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond >= 1024 * 1024) return String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024 * 1024));
        return String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024);
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024) return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024));
        if (bytes >= 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }
}
