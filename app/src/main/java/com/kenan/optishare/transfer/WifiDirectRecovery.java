package com.kenan.optishare.transfer;

import android.content.Context;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Best-effort recovery for a destroyed Wi-Fi Direct group. */
public final class WifiDirectRecovery {
    public static final class Peer {
        public final String deviceAddress;
        public final String host;
        Peer(String deviceAddress, String host) {
            this.deviceAddress = deviceAddress;
            this.host = host;
        }
    }

    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final Handler main = new Handler(Looper.getMainLooper());

    public WifiDirectRecovery(Context context) {
        manager = (WifiP2pManager) context.getApplicationContext().getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager == null ? null : manager.initialize(context.getApplicationContext(), Looper.getMainLooper(), null);
    }

    public boolean available() { return manager != null && channel != null; }

    /** Captures the current group owner MAC and host IP while the group is still formed. */
    public Peer capture(long timeoutMs) {
        if (!available()) return null;
        AtomicReference<String> address = new AtomicReference<>();
        AtomicReference<String> host = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(2);
        main.post(() -> {
            try {
                manager.requestGroupInfo(channel, group -> {
                    if (group != null && group.getOwner() != null) address.set(group.getOwner().deviceAddress);
                    latch.countDown();
                });
            } catch (SecurityException e) { latch.countDown(); }
            try {
                manager.requestConnectionInfo(channel, info -> {
                    if (info != null && info.groupOwnerAddress != null) host.set(info.groupOwnerAddress.getHostAddress());
                    latch.countDown();
                });
            } catch (SecurityException e) { latch.countDown(); }
        });
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return address.get() == null ? null : new Peer(address.get(), host.get());
    }

    /** Attempts to reconnect to the known receiver and returns the new group-owner IP. */
    public String recover(String deviceAddress, long timeoutMs) {
        if (!available() || deviceAddress == null || deviceAddress.trim().isEmpty()) return null;
        CountDownLatch connectRequest = new CountDownLatch(1);
        AtomicReference<Boolean> requestOk = new AtomicReference<>(Boolean.FALSE);
        main.post(() -> {
            try {
                manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                    @Override public void onSuccess() { connectKnown(); }
                    @Override public void onFailure(int reason) { connectKnown(); }
                    private void connectKnown() {
                        try {
                            WifiP2pConfig config = new WifiP2pConfig();
                            config.deviceAddress = deviceAddress;
                            config.groupOwnerIntent = 0;
                            config.wps.setup = WpsInfo.PBC;
                            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                                @Override public void onSuccess() { requestOk.set(Boolean.TRUE); connectRequest.countDown(); }
                                @Override public void onFailure(int reason) { connectRequest.countDown(); }
                            });
                        } catch (SecurityException e) { connectRequest.countDown(); }
                    }
                });
            } catch (SecurityException e) { connectRequest.countDown(); }
        });
        try { connectRequest.await(Math.min(5000, timeoutMs), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        if (!Boolean.TRUE.equals(requestOk.get())) return null;

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted()) {
            CountDownLatch infoLatch = new CountDownLatch(1);
            AtomicReference<String> host = new AtomicReference<>();
            main.post(() -> {
                try {
                    manager.requestConnectionInfo(channel, info -> {
                        if (info != null && info.groupFormed && info.groupOwnerAddress != null) {
                            host.set(info.groupOwnerAddress.getHostAddress());
                        }
                        infoLatch.countDown();
                    });
                } catch (SecurityException e) { infoLatch.countDown(); }
            });
            try { infoLatch.await(1500, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
            if (host.get() != null) return host.get();
            try { Thread.sleep(400); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
        return null;
    }
}
