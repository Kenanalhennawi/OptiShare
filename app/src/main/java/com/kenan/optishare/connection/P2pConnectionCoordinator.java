package com.kenan.optishare.connection;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Production Wi-Fi Direct coordinator.
 *
 * Primary discovery uses DNS-SD so the sender sees OptiShare receivers rather than arbitrary
 * Wi-Fi Direct devices. Generic peer discovery remains a compatibility fallback for OEMs with
 * incomplete DNS-SD implementations.
 */
public final class P2pConnectionCoordinator {
    public enum State { IDLE, PREPARING, ADVERTISING, SEARCHING, FOUND, CONNECTING, NEGOTIATING, CONNECTED, RETRYING, FAILED, STOPPED }

    public interface Listener {
        void onState(State state, String message, int attempt);
        void onPeers(List<WifiP2pDevice> peers);
        void onConnected(WifiP2pInfo info, String peerName);
        void onThisDevice(WifiP2pDevice device);
    }

    private static final String SERVICE_TYPE = "_optishare._tcp";
    private static final String SERVICE_PREFIX = "OptiShare-";
    private static final long SERVICE_DISCOVERY_INTERVAL_MS = 3200L;
    private static final long FALLBACK_AFTER_MS = 9000L;
    private static final long CONNECT_TIMEOUT_MS = 18000L;
    private static final long RETRY_BASE_MS = 900L;
    private static final int MAX_CONNECT_ATTEMPTS = 4;
    private static final int MAX_DISCOVERY_FAILURES = 8;

    private final Context context;
    private final Listener listener;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, WifiP2pDevice> optiSharePeers = new LinkedHashMap<>();
    private final Map<String, String> advertisedNames = new HashMap<>();

    private WifiP2pDnsSdServiceRequest serviceRequest;
    private boolean registered;
    private boolean stopped = true;
    private boolean receiverMode;
    private boolean fallbackPeerDiscovery;
    private long senderStartedAt;
    private int connectAttempt;
    private int discoveryFailures;
    private String requestedAddress;
    private String requestedName;
    private WifiP2pDevice connectingDevice;
    private String receiverSessionId;

    private final Runnable discoveryLoop = new Runnable() {
        @Override public void run() {
            if (stopped || receiverMode) return;
            long elapsed = System.currentTimeMillis() - senderStartedAt;
            if (elapsed >= FALLBACK_AFTER_MS && optiSharePeers.isEmpty()) {
                fallbackPeerDiscovery = true;
                emit(State.RETRYING, "OptiShare service discovery is slow on this phone. Trying compatibility search…", discoveryFailures);
                discoverGenericPeers();
            } else {
                discoverServices();
            }
            handler.postDelayed(this, SERVICE_DISCOVERY_INTERVAL_MS);
        }
    };

    private final Runnable connectTimeout = () -> {
        if (stopped || connectingDevice == null) return;
        emit(State.RETRYING, "Connection timed out. Retrying automatically…", connectAttempt);
        cancelConnectThenRetry();
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    emit(State.FAILED, "Wi-Fi Direct is unavailable. Turn Wi-Fi on and retry.", connectAttempt);
                }
                return;
            }
            if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                WifiP2pDevice device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if (device != null && listener != null) listener.onThisDevice(device);
                return;
            }
            if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                if (fallbackPeerDiscovery) requestFallbackPeers();
                return;
            }
            if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                NetworkInfo info = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if (info != null && info.isConnected()) {
                    handler.removeCallbacks(connectTimeout);
                    emit(State.NEGOTIATING, "Peer connected. Securing transfer channel…", connectAttempt);
                    requestConnectionInfo();
                } else if (!stopped && !receiverMode && connectingDevice != null) {
                    emit(State.RETRYING, "Direct link dropped. Restoring connection…", connectAttempt);
                } else if (!stopped && receiverMode) {
                    emit(State.ADVERTISING, "Ready to receive. Waiting for a sender…", connectAttempt);
                }
            }
        }
    };

    public P2pConnectionCoordinator(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        this.channel = manager == null ? null : manager.initialize(this.context, Looper.getMainLooper(),
                () -> emit(State.FAILED, "Nearby connection service restarted. Please retry.", connectAttempt));
    }

    public boolean available() {
        return manager != null && channel != null;
    }

    public void startSender() {
        if (!available()) return;
        stopped = false;
        receiverMode = false;
        fallbackPeerDiscovery = false;
        senderStartedAt = System.currentTimeMillis();
        connectAttempt = 0;
        discoveryFailures = 0;
        requestedAddress = null;
        requestedName = null;
        connectingDevice = null;
        optiSharePeers.clear();
        advertisedNames.clear();
        register();
        emitPeers();
        emit(State.PREPARING, "Preparing OptiShare discovery…", 0);
        clearStaleState(() -> configureServiceDiscovery(() -> {
            emit(State.SEARCHING, "Looking for OptiShare receivers nearby…", 0);
            handler.removeCallbacks(discoveryLoop);
            handler.post(discoveryLoop);
        }));
    }

    public void startReceiver() {
        if (!available()) return;
        stopped = false;
        receiverMode = true;
        fallbackPeerDiscovery = false;
        connectAttempt = 0;
        discoveryFailures = 0;
        connectingDevice = null;
        receiverSessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.US);
        register();
        emit(State.PREPARING, "Preparing private receiving session…", 0);
        clearStaleState(() -> advertiseReceiver(this::createReceiverGroup));
    }

    public void connect(WifiP2pDevice device) {
        if (device == null || stopped) return;
        handler.removeCallbacks(discoveryLoop);
        connectingDevice = device;
        requestedAddress = device.deviceAddress;
        String advertised = advertisedNames.get(device.deviceAddress);
        requestedName = advertised == null || advertised.trim().isEmpty() ? safeName(device) : advertised;
        connectAttempt = Math.max(1, connectAttempt + 1);
        emit(State.CONNECTING, "Connecting to " + requestedName + "…", connectAttempt);

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = WpsInfo.PBC;
        config.groupOwnerIntent = 0;

        try {
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    emit(State.CONNECTING, "Creating the private direct link with " + requestedName + "…", connectAttempt);
                    handler.removeCallbacks(connectTimeout);
                    handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
                }

                @Override public void onFailure(int reason) {
                    retryConnect("Connection request failed: " + reasonText(reason));
                }
            });
        } catch (SecurityException e) {
            emit(State.FAILED, "Android blocked nearby-device access. Check OptiShare permissions.", connectAttempt);
        }
    }

    /** Pairing QR identifies an exact receiver; service/generic discovery continues until it appears. */
    public void connectWhenFound(String deviceAddress, String displayName) {
        requestedAddress = deviceAddress;
        requestedName = displayName;
        WifiP2pDevice exact = optiSharePeers.get(deviceAddress);
        if (exact != null) {
            connect(exact);
            return;
        }
        emit(State.SEARCHING, "Receiver identified. Finding its direct radio link…", connectAttempt);
        fallbackPeerDiscovery = true;
        discoverGenericPeers();
        if (!receiverMode) {
            handler.removeCallbacks(discoveryLoop);
            handler.postDelayed(discoveryLoop, SERVICE_DISCOVERY_INTERVAL_MS);
        }
    }

    public List<WifiP2pDevice> currentPeers() {
        return sortedPeers();
    }

    public void stop() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        connectingDevice = null;
        try { manager.stopPeerDiscovery(channel, noOp()); } catch (Exception ignored) { }
        try { manager.cancelConnect(channel, noOp()); } catch (Exception ignored) { }
        clearServiceDiscoveryArtifacts();
        unregister();
        emit(State.STOPPED, "Stopped", connectAttempt);
    }

    public void releaseGroup() {
        try { if (manager != null && channel != null) manager.removeGroup(channel, noOp()); }
        catch (Exception ignored) { }
        clearServiceDiscoveryArtifacts();
    }

    private void advertiseReceiver(Runnable next) {
        if (stopped) return;
        Map<String, String> record = new HashMap<>();
        record.put("app", "OptiShare");
        record.put("version", "3");
        record.put("session", receiverSessionId == null ? "" : receiverSessionId);
        WifiP2pDnsSdServiceInfo service = WifiP2pDnsSdServiceInfo.newInstance(
                SERVICE_PREFIX + (receiverSessionId == null ? "Receiver" : receiverSessionId),
                SERVICE_TYPE,
                record);
        try {
            manager.clearLocalServices(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { addLocalService(service, next); }
                @Override public void onFailure(int reason) { addLocalService(service, next); }
            });
        } catch (SecurityException e) {
            emit(State.FAILED, "Android blocked receiver advertising. Check nearby-device permission.", connectAttempt);
        }
    }

    private void addLocalService(WifiP2pDnsSdServiceInfo service, Runnable next) {
        try {
            manager.addLocalService(channel, service, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    emit(State.ADVERTISING, "OptiShare receiver is visible nearby…", connectAttempt);
                    next.run();
                }
                @Override public void onFailure(int reason) {
                    // Some OEMs have broken DNS-SD but working direct groups. Continue with group setup.
                    emit(State.RETRYING, "Service advertising is limited on this phone. Using compatibility mode…", connectAttempt);
                    next.run();
                }
            });
        } catch (SecurityException e) {
            emit(State.FAILED, "Android blocked receiver advertising. Check nearby-device permission.", connectAttempt);
        }
    }

    private void createReceiverGroup() {
        if (stopped) return;
        emit(State.ADVERTISING, "Creating a private receiver link…", connectAttempt);
        try {
            manager.createGroup(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    emit(State.ADVERTISING, "Ready to receive. Waiting for a sender…", connectAttempt);
                }
                @Override public void onFailure(int reason) {
                    if (reason == WifiP2pManager.BUSY && connectAttempt < MAX_CONNECT_ATTEMPTS) {
                        connectAttempt++;
                        emit(State.RETRYING, "Android Wi-Fi Direct is busy. Retrying receiver setup…", connectAttempt);
                        handler.postDelayed(() -> clearStaleState(() -> advertiseReceiver(P2pConnectionCoordinator.this::createReceiverGroup)),
                                RETRY_BASE_MS * connectAttempt);
                    } else {
                        emit(State.FAILED, "Receiver could not start: " + reasonText(reason), connectAttempt);
                    }
                }
            });
        } catch (SecurityException e) {
            emit(State.FAILED, "Android blocked receiver setup. Check nearby-device permission.", connectAttempt);
        }
    }

    private void configureServiceDiscovery(Runnable next) {
        if (stopped) return;
        manager.setDnsSdResponseListeners(channel,
                (instanceName, registrationType, srcDevice) -> {
                    if (stopped || srcDevice == null || instanceName == null) return;
                    if (!instanceName.startsWith(SERVICE_PREFIX)) return;
                    addOptiSharePeer(srcDevice, cleanAdvertisedName(instanceName));
                },
                (fullDomainName, txtRecordMap, srcDevice) -> {
                    if (stopped || srcDevice == null || txtRecordMap == null) return;
                    String app = txtRecordMap.get("app");
                    if (!"OptiShare".equalsIgnoreCase(app)) return;
                    addOptiSharePeer(srcDevice, safeName(srcDevice));
                });

        serviceRequest = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE);
        try {
            manager.clearServiceRequests(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { addServiceRequest(next); }
                @Override public void onFailure(int reason) { addServiceRequest(next); }
            });
        } catch (SecurityException e) {
            fallbackPeerDiscovery = true;
            emit(State.RETRYING, "OptiShare service discovery is unavailable. Using compatibility search…", discoveryFailures);
            next.run();
        }
    }

    private void addServiceRequest(Runnable next) {
        try {
            manager.addServiceRequest(channel, serviceRequest, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { next.run(); }
                @Override public void onFailure(int reason) {
                    fallbackPeerDiscovery = true;
                    emit(State.RETRYING, "Service discovery could not initialize. Using compatibility search…", discoveryFailures);
                    next.run();
                }
            });
        } catch (SecurityException e) {
            fallbackPeerDiscovery = true;
            next.run();
        }
    }

    private void discoverServices() {
        if (stopped || receiverMode) return;
        try {
            manager.discoverServices(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    discoveryFailures = 0;
                    emit(State.SEARCHING, optiSharePeers.isEmpty()
                            ? "Searching for OptiShare receivers…"
                            : optiSharePeers.size() + " OptiShare receiver" + (optiSharePeers.size() == 1 ? "" : "s") + " found", connectAttempt);
                }
                @Override public void onFailure(int reason) {
                    discoveryFailures++;
                    if (reason == WifiP2pManager.BUSY && discoveryFailures <= MAX_DISCOVERY_FAILURES) {
                        emit(State.RETRYING, "Nearby service scan is busy. Retrying automatically…", discoveryFailures);
                    } else {
                        fallbackPeerDiscovery = true;
                        discoverGenericPeers();
                    }
                }
            });
        } catch (SecurityException e) {
            fallbackPeerDiscovery = true;
            discoverGenericPeers();
        }
    }

    private void addOptiSharePeer(WifiP2pDevice device, String advertisedName) {
        if (device.deviceAddress == null) return;
        optiSharePeers.put(device.deviceAddress, device);
        if (advertisedName != null && !advertisedName.trim().isEmpty()) {
            advertisedNames.put(device.deviceAddress, advertisedName.trim());
        }
        emitPeers();
        emit(State.FOUND, optiSharePeers.size() + " OptiShare receiver" + (optiSharePeers.size() == 1 ? "" : "s") + " found", connectAttempt);
        if (requestedAddress != null && requestedAddress.equalsIgnoreCase(device.deviceAddress)) {
            connect(device);
        }
    }

    private void discoverGenericPeers() {
        if (stopped || receiverMode) return;
        try {
            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { requestFallbackPeers(); }
                @Override public void onFailure(int reason) {
                    discoveryFailures++;
                    if (discoveryFailures > MAX_DISCOVERY_FAILURES) {
                        emit(State.FAILED, "Nearby search could not start. Toggle Wi-Fi and retry.", discoveryFailures);
                    } else {
                        emit(State.RETRYING, "Compatibility search retry " + discoveryFailures + "…", discoveryFailures);
                    }
                }
            });
        } catch (SecurityException e) {
            emit(State.FAILED, "Android blocked nearby-device access. Check OptiShare permissions.", discoveryFailures);
        }
    }

    private void requestFallbackPeers() {
        if (stopped || !fallbackPeerDiscovery) return;
        try {
            manager.requestPeers(channel, list -> {
                if (list == null) return;
                for (WifiP2pDevice peer : list.getDeviceList()) {
                    if (peer == null || peer.deviceAddress == null) continue;
                    // Generic peers are exposed only in compatibility mode. DNS-SD-discovered
                    // OptiShare devices remain preferred and retain their advertised identity.
                    if (!optiSharePeers.containsKey(peer.deviceAddress)) {
                        optiSharePeers.put(peer.deviceAddress, peer);
                    }
                }
                emitPeers();
                if (requestedAddress != null) {
                    WifiP2pDevice target = optiSharePeers.get(requestedAddress);
                    if (target != null) connect(target);
                }
            });
        } catch (SecurityException e) {
            emit(State.FAILED, "Android blocked nearby-device access. Check OptiShare permissions.", discoveryFailures);
        }
    }

    private void emitPeers() {
        if (listener != null) listener.onPeers(sortedPeers());
    }

    private List<WifiP2pDevice> sortedPeers() {
        List<WifiP2pDevice> result = new ArrayList<>(optiSharePeers.values());
        Collections.sort(result, Comparator.comparing(P2pConnectionCoordinator::safeName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private void requestConnectionInfo() {
        try {
            manager.requestConnectionInfo(channel, info -> {
                if (info == null || !info.groupFormed || info.groupOwnerAddress == null) return;
                handler.removeCallbacks(connectTimeout);
                emit(State.CONNECTED, "Peer connection established", connectAttempt);
                String peer = requestedName == null || requestedName.trim().isEmpty() ? "Nearby device" : requestedName;
                if (listener != null) listener.onConnected(info, peer);
            });
        } catch (SecurityException e) {
            emit(State.FAILED, "Android blocked connection details. Check OptiShare permissions.", connectAttempt);
        }
    }

    private void retryConnect(String message) {
        handler.removeCallbacks(connectTimeout);
        if (connectAttempt >= MAX_CONNECT_ATTEMPTS) {
            emit(State.FAILED, message + ". Tap restart search or use QR pairing.", connectAttempt);
            connectingDevice = null;
            return;
        }
        emit(State.RETRYING, message + ". Retrying…", connectAttempt);
        cancelConnectThenRetry();
    }

    private void cancelConnectThenRetry() {
        WifiP2pDevice retry = connectingDevice;
        try {
            manager.cancelConnect(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { scheduleRetry(retry); }
                @Override public void onFailure(int reason) { scheduleRetry(retry); }
            });
        } catch (Exception e) {
            scheduleRetry(retry);
        }
    }

    private void scheduleRetry(WifiP2pDevice retry) {
        handler.postDelayed(() -> {
            if (stopped) return;
            if (retry != null) connect(retry);
            else startSender();
        }, RETRY_BASE_MS * Math.max(1, connectAttempt));
    }

    private void clearStaleState(Runnable next) {
        handler.removeCallbacks(connectTimeout);
        try { manager.cancelConnect(channel, noOp()); } catch (Exception ignored) { }
        try { manager.stopPeerDiscovery(channel, noOp()); } catch (Exception ignored) { }
        clearServiceDiscoveryArtifacts();
        try {
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { handler.postDelayed(next, 400L); }
                @Override public void onFailure(int reason) { handler.postDelayed(next, 400L); }
            });
        } catch (Exception e) {
            handler.postDelayed(next, 400L);
        }
    }

    private void clearServiceDiscoveryArtifacts() {
        if (manager == null || channel == null) return;
        try { manager.clearServiceRequests(channel, noOp()); } catch (Exception ignored) { }
        try { manager.clearLocalServices(channel, noOp()); } catch (Exception ignored) { }
        serviceRequest = null;
    }

    private void register() {
        if (registered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
        registered = true;
    }

    private void unregister() {
        if (!registered) return;
        try { context.unregisterReceiver(receiver); } catch (Exception ignored) { }
        registered = false;
    }

    private void emit(State state, String message, int attempt) {
        if (listener != null) handler.post(() -> listener.onState(state, message, attempt));
    }

    private String cleanAdvertisedName(String instanceName) {
        if (instanceName == null || !instanceName.startsWith(SERVICE_PREFIX)) return null;
        // DNS-SD instance intentionally contains a session token, not user PII. The human device
        // name is still obtained from the Wi-Fi P2P device object when available.
        return null;
    }

    private static String reasonText(int reason) {
        if (reason == WifiP2pManager.BUSY) return "Wi-Fi Direct is busy";
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) return "Wi-Fi Direct is not supported";
        if (reason == WifiP2pManager.ERROR) return "Android Wi-Fi Direct error";
        return "error " + reason;
    }

    private static String safeName(WifiP2pDevice device) {
        return device == null || device.deviceName == null || device.deviceName.trim().isEmpty()
                ? "Android device" : device.deviceName.trim();
    }

    private static WifiP2pManager.ActionListener noOp() {
        return new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { }
            @Override public void onFailure(int reason) { }
        };
    }
}
