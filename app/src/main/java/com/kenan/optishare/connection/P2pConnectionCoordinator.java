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
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Resilient Wi-Fi Direct coordinator used by OptiShare v3. */
public final class P2pConnectionCoordinator {
    public enum State { IDLE, PREPARING, ADVERTISING, SEARCHING, FOUND, CONNECTING, NEGOTIATING, CONNECTED, RETRYING, FAILED, STOPPED }
    public interface Listener {
        void onState(State state, String message, int attempt);
        void onPeers(List<WifiP2pDevice> peers);
        void onConnected(WifiP2pInfo info, String peerName);
        void onThisDevice(WifiP2pDevice device);
    }

    private static final long DISCOVERY_INTERVAL_MS = 2600L;
    private static final long CONNECT_TIMEOUT_MS = 18000L;
    private static final long RETRY_BASE_MS = 900L;
    private static final int MAX_CONNECT_ATTEMPTS = 4;
    private static final int MAX_DISCOVERY_FAILURES = 8;

    private final Context context;
    private final Listener listener;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<WifiP2pDevice> peers = new ArrayList<>();
    private boolean registered;
    private boolean stopped = true;
    private boolean receiverMode;
    private int connectAttempt;
    private int discoveryFailures;
    private String requestedAddress;
    private String requestedName;
    private WifiP2pDevice connectingDevice;

    private final Runnable discoveryLoop = new Runnable() {
        @Override public void run() {
            if (stopped || receiverMode) return;
            discoverNow();
            handler.postDelayed(this, DISCOVERY_INTERVAL_MS);
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
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) emit(State.FAILED, "Wi-Fi Direct is unavailable. Turn Wi-Fi on and retry.", connectAttempt);
                return;
            }
            if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                WifiP2pDevice d = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if (d != null && listener != null) listener.onThisDevice(d);
                return;
            }
            if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) { requestPeers(); return; }
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

    public boolean available() { return manager != null && channel != null; }

    public void startSender() {
        stopped = false; receiverMode = false; connectAttempt = 0; discoveryFailures = 0;
        requestedAddress = null; requestedName = null; connectingDevice = null;
        register(); emit(State.PREPARING, "Preparing nearby connection…", 0);
        clearStaleState(() -> {
            emit(State.SEARCHING, "Looking for nearby OptiShare receivers…", 0);
            handler.removeCallbacks(discoveryLoop); handler.post(discoveryLoop);
        });
    }

    public void startReceiver() {
        stopped = false; receiverMode = true; connectAttempt = 0; discoveryFailures = 0; connectingDevice = null;
        register(); emit(State.PREPARING, "Preparing private receiving session…", 0);
        clearStaleState(this::createReceiverGroup);
    }

    public void connect(WifiP2pDevice device) {
        if (device == null || stopped) return;
        handler.removeCallbacks(discoveryLoop);
        connectingDevice = device; requestedAddress = device.deviceAddress; requestedName = safeName(device);
        connectAttempt = Math.max(1, connectAttempt + 1);
        emit(State.CONNECTING, "Connecting to " + requestedName + "…", connectAttempt);
        WifiP2pConfig config = new WifiP2pConfig(); config.deviceAddress = device.deviceAddress; config.wps.setup = WpsInfo.PBC; config.groupOwnerIntent = 0;
        try {
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    emit(State.CONNECTING, "Waiting for " + requestedName + " to accept the direct link…", connectAttempt);
                    handler.removeCallbacks(connectTimeout); handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
                }
                @Override public void onFailure(int reason) { retryConnect("Connection request failed: " + reasonText(reason)); }
            });
        } catch (SecurityException e) { emit(State.FAILED, "Nearby permission was blocked by Android.", connectAttempt); }
    }

    public void connectWhenFound(String deviceAddress, String displayName) {
        requestedAddress = deviceAddress; requestedName = displayName;
        for (WifiP2pDevice peer : new ArrayList<>(peers)) if (matchesRequested(peer)) { connect(peer); return; }
        emit(State.SEARCHING, "Receiver identified. Finding its direct link…", connectAttempt);
        if (!receiverMode) { handler.removeCallbacks(discoveryLoop); handler.post(discoveryLoop); }
    }

    public List<WifiP2pDevice> currentPeers() { return new ArrayList<>(peers); }

    public void stop() {
        stopped = true; handler.removeCallbacksAndMessages(null); connectingDevice = null;
        try { if (manager != null && channel != null) manager.stopPeerDiscovery(channel, noOp()); } catch (Exception ignored) { }
        try { if (manager != null && channel != null) manager.cancelConnect(channel, noOp()); } catch (Exception ignored) { }
        unregister(); emit(State.STOPPED, "Stopped", connectAttempt);
    }

    public void releaseGroup() { try { if (manager != null && channel != null) manager.removeGroup(channel, noOp()); } catch (Exception ignored) { } }

    private void createReceiverGroup() {
        if (stopped) return;
        emit(State.ADVERTISING, "Making this phone visible to nearby senders…", connectAttempt);
        try {
            manager.createGroup(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    // Group ownership means the receiver is READY, not CONNECTED. Wait for
                    // WIFI_P2P_CONNECTION_CHANGED_ACTION before reporting a peer connection.
                    emit(State.ADVERTISING, "Ready to receive. Waiting for a sender…", connectAttempt);
                }
                @Override public void onFailure(int reason) {
                    if (reason == WifiP2pManager.BUSY && connectAttempt < MAX_CONNECT_ATTEMPTS) {
                        connectAttempt++; emit(State.RETRYING, "Android Wi-Fi Direct is busy. Retrying receiver setup…", connectAttempt);
                        handler.postDelayed(() -> clearStaleState(P2pConnectionCoordinator.this::createReceiverGroup), RETRY_BASE_MS * connectAttempt);
                    } else emit(State.FAILED, "Receiver could not start: " + reasonText(reason), connectAttempt);
                }
            });
        } catch (SecurityException e) { emit(State.FAILED, "Nearby permission was blocked by Android.", connectAttempt); }
    }

    private void discoverNow() {
        if (stopped || receiverMode) return;
        try {
            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { discoveryFailures = 0; requestPeers(); }
                @Override public void onFailure(int reason) {
                    discoveryFailures++;
                    if (reason == WifiP2pManager.BUSY && discoveryFailures <= MAX_DISCOVERY_FAILURES) emit(State.RETRYING, "Nearby radio is busy. Retrying search automatically…", discoveryFailures);
                    else if (discoveryFailures > MAX_DISCOVERY_FAILURES) emit(State.FAILED, "Nearby search did not start. Toggle Wi-Fi and retry.", discoveryFailures);
                    else emit(State.SEARCHING, "Searching… " + reasonText(reason), discoveryFailures);
                }
            });
        } catch (SecurityException e) { emit(State.FAILED, "Nearby permission was blocked by Android.", discoveryFailures); }
    }

    private void requestPeers() {
        if (stopped) return;
        try {
            manager.requestPeers(channel, list -> {
                peers.clear(); if (list != null) peers.addAll(list.getDeviceList());
                Collections.sort(peers, Comparator.comparing(P2pConnectionCoordinator::safeName, String.CASE_INSENSITIVE_ORDER));
                if (listener != null) listener.onPeers(new ArrayList<>(peers));
                if (!peers.isEmpty()) emit(State.FOUND, peers.size() + " nearby device" + (peers.size() == 1 ? "" : "s") + " found", connectAttempt);
                if (requestedAddress != null) for (WifiP2pDevice peer : peers) if (matchesRequested(peer)) { connect(peer); break; }
            });
        } catch (SecurityException e) { emit(State.FAILED, "Nearby permission was blocked by Android.", discoveryFailures); }
    }

    private void requestConnectionInfo() {
        try {
            manager.requestConnectionInfo(channel, info -> {
                if (info == null || !info.groupFormed || info.groupOwnerAddress == null) return;
                handler.removeCallbacks(connectTimeout); emit(State.CONNECTED, "Peer connection established", connectAttempt);
                String peer = requestedName == null ? "Nearby device" : requestedName;
                if (listener != null) listener.onConnected(info, peer);
            });
        } catch (SecurityException e) { emit(State.FAILED, "Nearby permission was blocked by Android.", connectAttempt); }
    }

    private void retryConnect(String message) {
        handler.removeCallbacks(connectTimeout);
        if (connectAttempt >= MAX_CONNECT_ATTEMPTS) { emit(State.FAILED, message + ". Tap retry or use QR pairing.", connectAttempt); connectingDevice = null; return; }
        emit(State.RETRYING, message + ". Retrying…", connectAttempt); cancelConnectThenRetry();
    }

    private void cancelConnectThenRetry() {
        WifiP2pDevice retry = connectingDevice;
        try { manager.cancelConnect(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { scheduleRetry(retry); }
            @Override public void onFailure(int reason) { scheduleRetry(retry); }
        }); } catch (Exception e) { scheduleRetry(retry); }
    }

    private void scheduleRetry(WifiP2pDevice retry) {
        handler.postDelayed(() -> { if (stopped) return; if (retry != null) connect(retry); else startSender(); }, RETRY_BASE_MS * Math.max(1, connectAttempt));
    }

    private void clearStaleState(Runnable next) {
        handler.removeCallbacks(connectTimeout);
        try { manager.cancelConnect(channel, noOp()); } catch (Exception ignored) { }
        try { manager.stopPeerDiscovery(channel, noOp()); } catch (Exception ignored) { }
        try { manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { handler.postDelayed(next, 350L); }
            @Override public void onFailure(int reason) { handler.postDelayed(next, 350L); }
        }); } catch (Exception e) { handler.postDelayed(next, 350L); }
    }

    private boolean matchesRequested(WifiP2pDevice peer) { return requestedAddress != null && peer != null && peer.deviceAddress != null && requestedAddress.equalsIgnoreCase(peer.deviceAddress); }

    private void register() {
        if (registered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        ContextCompat.registerReceiver(context, receiver, f, ContextCompat.RECEIVER_EXPORTED);
        registered = true;
    }

    private void unregister() { if (!registered) return; try { context.unregisterReceiver(receiver); } catch (Exception ignored) { } registered = false; }
    private void emit(State state, String message, int attempt) { if (listener != null) handler.post(() -> listener.onState(state, message, attempt)); }
    private static String reasonText(int reason) { if (reason == WifiP2pManager.BUSY) return "Wi-Fi Direct is busy"; if (reason == WifiP2pManager.P2P_UNSUPPORTED) return "Wi-Fi Direct is not supported"; if (reason == WifiP2pManager.ERROR) return "Android Wi-Fi Direct error"; return "error " + reason; }
    private static String safeName(WifiP2pDevice device) { return device == null || device.deviceName == null || device.deviceName.trim().isEmpty() ? "Android device" : device.deviceName.trim(); }
    private static WifiP2pManager.ActionListener noOp() { return new WifiP2pManager.ActionListener() { @Override public void onSuccess() { } @Override public void onFailure(int reason) { } }; }
}
