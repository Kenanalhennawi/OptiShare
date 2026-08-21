package com.kenan.optishare;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WpsInfo;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DirectActivity extends ComponentActivity implements WifiP2pManager.PeerListListener, WifiP2pManager.ConnectionInfoListener {
    private static final int REQ_NEARBY = 801;
    private static final int PORT = 8988;
    private static final int MAGIC = 0x4F505432;
    private static final int MODE_IDLE = 0, MODE_SEND = 1, MODE_RECEIVE = 2;

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final List<WifiP2pDevice> peers = new ArrayList<>();

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private WifiP2pDevice thisDevice;
    private int mode = MODE_IDLE;
    private Uri pendingFile;
    private String pendingQrAddress;
    private String pendingQrName;
    private String sessionToken;

    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private boolean socketStarting;

    private TextView statusTitle, statusSub, stepText, transferTitle, transferSub;
    private View statusDot;
    private ProgressBar progress;
    private LinearLayout contentArea;

    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                pendingFile = uri;
                mode = MODE_SEND;
                showSenderChoice();
            });

    private final ActivityResultLauncher<ScanOptions> scanner = registerForActivityResult(
            new ScanContract(), result -> {
                if (result == null || result.getContents() == null) return;
                handleQr(result.getContents());
            });

    private final BroadcastReceiver p2pReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(a)) {
                thisDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if (mode == MODE_RECEIVE) refreshReceiverQr();
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(a)) {
                if (hasPermission()) manager.requestPeers(channel, DirectActivity.this);
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(a)) {
                NetworkInfo ni = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if (ni != null && ni.isConnected()) {
                    setStatus("Connected", "Opening OptiShare transfer channel…", 2);
                    manager.requestConnectionInfo(channel, DirectActivity.this);
                } else if (mode != MODE_IDLE) {
                    setStatus(mode == MODE_RECEIVE ? "Waiting for sender" : "Looking for receiver",
                            mode == MODE_RECEIVE ? "Keep this screen open" : "Scan QR or choose the receiving phone", 1);
                }
            } else if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(a)) {
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) setStatus("Wi‑Fi Direct is off", "Turn Wi‑Fi on and retry", 3);
            }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        manager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), () -> setStatus("Connection service reset", "Reopen OptiShare", 3));
        buildUi();
        requestPermission();
        showHome();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(28));
        root.setBackgroundColor(Color.rgb(6, 15, 28));
        scroll.addView(root);

        TextView brand = txt("OptiShare", 31, Color.WHITE, true); root.addView(brand);
        TextView tag = txt("Direct sharing • no Internet required", 14, Color.rgb(132, 180, 211), false); tag.setPadding(0,0,0,dp(16)); root.addView(tag);

        LinearLayout statusCard = card();
        LinearLayout statusRow = new LinearLayout(this); statusRow.setOrientation(LinearLayout.HORIZONTAL); statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this); LinearLayout.LayoutParams dotp = new LinearLayout.LayoutParams(dp(12),dp(12)); dotp.setMargins(0,0,dp(12),0); statusRow.addView(statusDot,dotp);
        LinearLayout sw = new LinearLayout(this); sw.setOrientation(LinearLayout.VERTICAL);
        statusTitle = txt("Ready",18,Color.WHITE,true); statusSub = txt("Choose Send or Receive",13,Color.rgb(150,171,194),false); sw.addView(statusTitle); sw.addView(statusSub); statusRow.addView(sw,new LinearLayout.LayoutParams(0,-2,1)); statusCard.addView(statusRow);
        stepText = txt("1  Choose a role",12,Color.rgb(56,189,248),true); stepText.setPadding(0,dp(10),0,0); statusCard.addView(stepText); root.addView(statusCard);

        contentArea = new LinearLayout(this); contentArea.setOrientation(LinearLayout.VERTICAL); LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1,-2); cp.setMargins(0,dp(16),0,0); root.addView(contentArea,cp);

        LinearLayout transferCard = card(); transferTitle=txt("No active transfer",16,Color.WHITE,true); transferSub=txt("Transfer progress will appear here.",13,Color.rgb(150,173,195),false); progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(1000); transferCard.addView(transferTitle); transferCard.addView(transferSub); LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(8));pp.setMargins(0,dp(12),0,0);transferCard.addView(progress,pp); LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2);tp.setMargins(0,dp(18),0,0);root.addView(transferCard,tp);

        TextView footer=txt("QR only starts pairing — files move over the direct link\nDesigned & developed by Kenan Alhennawi",12,Color.rgb(56,189,248),false);footer.setGravity(Gravity.CENTER);footer.setPadding(0,dp(22),0,0);root.addView(footer);
        setContentView(scroll);
    }

    private void showHome() {
        mode=MODE_IDLE; pendingFile=null; pendingQrAddress=null; pendingQrName=null; sessionToken=null; closeSocket();
        try { manager.removeGroup(channel, new QuietAction()); } catch(Exception ignored) {}
        contentArea.removeAllViews();
        TextView title=txt("What do you want to do?",18,Color.WHITE,true); title.setPadding(0,0,0,dp(10)); contentArea.addView(title);
        Button send=action("↑","Send files","Choose a file, then pair with the receiver"); send.setOnClickListener(v->picker.launch(new String[]{"*/*"})); contentArea.addView(send,new LinearLayout.LayoutParams(-1,dp(126)));
        Button receive=action("↓","Receive files","Create a receiving session and wait"); receive.setOnClickListener(v->startReceiveMode()); LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(126));rp.setMargins(0,dp(10),0,0);contentArea.addView(receive,rp);
        setStatus("Ready", "Choose Send or Receive", 0); stepText.setText("1  Choose a role");
    }

    private void showSenderChoice() {
        contentArea.removeAllViews();
        String name=queryName(pendingFile); long size=querySize(pendingFile);
        contentArea.addView(txt("Send " + name,18,Color.WHITE,true));
        contentArea.addView(txt(size>=0?format(size):"Selected file",13,Color.rgb(150,171,194),false));
        Button scan=primary("Scan receiver QR"); scan.setOnClickListener(v->scanReceiverQr()); contentArea.addView(scan,buttonParams(12));
        Button nearby=secondary("Find receiver nearby"); nearby.setOnClickListener(v->discover()); contentArea.addView(nearby,buttonParams(10));
        Button back=secondary("Cancel"); back.setOnClickListener(v->showHome()); contentArea.addView(back,buttonParams(10));
        setStatus("File selected", "Now pair with the receiving phone", 1); stepText.setText("2  Pair with receiver");
    }

    private void startReceiveMode() {
        mode=MODE_RECEIVE; sessionToken=UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.US); contentArea.removeAllViews();
        contentArea.addView(txt("Ready to receive",20,Color.WHITE,true));
        contentArea.addView(txt("Keep this screen open. The sender can scan your QR or find this phone nearby.",14,Color.rgb(150,174,197),false));
        TextView qrHint=txt("Preparing pairing QR…",14,Color.rgb(56,189,248),true); qrHint.setTag("qrHint"); qrHint.setPadding(0,dp(14),0,dp(6)); contentArea.addView(qrHint);
        Button nearby=secondary("Also make me discoverable nearby"); nearby.setOnClickListener(v->discover()); contentArea.addView(nearby,buttonParams(10));
        Button cancel=secondary("Stop receiving");cancel.setOnClickListener(v->showHome());contentArea.addView(cancel,buttonParams(10));
        setStatus("Creating receiving session…","One moment",1);stepText.setText("2  Waiting for sender");
        if(!hasPermission()){requestPermission();return;}
        manager.createGroup(channel,new WifiP2pManager.ActionListener(){public void onSuccess(){setStatus("Waiting for sender","Scan the QR from the sending phone",1);manager.requestDeviceInfo(channel,d->{thisDevice=d;refreshReceiverQr();});}public void onFailure(int r){setStatus("Could not start receiving","Wi‑Fi Direct error "+r,3);}});
    }

    private void refreshReceiverQr() {
        if(mode!=MODE_RECEIVE||thisDevice==null||thisDevice.deviceAddress==null)return;
        runOnUiThread(()->{
            View old=contentArea.findViewWithTag("qrImage"); if(old!=null)contentArea.removeView(old);
            TextView hint=(TextView)contentArea.findViewWithTag("qrHint"); if(hint!=null)hint.setText("Scan this QR on the sending phone");
            try {
                String payload="OPTISHARE2|"+sessionToken+"|"+thisDevice.deviceAddress+"|"+nameOf(thisDevice);
                ImageView img=new ImageView(this);img.setTag("qrImage");img.setImageBitmap(makeQr(payload,720));img.setAdjustViewBounds(true);img.setPadding(dp(8),dp(8),dp(8),dp(8));GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(14));img.setBackground(bg);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,dp(300));contentArea.addView(img,2,ip);
            } catch(Exception e){if(hint!=null)hint.setText("QR error: "+safe(e));}
        });
    }

    private void scanReceiverQr() {
        ScanOptions o=new ScanOptions();o.setPrompt("Scan the receiver's OptiShare QR");o.setBeepEnabled(false);o.setOrientationLocked(false);o.setDesiredBarcodeFormats(ScanOptions.QR_CODE);scanner.launch(o);
    }

    private void handleQr(String value) {
        if(mode!=MODE_SEND||pendingFile==null){showMessage("Choose a file first","Tap Send files, choose the file, then scan the receiver QR.");return;}
        if(value==null||!value.startsWith("OPTISHARE2|")){showMessage("Invalid QR","This is not an OptiShare receiving QR.");return;}
        String[] p=value.split("\\|",4);if(p.length<4){showMessage("Invalid QR","Pairing information is incomplete.");return;}
        pendingQrAddress=p[2];pendingQrName=p[3];setStatus("Receiver identified",pendingQrName+" • connecting automatically…",1);stepText.setText("3  Connecting automatically");discover();
    }

    private void discover() {
        if(!hasPermission()){requestPermission();return;}
        setStatus(mode==MODE_SEND?"Finding receiver…":"Waiting for sender…","Searching nearby",1);
        manager.discoverPeers(channel,new WifiP2pManager.ActionListener(){public void onSuccess(){manager.requestPeers(channel,DirectActivity.this);}public void onFailure(int r){setStatus("Search failed","Wi‑Fi Direct error "+r,3);}});
    }

    @Override public void onPeersAvailable(WifiP2pDeviceList list) {
        peers.clear();peers.addAll(list.getDeviceList());
        if(mode!=MODE_SEND)return;
        WifiP2pDevice match=null;
        if(pendingQrAddress!=null){for(WifiP2pDevice d:peers){if(pendingQrAddress.equalsIgnoreCase(d.deviceAddress)){match=d;break;}}if(match==null&&pendingQrName!=null){for(WifiP2pDevice d:peers){if(pendingQrName.equals(nameOf(d))){match=d;break;}}}}
        if(match!=null){connect(match);return;}
        showNearbyChoices();
    }

    private void showNearbyChoices() {
        runOnUiThread(()->{
            contentArea.removeAllViews();contentArea.addView(txt("Choose receiving phone",18,Color.WHITE,true));
            if(peers.isEmpty()){contentArea.addView(txt("No receiver found yet. Open Receive on the other phone, then try again.",14,Color.rgb(170,185,200),false));Button retry=primary("Search again");retry.setOnClickListener(v->discover());contentArea.addView(retry,buttonParams(12));}
            else for(WifiP2pDevice d:peers){Button b=secondary(nameOf(d)+"\n"+deviceStatus(d.status));b.setOnClickListener(v->connect(d));contentArea.addView(b,buttonParams(8));}
            Button scan=secondary("Scan QR instead");scan.setOnClickListener(v->scanReceiverQr());contentArea.addView(scan,buttonParams(10));
        });
    }

    private void connect(WifiP2pDevice d) {
        WifiP2pConfig cfg=new WifiP2pConfig();cfg.deviceAddress=d.deviceAddress;cfg.groupOwnerIntent=0;cfg.wps.setup=WpsInfo.PBC;
        setStatus("Connecting…",nameOf(d),1);stepText.setText("3  Connecting to "+nameOf(d));
        manager.connect(channel,cfg,new WifiP2pManager.ActionListener(){public void onSuccess(){setStatus("Connection request sent","Android may briefly show a Wi‑Fi Direct confirmation on the receiver",1);}public void onFailure(int r){setStatus("Connection failed","Wi‑Fi Direct error "+r,3);}});
    }

    @Override public void onConnectionInfoAvailable(WifiP2pInfo info) {
        if(!info.groupFormed||info.groupOwnerAddress==null)return;
        setStatus("Direct link established","Opening transfer channel…",2);
        if(info.isGroupOwner)startServer(); else startClient(info.groupOwnerAddress.getHostAddress());
    }

    private void startServer() {
        if(socketStarting||ready())return;socketStarting=true;
        io.execute(()->{try(ServerSocket server=new ServerSocket(PORT)){server.setReuseAddress(true);Socket s=server.accept();installSocket(s);}catch(Exception e){setStatus("Transfer channel failed",safe(e),3);}finally{socketStarting=false;}});
    }

    private void startClient(String host) {
        if(socketStarting||ready())return;socketStarting=true;
        io.execute(()->{try{Thread.sleep(250);Socket s=new Socket();s.connect(new InetSocketAddress(host,PORT),8000);installSocket(s);}catch(Exception e){setStatus("Transfer channel failed","Could not open socket",3);}finally{socketStarting=false;}});
    }

    private synchronized void installSocket(Socket s)throws Exception {
        closeSocket();socket=s;socket.setTcpNoDelay(true);input=new DataInputStream(new BufferedInputStream(s.getInputStream(),128*1024));output=new DataOutputStream(new BufferedOutputStream(s.getOutputStream(),128*1024));
        setStatus("Connected ✓",mode==MODE_SEND?"Receiver ready — sending file now":"Sender connected — waiting for file request",2);stepText.setText(mode==MODE_SEND?"4  Sending file":"3  Sender connected");
        if(mode==MODE_SEND&&pendingFile!=null)sendPendingFile(); else if(mode==MODE_RECEIVE)io.execute(this::receiveOneFile);
    }

    private void sendPendingFile() {
        Uri uri=pendingFile;io.execute(()->{
            try {
                String name=queryName(uri);long total=querySize(uri);if(total<0)total=measure(uri);byte[] digest=hash(uri);final long size=total;
                synchronized(this){output.writeInt(MAGIC);output.writeUTF(name);output.writeLong(size);output.writeInt(digest.length);output.write(digest);output.flush();}
                setTransfer("Waiting for receiver",name+" • "+format(size),0);
                boolean accepted=input.readBoolean();
                if(!accepted){setTransfer("Receiver declined",name,0);setStatus("Connected ✓","Receiver declined the file",2);return;}
                byte[] buf=new byte[256*1024];long sent=0;long start=System.nanoTime();try(InputStream in=getContentResolver().openInputStream(uri)){int n;while((n=in.read(buf))!=-1){synchronized(this){output.write(buf,0,n);}sent+=n;updateProgress(true,name,sent,size,start);}}synchronized(this){output.flush();}
                setTransfer("Sent ✓",name+" • "+format(size),1000);setStatus("Connected ✓","Transfer complete",2);stepText.setText("✓  File sent successfully");
            } catch(Exception e){setTransfer("Send failed",safe(e),0);}
        });
    }

    private void receiveOneFile() {
        try {
            int magic=input.readInt();if(magic!=MAGIC)throw new IllegalStateException("Invalid OptiShare request");
            String name=sanitize(input.readUTF());long total=input.readLong();int hl=input.readInt();if(total<0||hl<=0||hl>128)throw new IllegalStateException("Invalid file metadata");byte[] expected=new byte[hl];input.readFully(expected);
            final boolean[] accepted={false};CountDownLatch latch=new CountDownLatch(1);
            runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Incoming file").setMessage(name+"\n"+format(total)+"\n\nAccept from connected sender?").setPositiveButton("Accept",(d,w)->{accepted[0]=true;latch.countDown();}).setNegativeButton("Decline",(d,w)->{accepted[0]=false;latch.countDown();}).setOnCancelListener(d->{accepted[0]=false;latch.countDown();}).show());
            if(!latch.await(60,TimeUnit.SECONDS)){accepted[0]=false;}
            synchronized(this){output.writeBoolean(accepted[0]);output.flush();}
            if(!accepted[0]){setStatus("Waiting for sender","File request declined",1);return;}
            File dir=receivedDir();if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Cannot create receive folder");File target=uniqueFile(dir,name);MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] buf=new byte[256*1024];long got=0;long start=System.nanoTime();try(BufferedOutputStream fos=new BufferedOutputStream(new FileOutputStream(target),256*1024)){while(got<total){int n=input.read(buf,0,(int)Math.min(buf.length,total-got));if(n<0)throw new EOFException("Connection ended");fos.write(buf,0,n);md.update(buf,0,n);got+=n;updateProgress(false,name,got,total,start);}fos.flush();}
            if(!Arrays.equals(expected,md.digest())){target.delete();throw new IllegalStateException("SHA-256 verification failed");}
            setTransfer("Received ✓",target.getName()+" • "+format(total)+" • verified",1000);setStatus("Connected ✓","File received successfully",2);stepText.setText("✓  File received successfully");
        } catch(Exception e){setTransfer("Receive failed",safe(e),0);}
    }

    private void updateProgress(boolean sending,String name,long done,long total,long start) {
        int p=total==0?1000:(int)Math.min(1000,done*1000L/total);double sec=Math.max(.001,(System.nanoTime()-start)/1e9);double mbps=(done/(1024d*1024d))/sec;setTransfer((sending?"Sending ":"Receiving ")+name,format(done)+" / "+format(total)+String.format(Locale.US," • %.2f MB/s • %d%%",mbps,p/10),p);
    }

    private void setTransfer(String title,String sub,int p){runOnUiThread(()->{transferTitle.setText(title);transferSub.setText(sub);progress.setProgress(p);});}
    private void setStatus(String title,String sub,int state){runOnUiThread(()->{statusTitle.setText(title);statusSub.setText(sub);int c=state==2?Color.rgb(57,217,138):state==1?Color.rgb(250,190,60):state==3?Color.rgb(255,94,94):Color.rgb(115,135,155);GradientDrawable g=new GradientDrawable();g.setShape(GradientDrawable.OVAL);g.setColor(c);statusDot.setBackground(g);});}

    private Bitmap makeQr(String value,int size)throws Exception{BitMatrix m=new MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,size,size);Bitmap b=Bitmap.createBitmap(size,size,Bitmap.Config.RGB_565);for(int y=0;y<size;y++)for(int x=0;x<size;x++)b.setPixel(x,y,m.get(x,y)?Color.BLACK:Color.WHITE);return b;}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(16),dp(16),dp(16),dp(16));GradientDrawable g=new GradientDrawable();g.setColor(Color.rgb(14,28,45));g.setCornerRadius(dp(18));g.setStroke(dp(1),Color.rgb(31,53,75));l.setBackground(g);return l;}
    private TextView txt(String s,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
    private Button primary(String s){Button b=new Button(this);b.setAllCaps(false);b.setText(s);b.setTextSize(15);b.setTextColor(Color.rgb(4,20,34));GradientDrawable g=new GradientDrawable();g.setColor(Color.rgb(56,189,248));g.setCornerRadius(dp(14));b.setBackground(g);return b;}
    private Button secondary(String s){Button b=new Button(this);b.setAllCaps(false);b.setText(s);b.setTextColor(Color.WHITE);GradientDrawable g=new GradientDrawable();g.setColor(Color.rgb(25,47,68));g.setCornerRadius(dp(14));b.setBackground(g);return b;}
    private Button action(String icon,String title,String sub){Button b=new Button(this);b.setAllCaps(false);b.setGravity(Gravity.CENTER);b.setText(icon+"\n"+title+"\n"+sub);b.setTextSize(15);b.setTextColor(Color.WHITE);GradientDrawable g=new GradientDrawable();g.setColor(Color.rgb(14,28,45));g.setCornerRadius(dp(18));g.setStroke(dp(1),Color.rgb(35,61,85));b.setBackground(g);return b;}
    private LinearLayout.LayoutParams buttonParams(int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52));p.setMargins(0,dp(top),0,0);return p;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    private String queryName(Uri uri){Cursor c=null;try{c=getContentResolver().query(uri,null,null,null,null);if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0){String n=c.getString(i);if(n!=null)return sanitize(n);}}}finally{if(c!=null)c.close();}return "file.bin";}
    private long querySize(Uri uri){Cursor c=null;try{c=getContentResolver().query(uri,null,null,null,null);if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.SIZE);if(i>=0&&!c.isNull(i))return c.getLong(i);}}finally{if(c!=null)c.close();}return -1;}
    private long measure(Uri uri)throws Exception{long n=0;byte[] b=new byte[128*1024];try(InputStream in=getContentResolver().openInputStream(uri)){int r;while((r=in.read(b))!=-1)n+=r;}return n;}
    private byte[] hash(Uri uri)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[256*1024];try(InputStream in=getContentResolver().openInputStream(uri)){int r;while((r=in.read(b))!=-1)md.update(b,0,r);}return md.digest();}
    private File receivedDir(){return new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"OptiShare Received");}
    private File uniqueFile(File d,String n){File f=new File(d,n);if(!f.exists())return f;int dot=n.lastIndexOf('.');String a=dot>0?n.substring(0,dot):n,e=dot>0?n.substring(dot):"";for(int i=1;i<9999;i++){File x=new File(d,a+" ("+i+")"+e);if(!x.exists())return x;}return new File(d,System.currentTimeMillis()+"-"+n);}
    private String sanitize(String n){return n.replace("/","_").replace("\\","_").replace("\u0000","_");}
    private String nameOf(WifiP2pDevice d){String n=d==null?null:d.deviceName;return n==null||n.trim().isEmpty()?"Android device":n.trim();}
    private String deviceStatus(int s){switch(s){case WifiP2pDevice.CONNECTED:return"Connected";case WifiP2pDevice.INVITED:return"Invitation pending";case WifiP2pDevice.AVAILABLE:return"Available";case WifiP2pDevice.UNAVAILABLE:return"Busy";default:return"Nearby";}}
    private String format(long b){if(b<1024)return b+" B";if(b<1024L*1024)return String.format(Locale.US,"%.1f KB",b/1024d);if(b<1024L*1024*1024)return String.format(Locale.US,"%.2f MB",b/(1024d*1024d));return String.format(Locale.US,"%.2f GB",b/(1024d*1024d*1024d));}
    private String safe(Throwable e){String m=e.getMessage();return m==null||m.isEmpty()?e.getClass().getSimpleName():m;}
    private void showMessage(String t,String m){runOnUiThread(()->new AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("OK",null).show());}

    private boolean hasPermission(){return Build.VERSION.SDK_INT>=33?checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)==PackageManager.PERMISSION_GRANTED:checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    private void requestPermission(){if(hasPermission())return;if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},REQ_NEARBY);else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},REQ_NEARBY);}
    private boolean ready(){return socket!=null&&socket.isConnected()&&!socket.isClosed()&&input!=null&&output!=null;}
    private synchronized void closeSocket(){try{if(input!=null)input.close();}catch(Exception ignored){}try{if(output!=null)output.close();}catch(Exception ignored){}try{if(socket!=null)socket.close();}catch(Exception ignored){}input=null;output=null;socket=null;socketStarting=false;}

    @Override protected void onResume(){super.onResume();IntentFilter f=new IntentFilter();f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);f.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);ContextCompat.registerReceiver(this,p2pReceiver,f,ContextCompat.RECEIVER_NOT_EXPORTED);}
    @Override protected void onPause(){super.onPause();try{unregisterReceiver(p2pReceiver);}catch(Exception ignored){}}
    @Override protected void onDestroy(){closeSocket();io.shutdownNow();super.onDestroy();}
    @Override public void onBackPressed(){showHome();}

    private static class QuietAction implements WifiP2pManager.ActionListener{public void onSuccess(){}public void onFailure(int r){}}
}
