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
import com.kenan.optishare.device.DeviceIdentity;
import com.kenan.optishare.device.DeviceIdentityKey;
import com.kenan.optishare.device.TrustedDeviceStore;
import com.kenan.optishare.model.TransferItem;
import com.kenan.optishare.protocol.BatchManifest;
import com.kenan.optishare.storage.FileClassifier;
import com.kenan.optishare.storage.FolderTransferQueue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground service for encrypted, resumable, multi-file transfers. */
public final class TransferService extends Service {
    public static final String ACTION_START_RECEIVER = "com.kenan.optishare.action.START_RECEIVER";
    public static final String ACTION_SEND = "com.kenan.optishare.action.SEND";
    public static final String ACTION_BENCHMARK = "com.kenan.optishare.action.BENCHMARK";
    public static final String ACTION_ACCEPT = "com.kenan.optishare.action.ACCEPT";
    public static final String ACTION_DECLINE = "com.kenan.optishare.action.DECLINE";
    public static final String ACTION_TRUST_ACCEPT = "com.kenan.optishare.action.TRUST_ACCEPT";
    public static final String ACTION_RESUME_PENDING = "com.kenan.optishare.action.RESUME_PENDING";
    public static final String ACTION_STOP = "com.kenan.optishare.action.STOP";
    public static final String ACTION_PAUSE = "com.kenan.optishare.action.PAUSE";
    public static final String ACTION_EVENT = "com.kenan.optishare.TRANSFER_EVENT";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_ROUTE = "route";
    public static final String EXTRA_URIS = "uris";
    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_SPEED = "speed";
    public static final String EXTRA_SESSION = "session";
    public static final String EXTRA_DONE = "done";
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_ETA_SECONDS = "eta_seconds";
    public static final String EXTRA_AVG_SPEED = "avg_speed";
    public static final String EXTRA_DURATION_MS = "duration_ms";
    public static final String EXTRA_RECONNECTS = "reconnects";
    public static final String EXTRA_FILE_COUNT = "file_count";
    public static final String EXTRA_TOTAL_BYTES = "total_bytes";
    public static final int PORT = 49888;

    private static final String CHANNEL = "optishare_transfers";
    private static final int NOTIFICATION_ID = 2200;
    private static final int MAX_SOCKET_RETRIES = 8;
    private static final long APPROVAL_TIMEOUT_MS = 90_000L;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket serverSocket;
    private volatile Socket activeSocket;
    private volatile BatchManifest activeManifest;
    private volatile List<TransferItem> activeItems;
    private SenderSessionStore senderStore;
    private WifiDirectRecovery wifiRecovery;
    private LanDiscovery lanDiscovery;
    private RoutePerformanceStore routeStore;
    private TrustedDeviceStore trustedStore;
    private volatile String currentRoute = RoutePerformanceStore.ROUTE_DIRECT;
    private volatile String activePeerFingerprint;
    private volatile long activeTransferStartedNanos;
    private volatile int reconnectCount;
    private volatile long latestBatchDone;
    private volatile double latestSpeed;

    @Override public void onCreate() {
        super.onCreate();
        senderStore = new SenderSessionStore(this);
        wifiRecovery = new WifiDirectRecovery(this);
        lanDiscovery = new LanDiscovery(this);
        routeStore = new RoutePerformanceStore(this);
        trustedStore = new TrustedDeviceStore(this);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            SenderSessionStore.Pending pending = senderStore.load();
            if (pending != null) {
                startForeground(NOTIFICATION_ID,
                        notification("OptiShare", "Restoring interrupted transfer…", 0, false));
                startPendingSender(pending);
            }
            return START_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_ACCEPT.equals(action)) {
            IncomingApproval.decide(true);
            return START_STICKY;
        }
        if (ACTION_DECLINE.equals(action)) {
            IncomingApproval.decide(false);
            return START_STICKY;
        }
        if (ACTION_TRUST_ACCEPT.equals(action)) {
            if (activePeerFingerprint != null) {
                trustedStore.trust(activePeerFingerprint,
                        "Device " + DeviceIdentityKey.shortFingerprint(activePeerFingerprint));
                broadcast("trusted_peer", "Device trusted securely ✓", 0, 0, null);
            }
            IncomingApproval.decide(true);
            return START_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            pauseOutgoingTransfer();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopTransfer(true);
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID,
                notification("OptiShare", "Preparing secure transfer…", 0, false));
        if (ACTION_START_RECEIVER.equals(action)) {
            startReceiver();
        } else if (ACTION_SEND.equals(action)) {
            startSender(intent);
        } else if (ACTION_BENCHMARK.equals(action)) {
            startBenchmark(intent);
        } else if (ACTION_RESUME_PENDING.equals(action)) {
            SenderSessionStore.Pending pending = senderStore.load();
            if (pending != null) startPendingSender(pending);
            else broadcast("error", "No resumable outgoing session was found", 0, 0, null);
        }
        return START_STICKY;
    }

    private void resetMetrics() {
        activeTransferStartedNanos = System.nanoTime();
        reconnectCount = 0;
        latestBatchDone = 0L;
        latestSpeed = 0d;
    }

    private void startReceiver() {
        if (!running.compareAndSet(false, true)) return;
        activeTransferStartedNanos = 0L;
        reconnectCount = 0;
        latestBatchDone = 0L;
        latestSpeed = 0d;
        activePeerFingerprint = null;
        lanDiscovery.advertise(new DeviceIdentity(this).name(), PORT, new LanDiscovery.Listener() {
            @Override public void onPeer(String name, String host) { }
            @Override public void onStatus(String message) {
                broadcast("receiver_ready", message + " • Wi-Fi Direct also available when supported", 0, 0, null);
            }
        });
        updateNotification("Ready to receive", "Waiting via Wi-Fi Direct or the same Wi-Fi network", 0, false);
        broadcast("receiver_ready", "Waiting via Wi-Fi Direct or same Wi-Fi…", 0, 0, null);
        executor.execute(() -> {
            try (ServerSocket server = new ServerSocket()) {
                serverSocket = server;
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(PORT));
                while (running.get()) {
                    Socket socket = server.accept();
                    activeSocket = socket;
                    try {
                        broadcast("connected", "Sender connected — verifying secure session", 0, 0, null);
                        receiveOne(socket);
                    } catch (Exception transferError) {
                        if (running.get() && !isTerminalUserDecision(transferError)) {
                            reconnectCount++;
                            String session = activeManifest == null
                                    ? null : activeManifest.getSessionId();
                            broadcast("reconnecting",
                                    "Connection interrupted — waiting for sender to reconnect",
                                    0, 0, session);
                            updateNotification("Waiting to resume",
                                    "Confirmed progress is preserved", 0, false);
                        }
                    } finally {
                        closeSocket();
                    }
                }
            } catch (Exception serverError) {
                if (running.get()) broadcast("error", safe(serverError), 0, 0, null);
            } finally {
                running.set(false);
                lanDiscovery.stopAdvertising();
                serverSocket = null;
                stopForeground(true);
                stopSelf();
            }
        });
    }

    private void receiveOne(Socket socket) throws Exception {
        new TransferEngine(this).receive(socket, new TransferEngine.Listener() {
            @Override public boolean onPeerIdentity(String fingerprint) {
                activePeerFingerprint = fingerprint;
                boolean trusted = trustedStore.isTrusted(fingerprint);
                if (trusted) broadcast("trusted_peer", "Trusted device verified ✓", 0, 0, null);
                return trusted;
            }

            @Override public void onSecurityCode(String code) {
                requireSecurityConfirmation(code, null);
            }

            @Override public void onIncomingBatch(BatchManifest manifest) {
                activeManifest = manifest;
                if (activeTransferStartedNanos == 0L) resetMetrics();
                String approvalKey = "batch:" + manifest.getSessionId();
                String detail = manifest.getEntries().size() + " files • "
                        + formatBytes(manifest.totalBytes());
                if (!trustedStore.autoAccept(activePeerFingerprint)) {
                    IncomingApproval.begin(
                            approvalKey,
                            "Incoming OptiShare transfer",
                            detail + "\nConfirm only if you expect this transfer.");
                } else {
                    broadcast("trusted_peer", "Trusted device • auto-accept enabled",
                            0, 0, manifest.getSessionId());
                }
                broadcast("incoming", detail, 0, 0, manifest.getSessionId());
                updateNotification("Incoming files", detail + " — accept or decline", 0, false);
            }

            @Override public boolean acceptIncomingBatch(BatchManifest manifest) {
                if (!running.get()) return false;
                if (trustedStore.autoAccept(activePeerFingerprint)) return true;
                try {
                    boolean accepted = IncomingApproval.await(
                            "batch:" + manifest.getSessionId(), APPROVAL_TIMEOUT_MS);
                    if (!accepted) {
                        broadcast("declined", "Transfer declined or approval timed out",
                                0, 0, manifest.getSessionId());
                    }
                    return accepted;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            @Override public void onProgress(String sessionId, String fileId, String fileName,
                                             long done, long total, long batchDone,
                                             long batchTotal, double bytesPerSecond) {
                latestBatchDone = batchDone;
                latestSpeed = bytesPerSecond;
                int p = percent(batchDone, batchTotal);
                long eta = etaSeconds(batchDone, batchTotal, bytesPerSecond);
                String message = "Receiving " + fileName + " • "
                        + formatProgress(batchDone, batchTotal, bytesPerSecond, eta);
                updateNotification("Receiving files", message, p, true);
                broadcastProgress(message, p, bytesPerSecond, sessionId,
                        batchDone, batchTotal, eta);
            }

            @Override public void onFileCompleted(String sessionId, String fileId,
                                                  Uri publishedUri) {
                BatchManifest.Entry entry=findEntry(fileId);
                if(entry!=null&&"text/plain".equalsIgnoreCase(entry.mime)&&entry.name.startsWith("OptiShare Text")){
                    String text=readSmallText(publishedUri);
                    if(text!=null)broadcast("text_received",text,0,0,sessionId);
                }
                broadcast("file_done", "Verified ✓ • saved to Download/OptiShare",
                        0, 0, sessionId);
            }

            @Override public void onCompleted(String sessionId) {
                String summary = benchmarkSummary("Received");
                updateNotification("Transfer complete", summary, 100, false);
                broadcastCompleted(summary, sessionId, "incoming");
            }

            @Override public void onBenchmarkCompleted(long bytes, long durationMs, double bytesPerSecond) {
                String summary = "Encrypted Android speed test • " + formatBytes(bytes)
                        + " in " + formatElapsed(durationMs / 1000.0) + " • " + formatSpeed(bytesPerSecond);
                updateNotification("Speed test complete", summary, 100, false);
                broadcastBenchmarkCompleted(summary, bytes, durationMs, bytesPerSecond, "incoming");
            }

            @Override public void onError(String sessionId, Throwable error, boolean resumable) {
                if (isTerminalUserDecision(error)) return;
                broadcast(resumable ? "reconnecting" : "error",
                        resumable ? "Connection interrupted — ready to resume" : safe(error),
                        0, 0, sessionId);
            }
        });
    }

    private void startSender(Intent intent) {
        final String initialHost = intent.getStringExtra(EXTRA_HOST);
        String requestedRoute = intent.getStringExtra(EXTRA_ROUTE);
        currentRoute = RoutePerformanceStore.ROUTE_LAN.equals(requestedRoute) ? RoutePerformanceStore.ROUTE_LAN : RoutePerformanceStore.ROUTE_DIRECT;
        final ArrayList<String> rawUris = intent.getStringArrayListExtra(EXTRA_URIS);
        if (initialHost == null || rawUris == null || rawUris.isEmpty()) {
            broadcast("error", "Missing receiver or files", 0, 0, null);
            stopSelf();
            return;
        }
        if (!running.compareAndSet(false, true)) return;
        resetMetrics();
        executor.execute(() -> {
            try {
                List<TransferItem> richItems = FolderTransferQueue.takeAll();
                activeItems = mergeRichItems(rawUris, richItems);
                TransferEngine engine = new TransferEngine(this);
                activeManifest = engine.buildManifest(activeItems);
                WifiDirectRecovery.Peer peer = RoutePerformanceStore.ROUTE_DIRECT.equals(currentRoute)
                        ? wifiRecovery.capture(2500) : null;
                String host = peer != null && peer.host != null ? peer.host : initialHost;
                String peerAddress = peer == null ? null : peer.deviceAddress;
                senderStore.save(host, peerAddress, currentRoute, activeItems, activeManifest);
                runSenderLoop(host, peerAddress, engine);
            } catch (Exception error) {
                failSender(error);
            }
        });
    }


    private void startBenchmark(Intent intent) {
        final String host = intent.getStringExtra(EXTRA_HOST);
        String requestedRoute = intent.getStringExtra(EXTRA_ROUTE);
        currentRoute = RoutePerformanceStore.ROUTE_LAN.equals(requestedRoute)
                ? RoutePerformanceStore.ROUTE_LAN : RoutePerformanceStore.ROUTE_DIRECT;
        if (host == null || host.trim().isEmpty()) {
            broadcast("benchmark_error", "Missing Android receiver for speed test", 0, 0, null);
            stopSelf();
            return;
        }
        if (!running.compareAndSet(false, true)) return;
        activeManifest = null;
        activeItems = null;
        activePeerFingerprint = null;
        resetMetrics();
        updateNotification("Android speed test", "Measuring encrypted local throughput", 0, true);
        broadcast("benchmark_started", "Testing the encrypted Android route…", 0, 0, null);
        executor.execute(() -> {
            try {
                Socket socket = new Socket();
                activeSocket = socket;
                socket.connect(new InetSocketAddress(host, PORT), 8000);
                new TransferEngine(this).benchmark(socket, new TransferEngine.Listener() {
                    @Override public boolean onPeerIdentity(String fingerprint) {
                        activePeerFingerprint = fingerprint;
                        boolean trusted = trustedStore.isTrusted(fingerprint);
                        if (trusted) broadcast("trusted_peer", "Trusted device verified ✓", 0, 0, null);
                        return trusted;
                    }

                    @Override public void onSecurityCode(String code) {
                        requireSecurityConfirmation(code, null);
                    }

                    @Override public void onIncomingBatch(BatchManifest manifest) { }
                    @Override public boolean acceptIncomingBatch(BatchManifest manifest) { return true; }
                    @Override public void onProgress(String sessionId, String fileId, String fileName,
                                                     long done, long total, long batchDone,
                                                     long batchTotal, double bytesPerSecond) { }
                    @Override public void onFileCompleted(String sessionId, String fileId, Uri publishedUri) { }
                    @Override public void onCompleted(String sessionId) { }
                    @Override public void onError(String sessionId, Throwable error, boolean resumable) { }

                    @Override public void onBenchmarkCompleted(long bytes, long durationMs,
                                                               double bytesPerSecond) {
                        routeStore.recordSuccess(currentRoute, bytesPerSecond);
                        String summary = "Encrypted Android speed test • " + formatBytes(bytes)
                                + " in " + formatElapsed(durationMs / 1000.0)
                                + " • " + formatSpeed(bytesPerSecond)
                                + " • " + routeLabel(currentRoute);
                        updateNotification("Speed test complete", summary, 100, false);
                        broadcastBenchmarkCompleted(summary, bytes, durationMs,
                                bytesPerSecond, currentRoute);
                    }
                });
            } catch (Exception error) {
                routeStore.recordFailure(currentRoute);
                String message = safe(error);
                if (message.toLowerCase(Locale.US).contains("invalid frame type")) {
                    message = "The other OptiShare version does not support Android speed test yet";
                }
                broadcast("benchmark_error", message, 0, 0, null);
                updateNotification("Speed test failed", message, 0, false);
            } finally {
                running.set(false);
                closeSocket();
                stopForeground(false);
                stopSelf();
            }
        });
    }

    private void startPendingSender(SenderSessionStore.Pending pending) {
        if (!running.compareAndSet(false, true)) return;
        currentRoute = RoutePerformanceStore.ROUTE_LAN.equals(pending.route)
                ? RoutePerformanceStore.ROUTE_LAN : RoutePerformanceStore.ROUTE_DIRECT;
        resetMetrics();
        executor.execute(() -> {
            try {
                activeItems = pending.items;
                activeManifest = pending.manifest;
                broadcast("reconnecting", "Restoring interrupted session…", 0, 0,
                        activeManifest.getSessionId());
                runSenderLoop(pending.host, pending.peerAddress, new TransferEngine(this));
            } catch (Exception error) {
                failSender(error);
            }
        });
    }

    private void runSenderLoop(String initialHost, String peerAddress,
                               TransferEngine engine) throws Exception {
        int attempt = 0;
        String host = initialHost;
        try {
            while (running.get() && attempt < MAX_SOCKET_RETRIES) {
                attempt++;
                try {
                    if (attempt > 1) {
                        reconnectCount++;
                        broadcast("reconnecting", "Reconnecting… attempt " + attempt,
                                0, 0, activeManifest.getSessionId());
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
                    if (isTerminalUserDecision(transferError)) {
                        senderStore.clear();
                        broadcast("declined", safe(transferError), 0, 0,
                                activeManifest.getSessionId());
                        return;
                    }
                    if (peerAddress != null && wifiRecovery.available()) {
                        String recoveredHost = wifiRecovery.recover(peerAddress, 12_000);
                        if (recoveredHost != null) {
                            host = recoveredHost;
                            senderStore.save(host, peerAddress, currentRoute, activeItems, activeManifest);
                            broadcast("reconnecting",
                                    "Direct link restored — resuming encrypted session",
                                    0, 0, activeManifest.getSessionId());
                        }
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
        if (isTerminalUserDecision(error)) {
            senderStore.clear();
            broadcast("declined", safe(error), 0, 0, session);
        } else {
            routeStore.recordFailure(currentRoute);
            broadcast("error", safe(error) + " — session kept for resume", 0, 0, session);
            updateNotification("Transfer paused",
                    "Open OptiShare to resume the pending session", 0, false);
        }
        running.set(false);
        closeSocket();
        stopSelf();
    }

    private void sendOne(TransferEngine engine, Socket socket) throws Exception {
        engine.send(socket, activeManifest, activeItems, new TransferEngine.Listener() {
            @Override public boolean onPeerIdentity(String fingerprint) {
                activePeerFingerprint = fingerprint;
                boolean trusted = trustedStore.isTrusted(fingerprint);
                if (trusted) broadcast("trusted_peer", "Trusted device verified ✓",
                        0, 0, activeManifest.getSessionId());
                return trusted;
            }

            @Override public void onSecurityCode(String code) {
                requireSecurityConfirmation(code, activeManifest.getSessionId());
            }

            @Override public void onIncomingBatch(BatchManifest manifest) { }
            @Override public boolean acceptIncomingBatch(BatchManifest manifest) { return true; }

            @Override public void onProgress(String sessionId, String fileId, String fileName,
                                             long done, long total, long batchDone,
                                             long batchTotal, double bytesPerSecond) {
                latestBatchDone = batchDone;
                latestSpeed = bytesPerSecond;
                int p = percent(batchDone, batchTotal);
                long eta = etaSeconds(batchDone, batchTotal, bytesPerSecond);
                String message = "Sending " + fileName + " • "
                        + formatProgress(batchDone, batchTotal, bytesPerSecond, eta);
                updateNotification("Sending files", message, p, true);
                broadcastProgress(message, p, bytesPerSecond, sessionId,
                        batchDone, batchTotal, eta);
            }

            @Override public void onFileCompleted(String sessionId, String fileId,
                                                  Uri publishedUri) { }

            @Override public void onCompleted(String sessionId) {
                routeStore.recordSuccess(currentRoute, averageBytesPerSecond());
                String summary = benchmarkSummary("Sent") + " • " + routeLabel(currentRoute);
                updateNotification("Transfer complete", summary, 100, false);
                broadcastCompleted(summary, sessionId, currentRoute);
            }

            @Override public void onError(String sessionId, Throwable error, boolean resumable) {
                if (isTerminalUserDecision(error)) return;
                broadcast(resumable ? "reconnecting" : "error",
                        resumable ? "Connection interrupted — resuming automatically" : safe(error),
                        0, 0, sessionId);
            }
        });
    }

    /**
     * Prevents an unauthenticated MITM from being silently accepted. Both users must see the same
     * six-digit code and explicitly confirm before any manifest or file data is accepted.
     */
    private void requireSecurityConfirmation(String code, String sessionId) {
        String formatted = formatCode(code);
        String key = "security:" + code;
        IncomingApproval.begin(
                key,
                "Verify OptiShare security code",
                "Compare both phones. The code must be " + formatted
                        + ". Confirm only if both screens match exactly.",
                activePeerFingerprint);
        broadcast("security_confirm", "Security code: " + formatted,
                0, 0, sessionId);
        updateNotification("Verify secure connection",
                "Compare code " + formatted + " on both phones", 0, false);
        try {
            boolean accepted = IncomingApproval.await(key, APPROVAL_TIMEOUT_MS);
            if (!accepted) {
                throw new SecurityException(
                        "Security-code confirmation declined or timed out");
            }
            broadcast("security", "Security code confirmed: " + formatted,
                    0, 0, sessionId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SecurityException("Security-code confirmation interrupted", e);
        }
    }

    private List<TransferItem> mergeRichItems(List<String> rawUris,List<TransferItem> rich) throws Exception {
        Map<String,TransferItem> richByUri=new LinkedHashMap<>();
        if(rich!=null)for(TransferItem item:rich)if(item!=null&&item.getUri()!=null)richByUri.put(item.getUri().toString(),item);
        List<TransferItem> result=new ArrayList<>();
        for(String raw:rawUris){TransferItem item=richByUri.get(raw);if(item!=null){result.add(item);continue;}result.addAll(resolveItems(java.util.Collections.singletonList(raw)));}
        return result;
    }

    private BatchManifest.Entry findEntry(String fileId){
        if(activeManifest==null||fileId==null)return null;
        for(BatchManifest.Entry entry:activeManifest.getEntries())if(fileId.equals(entry.id))return entry;
        return null;
    }

    private String readSmallText(Uri uri){
        if(uri==null)return null;
        try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)return null;byte[] buffer=new byte[8192];int total=0,n;
            while((n=in.read(buffer))!=-1){total+=n;if(total>65536)return null;out.write(buffer,0,n);}return out.toString(StandardCharsets.UTF_8.name());
        }catch(Exception ignored){return null;}
    }

    private List<TransferItem> resolveItems(List<String> rawUris) throws Exception {
        if (rawUris.size() > 10_000) throw new IllegalArgumentException("Too many selected files");
        List<TransferItem> result = new ArrayList<>();
        ContentResolver resolver = getContentResolver();
        for (String raw : rawUris) {
            Uri uri = Uri.parse(raw);
            String name = "file.bin";
            long size = -1;
            Cursor cursor = null;
            try {
                cursor = resolver.query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                        null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        name = TransferItem.safeName(cursor.getString(nameIndex));
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex);
                    }
                }
            } finally {
                if (cursor != null) cursor.close();
            }
            String mime = resolver.getType(uri);
            if (size < 0) size = measure(uri);
            result.add(new TransferItem(uri, name, mime, size,
                    FileClassifier.classify(mime, name)));
        }
        return result;
    }

    private long measure(Uri uri) throws Exception {
        long total = 0;
        byte[] buffer = new byte[1024 * 1024];
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("Cannot open selected file");
            int n;
            while ((n = in.read(buffer)) != -1) total += n;
        }
        return total;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "File transfers", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Active encrypted OptiShare local file transfers");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(channel);
        }
    }

    private Notification notification(String title, String text,
                                      int progress, boolean ongoingProgress) {
        Intent open = new Intent(this, V2Activity.class);
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        Intent stop = new Intent(this, TransferService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_optishare)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoingProgress);

        if (ongoingProgress && activeManifest != null && activeItems != null) {
            Intent pause = new Intent(this, TransferService.class).setAction(ACTION_PAUSE);
            PendingIntent pausePending = PendingIntent.getService(this, 2, pause,
                    PendingIntent.FLAG_UPDATE_CURRENT | immutable);
            builder.addAction(0, "Pause", pausePending);
        } else if (!ongoingProgress && "Transfer paused".equals(title)
                && senderStore != null && senderStore.exists()) {
            Intent resume = new Intent(this, TransferService.class).setAction(ACTION_RESUME_PENDING);
            PendingIntent resumePending = PendingIntent.getService(this, 3, resume,
                    PendingIntent.FLAG_UPDATE_CURRENT | immutable);
            builder.addAction(0, "Resume", resumePending);
        }
        builder.addAction(0, "Cancel", stopPending);
        if (ongoingProgress) builder.setProgress(100, progress, false);
        return builder.build();
    }

    private void updateNotification(String title, String text, int progress, boolean ongoing) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, notification(title, text, progress, ongoing));
    }

    private void broadcast(String event, String message, int progress,
                           double speed, String session) {
        Intent intent = new Intent(ACTION_EVENT);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_EVENT, event);
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_PROGRESS, progress);
        intent.putExtra(EXTRA_SPEED, speed);
        intent.putExtra(EXTRA_SESSION, session);
        sendBroadcast(intent);
    }

    private void broadcastCompleted(String message, String session, String route) {
        long total = activeManifest == null ? latestBatchDone : activeManifest.totalBytes();
        int fileCount = activeManifest == null ? 0 : activeManifest.getEntries().size();
        double average = averageBytesPerSecond();
        long durationMs = activeTransferStartedNanos == 0L ? 0L
                : Math.max(0L, Math.round((System.nanoTime() - activeTransferStartedNanos) / 1_000_000.0));
        Intent intent = new Intent(ACTION_EVENT);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_EVENT, "completed");
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_PROGRESS, 100);
        intent.putExtra(EXTRA_SESSION, session);
        intent.putExtra(EXTRA_ROUTE, route == null ? "unknown" : route);
        intent.putExtra(EXTRA_AVG_SPEED, average);
        intent.putExtra(EXTRA_DURATION_MS, durationMs);
        intent.putExtra(EXTRA_RECONNECTS, reconnectCount);
        intent.putExtra(EXTRA_FILE_COUNT, fileCount);
        intent.putExtra(EXTRA_TOTAL_BYTES, total);
        sendBroadcast(intent);
    }


    private void broadcastBenchmarkCompleted(String message, long bytes, long durationMs,
                                             double speed, String route) {
        Intent intent = new Intent(ACTION_EVENT);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_EVENT, "benchmark_completed");
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_PROGRESS, 100);
        intent.putExtra(EXTRA_SPEED, speed);
        intent.putExtra(EXTRA_DONE, bytes);
        intent.putExtra(EXTRA_TOTAL, bytes);
        intent.putExtra(EXTRA_TOTAL_BYTES, bytes);
        intent.putExtra(EXTRA_DURATION_MS, durationMs);
        intent.putExtra(EXTRA_ROUTE, route == null ? "unknown" : route);
        sendBroadcast(intent);
    }

    private void broadcastProgress(String message, int progress, double speed, String session,
                                   long done, long total, long etaSeconds) {
        Intent intent = new Intent(ACTION_EVENT);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_EVENT, "progress");
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_PROGRESS, progress);
        intent.putExtra(EXTRA_SPEED, speed);
        intent.putExtra(EXTRA_SESSION, session);
        intent.putExtra(EXTRA_DONE, done);
        intent.putExtra(EXTRA_TOTAL, total);
        intent.putExtra(EXTRA_ETA_SECONDS, etaSeconds);
        sendBroadcast(intent);
    }

    private void pauseOutgoingTransfer() {
        if (!running.get() || activeManifest == null || activeItems == null) {
            broadcast("pause_unavailable", "Pause is available after an outgoing transfer starts", 0, 0,
                    activeManifest == null ? null : activeManifest.getSessionId());
            return;
        }
        if (latestBatchDone <= 0L) {
            broadcast("pause_unavailable", "Secure setup is still finishing — pause will be available once file data starts", 0, 0,
                    activeManifest.getSessionId());
            return;
        }
        int p = percent(latestBatchDone, activeManifest.totalBytes());
        running.set(false);
        closeSocket();
        broadcast("paused", "Paused safely • confirmed progress kept for resume", p, latestSpeed,
                activeManifest.getSessionId());
        updateNotification("Transfer paused", "Open OptiShare and tap Resume", p, false);
        stopForeground(false);
        stopSelf();
    }

    private void stopTransfer(boolean userCancelled) {
        running.set(false);
        IncomingApproval.cancel();
        if (userCancelled && senderStore != null) senderStore.clear();
        if (lanDiscovery != null) lanDiscovery.stopAdvertising();
        closeSocket();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) { }
        serverSocket = null;
        stopForeground(true);
        stopSelf();
    }

    private void closeSocket() {
        try {
            if (activeSocket != null) activeSocket.close();
        } catch (Exception ignored) { }
        activeSocket = null;
    }

    @Override public void onDestroy() {
        running.set(false);
        IncomingApproval.cancel();
        if (lanDiscovery != null) lanDiscovery.close();
        closeSocket();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) { }
        executor.shutdownNow();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private static int percent(long done, long total) {
        return total <= 0 ? 0
                : (int) Math.max(0, Math.min(100, done * 100L / total));
    }

    private static long etaSeconds(long done, long total, double bytesPerSecond) {
        if (bytesPerSecond <= 1 || total <= done) return 0L;
        return Math.max(1L, Math.round((total - done) / bytesPerSecond));
    }

    private static String formatProgress(long done, long total,
                                         double bytesPerSecond, long etaSeconds) {
        StringBuilder value = new StringBuilder();
        value.append(percent(done, total)).append("% • ")
                .append(formatBytes(done)).append(" / ").append(formatBytes(total))
                .append(" • ").append(formatSpeed(bytesPerSecond));
        if (etaSeconds > 0) value.append(" • ").append(formatDuration(etaSeconds)).append(" left");
        return value.toString();
    }

    private double averageBytesPerSecond() {
        long total = activeManifest == null ? latestBatchDone : activeManifest.totalBytes();
        double seconds = activeTransferStartedNanos == 0L ? 0d : Math.max(0.001, (System.nanoTime() - activeTransferStartedNanos) / 1_000_000_000.0);
        return total <= 0 || seconds <= 0 ? latestSpeed : total / seconds;
    }

    private static String routeLabel(String route) { return RoutePerformanceStore.ROUTE_LAN.equals(route) ? "same Wi-Fi" : "Wi-Fi Direct"; }

    private String benchmarkSummary(String verb) {
        long total = activeManifest == null ? latestBatchDone : activeManifest.totalBytes();
        double seconds = activeTransferStartedNanos == 0L ? 0d
                : Math.max(0.001, (System.nanoTime() - activeTransferStartedNanos) / 1_000_000_000.0);
        double average = averageBytesPerSecond();
        StringBuilder value = new StringBuilder();
        value.append(verb).append(" ").append(formatBytes(total))
                .append(" in ").append(formatElapsed(seconds))
                .append(" • avg ").append(formatSpeed(average));
        if (reconnectCount > 0) {
            value.append(" • ").append(reconnectCount).append(reconnectCount == 1
                    ? " reconnect" : " reconnects");
        }
        return value.toString();
    }

    private static boolean isTerminalUserDecision(Throwable t) {
        String message = safe(t).toLowerCase(Locale.US);
        return message.contains("declined")
                || message.contains("approval timed out")
                || message.contains("security-code confirmation");
    }

    private static String safe(Throwable t) {
        String message = t == null ? null : t.getMessage();
        return message == null || message.trim().isEmpty()
                ? (t == null ? "Unknown error" : t.getClass().getSimpleName())
                : message;
    }

    private static String formatCode(String code) {
        if (code == null || code.length() != 6) return code;
        return code.substring(0, 3) + " " + code.substring(3);
    }

    private static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond >= 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB/s",
                    bytesPerSecond / (1024 * 1024));
        }
        return String.format(Locale.US, "%.0f KB/s", Math.max(0d, bytesPerSecond) / 1024);
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.2f GB",
                    bytes / (1024.0 * 1024 * 1024));
        }
        if (bytes >= 1024L * 1024) {
            return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024));
        }
        if (bytes >= 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private static String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        long remain = seconds % 60;
        if (minutes < 60) return minutes + "m " + remain + "s";
        long hours = minutes / 60;
        return hours + "h " + (minutes % 60) + "m";
    }

    private static String formatElapsed(double seconds) {
        if (seconds < 10) return String.format(Locale.US, "%.1fs", seconds);
        return formatDuration(Math.max(1L, Math.round(seconds)));
    }
}
