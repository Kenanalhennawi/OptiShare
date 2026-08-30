package com.kenan.optishare.transfer;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.kenan.optishare.R;
import com.kenan.optishare.V2Activity;
import com.kenan.optishare.storage.DownloadStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;

/** Local-browser receiver. This route is local HTTP + one-time session token, not app E2E crypto. */
public final class BrowserReceiveService extends Service {
    public static final String ACTION_START = "com.kenan.optishare.browser.START";
    public static final String ACTION_STOP = "com.kenan.optishare.browser.STOP";
    public static final String ACTION_EVENT = "com.kenan.optishare.BROWSER_EVENT";
    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_PROGRESS = "progress";
    public static final int PORT = 49889;
    public static final int DISCOVERY_PORT = 49894;
    private static final String DISCOVERY_REQUEST = "OPTISHARE_ANDROID_DISCOVER_V1";

    private static final String CHANNEL = "optishare_browser_receive";
    private static final int NOTIFICATION_ID = 2240;
    private static final long TOKEN_LIFETIME_MS = 15L * 60L * 1000L;
    private static final long APPROVAL_TIMEOUT_MS = 90_000L;
    private static final long MAX_FILE_BYTES = 4L * 1024L * 1024L * 1024L;

    private final AtomicBoolean active = new AtomicBoolean(false);
    private BrowserServer server;
    private String token;
    private long expiresAt;
    private DownloadStore downloadStore;
    private DatagramSocket discoverySocket;
    private Thread discoveryThread;
    private SecurePcReceiveServer securePcServer;

    @Override public void onCreate() {
        super.onCreate();
        downloadStore = new DownloadStore(this);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopBrowser();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) startBrowser();
        return START_STICKY;
    }

    private synchronized void startBrowser() {
        if (active.get()) {
            broadcastReady();
            return;
        }
        token = randomToken();
        expiresAt = System.currentTimeMillis() + TOKEN_LIFETIME_MS;
        startForeground(NOTIFICATION_ID, notification("Browser receive ready",
                "Local same-Wi-Fi upload • approval required on this phone"));
        try {
            server = new BrowserServer();
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            securePcServer = new SecurePcReceiveServer(this, downloadStore, this::ensureActiveToken,
                    (event, message, progress) -> broadcast(event, message, null, progress));
            securePcServer.start();
            active.set(true);
            startDiscoveryResponder();
            broadcastReady();
        } catch (Exception error) {
            broadcast("error", "Browser receiver could not start: " + safe(error), null, 0);
            stopBrowser();
        }
    }

    private synchronized void stopBrowser() {
        active.set(false);
        stopDiscoveryResponder();
        IncomingApproval.cancel();
        if (server != null) {
            try { server.stop(); } catch (Exception ignored) { }
            server = null;
        }
        if (securePcServer != null) { securePcServer.stop(); securePcServer = null; }
        token = null;
        expiresAt = 0L;
        broadcast("stopped", "Browser receiver stopped", null, 0);
        stopForeground(true);
        stopSelf();
    }

    private synchronized void startDiscoveryResponder() throws SocketException {
        stopDiscoveryResponder();
        discoverySocket = new DatagramSocket(DISCOVERY_PORT);
        discoverySocket.setBroadcast(true);
        discoverySocket.setSoTimeout(500);
        discoveryThread = new Thread(() -> {
            byte[] buffer = new byte[512];
            while (active.get() && discoverySocket != null && !discoverySocket.isClosed()) {
                try {
                    DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                    discoverySocket.receive(request);
                    String value = new String(request.getData(), request.getOffset(),
                            request.getLength(), StandardCharsets.UTF_8).trim();
                    if (!DISCOVERY_REQUEST.equals(value)) continue;
                    String activeToken = ensureActiveToken();
                    String device = android.os.Build.MODEL == null ? "Android"
                            : android.os.Build.MODEL.replace('|', '-');
                    String response = "OPTISHARE_ANDROID_V1|" + device + "|" + PORT
                            + "|" + activeToken + "|2|" + SecurePcReceiveServer.PORT;
                    byte[] payload = response.getBytes(StandardCharsets.UTF_8);
                    discoverySocket.send(new DatagramPacket(payload, payload.length,
                            request.getAddress(), request.getPort()));
                } catch (java.net.SocketTimeoutException ignored) {
                } catch (Exception error) {
                    if (active.get()) broadcast("error",
                            "Android discovery stopped: " + safe(error), null, 0);
                    break;
                }
            }
        }, "OptiShare-Android-Discovery");
        discoveryThread.setDaemon(true);
        discoveryThread.start();
    }

    private synchronized void stopDiscoveryResponder() {
        DatagramSocket socket = discoverySocket;
        discoverySocket = null;
        if (socket != null) socket.close();
        Thread thread = discoveryThread;
        discoveryThread = null;
        if (thread != null) thread.interrupt();
    }

    private synchronized String ensureActiveToken() {
        if (token == null || System.currentTimeMillis() > expiresAt) {
            token = randomToken();
            expiresAt = System.currentTimeMillis() + TOKEN_LIFETIME_MS;
        }
        return token;
    }

    private void broadcastReady() {
        String ip = localIpv4();
        if (ip == null) {
            broadcast("error", "Connect this phone to Wi-Fi or a local hotspot first", null, 0);
            return;
        }
        // A URL fragment is not sent in the initial HTTP request or stored in server access logs.
        String url = "http://" + ip + ":" + PORT + "/#token=" + token;
        broadcast("ready", "Open this address on a device connected to the same local network", url, 0);
    }

    private final class BrowserServer extends NanoHTTPD {
        BrowserServer() { super(PORT); }

        @Override public Response serve(IHTTPSession session) {
            try {
                if (Method.GET.equals(session.getMethod()) && "/".equals(session.getUri())) return page();
                if (!BrowserRequestPolicy.allowedOrigin(session.getHeaders())) {
                    return text(Response.Status.FORBIDDEN, "Cross-origin request rejected");
                }
                String supplied = queryToken(session);
                if (!validToken(supplied)) return text(Response.Status.UNAUTHORIZED,
                        "This OptiShare browser link is invalid or expired.");
                if (!Method.POST.equals(session.getMethod())) return text(Response.Status.NOT_FOUND, "Not found");
                if ("/clipboard".equals(session.getUri())) return receiveClipboard(session, supplied);
                if ("/benchmark".equals(session.getUri())) return receiveBenchmark(session, supplied);
                if ("/upload".equals(session.getUri())) return receiveUpload(session, supplied);
                return text(Response.Status.NOT_FOUND, "Not found");
            } catch (Exception error) {
                broadcast("error", safe(error), null, 0);
                return text(Response.Status.INTERNAL_ERROR, "Upload failed: " + html(safe(error)));
            }
        }
    }

    private Response receiveClipboard(NanoHTTPD.IHTTPSession session, String supplied) throws Exception {
        Map<String, String> headers = session.getHeaders();
        long length = parseLength(headers.get("content-length"));
        if (length <= 0 || length > 256L * 1024L) return text(Response.Status.BAD_REQUEST, "Clipboard text must be 1 B to 256 KB");
        String approvalKey = "browser-clipboard:" + supplied + ":" + System.nanoTime();
        IncomingApproval.begin(approvalKey, "Clipboard from Windows",
                "Copy " + human(length) + " of text into this phone's clipboard?\nAccept only if you started this transfer.");
        if (!IncomingApproval.await(approvalKey, APPROVAL_TIMEOUT_MS)) return text(Response.Status.FORBIDDEN, "Clipboard transfer declined on the phone");
        if (!validToken(supplied)) return text(Response.Status.UNAUTHORIZED, "Session expired");
        byte[] data = new byte[(int) length];
        int done = 0;
        try (InputStream in = session.getInputStream()) {
            while (done < data.length) {
                int n = in.read(data, done, data.length - done);
                if (n < 0) break;
                if (n == 0) continue;
                done += n;
            }
        }
        if (done != data.length) throw new IllegalStateException("Clipboard connection ended early");
        String value = new String(data, StandardCharsets.UTF_8);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) throw new IllegalStateException("Android clipboard service unavailable");
        clipboard.setPrimaryClip(ClipData.newPlainText("OptiShare clipboard", value));
        broadcast("clipboard", "Windows clipboard copied to this phone ✓", null, 100);
        return text(Response.Status.OK, "Clipboard copied successfully");
    }

    private Response receiveBenchmark(NanoHTTPD.IHTTPSession session, String supplied) throws Exception {
        long length = parseLength(session.getHeaders().get("content-length"));
        if (length < 1024L || length > 32L * 1024L * 1024L) return text(Response.Status.BAD_REQUEST, "Benchmark payload must be 1 KB to 32 MB");
        long started = System.nanoTime();
        long done = 0L;
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream in = session.getInputStream()) {
            while (done < length) {
                int n = in.read(buffer, 0, (int)Math.min(buffer.length, length - done));
                if (n < 0) break;
                if (n == 0) continue;
                done += n;
            }
        }
        if (done != length) throw new IllegalStateException("Benchmark connection ended early");
        long elapsedMs = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
        double speed = done / Math.max(0.001d, elapsedMs / 1000d);
        broadcast("benchmark", "LAN benchmark • " + human((long)speed) + "/s", null, 100);
        return text(Response.Status.OK, "bytes=" + done + ";elapsed_ms=" + elapsedMs + ";bytes_per_second=" + Math.round(speed));
    }

    private Response receiveUpload(NanoHTTPD.IHTTPSession session, String supplied) throws Exception {
        Map<String, String> headers = session.getHeaders();
        long length = parseLength(headers.get("content-length"));
        if (length <= 0) return text(Response.Status.BAD_REQUEST, "Missing file data");
        if (length > MAX_FILE_BYTES) return text(Response.Status.BAD_REQUEST,
                "Maximum browser upload is 4 GB per file");
        downloadStore.ensureCapacity(length);

        String encodedName = headers.get("x-optishare-name");
        String name = encodedName == null ? "browser-upload.bin"
                : URLDecoder.decode(encodedName, StandardCharsets.UTF_8.name());
        name = com.kenan.optishare.model.TransferItem.safeName(name);
        String mime = headers.get("content-type");
        if (mime == null || mime.trim().isEmpty()) mime = "application/octet-stream";
        int semicolon = mime.indexOf(';');
        if (semicolon > 0) mime = mime.substring(0, semicolon).trim();

        String approvalKey = "browser:" + supplied + ":" + System.nanoTime();
        IncomingApproval.begin(approvalKey, "Browser upload request",
                name + " • " + human(length)
                        + "\nLocal browser mode uses same-network HTTP, not OptiShare app-to-app E2E encryption."
                        + "\nAccept only if you started this browser transfer.");
        if (!IncomingApproval.await(approvalKey, APPROVAL_TIMEOUT_MS)) {
            return text(Response.Status.FORBIDDEN, "Upload declined on the phone");
        }
        if (!validToken(supplied)) return text(Response.Status.UNAUTHORIZED, "Session expired");

        File dir = new File(getCacheDir(), "browser_inbox");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create upload cache");
        File temp = File.createTempFile("incoming-", ".part", dir);
        long done = 0L;
        long lastReport = 0L;
        try (InputStream in = session.getInputStream();
             FileOutputStream out = new FileOutputStream(temp, false)) {
            byte[] buffer = new byte[1024 * 1024];
            while (done < length) {
                int want = (int) Math.min(buffer.length, length - done);
                int n = in.read(buffer, 0, want);
                if (n < 0) break;
                if (n == 0) continue;
                out.write(buffer, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastReport >= 250 || done == length) {
                    int progress = (int) Math.min(100L, done * 100L / Math.max(1L, length));
                    broadcast("progress", "Receiving " + name + " • " + human(done)
                            + " / " + human(length), null, progress);
                    lastReport = now;
                }
            }
            out.getFD().sync();
        }
        if (done != length) {
            temp.delete();
            throw new IllegalStateException("Browser connection ended before the full file arrived");
        }
        Uri published;
        try {
            published = downloadStore.publishBrowserFile(temp, name, mime);
        } finally {
            if (temp.exists()) temp.delete();
        }
        broadcast("completed", "Saved " + name + " to Download/OptiShare/Browser", null, 100);
        return text(Response.Status.OK, published == null ? "Saved" : "Saved successfully");
    }

    private Response page() {
        String body = "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>OptiShare Browser Receive</title><style>body{font-family:system-ui;background:#071a32;color:#fff;max-width:720px;margin:auto;padding:28px}"
                + ".card{background:#102d4d;border-radius:20px;padding:24px}button{background:#238fff;color:white;border:0;padding:14px 20px;border-radius:12px;font-weight:700}"
                + "input{display:block;margin:18px 0}small{color:#9ec5e3}.item{margin-top:12px;color:#bfe3ff}</style></head><body><div class='card'>"
                + "<h1>OptiShare</h1><p>Send files to this phone over the local network.</p>"
                + "<small>The phone must approve every file. This browser route is local HTTP and is not the encrypted app-to-app mode.</small>"
                + "<input id='files' type='file' multiple><button onclick='send()'>Send selected files</button><div id='status'></div>"
                + "</div><script>const token=new URLSearchParams(location.hash.slice(1)).get('token')||'';history.replaceState(null,'',location.pathname);"
                + "async function send(){if(!token){document.getElementById('status').textContent='Invalid or expired receive link';return;}const fs=document.getElementById('files').files;"
                + "for(const f of fs){const s=document.getElementById('status');s.innerHTML+='<div class=item>Waiting for phone approval: '+esc(f.name)+'</div>';"
                + "try{const r=await fetch('/upload?token='+encodeURIComponent(token),{method:'POST',headers:{'Content-Type':f.type||'application/octet-stream','X-OptiShare-Name':encodeURIComponent(f.name)},body:f});"
                + "const t=await r.text();s.innerHTML+='<div class=item>'+esc(f.name)+': '+esc(t)+'</div>';}catch(e){s.innerHTML+='<div class=item>'+esc(f.name)+': failed</div>';}}}"
                + "function esc(v){return String(v).replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]));}</script></body></html>";
        return protect(NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body));
    }

    private String queryToken(NanoHTTPD.IHTTPSession session) {
        Map<String, java.util.List<String>> parameters = session.getParameters();
        java.util.List<String> values = parameters == null ? null : parameters.get("token");
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private boolean validToken(String supplied) {
        return active.get() && token != null && supplied != null
                && System.currentTimeMillis() <= expiresAt
                && constantTimeEquals(token, supplied);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] left = a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getBytes(StandardCharsets.UTF_8);
        int diff = left.length ^ right.length;
        int max = Math.max(left.length, right.length);
        for (int i = 0; i < max; i++) {
            byte x = i < left.length ? left[i] : 0;
            byte y = i < right.length ? right[i] : 0;
            diff |= x ^ y;
        }
        return diff == 0;
    }

    private static long parseLength(String value) {
        try { return Long.parseLong(value == null ? "-1" : value.trim()); }
        catch (Exception ignored) { return -1L; }
    }

    private static String localIpv4() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    String value = address.getHostAddress();
                    if (value != null && (value.startsWith("192.168.") || value.startsWith("10.")
                            || value.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*"))) return value;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static String randomToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder(32);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private void broadcast(String event, String message, String url, int progress) {
        Intent intent = new Intent(ACTION_EVENT).setPackage(getPackageName())
                .putExtra(EXTRA_EVENT, event).putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_PROGRESS, progress);
        if (url != null) intent.putExtra(EXTRA_URL, url);
        sendBroadcast(intent);
    }

    private NotificationCompat.Builder notificationBuilder(String title, String text) {
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 40,
                new Intent(this, V2Activity.class), PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        PendingIntent stop = PendingIntent.getService(this, 41,
                new Intent(this, BrowserReceiveService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        return new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_optishare)
                .setContentTitle(title).setContentText(text).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, getString(R.string.stop), stop);
    }

    private android.app.Notification notification(String title, String text) {
        return notificationBuilder(title, text).build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    getString(R.string.browser_receive_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.browser_receive_channel_description));
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private static Response text(Response.IStatus status, String value) {
        return protect(NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=utf-8", value));
    }

    private static Response protect(Response response) {
        response.addHeader("Cache-Control", "no-store, max-age=0");
        response.addHeader("Pragma", "no-cache");
        response.addHeader("Referrer-Policy", "no-referrer");
        response.addHeader("X-Content-Type-Options", "nosniff");
        response.addHeader("X-Frame-Options", "DENY");
        response.addHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; form-action 'none'; base-uri 'none'");
        response.addHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
        return response;
    }

    private static String human(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024L) return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024));
        if (bytes >= 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private static String safe(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null || value.trim().isEmpty() ? "Unknown browser receive error" : value;
    }

    private static String html(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override public void onDestroy() {
        if (server != null) try { server.stop(); } catch (Exception ignored) { }
        if (securePcServer != null) { securePcServer.stop(); securePcServer = null; }
        active.set(false);
        IncomingApproval.cancel();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
