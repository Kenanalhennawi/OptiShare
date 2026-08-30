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
import com.kenan.optishare.settings.AppSettings;
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
    public static final String EXTRA_FALLBACK_HOST = "fallback_host";
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
    public static final String EXTRA_FILE_ID = "file_id";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_FILE_DONE = "file_done_bytes";
    public static final String EXTRA_FILE_TOTAL = "file_total_bytes";
    public static final String EXTRA_FILE_INDEX = "file_index";
    public static final int PORT = 49888;
    public static final int PARALLEL_BENCHMARK_PORT = 49891;
    public static final int STRIPED_TRANSFER_PORT = 49892;

    private static final String CHANNEL = "optishare_transfers";
    private static final String COMPLETION_CHANNEL = "optishare_transfer_results";
    private static final int NOTIFICATION_ID = 2200;
    private static final int MAX_SOCKET_RETRIES = 8;
    private static final long APPROVAL_TIMEOUT_MS = 90_000L;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket serverSocket;
    private volatile ServerSocket benchmarkServerSocket;
    private volatile ServerSocket stripedServerSocket;
    private volatile Socket activeSocket;
    private volatile StripedTransferEngine activeStripedEngine;
    private volatile boolean stripedActive;
    private volatile BatchManifest activeManifest;
    private volatile List<TransferItem> activeItems;
    private SenderSessionStore senderStore;
    private WifiDirectRecovery wifiRecovery;
    private LanRecovery lanRecovery;
    private LanDiscovery lanDiscovery;
    private RoutePerformanceStore routeStore;
    private TrustedDeviceStore trustedStore;
    private final HandshakeRateLimiter handshakeLimiter = new HandshakeRateLimiter();
    private volatile String currentRoute = RoutePerformanceStore.ROUTE_DIRECT;
    private volatile String activePeerFingerprint;
    private volatile long activeTransferStartedNanos;
    private volatile long dataTransferStartedNanos;
    private volatile long accumulatedDataTransferMs;
    private volatile boolean resumedSession;
    private volatile int reconnectCount;
    private volatile long latestBatchDone;
    private volatile double latestSpeed;

    @Override public void onCreate() {
        super.onCreate();
        senderStore = new SenderSessionStore(this);
        wifiRecovery = new WifiDirectRecovery(this);
        lanRecovery = new LanRecovery(this);
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
        dataTransferStartedNanos = 0L;
        accumulatedDataTransferMs = 0L;
        resumedSession = false;
        reconnectCount = 0;
        latestBatchDone = 0L;
        latestSpeed = 0d;
    }

    private void startReceiver() {
        if (!running.compareAndSet(false, true)) return;
        activeTransferStartedNanos = 0L;
        dataTransferStartedNanos = 0L;
        accumulatedDataTransferMs = 0L;
        resumedSession = false;
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
        startParallelBenchmarkReceiver();
        startStripedReceiver();
        executor.execute(() -> {
            try (ServerSocket server = new ServerSocket()) {
                serverSocket = server;
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(PORT));
                while (running.get()) {
                    Socket socket = server.accept();
                    if (!allowIncomingHandshake(socket)) continue;
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

    private void startStripedReceiver() {
        executor.execute(() -> {
            try (ServerSocket server = new ServerSocket()) {
                stripedServerSocket = server;
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(STRIPED_TRANSFER_PORT));
                while (running.get()) {
                    Socket socket = server.accept();
                    if (!allowIncomingHandshake(socket)) continue;
                    executor.execute(() -> {
                        try {
                            new StripedTransferEngine(this).receive(socket, trustedStore, new StripedTransferEngine.Listener() {
                                @Override public void onIncoming(String sessionId, String name, long totalBytes, String fingerprint, StripedTransferEngine.Approval approval) {
                                    activePeerFingerprint = fingerprint;
                                    activeTransferStartedNanos = System.nanoTime();
                                    dataTransferStartedNanos = 0L;
                                    latestBatchDone = 0L;
                                    latestSpeed = 0d;
                                    if (trustedStore.autoAccept(fingerprint)) { approval.decide(true); return; }
                                    String key = "striped:" + sessionId;
                                    IncomingApproval.begin(key, "Incoming accelerated OptiShare transfer", name + " • " + formatBytes(totalBytes) + "\n2 encrypted streams • verify this transfer is expected.");
                                    broadcast("incoming", "Accelerated transfer • " + name + " • " + formatBytes(totalBytes), 0, 0, sessionId);
                                    try { approval.decide(IncomingApproval.await(key, APPROVAL_TIMEOUT_MS)); }
                                    catch (InterruptedException e) { Thread.currentThread().interrupt(); approval.decide(false); }
                                }
                                @Override public void onProgress(long done, long total, double speed) {
                                    if (dataTransferStartedNanos == 0L && done > 0L) dataTransferStartedNanos = System.nanoTime();
                                    latestBatchDone = done; latestSpeed = speed;
                                    int p = percent(done, total); long eta = etaSeconds(done, total, speed);
                                    String message = "Receiving with 2 encrypted streams • " + formatProgress(done, total, speed, eta);
                                    updateNotification("Accelerated receive", message, p, true);
                                    broadcastProgress(message, p, speed, null, done, total, eta);
                                }
                                @Override public void onCompleted(String sessionId, Uri publishedUri, long durationMs, double speed) {
                                    latestBatchDone = Math.max(latestBatchDone, 1L); latestSpeed = speed;
                                    String summary = "Received with 2 streams • " + formatElapsed(durationMs / 1000.0) + " • avg " + formatSpeed(speed);
                                    updateNotification("Transfer complete", summary, 100, false);
                                    broadcastCompleted(summary, sessionId, RoutePerformanceStore.ROUTE_LAN);
                                }
                            });
                        } catch (Exception ignored) { }
                    });
                }
            } catch (Exception ignored) {
                // Normal resumable receiver remains available if accelerated port cannot bind.
            } finally { stripedServerSocket = null; }
        });
    }

    private void startParallelBenchmarkReceiver() {
        executor.execute(() -> {
            try (ServerSocket server = new ServerSocket()) {
                benchmarkServerSocket = server;
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(PARALLEL_BENCHMARK_PORT));
                while (running.get()) {
                    Socket socket = server.accept();
                    if (!allowIncomingHandshake(socket)) continue;
                    executor.execute(() -> receiveTrustedBenchmark(socket));
                }
            } catch (Exception ignored) {
                // The normal transfer listener remains available even if the optional parallel
                // benchmark port cannot be opened on a particular OEM/network.
            } finally {
                benchmarkServerSocket = null;
            }
        });
    }

    private void receiveTrustedBenchmark(Socket socket) {
        try (Socket local = socket) {
            new TransferEngine(this).receive(local, new TransferEngine.Listener() {
                @Override public boolean onPeerIdentity(String fingerprint) {
                    return fingerprint != null && trustedStore.isTrusted(fingerprint);
                }
                @Override public void onSecurityCode(String code) {
                    throw new SecurityException("Trust both devices before the 2-stream benchmark");
                }
                @Override public void onIncomingBatch(BatchManifest manifest) { }
                @Override public boolean acceptIncomingBatch(BatchManifest manifest) { return false; }
                @Override public void onProgress(String sessionId, String fileId, String fileName, long done, long total, long batchDone, long batchTotal, double bytesPerSecond) { }
                @Override public void onFileCompleted(String sessionId, String fileId, Uri publishedUri) { }
                @Override public void onCompleted(String sessionId) { }
                @Override public void onError(String sessionId, Throwable error, boolean resumable) { }
            });
        } catch (Exception ignored) { }
    }

    private boolean allowIncomingHandshake(Socket socket) {
        String address = socket == null || socket.getInetAddress() == null
                ? null : socket.getInetAddress().getHostAddress();
        boolean allowed = handshakeLimiter.allow(address, System.nanoTime() / 1_000_000L);
        if (!allowed && socket != null) {
            try { socket.close(); } catch (Exception ignored) { }
        }
        return allowed;
    }

    private static final class BenchmarkSample {
        final long bytes;
        final long durationMs;
        final double bytesPerSecond;
        BenchmarkSample(long bytes, long durationMs, double bytesPerSecond) {
            this.bytes = bytes;
            this.durationMs = durationMs;
            this.bytesPerSecond = bytesPerSecond;
        }
    }

    private BenchmarkSample runBenchmarkSocket(String host, int port, boolean trustedOnly, String expectedFingerprint) throws Exception {
        final long[] bytes = {0L};
        final long[] duration = {0L};
        final double[] speed = {0d};
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 8000);
            new TransferEngine(this).benchmark(socket, new TransferEngine.Listener() {
                @Override public boolean onPeerIdentity(String fingerprint) {
                    if (trustedOnly) {
                        return fingerprint != null
                                && trustedStore.isTrusted(fingerprint)
                                && (expectedFingerprint == null || expectedFingerprint.equals(fingerprint));
                    }
                    activePeerFingerprint = fingerprint;
                    boolean trusted = fingerprint != null && trustedStore.isTrusted(fingerprint);
                    if (trusted) broadcast("trusted_peer", "Trusted device verified ✓", 0, 0, null);
                    return trusted;
                }
                @Override public void onSecurityCode(String code) {
                    if (trustedOnly) throw new SecurityException("Trust both devices before the 2-stream benchmark");
                    requireSecurityConfirmation(code, null);
                }
                @Override public void onIncomingBatch(BatchManifest manifest) { }
                @Override public boolean acceptIncomingBatch(BatchManifest manifest) { return true; }
                @Override public void onProgress(String sessionId, String fileId, String fileName, long done, long total, long batchDone, long batchTotal, double bytesPerSecond) { }
                @Override public void onFileCompleted(String sessionId, String fileId, Uri publishedUri) { }
                @Override public void onCompleted(String sessionId) { }
                @Override public void onError(String sessionId, Throwable error, boolean resumable) { }
                @Override public void onBenchmarkCompleted(long sampleBytes, long durationMs, double bytesPerSecond) {
                    bytes[0] = sampleBytes;
                    duration[0] = durationMs;
                    speed[0] = bytesPerSecond;
                }
            });
        }
        if (bytes[0] <= 0L || duration[0] <= 0L || speed[0] <= 0d) throw new java.io.IOException("Speed test returned no measurement");
        return new BenchmarkSample(bytes[0], duration[0], speed[0]);
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
                if (dataTransferStartedNanos == 0L && batchDone > 0L) dataTransferStartedNanos = System.nanoTime();
                latestBatchDone = batchDone;
                latestSpeed = bytesPerSecond;
                int p = percent(batchDone, batchTotal);
                long eta = etaSeconds(batchDone, batchTotal, bytesPerSecond);
                String message = "Receiving " + fileName + " • "
                        + formatProgress(batchDone, batchTotal, bytesPerSecond, eta);
                updateNotification("Receiving files", message, p, true);
                broadcastProgress(message, p, bytesPerSecond, sessionId,
                        batchDone, batchTotal, eta, fileId, fileName, done, total, entryIndex(fileId));
            }

            @Override public void onFileCompleted(String sessionId, String fileId,
                                                  Uri publishedUri) {
                BatchManifest.Entry entry=findEntry(fileId);
                if(entry!=null&&"text/plain".equalsIgnoreCase(entry.mime)&&entry.name.startsWith("OptiShare Text")){
                    String text=readSmallText(publishedUri);
                    if(text!=null)broadcast("text_received",text,0,0,sessionId);
                }
                String completedName = entry == null ? "Received file" : entry.name;
                broadcastFileDone(sessionId, fileId, completedName, entryIndex(fileId),
                        "Verified ✓ • saved to Download/OptiShare");
            }

            @Override public void onFileFailed(String sessionId, String fileId,
                                               String fileName, String reason) {
                broadcastFileFailed(sessionId, fileId, fileName, entryIndex(fileId), reason);
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
        final String fallbackHost = AdaptiveRouteOrchestrator.verifiedLanFallback(
                currentRoute, intent.getStringExtra(EXTRA_FALLBACK_HOST));
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
                senderStore.save(host, peerAddress, currentRoute, fallbackHost, activeItems, activeManifest);
                if (shouldUseStripedTransfer()) {
                    try {
                        runStripedSender(host);
                        senderStore.clear();
                        running.set(false);
                        stopForeground(false);
                        stopSelf();
                        return;
                    } catch (Exception stripedError) {
                        stripedActive = false;
                        activeStripedEngine = null;
                        if (!running.get()) return;
                        routeStore.recordParallelFailure(routeStore.parallelPeerFingerprint());
                        accumulatedDataTransferMs = currentAccumulatedDataMs();
                        dataTransferStartedNanos = 0L;
                        resumedSession = accumulatedDataTransferMs > 0L;
                        senderStore.updateElapsedDataMs(accumulatedDataTransferMs);
                        latestBatchDone = 0L; latestSpeed = 0d;
                        broadcast("parallel_fallback", "2-stream acceleration unavailable — using an isolated 1-stream session; a new benchmark is required before retrying acceleration", 0, 0, activeManifest.getSessionId());
                        updateNotification("Using reliable fallback", "Continuing with the normal encrypted resumable stream", 0, true);
                    }
                }
                runSenderLoop(host, peerAddress, fallbackHost, engine);
            } catch (Exception error) {
                failSender(error);
            }
        });
    }


    private boolean shouldUseStripedTransfer() {
        if (!new AppSettings(this).smartRoute()) return false;
        if (!RoutePerformanceStore.ROUTE_LAN.equals(currentRoute) || !routeStore.parallelRecommended()) return false;
        if (activeItems == null || activeManifest == null || activeItems.size() != 1 || activeManifest.getEntries().size() != 1) return false;
        if (activeItems.get(0).getSize() < StripedTransferEngine.MIN_FILE_BYTES) return false;
        String fingerprint = routeStore.parallelPeerFingerprint();
        return fingerprint != null && trustedStore.isTrusted(fingerprint);
    }

    private void runStripedSender(String host) throws Exception {
        String expectedFingerprint = routeStore.parallelPeerFingerprint();
        BatchManifest parallelManifest = new BatchManifest(
                java.util.UUID.randomUUID().toString(), activeManifest.getCreatedAt(),
                activeManifest.getEntries());
        StripedTransferEngine engine = new StripedTransferEngine(this);
        activeStripedEngine = engine; stripedActive = true;
        broadcast("parallel_started", "SmartRoute selected 2 encrypted streams • benchmark showed a meaningful gain", 0, 0, activeManifest.getSessionId());
        updateNotification("2-stream acceleration", "Sending large file over two authenticated encrypted streams", 0, true);
        try {
            engine.send(host, STRIPED_TRANSFER_PORT, parallelManifest, activeItems.get(0), expectedFingerprint, new StripedTransferEngine.Listener() {
                @Override public void onIncoming(String sessionId, String name, long totalBytes, String fingerprint, StripedTransferEngine.Approval approval) { }
                @Override public void onProgress(long done, long total, double speed) {
                    if (dataTransferStartedNanos == 0L && done > 0L) dataTransferStartedNanos = System.nanoTime();
                    latestBatchDone = done; latestSpeed = speed;
                    int p = percent(done, total); long eta = etaSeconds(done, total, speed);
                    String message = "Sending with 2 encrypted streams • " + formatProgress(done, total, speed, eta);
                    updateNotification("Accelerated send", message, p, true);
                    broadcastProgress(message, p, speed, activeManifest.getSessionId(), done, total, eta);
                }
                @Override public void onCompleted(String sessionId, Uri publishedUri, long durationMs, double speed) {
                    latestBatchDone = activeManifest.totalBytes(); latestSpeed = speed;
                    routeStore.recordSuccess(currentRoute, speed);
                    String summary = "Sent " + formatBytes(activeManifest.totalBytes()) + " in " + formatElapsed(durationMs / 1000.0) + " • avg " + formatSpeed(speed) + " • 2 encrypted streams • same Wi-Fi";
                    updateNotification("Transfer complete", summary, 100, false);
                    broadcastCompleted(summary, activeManifest.getSessionId(), currentRoute);
                }
            });
        } finally { stripedActive = false; activeStripedEngine = null; }
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
        updateNotification("Adaptive Android speed test", "Measuring 1 stream first", 0, true);
        broadcast("benchmark_started", "Adaptive test: measuring 1 encrypted stream…", 0, 0, null);
        executor.execute(() -> {
            try {
                BenchmarkSample single = runBenchmarkSocket(host, PORT, false, null);
                routeStore.recordSuccess(currentRoute, single.bytesPerSecond);
                String fingerprint = activePeerFingerprint;
                boolean localTrusted = fingerprint != null && trustedStore.isTrusted(fingerprint);
                if (!localTrusted) {
                    String summary = "1 stream • " + formatSpeed(single.bytesPerSecond)
                            + " • Trust this device on both phones, then rerun to compare 2 streams";
                    updateNotification("Speed test complete", summary, 100, false);
                    broadcastBenchmarkCompleted(summary, single.bytes, single.durationMs,
                            single.bytesPerSecond, currentRoute);
                    return;
                }

                updateNotification("Adaptive Android speed test", "Comparing 2 encrypted streams", 55, true);
                broadcast("benchmark_started", "1 stream: " + formatSpeed(single.bytesPerSecond)
                        + " • now testing 2 streams…", 55, single.bytesPerSecond, null);
                long dualStarted = System.nanoTime();
                java.util.concurrent.Future<BenchmarkSample> first = executor.submit(
                        () -> runBenchmarkSocket(host, PARALLEL_BENCHMARK_PORT, true, fingerprint));
                java.util.concurrent.Future<BenchmarkSample> second = executor.submit(
                        () -> runBenchmarkSocket(host, PARALLEL_BENCHMARK_PORT, true, fingerprint));
                BenchmarkSample a = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
                BenchmarkSample b = second.get(20, java.util.concurrent.TimeUnit.SECONDS);
                long dualDurationMs = Math.max(1L, Math.round((System.nanoTime() - dualStarted) / 1_000_000.0));
                long dualBytes = a.bytes + b.bytes;
                double dualSpeed = dualBytes / Math.max(0.001, dualDurationMs / 1000.0);
                int gain = ParallelBenchmarkDecision.improvementPercent(single.bytesPerSecond, dualSpeed);
                boolean recommendDual = ParallelBenchmarkDecision.recommendTwoStreams(single.bytesPerSecond, dualSpeed);
                routeStore.recordParallelBenchmark(single.bytesPerSecond, dualSpeed, fingerprint);
                String summary = "1 stream " + formatSpeed(single.bytesPerSecond)
                        + " • 2 streams " + formatSpeed(dualSpeed)
                        + " • " + (gain >= 0 ? "+" : "") + gain + "% • "
                        + (recommendDual ? "2-stream recommended" : "1-stream recommended");
                updateNotification("Adaptive speed test complete", summary, 100, false);
                broadcastBenchmarkCompleted(summary, dualBytes, dualDurationMs, dualSpeed, currentRoute);
            } catch (Exception error) {
                String message = safe(error);
                if (message.toLowerCase(Locale.US).contains("trust both devices")) {
                    message = "1-stream test works; trust the device on both phones before the 2-stream comparison";
                } else if (message.toLowerCase(Locale.US).contains("invalid frame type")) {
                    message = "The other OptiShare version does not support adaptive speed test yet";
                }
                broadcast("benchmark_error", message, 0, 0, null);
                updateNotification("Speed test stopped", message, 0, false);
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
        accumulatedDataTransferMs = Math.max(0L, pending.elapsedDataMs);
        resumedSession = accumulatedDataTransferMs > 0L;
        executor.execute(() -> {
            try {
                activeItems = pending.items;
                activeManifest = pending.manifest;
                broadcast("reconnecting", "Restoring interrupted session…", 0, 0,
                        activeManifest.getSessionId());
                runSenderLoop(pending.host, pending.peerAddress, pending.fallbackHost,
                        new TransferEngine(this));
            } catch (Exception error) {
                failSender(error);
            }
        });
    }

    private void runSenderLoop(String initialHost, String initialPeerAddress, String fallbackHost,
                               TransferEngine engine) throws Exception {
        int attempt = 0;
        String host = initialHost;
        String peerAddress = initialPeerAddress;
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
                    boolean directRecovered = false;
                    if (RoutePerformanceStore.ROUTE_DIRECT.equals(currentRoute)
                            && peerAddress != null && wifiRecovery.available()) {
                        String recoveredHost = wifiRecovery.recover(peerAddress, 12_000);
                        if (recoveredHost != null) {
                            directRecovered = true;
                            host = recoveredHost;
                            senderStore.updateConnection(host, peerAddress, currentRoute, fallbackHost);
                            broadcast("reconnecting",
                                    "Direct link restored — resuming encrypted session",
                                    0, 0, activeManifest.getSessionId());
                        }
                    }
                    if (AdaptiveRouteOrchestrator.shouldSwitchToLan(
                            currentRoute, fallbackHost, directRecovered)) {
                        routeStore.recordFailure(RoutePerformanceStore.ROUTE_DIRECT);
                        host = fallbackHost.trim();
                        peerAddress = null;
                        fallbackHost = null;
                        currentRoute = RoutePerformanceStore.ROUTE_LAN;
                        senderStore.updateConnection(host, null, currentRoute, null);
                        broadcast("route_switched",
                                "Direct link unavailable — continuing from verified checkpoint over same Wi-Fi",
                                0, 0, activeManifest.getSessionId());
                        updateNotification("Switching to same Wi-Fi",
                                "Keeping verified progress and reconnecting securely", 0, true);
                    }
                    if (AdaptiveRouteOrchestrator.shouldRediscoverLan(currentRoute, attempt)) {
                        String recoveredLanHost = lanRecovery.recover(6_000L);
                        String selectedHost = AdaptiveRouteOrchestrator.selectRecoveredLanHost(
                                host, recoveredLanHost);
                        if (!selectedHost.equals(host)) {
                            host = selectedHost;
                            senderStore.updateConnection(host, null, currentRoute, null);
                            broadcast("reconnecting",
                                    "Receiver found again after network change — verifying identity and resuming",
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
            accumulatedDataTransferMs = currentAccumulatedDataMs();
            dataTransferStartedNanos = 0L;
            senderStore.updateElapsedDataMs(accumulatedDataTransferMs);
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
                if (dataTransferStartedNanos == 0L && batchDone > 0L) dataTransferStartedNanos = System.nanoTime();
                latestBatchDone = batchDone;
                latestSpeed = bytesPerSecond;
                int p = percent(batchDone, batchTotal);
                long eta = etaSeconds(batchDone, batchTotal, bytesPerSecond);
                String message = "Sending " + fileName + " • "
                        + formatProgress(batchDone, batchTotal, bytesPerSecond, eta);
                updateNotification("Sending files", message, p, true);
                broadcastProgress(message, p, bytesPerSecond, sessionId,
                        batchDone, batchTotal, eta, fileId, fileName, done, total, entryIndex(fileId));
            }

            @Override public void onFileCompleted(String sessionId, String fileId,
                                                  Uri publishedUri) {
                BatchManifest.Entry entry = findEntry(fileId);
                String completedName = entry == null ? "Sent file" : entry.name;
                broadcastFileDone(sessionId, fileId, completedName, entryIndex(fileId),
                        "Sent and verified ✓");
            }

            @Override public void onFileFailed(String sessionId, String fileId,
                                               String fileName, String reason) {
                broadcastFileFailed(sessionId, fileId, fileName, entryIndex(fileId), reason);
            }

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
                    CHANNEL, getString(R.string.transfer_channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Active encrypted OptiShare local file transfers");
            NotificationChannel completion = new NotificationChannel(
                    COMPLETION_CHANNEL, getString(R.string.transfer_results_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
            completion.setDescription(getString(R.string.transfer_results_channel_description));
            NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
            manager.createNotificationChannel(completion);
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
        AppSettings userSettings = new AppSettings(this);
        boolean completion = !ongoingProgress && title != null
                && (title.toLowerCase(Locale.US).contains("complete")
                || title.toLowerCase(Locale.US).contains("stopped")
                || title.toLowerCase(Locale.US).contains("failed"));
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, completion ? COMPLETION_CHANNEL : CHANNEL)
                .setSmallIcon(R.drawable.ic_optishare)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoingProgress);
        builder.setSilent(ongoingProgress || !userSettings.soundEnabled()
                || (completion && !userSettings.completionSound()));
        if (completion && !userSettings.completionNotifications()) builder.setTimeoutAfter(1L);

        if (ongoingProgress && activeManifest != null && activeItems != null) {
            Intent pause = new Intent(this, TransferService.class).setAction(ACTION_PAUSE);
            PendingIntent pausePending = PendingIntent.getService(this, 2, pause,
                    PendingIntent.FLAG_UPDATE_CURRENT | immutable);
            builder.addAction(0, getString(R.string.pause), pausePending);
        } else if (!ongoingProgress && "Transfer paused".equals(title)
                && senderStore != null && senderStore.exists()) {
            Intent resume = new Intent(this, TransferService.class).setAction(ACTION_RESUME_PENDING);
            PendingIntent resumePending = PendingIntent.getService(this, 3, resume,
                    PendingIntent.FLAG_UPDATE_CURRENT | immutable);
            builder.addAction(0, getString(R.string.resume), resumePending);
        }
        builder.addAction(0, getString(R.string.cancel), stopPending);
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
        long durationMs = dataElapsedMsForSummary();
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
        broadcastProgress(message, progress, speed, session, done, total, etaSeconds, null, null, 0L, 0L, -1);
    }

    private void broadcastProgress(String message, int progress, double speed, String session,
                                   long done, long total, long etaSeconds, String fileId, String fileName,
                                   long fileDone, long fileTotal, int fileIndex) {
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
        if (fileId != null) intent.putExtra(EXTRA_FILE_ID, fileId);
        if (fileName != null) intent.putExtra(EXTRA_FILE_NAME, fileName);
        intent.putExtra(EXTRA_FILE_DONE, fileDone);
        intent.putExtra(EXTRA_FILE_TOTAL, fileTotal);
        intent.putExtra(EXTRA_FILE_INDEX, fileIndex);
        sendBroadcast(intent);
    }

    private void broadcastFileDone(String session, String fileId, String fileName, int fileIndex, String message) {
        Intent intent = new Intent(ACTION_EVENT);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_EVENT, "file_done");
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_SESSION, session);
        intent.putExtra(EXTRA_FILE_ID, fileId);
        intent.putExtra(EXTRA_FILE_NAME, fileName);
        intent.putExtra(EXTRA_FILE_INDEX, fileIndex);
        sendBroadcast(intent);
    }

    private void broadcastFileFailed(String session, String fileId, String fileName,
                                     int fileIndex, String reason) {
        Intent intent = new Intent(ACTION_EVENT);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_EVENT, "file_failed");
        intent.putExtra(EXTRA_MESSAGE, reason == null ? "File failed" : reason);
        intent.putExtra(EXTRA_SESSION, session);
        intent.putExtra(EXTRA_FILE_ID, fileId);
        intent.putExtra(EXTRA_FILE_NAME, fileName);
        intent.putExtra(EXTRA_FILE_INDEX, fileIndex);
        sendBroadcast(intent);
    }

    private int entryIndex(String fileId) {
        if (activeManifest == null || fileId == null) return -1;
        List<BatchManifest.Entry> entries = activeManifest.getEntries();
        for (int i = 0; i < entries.size(); i++) if (fileId.equals(entries.get(i).id)) return i;
        return -1;
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
        accumulatedDataTransferMs = currentAccumulatedDataMs();
        dataTransferStartedNanos = 0L;
        senderStore.updateElapsedDataMs(accumulatedDataTransferMs);
        running.set(false);
        if (stripedActive && activeStripedEngine != null) activeStripedEngine.cancel();
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
        if (activeStripedEngine != null) activeStripedEngine.cancel();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) { }
        try {
            if (benchmarkServerSocket != null) benchmarkServerSocket.close();
        } catch (Exception ignored) { }
        try {
            if (stripedServerSocket != null) stripedServerSocket.close();
        } catch (Exception ignored) { }
        serverSocket = null;
        benchmarkServerSocket = null;
        stripedServerSocket = null;
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
        if (activeStripedEngine != null) activeStripedEngine.cancel();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) { }
        try {
            if (benchmarkServerSocket != null) benchmarkServerSocket.close();
        } catch (Exception ignored) { }
        try {
            if (stripedServerSocket != null) stripedServerSocket.close();
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
        double seconds = Math.max(0.001, dataElapsedMsForSummary() / 1000.0);
        return total <= 0 ? latestSpeed : total / seconds;
    }

    private long currentAccumulatedDataMs() {
        long current = dataTransferStartedNanos == 0L ? 0L
                : Math.max(0L, Math.round((System.nanoTime() - dataTransferStartedNanos) / 1_000_000.0));
        return Math.max(0L, accumulatedDataTransferMs + current);
    }

    private long dataElapsedMsForSummary() {
        long dataMs = currentAccumulatedDataMs();
        if (dataMs > 0L) return dataMs;
        return activeTransferStartedNanos == 0L ? 0L
                : Math.max(0L, Math.round((System.nanoTime() - activeTransferStartedNanos) / 1_000_000.0));
    }

    private static String routeLabel(String route) { return RoutePerformanceStore.ROUTE_LAN.equals(route) ? "same Wi-Fi" : "Wi-Fi Direct"; }

    private String benchmarkSummary(String verb) {
        long total = activeManifest == null ? latestBatchDone : activeManifest.totalBytes();
        double seconds = Math.max(0.001, dataElapsedMsForSummary() / 1000.0);
        double average = averageBytesPerSecond();
        StringBuilder value = new StringBuilder();
        value.append(verb).append(" ").append(formatBytes(total))
                .append(" in ").append(formatElapsed(seconds))
                .append(" • avg ").append(formatSpeed(average));
        if (resumedSession) value.append(" • resumed");
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
