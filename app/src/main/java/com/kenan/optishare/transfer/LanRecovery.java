package com.kenan.optishare.transfer;

import android.content.Context;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Rediscovers a receiver after Wi-Fi reconnect/DHCP changes. Crypto revalidates identity later. */
public final class LanRecovery {
    private final Context context;

    public LanRecovery(Context context) {
        this.context = context.getApplicationContext();
    }

    public String recover(long timeoutMs) {
        if (timeoutMs <= 0L) return null;
        LanDiscovery discovery = new LanDiscovery(context);
        if (!discovery.available()) return null;
        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch found = new CountDownLatch(1);
        discovery.discover(new LanDiscovery.Listener() {
            @Override public void onPeer(String name, String host) {
                if (host != null && result.compareAndSet(null, host.trim())) found.countDown();
            }
            @Override public void onStatus(String message) { }
        });
        try {
            found.await(Math.min(15_000L, timeoutMs), TimeUnit.MILLISECONDS);
            return result.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            discovery.close();
        }
    }
}
