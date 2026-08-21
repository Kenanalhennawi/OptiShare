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

/** Foreground service for encrypted, resumable, multi-file transfers. */
public final class TransferService extends Service {
    public static final String ACTION_START_RECEIVER = "com.kenan.optishare.action.START_RECEIVER";
    public static final String ACTION_SEND = "com.kenan.optishare.action.SEND";
    public static final String ACTION_ACCEPT = "com.kenan.optishare.action.ACCEPT";
    public static final String ACTION_DECLINE = "com.kenan.optishare.action.DECLINE";
    public static final String ACTION_RESUME_PENDING = "com.kenan.optishare.action.RESUME_PENDING";
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
    private static final long APPROVAL_TIMEOUT_MS = 60_000L;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket serverSocket;
    private volatile Socket activeSocket;
    private volatile BatchManifest activeManifest;
    private volatile List<TransferItem> activeItems;
    private SenderSessionStore senderStore;

    @Override public void onCreate() {
        super.onCreate();
        senderStore = new SenderSessionStore(this);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            SenderSessionStore.Pending pending = senderStore.load();
            if (pending != null) {
                startForeground(NOTIFICATION_ID, notification("OptiShare", "Restoring interrupted transfer…", 0, false));
                startPendingSender(pending);
            }
            return START_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_ACCEPT.equals(action)) { IncomingApproval.decide(true); return START_STICKY; }
        if (ACTION_DECLINE.equals(action)) { IncomingApproval.decide(false); return START_STICKY; }
        if (ACTION_STOP.equals(action)) { stopTransfer(true); return START_NOT_STICKY; }
        startForeground(NOTIFICATION_ID, notification("OptiShare", "Preparing secure transfer…", 0, false));
        if (ACTION_START_RECEIVER.equals(action)) startReceiver();
        else if (ACTION_SEND.equals(action)) startSender(intent);
        else if (ACTION_RESUME_PENDING.equals(action)) {
            SenderSessionStore.Pending pending = senderStore.load();
            if (pending != null) startPendingSender(pending);
            else broadcast("error", "No resumable outgoing session was found", 0, 0, null);
        }
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
                    try {
                        broadcast("connected", "Sender connected securely", 0, 0, null);
                        receiveOne(socket);
                    } catch (Exception transferError) {
                        if (running.get() && !isDecline(transferError)) {
                            String session = activeManifest == null ? null : activeManifest.getSessionId();
                            broadcast("reconnecting", "Connection interrupted — waiting for sender to reconnect", 0, 0, session);
                            updateNotification("Waiting to resume", "Confirmed progress is preserved", 0, false);
                        }
                    } finally {
                        closeSocket();
                    }
                }
            } catch (Exception serverError) {
                if (running.get()) broadcast("error", safe(serverError), 0, 0, null);
            } finally {
                running.set(false);
                serverSocket = null;
                stopForeground(true);
                stopSelf();
            }
        });
    }

    private void receiveOne(Socket socket) throws Exception {
        new TransferEngine(this).receive(socket, new TransferEngine.Listener() {
            @Override public void onSecurityCode(String code) {
                broadcast("security", "Security code: " + formatCode(code), 0, 0, null);
            }
            @Override public void onIncomingBatch(BatchManifest manifest) {
                activeManifest = manifest;
                IncomingApproval.begin(manifest.getSessionId());
                String detail = manifest.getEntries().size() + " files • " + formatBytes(manifest.totalBytes());
                broadcast("incoming", detail, 0, 0, manifest.getSessionId());
                updateNotification("Incoming files", detail + " — accept or decline", 0, false);
            }
            @Override public boolean acceptIncomingBatch(BatchManifest manifest) {
                if (!running.get()) return false;
                try {
                    boolean accepted = IncomingApproval.await(manifest.getSessionId(), APPROVAL_TIMEOUT_MS);
                    if (!accepted) broadcast("declined", "Transfer declined or approval timed out", 0, 0, manifest.getSessionId());
                    return accepted;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            @Override public void onProgress(String sessionId, String fileId, String fileName, long done, long total, long batchDone, long batchTotal, double bytesPerSecond) {
                int p = percent(batchDone, batchTotal);
                String message = "Receiving " + fileName + " • " + p + "% • " + formatSpeed(bytesPerSecond);
                updateNotification("Receiving files", message, p, true);
                broadcast("progress", message, p, bytesPerSecond, sessionId);
            }
            @Override public void onFileCompleted(String sessionId, String fileId, Uri publishedUri) {
                broadcast("file_done", "Verified and saved to Download/OptiShare", 0, 0, sessionId);
            }
            @Override public void onCompleted(String sessionId) {
                updateNotification("Transfer complete", "Files saved to Download/OptiShare", 100, false);
                broadcast("completed", "Transfer complete ✓", 100, 0, sessionId);
            }
            @Override public void onError(String sessionId, Throwable error, boolean resumable) {
                if (!isDecline(error)) broadcast(resumable ? "reconnecting" : "error", resumable ? "Connection interrupted — ready to resume" : safe(error), 0, 0, sessionId);
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
                senderStore.save(host, activeItems, activeManifest);
                runSenderLoop(host, engine);
            } catch (Exception error) {
                failSender(error);
            }
        });
    }

    private void startPendingSender(SenderSessionStore.Pending pending) {
        if (!running.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                activeItems = pending.items;
                activeManifest = pending.manifest;
                broadcast("reconnecting", "Restoring interrupted session…", 0, 0, activeManifest.getSessionId());
                runSenderLoop(pending.host, new TransferEngine(this));
            } catch (Exception error) {
                failSender(error);
            }
        });
    }

    private void runSenderLoop(String host, TransferEngine engine) throws Exception {
        int attempt = 0;
        try {
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
                    senderStore.clear();
                    running.set(false);
                    stopForeground(false);
                    stopSelf();
                    return;
                } catch (Exception transferError) {
                    closeSocket();
                    if (isDecline(transferError)) {
                        senderStore.clear();
                        broadcast("declined", "Receiver declined the transfer", 0, 0, activeManifest.getSessionId());
                        return;
                    }
                    if (attempt >= MAX_SOCKET_RETRIES) throw transferError;
                }
            }
        } finally {
            running.set(false);
            closeSocket();
            stopSelf();
        }
    }

    private void failSender(Exception error) {
        String session = activeManifest == null ? null : activeManifest.getSessionId();
        broadcast("error", safe(error) + " — session kept for resume", 0, 0, session);
        updateNotification("Transfer paused", "Open OptiShare to resume the pending session", 0, false);
        running.set(false);
        closeSocket();
        stopSelf();
    }

    private void sendOne(TransferEngine engine, Socket socket) throws Exception {
        engine.send(socket, activeManifest, activeItems, new TransferEngine.Listener() {
            @Override public void onSecurityCode(String code) {
                broadcast("security", "Security code: " + formatCode(code), 0, 0, activeManifest.getSessionId());
            }
            @Override public void onIncomingBatch(BatchManifest manifest) { }
            @Override public boolean acceptIncomingBatch(BatchManifest manifest) { return true; }
            @Override public void onProgress(String sessionId, String fileId, String fileName, long done, long total, long batchDone, long batchTotal, double bytesPerSecond) {
                int p = percent(batchDone, batchTotal);
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
                if (!isDecline(error)) broadcast("reconnecting", "Connection interrupted — resuming automatically", 0, 0, sessionId);
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
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        Intent stop = new Intent(this, TransferService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | immutable);
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

    private void stopTransfer(boolean userCancelled) {
        running.set(false);
        IncomingApproval.cancel();
        if (userCancelled && senderStore != null) senderStore.clear();
        closeSocket();
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) { }
        serverSocket = null;
        stopForeground(true);
        stopSelf();
    }

    private void closeSocket() {
        try { if (activeSocket != null) activeSocket.close(); } catch (Exception ignored) { }
        activeSocket = null;
    }

    @Override public void onDestroy() {
        running.set(false);
        IncomingApproval.cancel();
        closeSocket();
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) { }
        executor.shutdownNow();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private static int percent(long done, long total) {
        return total <= 0 ? 0 : (int) Math.max(0, Math.min(100, done * 100L / total));
    }

    private static boolean isDecline(Throwable t) {
        String message = safe(t).toLowerCase(Locale.US);
        return message.contains("declined") || message.contains("approval timed out");
    }

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
