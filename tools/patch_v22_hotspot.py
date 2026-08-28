from pathlib import Path


def replace(path, old, new, label):
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise RuntimeError(f"missing {label} in {path}")
    p.write_text(s.replace(old, new, 1))


# Foreground hotspot service holds the reservation across activity recreation.
p = Path('app/src/main/AndroidManifest.xml')
s = p.read_text()
anchor = '''        <service
            android:name=".transfer.BrowserReceiveService"
            android:exported="false"
            android:foregroundServiceType="dataSync"
            android:stopWithTask="false" />
'''
new = anchor + '''
        <service
            android:name=".transfer.HotspotService"
            android:exported="false"
            android:foregroundServiceType="dataSync"
            android:stopWithTask="false" />
'''
if anchor not in s: raise RuntimeError('hotspot manifest anchor missing')
p.write_text(s.replace(anchor, new, 1))

Path('app/src/main/java/com/kenan/optishare/transfer/HotspotService.java').write_text(r'''package com.kenan.optishare.transfer;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.kenan.optishare.R;
import com.kenan.optishare.V2Activity;

/** Holds an Android Local-Only Hotspot reservation for no-router transfer fallback. */
public final class HotspotService extends Service {
    public static final String ACTION_START = "com.kenan.optishare.hotspot.START";
    public static final String ACTION_STOP = "com.kenan.optishare.hotspot.STOP";
    public static final String ACTION_EVENT = "com.kenan.optishare.HOTSPOT_EVENT";
    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_SSID = "ssid";
    public static final String EXTRA_PASSWORD = "password";

    private static final String CHANNEL = "optishare_hotspot";
    private static final int NOTIFICATION_ID = 2250;
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private boolean requestInFlight;

    @Override public void onCreate() { super.onCreate(); createChannel(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) { stopHotspot(); return START_NOT_STICKY; }
        if (ACTION_START.equals(action)) startHotspot();
        return START_STICKY;
    }

    private synchronized void startHotspot() {
        if (Build.VERSION.SDK_INT < 26) {
            broadcast("error", "Local hotspot fallback requires Android 8 or newer", null, null);
            stopSelf();
            return;
        }
        if (reservation != null || requestInFlight) {
            broadcast("status", "Local hotspot is already active or starting", null, null);
            return;
        }
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi == null) {
            broadcast("error", "Wi-Fi service is unavailable on this device", null, null);
            stopSelf();
            return;
        }
        startForeground(NOTIFICATION_ID, notification("Creating local transfer hotspot",
                "Android is preparing a no-Internet local network"));
        requestInFlight = true;
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation value) {
                    synchronized (HotspotService.this) {
                        requestInFlight = false;
                        reservation = value;
                    }
                    Credentials credentials = credentials(value);
                    if (credentials == null || credentials.ssid == null) {
                        broadcast("error", "Hotspot started but Android did not expose connection credentials", null, null);
                        return;
                    }
                    broadcast("started", "Connect the sending device to this local hotspot, then use normal OptiShare Send or Browser Receive.",
                            credentials.ssid, credentials.password);
                    updateNotification("OptiShare local hotspot active",
                            credentials.ssid + " • no Internet");
                }

                @Override public void onStopped() {
                    synchronized (HotspotService.this) { reservation = null; requestInFlight = false; }
                    broadcast("stopped", "Android stopped the local hotspot", null, null);
                    stopForeground(true); stopSelf();
                }

                @Override public void onFailed(int reason) {
                    synchronized (HotspotService.this) { reservation = null; requestInFlight = false; }
                    broadcast("error", failureMessage(reason), null, null);
                    stopForeground(true); stopSelf();
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (SecurityException denied) {
            requestInFlight = false;
            broadcast("permission", "Nearby Wi-Fi permission is required to create a local hotspot", null, null);
            stopForeground(true); stopSelf();
        } catch (Exception error) {
            requestInFlight = false;
            broadcast("error", error.getMessage() == null ? "Could not create local hotspot" : error.getMessage(), null, null);
            stopForeground(true); stopSelf();
        }
    }

    private synchronized void stopHotspot() {
        requestInFlight = false;
        if (reservation != null) {
            try { reservation.close(); } catch (Exception ignored) { }
            reservation = null;
        }
        broadcast("stopped", "Local hotspot stopped", null, null);
        stopForeground(true); stopSelf();
    }

    private static Credentials credentials(WifiManager.LocalOnlyHotspotReservation value) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                SoftApConfiguration cfg = value.getSoftApConfiguration();
                if (cfg == null) return null;
                return new Credentials(cfg.getSsid(), cfg.getPassphrase());
            }
            @SuppressWarnings("deprecation") WifiConfiguration cfg = value.getWifiConfiguration();
            if (cfg == null) return null;
            return new Credentials(stripQuotes(cfg.SSID), stripQuotes(cfg.preSharedKey));
        } catch (Exception ignored) { return null; }
    }

    private static String stripQuotes(String value) {
        if (value == null) return null;
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) return value.substring(1, value.length() - 1);
        return value;
    }

    private static String failureMessage(int reason) {
        if (reason == WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL)
            return "Android could not find a Wi-Fi channel for the local hotspot";
        if (reason == WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE)
            return "Current Wi-Fi or tethering mode conflicts with Local Hotspot";
        if (reason == WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED)
            return "This device or administrator does not allow local hotspot mode";
        return "Android could not create the local hotspot (" + reason + ")";
    }

    private void broadcast(String event, String message, String ssid, String password) {
        Intent intent = new Intent(ACTION_EVENT).setPackage(getPackageName())
                .putExtra(EXTRA_EVENT, event).putExtra(EXTRA_MESSAGE, message);
        if (ssid != null) intent.putExtra(EXTRA_SSID, ssid);
        if (password != null) intent.putExtra(EXTRA_PASSWORD, password);
        sendBroadcast(intent);
    }

    private android.app.Notification notification(String title, String text) {
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 50, new Intent(this, V2Activity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        PendingIntent stop = PendingIntent.getService(this, 51,
                new Intent(this, HotspotService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        return new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_optishare)
                .setContentTitle(title).setContentText(text).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, "Stop hotspot", stop).build();
    }

    private void updateNotification(String title, String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification(title, text));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Local hotspot",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("No-Internet local network for OptiShare fallback transfers");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    @Override public void onDestroy() {
        synchronized (this) {
            if (reservation != null) try { reservation.close(); } catch (Exception ignored) { }
            reservation = null; requestInFlight = false;
        }
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private static final class Credentials {
        final String ssid; final String password;
        Credentials(String ssid, String password) { this.ssid = ssid; this.password = password; }
    }
}
''')

p = Path('app/src/main/java/com/kenan/optishare/V2Activity.java')
s = p.read_text()
s = s.replace('import com.kenan.optishare.transfer.BrowserReceiveService;\n',
              'import com.kenan.optishare.transfer.BrowserReceiveService;\nimport com.kenan.optishare.transfer.HotspotService;\n', 1)
s = s.replace('    private boolean browserMode;\n', '    private boolean browserMode;\n    private boolean hotspotMode;\n', 1)
# Hotspot event receiver before onCreate.
marker = '    @Override protected void onCreate(Bundle state) {\n'
receiver = '''    private final BroadcastReceiver hotspotReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String event=intent.getStringExtra(HotspotService.EXTRA_EVENT);
            String message=intent.getStringExtra(HotspotService.EXTRA_MESSAGE);
            String ssid=intent.getStringExtra(HotspotService.EXTRA_SSID);
            String password=intent.getStringExtra(HotspotService.EXTRA_PASSWORD);
            if(event==null)return;
            if("started".equals(event)){
                hotspotMode=true;browserMode=false;
                setConnectionUi("LOCAL HOTSPOT READY",Color.rgb(255,188,70));
                setDiscoveryText(message);
                TextView label=findViewByTag("receiver_identity");
                if(label!=null)label.setText("SSID: "+ssid+"\\nPassword: "+(password==null?"Open network":password)+"\\nNo Internet • app-to-app encryption remains active");
                ImageView qr=findViewByTag("receiver_qr");
                if(qr!=null&&ssid!=null){String wifiQr="WIFI:T:"+(password==null||password.isEmpty()?"nopass":"WPA")+";S:"+escapeWifiQr(ssid)+";P:"+escapeWifiQr(password)+";;";try{qr.setImageBitmap(makeQr(wifiQr,720));}catch(Exception ignored){}}
            }else if("permission".equals(event)){
                hotspotMode=false;requestNearbyPermission();showMessage("Nearby permission required",message);
            }else if("error".equals(event)){
                hotspotMode=false;setConnectionUi("HOTSPOT UNAVAILABLE",Color.rgb(255,92,102));showHotspotFallbackError(message);
            }else if("stopped".equals(event)){
                hotspotMode=false;refreshReceiverIdentity();
            }
        }
    };

'''
if marker not in s: raise RuntimeError('hotspot receiver anchor missing')
s = s.replace(marker, receiver + marker, 1)
# Receive screen hotspot button.
old = '''        Button browser=secondaryButton("Receive from browser / PC");browser.setOnClickListener(v->startBrowserReceive());LinearLayout.LayoutParams bl=new LinearLayout.LayoutParams(-1,dp(50));bl.setMargins(0,dp(12),0,0);root.addView(browser,bl);
        root.addView(text("Browser mode works on the same local network with a temporary link and phone approval. App-to-app transfers remain the encrypted ECDH/AES-GCM mode.",11,Color.rgb(150,179,202),false));
        Button stop=secondaryButton("Stop receiving");stop.setOnClickListener(v->{stopTransferService();stopBrowserReceive();safeRemoveGroup();showHome();});'''
new = '''        Button browser=secondaryButton("Receive from browser / PC");browser.setOnClickListener(v->startBrowserReceive());LinearLayout.LayoutParams bl=new LinearLayout.LayoutParams(-1,dp(50));bl.setMargins(0,dp(12),0,0);root.addView(browser,bl);
        Button hotspot=secondaryButton("Create hotspot fallback");hotspot.setOnClickListener(v->startHotspotFallback());LinearLayout.LayoutParams hl=new LinearLayout.LayoutParams(-1,dp(50));hl.setMargins(0,dp(8),0,0);root.addView(hotspot,hl);
        root.addView(text("Browser mode works on the same local network with a temporary link and phone approval. Hotspot fallback creates a local no-Internet network when no router is available. App-to-app transfers remain encrypted.",11,Color.rgb(150,179,202),false));
        Button stop=secondaryButton("Stop receiving");stop.setOnClickListener(v->{stopTransferService();stopBrowserReceive();stopHotspotFallback();safeRemoveGroup();showHome();});'''
if old not in s: raise RuntimeError('hotspot button anchor missing')
s = s.replace(old, new, 1)
# Helpers before browser start.
anchor = '    private void startBrowserReceive(){\n'
helpers = '''    private void startHotspotFallback(){
        if(Build.VERSION.SDK_INT<26){showHotspotFallbackError("Local hotspot requires Android 8 or newer");return;}
        if(!hasNearbyPermission()){requestNearbyPermission();return;}
        Intent i=new Intent(this,HotspotService.class).setAction(HotspotService.ACTION_START);
        ContextCompat.startForegroundService(this,i);setDiscoveryText("Android is creating a local no-Internet hotspot…");
    }

    private void stopHotspotFallback(){
        try{startService(new Intent(this,HotspotService.class).setAction(HotspotService.ACTION_STOP));}catch(Exception ignored){}
        hotspotMode=false;
    }

    private void showHotspotFallbackError(String message){
        runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Hotspot fallback unavailable").setMessage(message+"\\nYou can still use Wi-Fi Direct, same-WiFi, or open Android hotspot/Wi-Fi settings manually.")
                .setPositiveButton("Wi-Fi settings",(d,w)->startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS))).setNegativeButton("Close",null).show());
    }

    private static String escapeWifiQr(String value){if(value==null)return"";return value.replace("\\\\","\\\\\\\\").replace(";","\\\\;").replace(",","\\\\,").replace(":","\\\\:");}

'''
if anchor not in s: raise RuntimeError('hotspot helper anchor missing')
s = s.replace(anchor, helpers + anchor, 1)
# Keep hotspot credential QR intact.
s = s.replace('if(currentScreen!=SCREEN_RECEIVE||browserMode)return;', 'if(currentScreen!=SCREEN_RECEIVE||browserMode||hotspotMode)return;', 1)
# Register/unregister receiver.
old = 'IntentFilter browser=new IntentFilter(BrowserReceiveService.ACTION_EVENT);ContextCompat.registerReceiver(this,browserReceiver,browser,ContextCompat.RECEIVER_NOT_EXPORTED);}\n'
new = 'IntentFilter browser=new IntentFilter(BrowserReceiveService.ACTION_EVENT);ContextCompat.registerReceiver(this,browserReceiver,browser,ContextCompat.RECEIVER_NOT_EXPORTED);IntentFilter hotspot=new IntentFilter(HotspotService.ACTION_EVENT);ContextCompat.registerReceiver(this,hotspotReceiver,hotspot,ContextCompat.RECEIVER_NOT_EXPORTED);}\n'
if old not in s: raise RuntimeError('hotspot registration anchor missing')
s = s.replace(old, new, 1)
s = s.replace('try{unregisterReceiver(browserReceiver);}catch(Exception ignored){}}\n', 'try{unregisterReceiver(browserReceiver);}catch(Exception ignored){}try{unregisterReceiver(hotspotReceiver);}catch(Exception ignored){}}\n', 1)
p.write_text(s)
