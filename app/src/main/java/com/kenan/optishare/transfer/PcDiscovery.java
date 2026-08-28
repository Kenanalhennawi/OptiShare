package com.kenan.optishare.transfer;

import android.content.Context;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Discovers OptiShare Windows Companion receivers on the same IPv4 LAN. */
public final class PcDiscovery {
    public static final int DISCOVERY_PORT = 49891;
    public static final String PROBE = "OPTISHARE_PC_DISCOVER_V1";
    private static final String RESPONSE_PREFIX = "OPTISHARE_PC_V1|";

    public interface Listener {
        void onPc(Peer peer);
        void onStatus(String message);
    }

    public static final class Peer {
        public final String name;
        public final String host;
        public final int port;
        public final String token;

        public Peer(String name, String host, int port, String token) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.token = token;
        }

        public String id() { return host + ":" + port + ":" + token; }
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile DatagramSocket socket;

    public PcDiscovery(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean available() {
        return context.getPackageManager().hasSystemFeature("android.hardware.wifi")
                || context.getPackageManager().hasSystemFeature("android.hardware.ethernet");
    }

    public void discover(Listener listener) {
        stop();
        if (!available()) {
            if (listener != null) listener.onStatus("PC discovery unavailable on this device");
            return;
        }
        running.set(true);
        executor.execute(() -> runDiscovery(listener));
    }

    public void stop() {
        running.set(false);
        DatagramSocket current = socket;
        if (current != null) current.close();
        socket = null;
    }

    private void runDiscovery(Listener listener) {
        Set<String> seen = new HashSet<>();
        try (DatagramSocket ds = new DatagramSocket()) {
            socket = ds;
            ds.setBroadcast(true);
            ds.setSoTimeout(550);
            byte[] probe = PROBE.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(probe, probe.length,
                    InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);

            for (int round = 0; running.get() && round < 8; round++) {
                try { ds.send(packet); }
                catch (Exception ignored) { }

                long deadline = System.currentTimeMillis() + 500L;
                while (running.get() && System.currentTimeMillis() < deadline) {
                    byte[] buffer = new byte[2048];
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    try {
                        ds.receive(response);
                    } catch (SocketTimeoutException timeout) {
                        break;
                    }
                    String text = new String(response.getData(), response.getOffset(),
                            response.getLength(), StandardCharsets.UTF_8).trim();
                    Peer peer = parse(text, response.getAddress());
                    if (peer != null && seen.add(peer.id()) && listener != null) {
                        listener.onPc(peer);
                    }
                }
                if (running.get()) {
                    try { Thread.sleep(500L); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (running.get() && seen.isEmpty() && listener != null) {
                listener.onStatus("No OptiShare PC receiver found on this Wi-Fi yet");
            }
        } catch (Exception error) {
            if (running.get() && listener != null) {
                listener.onStatus("PC discovery: " + safe(error));
            }
        } finally {
            socket = null;
            running.set(false);
        }
    }

    static Peer parse(String text, InetAddress source) {
        if (text == null || !text.startsWith(RESPONSE_PREFIX) || source == null) return null;
        String[] parts = text.split("\\|", 5);
        if (parts.length != 5) return null;
        String name = clean(parts[1], "Windows PC");
        int port;
        try { port = Integer.parseInt(parts[2]); }
        catch (NumberFormatException invalid) { return null; }
        if (port < 1024 || port > 65535) return null;
        String token = parts[3].trim();
        String version = parts[4].trim();
        if (token.length() < 16 || token.length() > 256 || !"1".equals(version)) return null;
        return new Peer(name, source.getHostAddress(), port, token);
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String cleaned = value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        if (cleaned.isEmpty()) return fallback;
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    private static String safe(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? (error == null ? "unknown error" : error.getClass().getSimpleName())
                : message;
    }
}
