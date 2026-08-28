from pathlib import Path


def replace(path, old, new, label):
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise RuntimeError(f"missing {label} in {path}")
    p.write_text(s.replace(old, new, 1))


# Embedded local HTTP server, no external cloud.
replace(
    'app/build.gradle',
    "    implementation 'androidx.documentfile:documentfile:1.0.1'\n",
    "    implementation 'androidx.documentfile:documentfile:1.0.1'\n    implementation 'org.nanohttpd:nanohttpd:2.3.1'\n",
    'nanohttpd dependency',
)

# Browser receiver is a foreground service so the page remains available with UI backgrounded.
p = Path('app/src/main/AndroidManifest.xml')
s = p.read_text()
anchor = '''        <service
            android:name=".transfer.TransferService"
            android:exported="false"
            android:foregroundServiceType="dataSync"
            android:stopWithTask="false" />
'''
new = anchor + '''
        <service
            android:name=".transfer.BrowserReceiveService"
            android:exported="false"
            android:foregroundServiceType="dataSync"
            android:stopWithTask="false" />
'''
if anchor not in s:
    raise RuntimeError('manifest service anchor missing')
p.write_text(s.replace(anchor, new, 1))

# Public verified-file publisher for browser uploads.
p = Path('app/src/main/java/com/kenan/optishare/storage/DownloadStore.java')
s = p.read_text()
anchor = '    private void writeVerifiedMarker(String sessionId, String fileId, long size)\n'
helper = '''    public Uri publishBrowserFile(File source, String name, String mime) throws IOException {
        if (source == null || !source.exists() || !source.isFile()) {
            throw new IOException("Browser upload temp file missing");
        }
        String safeName = TransferItem.safeName(name);
        String safeMime = mime == null || mime.trim().isEmpty()
                ? "application/octet-stream" : mime.trim();
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? publishMediaStore(source, safeName, safeMime, "Browser")
                : publishLegacy(source, safeName, "Browser");
    }

'''
if anchor not in s:
    raise RuntimeError('browser publisher anchor missing')
p.write_text(s.replace(anchor, helper + anchor, 1))

Path('app/src/main/java/com/kenan/optishare/transfer/BrowserReceiveService.java').write_text(r'''package com.kenan.optishare.transfer;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import fi.iki.elonen.NanoHTTPD;

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
            active.set(true);
            broadcastReady();
        } catch (Exception error) {
            broadcast("error", "Browser receiver could not start: " + safe(error), null, 0);
            stopBrowser();
        }
    }

    private synchronized void stopBrowser() {
        active.set(false);
        IncomingApproval.cancel();
        if (server != null) {
            try { server.stop(); } catch (Exception ignored) { }
            server = null;
        }
        token = null;
        expiresAt = 0L;
        broadcast("stopped", "Browser receiver stopped", null, 0);
        stopForeground(true);
        stopSelf();
    }

    private void broadcastReady() {
        String ip = localIpv4();
        if (ip == null) {
            broadcast("error", "Connect this phone to Wi-Fi or a local hotspot first", null, 0);
            return;
        }
        String url = "http://" + ip + ":" + PORT + "/?token=" + token;
        broadcast("ready", "Open this address on a device connected to the same local network", url, 0);
    }

    private final class BrowserServer extends NanoHTTPD {
        BrowserServer() { super(PORT); }

        @Override public Response serve(IHTTPSession session) {
            try {
                String supplied = queryToken(session);
                if (!validToken(supplied)) return text(Response.Status.UNAUTHORIZED,
                        "This OptiShare browser link is invalid or expired.");
                if (Method.GET.equals(session.getMethod())) return page(supplied);
                if (!Method.POST.equals(session.getMethod()) || !"/upload".equals(session.getUri())) {
                    return text(Response.Status.NOT_FOUND, "Not found");
                }
                return receiveUpload(session, supplied);
            } catch (Exception error) {
                broadcast("error", safe(error), null, 0);
                return text(Response.Status.INTERNAL_ERROR, "Upload failed: " + html(safe(error)));
            }
        }
    }

    private Response receiveUpload(NanoHTTPD.IHTTPSession session, String supplied) throws Exception {
        Map<String, String> headers = session.getHeaders();
        long length = parseLength(headers.get("content-length"));
        if (length <= 0) return text(Response.Status.BAD_REQUEST, "Missing file data");
        if (length > MAX_FILE_BYTES) return text(Response.Status.PAYLOAD_TOO_LARGE,
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

    private Response page(String supplied) {
        String body = "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>OptiShare Browser Receive</title><style>body{font-family:system-ui;background:#071a32;color:#fff;max-width:720px;margin:auto;padding:28px}"
                + ".card{background:#102d4d;border-radius:20px;padding:24px}button{background:#238fff;color:white;border:0;padding:14px 20px;border-radius:12px;font-weight:700}"
                + "input{display:block;margin:18px 0}small{color:#9ec5e3}.item{margin-top:12px;color:#bfe3ff}</style></head><body><div class='card'>"
                + "<h1>OptiShare</h1><p>Send files to this phone over the local network.</p>"
                + "<small>The phone must approve every file. This browser route is local HTTP and is not the encrypted app-to-app mode.</small>"
                + "<input id='files' type='file' multiple><button onclick='send()'>Send selected files</button><div id='status'></div>"
                + "</div><script>const token='" + supplied + "';async function send(){const fs=document.getElementById('files').files;"
                + "for(const f of fs){const s=document.getElementById('status');s.innerHTML+='<div class=item>Waiting for phone approval: '+esc(f.name)+'</div>';"
                + "try{const r=await fetch('/upload?token='+encodeURIComponent(token),{method:'POST',headers:{'Content-Type':f.type||'application/octet-stream','X-OptiShare-Name':encodeURIComponent(f.name)},body:f});"
                + "const t=await r.text();s.innerHTML+='<div class=item>'+esc(f.name)+': '+esc(t)+'</div>';}catch(e){s.innerHTML+='<div class=item>'+esc(f.name)+': failed</div>';}}}"
                + "function esc(v){return String(v).replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]));}</script></body></html>";
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body);
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
                .addAction(0, "Stop", stop);
    }

    private android.app.Notification notification(String title, String text) {
        return notificationBuilder(title, text).build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    "Browser receive", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Local browser file receiving sessions");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private static Response text(Response.IStatus status, String value) {
        return NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=utf-8", value);
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
        active.set(false);
        IncomingApproval.cancel();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
''')

# Activity UI + broadcasts.
p = Path('app/src/main/java/com/kenan/optishare/V2Activity.java')
s = p.read_text()
s = s.replace(
    'import com.kenan.optishare.transfer.LanDiscovery;\n',
    'import com.kenan.optishare.transfer.LanDiscovery;\nimport com.kenan.optishare.transfer.BrowserReceiveService;\n',
    1,
)
s = s.replace('    private boolean transferStarted;\n', '    private boolean transferStarted;\n    private boolean browserMode;\n', 1)
# browser receiver after transferReceiver.
marker = '    @Override protected void onCreate(Bundle state) {\n'
receiver = '''    private final BroadcastReceiver browserReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String event=intent.getStringExtra(BrowserReceiveService.EXTRA_EVENT);
            String message=intent.getStringExtra(BrowserReceiveService.EXTRA_MESSAGE);
            String url=intent.getStringExtra(BrowserReceiveService.EXTRA_URL);
            int progress=intent.getIntExtra(BrowserReceiveService.EXTRA_PROGRESS,0);
            if(event==null)return;
            if("ready".equals(event)){
                browserMode=true;
                setConnectionUi("BROWSER READY",Color.rgb(89,205,255));
                setDiscoveryText("Browser receive ready • same local network");
                TextView label=findViewByTag("receiver_identity");if(label!=null)label.setText(url+"\nOne-time local session • phone approval required");
                ImageView qr=findViewByTag("receiver_qr");if(qr!=null&&url!=null)try{qr.setImageBitmap(makeQr(url,720));}catch(Exception ignored){}
            }else if("progress".equals(event)){
                setDiscoveryText(message+" • "+progress+"%");
            }else if("completed".equals(event)){
                setDiscoveryText(message);
                setConnectionUi("BROWSER FILE SAVED ✓",Color.rgb(65,225,151));
            }else if("error".equals(event)){
                setDiscoveryText(message);setConnectionUi("BROWSER ERROR",Color.rgb(255,92,102));
            }else if("stopped".equals(event)){
                browserMode=false;refreshReceiverIdentity();
            }
        }
    };

'''
if marker not in s: raise RuntimeError('browser receiver insertion anchor missing')
s = s.replace(marker, receiver + marker, 1)
# Receive screen button and stop both services.
old = '''        root.addView(receiveCard);
        Button stop=secondaryButton("Stop receiving");stop.setOnClickListener(v->{stopTransferService();safeRemoveGroup();showHome();});LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(-1,dp(50));sl.setMargins(0,dp(12),0,0);root.addView(stop,sl);
        setContentView(scroll);startReceiverService();startReceiverMode();'''
new = '''        root.addView(receiveCard);
        Button browser=secondaryButton("Receive from browser / PC");browser.setOnClickListener(v->startBrowserReceive());LinearLayout.LayoutParams bl=new LinearLayout.LayoutParams(-1,dp(50));bl.setMargins(0,dp(12),0,0);root.addView(browser,bl);
        root.addView(text("Browser mode works on the same local network with a temporary link and phone approval. App-to-app transfers remain the encrypted ECDH/AES-GCM mode.",11,Color.rgb(150,179,202),false));
        Button stop=secondaryButton("Stop receiving");stop.setOnClickListener(v->{stopTransferService();stopBrowserReceive();safeRemoveGroup();showHome();});LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(-1,dp(50));sl.setMargins(0,dp(12),0,0);root.addView(stop,sl);
        setContentView(scroll);startReceiverService();startReceiverMode();'''
if old not in s: raise RuntimeError('receive browser button anchor missing')
s = s.replace(old, new, 1)
# browser helpers before startReceiverService
anchor = '    private void startReceiverService() {\n'
helpers = '''    private void startBrowserReceive(){
        Intent i=new Intent(this,BrowserReceiveService.class).setAction(BrowserReceiveService.ACTION_START);
        ContextCompat.startForegroundService(this,i);
        setDiscoveryText("Starting local browser receiver…");
    }

    private void stopBrowserReceive(){
        try{startService(new Intent(this,BrowserReceiveService.class).setAction(BrowserReceiveService.ACTION_STOP));}catch(Exception ignored){}
        browserMode=false;
    }

'''
if anchor not in s: raise RuntimeError('browser helper anchor missing')
s = s.replace(anchor, helpers + anchor, 1)
# Don't overwrite browser QR with Wi-Fi Direct identity.
s = s.replace('''    private void refreshReceiverIdentity() {
        if(currentScreen!=SCREEN_RECEIVE)return;''','''    private void refreshReceiverIdentity() {
        if(currentScreen!=SCREEN_RECEIVE||browserMode)return;''',1)
# Register browser receiver.
old = 'IntentFilter transfer=new IntentFilter(TransferService.ACTION_EVENT);ContextCompat.registerReceiver(this,transferReceiver,transfer,ContextCompat.RECEIVER_NOT_EXPORTED);}\n'
new = 'IntentFilter transfer=new IntentFilter(TransferService.ACTION_EVENT);ContextCompat.registerReceiver(this,transferReceiver,transfer,ContextCompat.RECEIVER_NOT_EXPORTED);IntentFilter browser=new IntentFilter(BrowserReceiveService.ACTION_EVENT);ContextCompat.registerReceiver(this,browserReceiver,browser,ContextCompat.RECEIVER_NOT_EXPORTED);}\n'
if old not in s: raise RuntimeError('onResume browser registration anchor missing')
s = s.replace(old, new, 1)
s = s.replace('try{unregisterReceiver(transferReceiver);}catch(Exception ignored){}}\n', 'try{unregisterReceiver(transferReceiver);}catch(Exception ignored){}try{unregisterReceiver(browserReceiver);}catch(Exception ignored){}}\n', 1)
p.write_text(s)
