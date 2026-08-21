package com.kenan.optishare;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.database.Cursor;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DirectActivity extends ComponentActivity implements WifiP2pManager.PeerListListener, WifiP2pManager.ConnectionInfoListener {
    private static final int REQ_NEARBY = 801;
    private static final int PORT = 8988;
    private static final int MAGIC = 0x4F505431;

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final List<WifiP2pDevice> peers = new ArrayList<>();
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private WifiP2pDevice thisDevice;

    private LinearLayout peerList;
    private TextView connectionTitle, connectionSubtitle, transferTitle, transferSubtitle, receivedSummary;
    private View connectionDot;
    private ProgressBar progress;
    private Button qrButton;

    private volatile Socket socket;
    private volatile DataOutputStream output;
    private volatile boolean socketConnecting;
    private volatile boolean receiveLoopRunning;
    private volatile boolean readyToReceive;
    private volatile String pendingQrAddress;

    private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) sendFile(uri); });

    private final ActivityResultLauncher<ScanOptions> qrScanner = registerForActivityResult(
            new ScanContract(), result -> { if (result != null && result.getContents() != null) parseQr(result.getContents()); });

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                thisDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if (qrButton != null && thisDevice != null) qrButton.setText("⌗  Pair by QR\n" + nameOf(thisDevice));
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                if (hasNearbyPermission()) manager.requestPeers(channel, DirectActivity.this);
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                NetworkInfo ni = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if (ni != null && ni.isConnected()) {
                    setConnection("Connected to peer", "Opening transfer channel…", 2);
                    manager.requestConnectionInfo(channel, DirectActivity.this);
                } else {
                    closeSocket();
                    setConnection("Not connected", "Choose a nearby device or pair by QR", 0);
                }
            } else if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) setConnection("Wi‑Fi Direct unavailable", "Turn Wi‑Fi on, then try again", 3);
            }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        manager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), () -> setConnection("Connection service reset", "Reopen OptiShare", 3));
        buildUi();
        requestNearbyPermissionIfNeeded();
        refreshReceived();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(28));
        root.setBackgroundColor(Color.rgb(6, 15, 28));
        scroll.addView(root);

        TextView brand = txt("OptiShare", 31, Color.WHITE, true);
        root.addView(brand);
        TextView tag = txt("Direct sharing • fast, local, private", 14, Color.rgb(132, 180, 211), false);
        tag.setPadding(0, 0, 0, dp(18));
        root.addView(tag);

        LinearLayout conn = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        connectionDot = new View(this);
        LinearLayout.LayoutParams dpp = new LinearLayout.LayoutParams(dp(12), dp(12));
        dpp.setMargins(0, 0, dp(12), 0);
        row.addView(connectionDot, dpp);
        LinearLayout textWrap = new LinearLayout(this);
        textWrap.setOrientation(LinearLayout.VERTICAL);
        connectionTitle = txt("Not connected", 18, Color.WHITE, true);
        connectionSubtitle = txt("Choose a nearby device or pair by QR", 13, Color.rgb(150, 171, 194), false);
        textWrap.addView(connectionTitle); textWrap.addView(connectionSubtitle);
        row.addView(textWrap, new LinearLayout.LayoutParams(0, -2, 1));
        conn.addView(row);
        Button find = primary("Find nearby devices");
        find.setOnClickListener(v -> discover());
        conn.addView(find, buttonParams(12));
        root.addView(conn);

        TextView transferLabel = txt("Transfer", 17, Color.WHITE, true);
        transferLabel.setPadding(0, dp(20), 0, dp(8)); root.addView(transferLabel);

        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        Button send = action("↑", "Send", "Choose file");
        send.setOnClickListener(v -> { if (!ready()) showMessage("Connect first", "Pair the two phones, then tap Send."); else filePicker.launch(new String[]{"*/*"}); });
        Button receive = action("↓", "Receive", "Wait for file");
        receive.setOnClickListener(v -> { if (!ready()) showMessage("Connect first", "Pair the two phones, then tap Receive."); else { readyToReceive = true; transferTitle.setText("Ready to receive"); transferSubtitle.setText("Waiting for the other phone…"); progress.setProgress(0); }});
        actions.addView(send, new LinearLayout.LayoutParams(0, dp(136), 1));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(136), 1); rp.setMargins(dp(10),0,0,0); actions.addView(receive, rp);
        root.addView(actions);

        qrButton = action("⌗", "Pair by QR", "Show or scan pairing code");
        qrButton.setOnClickListener(v -> qrMenu());
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-1, dp(112)); qp.setMargins(0, dp(10), 0, 0); root.addView(qrButton, qp);

        TextView nearby = txt("Nearby devices", 17, Color.WHITE, true); nearby.setPadding(0, dp(22), 0, dp(8)); root.addView(nearby);
        peerList = new LinearLayout(this); peerList.setOrientation(LinearLayout.VERTICAL); root.addView(peerList);
        showEmptyPeers("No devices yet", "Tap Find nearby devices on both phones.");

        TextView current = txt("Current transfer", 17, Color.WHITE, true); current.setPadding(0, dp(22), 0, dp(8)); root.addView(current);
        LinearLayout transferCard = card();
        transferTitle = txt("No active transfer", 16, Color.WHITE, true);
        transferSubtitle = txt("Connect two phones, then choose Send or Receive.", 13, Color.rgb(150, 173, 195), false);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(1000);
        transferCard.addView(transferTitle); transferCard.addView(transferSubtitle);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(8)); pp.setMargins(0, dp(12), 0, 0); transferCard.addView(progress, pp);
        root.addView(transferCard);

        TextView received = txt("Received files", 17, Color.WHITE, true); received.setPadding(0, dp(22), 0, dp(8)); root.addView(received);
        LinearLayout receivedCard = card(); receivedSummary = txt("No received files yet", 14, Color.rgb(165, 181, 199), false); receivedCard.addView(receivedSummary);
        Button list = secondary("View received files"); list.setOnClickListener(v -> showReceivedFiles()); receivedCard.addView(list, buttonParams(10)); root.addView(receivedCard);

        TextView footer = txt("No Internet required • QR is only for pairing\nDesigned & developed by Kenan Alhennawi", 12, Color.rgb(56,189,248), false);
        footer.setGravity(Gravity.CENTER); footer.setPadding(0, dp(24),0,0); root.addView(footer);
        setContentView(scroll);
        setConnection("Not connected", "Choose a nearby device or pair by QR", 0);
    }

    private LinearLayout card() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(16),dp(16),dp(16),dp(16)); GradientDrawable g=new GradientDrawable(); g.setColor(Color.rgb(14,28,45)); g.setCornerRadius(dp(18)); g.setStroke(dp(1),Color.rgb(31,53,75)); l.setBackground(g); return l; }
    private TextView txt(String s,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
    private Button primary(String s){Button b=new Button(this);b.setAllCaps(false);b.setText(s);b.setTextSize(15);b.setTextColor(Color.rgb(4,20,34));GradientDrawable g=new GradientDrawable();g.setColor(Color.rgb(56,189,248));g.setCornerRadius(dp(14));b.setBackground(g);return b;}
    private Button secondary(String s){Button b=new Button(this);b.setAllCaps(false);b.setText(s);b.setTextColor(Color.WHITE);GradientDrawable g=new GradientDrawable();g.setColor(Color.rgb(25,47,68));g.setCornerRadius(dp(14));b.setBackground(g);return b;}
    private Button action(String icon,String title,String sub){Button b=new Button(this);b.setAllCaps(false);b.setGravity(Gravity.CENTER);b.setText(icon+"\n"+title+"\n"+sub);b.setTextSize(15);b.setTextColor(Color.WHITE);GradientDrawable g=new GradientDrawable();g.setColor(Color.rgb(14,28,45));g.setCornerRadius(dp(18));g.setStroke(dp(1),Color.rgb(35,61,85));b.setBackground(g);return b;}
    private LinearLayout.LayoutParams buttonParams(int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(50));p.setMargins(0,dp(top),0,0);return p;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    private void setConnection(String title,String sub,int state){runOnUiThread(()->{if(connectionTitle==null)return;connectionTitle.setText(title);connectionSubtitle.setText(sub);int c=state==2?Color.rgb(57,217,138):state==1?Color.rgb(250,190,60):state==3?Color.rgb(255,94,94):Color.rgb(115,135,155);GradientDrawable g=new GradientDrawable();g.setShape(GradientDrawable.OVAL);g.setColor(c);connectionDot.setBackground(g);});}

    private void discover(){if(!hasNearbyPermission()){requestNearbyPermissionIfNeeded();return;}setConnection("Searching…","Looking for nearby OptiShare devices",1);manager.discoverPeers(channel,new WifiP2pManager.ActionListener(){public void onSuccess(){setConnection("Searching…","Keep OptiShare open on both phones",1);}public void onFailure(int r){setConnection("Discovery failed","Android Wi‑Fi Direct error "+r,3);}});}

    @Override public void onPeersAvailable(WifiP2pDeviceList list){peers.clear();peers.addAll(list.getDeviceList());runOnUiThread(()->{peerList.removeAllViews();if(peers.isEmpty()){showEmptyPeers("No devices found","Tap Find nearby devices on both phones and keep them close.");return;}for(WifiP2pDevice d:peers){LinearLayout c=card();c.addView(txt(nameOf(d),16,Color.WHITE,true));c.addView(txt(deviceStatus(d.status),12,Color.rgb(140,165,185),false));Button b=secondary("Connect");b.setOnClickListener(v->connectTo(d));c.addView(b,buttonParams(8));peerList.addView(c);if(pendingQrAddress!=null&&pendingQrAddress.equalsIgnoreCase(d.deviceAddress)){pendingQrAddress=null;connectTo(d);}}});}
    private void showEmptyPeers(String title,String sub){peerList.removeAllViews();LinearLayout c=card();c.addView(txt(title,15,Color.WHITE,true));c.addView(txt(sub,13,Color.rgb(140,160,180),false));peerList.addView(c);}

    private void connectTo(WifiP2pDevice d){if(!hasNearbyPermission()){requestNearbyPermissionIfNeeded();return;}WifiP2pConfig cfg=new WifiP2pConfig();cfg.deviceAddress=d.deviceAddress;setConnection("Connecting…",nameOf(d),1);manager.connect(channel,cfg,new WifiP2pManager.ActionListener(){public void onSuccess(){setConnection("Connection request sent","Approve on the other phone if Android asks",1);}public void onFailure(int r){setConnection("Connection failed","Android Wi‑Fi Direct error "+r,3);}});}

    @Override public void onConnectionInfoAvailable(WifiP2pInfo info){if(!info.groupFormed||info.groupOwnerAddress==null)return;setConnection("Direct link established",(info.isGroupOwner?"Host":"Client")+" • opening transfer channel…",2);if(info.isGroupOwner)startServer();else startClient(info.groupOwnerAddress.getHostAddress());}
    private void startServer(){if(socketConnecting||ready())return;socketConnecting=true;io.execute(()->{try(ServerSocket server=new ServerSocket(PORT)){installSocket(server.accept());}catch(Exception e){setConnection("Transfer channel failed",safe(e),3);}finally{socketConnecting=false;}});}
    private void startClient(String host){if(socketConnecting||ready())return;socketConnecting=true;io.execute(()->{try{Thread.sleep(350);Socket s=new Socket();s.connect(new InetSocketAddress(host,PORT),8000);installSocket(s);}catch(Exception e){setConnection("Transfer channel failed","Could not open direct socket",3);}finally{socketConnecting=false;}});}
    private synchronized void installSocket(Socket s)throws Exception{closeSocket();socket=s;socket.setTcpNoDelay(true);output=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(),128*1024));setConnection("Connected ✓","Direct transfer channel ready • Send or Receive",2);if(!receiveLoopRunning){receiveLoopRunning=true;io.execute(this::receiveLoop);}}
    private boolean ready(){Socket s=socket;return s!=null&&s.isConnected()&&!s.isClosed()&&output!=null;}

    private void sendFile(Uri uri){if(!ready()){showMessage("Not connected","Pair the phones before sending.");return;}io.execute(()->{try{String name=queryName(uri);long size=querySize(uri);if(size<0)size=measure(uri);final long total=size;runOnUiThread(()->{transferTitle.setText("Sending "+name);transferSubtitle.setText("Preparing…");progress.setProgress(0);});byte[] hash=hash(uri);synchronized(this){output.writeInt(MAGIC);output.writeUTF(name);output.writeLong(total);output.writeInt(hash.length);output.write(hash);output.flush();long sent=0;long started=System.nanoTime();byte[] buf=new byte[256*1024];try(InputStream in=getContentResolver().openInputStream(uri)){int n;while((n=in.read(buf))!=-1){output.write(buf,0,n);sent+=n;updateTransfer(true,name,sent,total,started);}}output.flush();}runOnUiThread(()->{transferTitle.setText("Sent ✓  "+name);transferSubtitle.setText(fmt(total)+" transferred successfully");progress.setProgress(1000);});}catch(Exception e){runOnUiThread(()->{transferTitle.setText("Send failed");transferSubtitle.setText(safe(e));});}});}

    private void receiveLoop(){try{DataInputStream in=new DataInputStream(new BufferedInputStream(socket.getInputStream(),128*1024));while(socket!=null&&!socket.isClosed()){int magic;try{magic=in.readInt();}catch(EOFException e){break;}if(magic!=MAGIC)throw new IllegalStateException("Invalid OptiShare packet");String name=sanitize(in.readUTF());long total=in.readLong();int hl=in.readInt();if(total<0||total>50L*1024*1024*1024||hl<=0||hl>128)throw new IllegalStateException("Invalid transfer metadata");byte[] expected=new byte[hl];in.readFully(expected);File dir=receivedDir();if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Cannot create receive folder");File target=uniqueFile(dir,name);runOnUiThread(()->{transferTitle.setText("Receiving "+name);transferSubtitle.setText(readyToReceive?"Incoming file accepted":"Incoming file detected automatically");progress.setProgress(0);});MessageDigest md=MessageDigest.getInstance("SHA-256");long got=0;long started=System.nanoTime();byte[] buf=new byte[256*1024];try(BufferedOutputStream file=new BufferedOutputStream(new FileOutputStream(target),256*1024)){while(got<total){int want=(int)Math.min(buf.length,total-got);int n=in.read(buf,0,want);if(n<0)throw new EOFException("Connection ended during transfer");file.write(buf,0,n);md.update(buf,0,n);got+=n;updateTransfer(false,name,got,total,started);}file.flush();}if(!Arrays.equals(expected,md.digest())){target.delete();throw new IllegalStateException("SHA-256 verification failed");}readyToReceive=false;runOnUiThread(()->{transferTitle.setText("Received ✓  "+target.getName());transferSubtitle.setText(fmt(total)+" • SHA-256 verified");progress.setProgress(1000);refreshReceived();});}}catch(Exception e){if(socket!=null&&!socket.isClosed())runOnUiThread(()->{transferTitle.setText("Connection interrupted");transferSubtitle.setText(safe(e));});}finally{receiveLoopRunning=false;}}

    private void updateTransfer(boolean sending,String name,long done,long total,long started){double sec=Math.max(.001,(System.nanoTime()-started)/1_000_000_000.0);double mbps=(done/(1024.0*1024.0))/sec;int p=total==0?1000:(int)Math.min(1000,done*1000L/total);runOnUiThread(()->{progress.setProgress(p);transferTitle.setText((sending?"Sending ":"Receiving ")+name);transferSubtitle.setText(String.format(Locale.US,"%s / %s  •  %.2f MB/s  •  %d%%",fmt(done),fmt(total),mbps,p/10));});}

    private void qrMenu(){String[] opts={"Show my pairing QR","Scan pairing QR"};new AlertDialog.Builder(this).setTitle("Pair by QR").setItems(opts,(d,w)->{if(w==0)showQr();else scanQr();}).setNegativeButton("Cancel",null).show();}
    private void showQr(){if(thisDevice==null||thisDevice.deviceAddress==null||thisDevice.deviceAddress.isEmpty()){showMessage("Pairing QR not ready","Tap Find nearby devices once, then try again.");discover();return;}try{String payload="OPTISHARE|"+thisDevice.deviceAddress+"|"+nameOf(thisDevice);ImageView image=new ImageView(this);image.setImageBitmap(makeQr(payload,720));image.setPadding(dp(18),dp(18),dp(18),dp(18));new AlertDialog.Builder(this).setTitle("Scan this on the other phone").setMessage("QR only pairs the phones. The file transfers over the fast direct link.").setView(image).setPositiveButton("Done",null).show();}catch(Exception e){showMessage("QR error",safe(e));}}
    private void scanQr(){ScanOptions o=new ScanOptions();o.setPrompt("Scan the OptiShare pairing QR");o.setBeepEnabled(false);o.setOrientationLocked(false);o.setDesiredBarcodeFormats(ScanOptions.QR_CODE);qrScanner.launch(o);}
    private void parseQr(String value){if(value==null||!value.startsWith("OPTISHARE|")){showMessage("Invalid QR","This is not an OptiShare pairing code.");return;}String[] p=value.split("\\|",3);if(p.length<2||p[1].isEmpty()){showMessage("Invalid QR","Missing device address.");return;}pendingQrAddress=p[1];setConnection("QR paired","Searching for "+(p.length>2?p[2]:"device")+"…",1);discover();}
    private Bitmap makeQr(String value,int size)throws Exception{BitMatrix m=new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE,size,size);Bitmap b=Bitmap.createBitmap(size,size,Bitmap.Config.RGB_565);for(int y=0;y<size;y++)for(int x=0;x<size;x++)b.setPixel(x,y,m.get(x,y)?Color.BLACK:Color.WHITE);return b;}

    private void refreshReceived(){if(receivedSummary==null)return;File[] f=receivedDir().listFiles();if(f==null||f.length==0){receivedSummary.setText("No received files yet");return;}long total=0;for(File x:f)total+=x.length();receivedSummary.setText(f.length+" file"+(f.length==1?"":"s")+" • "+fmt(total)+" stored");}
    private void showReceivedFiles(){File[] f=receivedDir().listFiles();if(f==null||f.length==0){showMessage("Received files","No files received yet.");return;}Arrays.sort(f,(a,b)->Long.compare(b.lastModified(),a.lastModified()));String[] rows=new String[Math.min(30,f.length)];for(int i=0;i<rows.length;i++)rows[i]=f[i].getName()+"\n"+fmt(f[i].length());new AlertDialog.Builder(this).setTitle("Received files").setItems(rows,null).setPositiveButton("Close",null).show();}
    private File receivedDir(){return new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"OptiShare Received");}
    private File uniqueFile(File dir,String name){File f=new File(dir,name);if(!f.exists())return f;int dot=name.lastIndexOf('.');String base=dot>0?name.substring(0,dot):name,ext=dot>0?name.substring(dot):"";for(int i=1;i<10000;i++){File c=new File(dir,base+" ("+i+")"+ext);if(!c.exists())return c;}return new File(dir,System.currentTimeMillis()+"-"+name);}

    private String queryName(Uri uri){Cursor c=null;try{c=getContentResolver().query(uri,null,null,null,null);if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0&&!c.isNull(i))return sanitize(c.getString(i));}}finally{if(c!=null)c.close();}return "file.bin";}
    private long querySize(Uri uri){Cursor c=null;try{c=getContentResolver().query(uri,null,null,null,null);if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.SIZE);if(i>=0&&!c.isNull(i))return c.getLong(i);}}finally{if(c!=null)c.close();}return -1;}
    private long measure(Uri uri)throws Exception{long total=0;byte[] b=new byte[128*1024];try(InputStream in=getContentResolver().openInputStream(uri)){int n;while((n=in.read(b))!=-1)total+=n;}return total;}
    private byte[] hash(Uri uri)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[256*1024];try(InputStream in=getContentResolver().openInputStream(uri)){int n;while((n=in.read(b))!=-1)md.update(b,0,n);}return md.digest();}
    private String sanitize(String n){return n==null?"file.bin":n.replace("/","_").replace("\\","_").replace("\u0000","_");}
    private String nameOf(WifiP2pDevice d){return d==null||d.deviceName==null||d.deviceName.trim().isEmpty()?"Android device":d.deviceName.trim();}
    private String deviceStatus(int s){switch(s){case WifiP2pDevice.CONNECTED:return "Connected";case WifiP2pDevice.INVITED:return "Invitation sent";case WifiP2pDevice.AVAILABLE:return "Available";case WifiP2pDevice.FAILED:return "Unavailable";case WifiP2pDevice.UNAVAILABLE:return "Busy";default:return "Nearby";}}
    private String fmt(long b){if(b<1024)return b+" B";if(b<1024L*1024)return String.format(Locale.US,"%.1f KB",b/1024.0);if(b<1024L*1024*1024)return String.format(Locale.US,"%.2f MB",b/(1024.0*1024.0));return String.format(Locale.US,"%.2f GB",b/(1024.0*1024.0*1024.0));}
    private String safe(Throwable e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private void showMessage(String t,String m){runOnUiThread(()->new AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("OK",null).show());}

    private boolean hasNearbyPermission(){if(Build.VERSION.SDK_INT>=33)return checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)==PackageManager.PERMISSION_GRANTED;return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    private void requestNearbyPermissionIfNeeded(){if(hasNearbyPermission())return;if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},REQ_NEARBY);else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},REQ_NEARBY);}
    private synchronized void closeSocket(){try{if(output!=null)output.close();}catch(Exception ignored){}try{if(socket!=null)socket.close();}catch(Exception ignored){}output=null;socket=null;receiveLoopRunning=false;socketConnecting=false;}

    @Override protected void onResume(){super.onResume();IntentFilter f=new IntentFilter();f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);f.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);ContextCompat.registerReceiver(this,receiver,f,ContextCompat.RECEIVER_NOT_EXPORTED);}
    @Override protected void onPause(){super.onPause();try{unregisterReceiver(receiver);}catch(Exception ignored){}}
    @Override protected void onDestroy(){closeSocket();io.shutdownNow();super.onDestroy();}
}
