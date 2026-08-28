package com.kenan.optishare.transfer;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;

import androidx.annotation.Nullable;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local-network discovery fallback for OptiShare.
 *
 * NSD/mDNS is only used to discover a real remote OptiShare receiver on the shared LAN. Addresses
 * belonging to this device, loopback/link-local addresses, and Android's common Wi-Fi Direct
 * 192.168.49.0/24 range are rejected so a stale P2P group can never be mislabeled as "same Wi-Fi".
 */
public final class LanDiscovery {
    public static final String SERVICE_TYPE = "_optishare._tcp.";

    public interface Listener {
        void onPeer(String name, String host);
        void onStatus(String message);
    }

    private final Context context;
    private final NsdManager nsd;
    private final AtomicBoolean advertising = new AtomicBoolean(false);
    private final AtomicBoolean discovering = new AtomicBoolean(false);
    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;
    private WifiManager.MulticastLock multicastLock;

    public LanDiscovery(Context context) {
        this.context = context.getApplicationContext();
        this.nsd = (NsdManager) this.context.getSystemService(Context.NSD_SERVICE);
    }

    public boolean available() {
        return nsd != null;
    }

    public void advertise(String deviceName, int port, @Nullable Listener listener) {
        if (nsd == null || port <= 0 || !advertising.compareAndSet(false, true)) return;
        acquireMulticastLock();
        NsdServiceInfo service = new NsdServiceInfo();
        service.setServiceName(safeServiceName(deviceName));
        service.setServiceType(SERVICE_TYPE);
        service.setPort(port);
        registrationListener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo info) {
                if (listener != null) listener.onStatus("Same-Wi-Fi receiver ready");
            }
            @Override public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
                advertising.set(false);
                releaseMulticastLockIfIdle();
                if (listener != null) listener.onStatus("LAN advertise unavailable (" + errorCode + ")");
            }
            @Override public void onServiceUnregistered(NsdServiceInfo info) { }
            @Override public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) { }
        };
        try {
            nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        } catch (RuntimeException error) {
            advertising.set(false);
            registrationListener = null;
            releaseMulticastLockIfIdle();
            if (listener != null) listener.onStatus("LAN advertise unavailable");
        }
    }

    public void stopAdvertising() {
        NsdManager.RegistrationListener listener = registrationListener;
        registrationListener = null;
        if (nsd != null && listener != null && advertising.getAndSet(false)) {
            try { nsd.unregisterService(listener); } catch (RuntimeException ignored) { }
        } else {
            advertising.set(false);
        }
        releaseMulticastLockIfIdle();
    }

    public void discover(Listener listener) {
        if (nsd == null || listener == null || !discovering.compareAndSet(false, true)) return;
        acquireMulticastLock();
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) {
                listener.onStatus("Also checking the same Wi-Fi network…");
            }

            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (!SERVICE_TYPE.equals(serviceInfo.getServiceType())) return;
                try {
                    nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) { }
                        @Override public void onServiceResolved(NsdServiceInfo info) {
                            if (!discovering.get() || info.getHost() == null || info.getPort() != TransferService.PORT) return;
                            String host = info.getHost().getHostAddress();
                            if (!isUsableRemoteLanHost(host)) {
                                listener.onStatus("Ignored a stale/self network route; still searching…");
                                return;
                            }
                            listener.onPeer(displayName(info.getServiceName()), host);
                        }
                    });
                } catch (RuntimeException ignored) { }
            }

            @Override public void onServiceLost(NsdServiceInfo serviceInfo) { }
            @Override public void onDiscoveryStopped(String serviceType) { }
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                discovering.set(false);
                releaseMulticastLockIfIdle();
                listener.onStatus("Same-Wi-Fi discovery unavailable (" + errorCode + ")");
            }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) { }
        };
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (RuntimeException error) {
            discovering.set(false);
            discoveryListener = null;
            releaseMulticastLockIfIdle();
            listener.onStatus("Same-Wi-Fi discovery unavailable");
        }
    }

    public void stopDiscovery() {
        NsdManager.DiscoveryListener listener = discoveryListener;
        discoveryListener = null;
        if (nsd != null && listener != null && discovering.getAndSet(false)) {
            try { nsd.stopServiceDiscovery(listener); } catch (RuntimeException ignored) { }
        } else {
            discovering.set(false);
        }
        releaseMulticastLockIfIdle();
    }

    public void close() {
        stopDiscovery();
        stopAdvertising();
    }

    private boolean isUsableRemoteLanHost(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        String host = value.trim();
        if (host.startsWith("192.168.49.")) return false;
        try {
            InetAddress candidate = InetAddress.getByName(host);
            if (candidate.isAnyLocalAddress() || candidate.isLoopbackAddress()
                    || candidate.isLinkLocalAddress() || candidate.isMulticastAddress()) return false;
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress local = addresses.nextElement();
                    if (candidate.equals(local)) return false;
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void acquireMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) return;
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) return;
        try {
            multicastLock = wifi.createMulticastLock("OptiShare-mDNS");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (RuntimeException ignored) {
            multicastLock = null;
        }
    }

    private void releaseMulticastLockIfIdle() {
        if (advertising.get() || discovering.get()) return;
        WifiManager.MulticastLock lock = multicastLock;
        multicastLock = null;
        if (lock != null && lock.isHeld()) {
            try { lock.release(); } catch (RuntimeException ignored) { }
        }
    }

    static String safeServiceName(String value) {
        String clean = value == null ? "Android" : value.trim();
        if (clean.isEmpty()) clean = "Android";
        clean = clean.replaceAll("[^A-Za-z0-9 _.-]", "");
        if (clean.isEmpty()) clean = "Android";
        if (clean.length() > 36) clean = clean.substring(0, 36);
        return "OptiShare-" + clean;
    }

    private static String displayName(String serviceName) {
        if (serviceName == null || serviceName.trim().isEmpty()) return "OptiShare device";
        String clean = serviceName.trim();
        if (clean.toLowerCase(Locale.US).startsWith("optishare-")) clean = clean.substring(10);
        return clean.isEmpty() ? "OptiShare device" : clean;
    }
}
