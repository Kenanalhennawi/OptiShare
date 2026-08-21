package com.kenan.optishare;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

public class DirectActivity extends Activity implements WifiP2pManager.PeerListListener, WifiP2pManager.ConnectionInfoListener {
    private static final int REQ=707;
    private WifiP2pManager manager; private WifiP2pManager.Channel channel;
    private final List<WifiP2pDevice> peers=new ArrayList<>();
    private LinearLayout peerList; private TextView status;

    private final BroadcastReceiver receiver=new BroadcastReceiver(){ @Override public void onReceive(Context c,Intent i){
        String a=i.getAction();
        if(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(a)){ if(hasPermission()) manager.requestPeers(channel,DirectActivity.this); }
        else if(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(a)){
            NetworkInfo ni=i.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
            if(ni!=null&&ni.isConnected()){ status.setText("CONNECTED ✓  Getting direct-link details…"); manager.requestConnectionInfo(channel,DirectActivity.this); }
            else status.setText("Ready — discover the second phone");
        }
        else if(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(a)){
            int s=i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,-1); if(s!=WifiP2pManager.WIFI_P2P_STATE_ENABLED) status.setText("Wi‑Fi Direct is disabled on this phone");
        }
    }};

    @Override protected void onCreate(Bundle b){super.onCreate(b); manager=(WifiP2pManager)getSystemService(WIFI_P2P_SERVICE); channel=manager.initialize(this,getMainLooper(),()->status.setText("Wi‑Fi Direct channel lost")); buildUi(); requestNeededPermission();}

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(28,24,28,24);root.setBackgroundColor(Color.rgb(7,17,31));
        TextView title=t("OptiShare Direct",28,Color.WHITE);title.setGravity(Gravity.CENTER);root.addView(title);
        TextView desc=t("Bring both phones close. Open this screen on both devices, then tap Discover. Select the other phone to create a private direct link.",14,Color.rgb(160,205,230));desc.setGravity(Gravity.CENTER);root.addView(desc);
        status=t("Ready",16,Color.WHITE);status.setGravity(Gravity.CENTER);root.addView(status);
        Button discover=new Button(this);discover.setText("Discover nearby OptiShare devices");discover.setAllCaps(false);discover.setOnClickListener(v->discover());root.addView(discover,new LinearLayout.LayoutParams(-1,-2));
        Button settings=new Button(this);settings.setText("Open Wi‑Fi settings");settings.setAllCaps(false);settings.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));root.addView(settings,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(this);peerList=new LinearLayout(this);peerList.setOrientation(LinearLayout.VERTICAL);scroll.addView(peerList);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView foot=t("Prototype goal: establish the high-speed peer-to-peer transport first. File streaming + progress comes next after connection is confirmed on your two phones.\n\nDesigned & developed by Kenan Alhennawi",13,Color.rgb(56,189,248));foot.setGravity(Gravity.CENTER);root.addView(foot);
        setContentView(root);
    }
    private TextView t(String s,int z,int c){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);v.setPadding(10,10,10,10);return v;}
    private boolean hasPermission(){return Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)==PackageManager.PERMISSION_GRANTED;}
    private void requestNeededPermission(){if(Build.VERSION.SDK_INT>=33&&!hasPermission())requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},REQ);else if(Build.VERSION.SDK_INT<33&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},REQ);}
    private void discover(){if(!hasPermission()&&Build.VERSION.SDK_INT>=33){requestNeededPermission();return;} status.setText("DISCOVERING… keep this screen open on both phones");manager.discoverPeers(channel,new WifiP2pManager.ActionListener(){public void onSuccess(){status.setText("DISCOVERING… waiting for nearby devices");}public void onFailure(int r){status.setText("Discovery failed (code "+r+")");}});}
    @Override public void onPeersAvailable(WifiP2pDeviceList list){peers.clear();peers.addAll(list.getDeviceList());peerList.removeAllViews(); if(peers.isEmpty()){peerList.addView(t("No devices found yet. Tap Discover on both phones.",15,Color.LTGRAY));return;} for(WifiP2pDevice d:peers){Button b=new Button(this);String n=(d.deviceName==null||d.deviceName.isEmpty())?"Android device":d.deviceName;b.setText(n+"\nTap to connect");b.setAllCaps(false);b.setOnClickListener(v->connect(d));peerList.addView(b,new LinearLayout.LayoutParams(-1,-2));}}
    private void connect(WifiP2pDevice d){WifiP2pConfig cfg=new WifiP2pConfig();cfg.deviceAddress=d.deviceAddress;status.setText("CONNECTING to "+d.deviceName+"…");manager.connect(channel,cfg,new WifiP2pManager.ActionListener(){public void onSuccess(){status.setText("Connection request sent — approve it on the other phone if Android asks");}public void onFailure(int r){status.setText("Connection failed (code "+r+")");}});}
    @Override public void onConnectionInfoAvailable(WifiP2pInfo info){String role=info.isGroupOwner?"HOST":"CLIENT";String host=info.groupOwnerAddress==null?"pending":info.groupOwnerAddress.getHostAddress();status.setText("DIRECT LINK READY ✓\nRole: "+role+" • Host: "+host+"\nNext stage: encrypted file streaming");}
    @Override protected void onResume(){super.onResume();IntentFilter f=new IntentFilter();f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);registerReceiver(receiver,f);}
    @Override protected void onPause(){super.onPause();try{unregisterReceiver(receiver);}catch(Exception ignored){}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
