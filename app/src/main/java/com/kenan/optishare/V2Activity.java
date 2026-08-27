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
import android.location.LocationManager;
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
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
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
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.kenan.optishare.device.DeviceIdentity;
import com.kenan.optishare.device.DeviceIdentityKey;
import com.kenan.optishare.device.TrustedDeviceStore;
import com.kenan.optishare.history.TransferHistoryStore;
import com.kenan.optishare.storage.MediaRepository;
import com.kenan.optishare.transfer.LanDiscovery;
import com.kenan.optishare.transfer.RoutePerformanceStore;
import com.kenan.optishare.transfer.SenderSessionStore;
import com.kenan.optishare.transfer.TransferService;
import com.kenan.optishare.ui.GalleryAdapter;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class V2Activity extends ComponentActivity implements
        WifiP2pManager.PeerListListener,
        WifiP2pManager.ConnectionInfoListener {

    private static final int REQ_MEDIA = 2101;
    private static final int REQ_NEARBY = 2102;
    private static final int REQ_LEGACY_WRITE = 2103;
    private static final int SCREEN_HOME = 0;
    private static final int SCREEN_GALLERY = 1;
    private static final int SCREEN_SEND = 2;
    private static final int SCREEN_DISCOVERY = 3;
    private static final int SCREEN_RECEIVE = 4;
    private static final int SCREEN_TRANSFER = 5;

    private final List<Uri> selected = new ArrayList<>();
    private final List<WifiP2pDevice> peers = new ArrayList<>();
    private int currentScreen = SCREEN_HOME;
    private String pendingGalleryType;
    private String pendingQrAddress;
    private String pendingQrName;
    private String connectedPeerName = "Nearby device";
    private boolean receiverMode;
    private boolean transferStarted;

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private WifiP2pDevice thisDevice;

    private LinearLayout peerList;
    private TextView discoveryState;
    private TextView connectionPill;
    private TextView transferState;
    private TextView transferDetail;
    private ProgressBar transferProgress;
    private Button transferPauseButton;
    private boolean transferPaused;

    private DeviceIdentity identity;
    private TransferHistoryStore historyStore;
    private TrustedDeviceStore trustedStore;
    private RoutePerformanceStore routeStore;
    private SenderSessionStore senderSessionStore;
    private LanDiscovery lanDiscovery;
    private String activeRoute = RoutePerformanceStore.ROUTE_DIRECT;
    private String pendingLanHost;
    private String pendingLanName;
    private long activeTransferStartedAt;
    private final Handler discoveryHandler = new Handler(Looper.getMainLooper());
    private int discoveryAttempt;
    private static final int MAX_DISCOVERY_ATTEMPTS = 8;
    private static final long DISCOVERY_RETRY_MS = 3500L;
    private final Runnable discoveryRetry = () -> {
        if (currentScreen == SCREEN_DISCOVERY && !transferStarted && peers.isEmpty()) {
            runDiscoveryPass();
        }
    };
    private final Runnable lanFallbackConnect = () -> {
        if (currentScreen == SCREEN_DISCOVERY && !transferStarted && peers.isEmpty()
                && pendingLanHost != null) {
            connectViaLan(pendingLanName, pendingLanHost);
        }
    };

    private final ActivityResultLauncher<Intent> externalPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Intent data = result.getData();
                int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        Uri uri = data.getClipData().getItemAt(i).getUri();
                        persistReadPermission(uri, takeFlags);
                        if (!selected.contains(uri)) selected.add(uri);
                    }
                } else if (data.getData() != null) {
                    Uri uri = data.getData();
                    persistReadPermission(uri, takeFlags);
                    if (!selected.contains(uri)) selected.add(uri);
                }
                showSendSelection();
            });

    private final ActivityResultLauncher<ScanOptions> qrScanner =
            registerForActivityResult(new ScanContract(), result -> {
                if (result == null || result.getContents() == null) return;
                handlePairingQr(result.getContents());
            });

    private final BroadcastReceiver p2pReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                thisDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if (currentScreen == SCREEN_RECEIVE) refreshReceiverIdentity();
                return;
            }
            if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                if (hasNearbyPermission()) {
                    try { manager.requestPeers(channel, V2Activity.this); }
                    catch (SecurityException ignored) { showNearbyPermissionHelp(); }
                }
                return;
            }
            if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                NetworkInfo info = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if (info != null && info.isConnected()) {
                    setConnectionUi("CONNECTED ✓", Color.rgb(65, 225, 151));
                    try { manager.requestConnectionInfo(channel, V2Activity.this); }
                    catch (SecurityException ignored) { showNearbyPermissionHelp(); }
                } else if (currentScreen == SCREEN_DISCOVERY || currentScreen == SCREEN_RECEIVE || currentScreen == SCREEN_TRANSFER) {
                    setConnectionUi("RECONNECTING…", Color.rgb(255, 188, 70));
                    if (transferStarted) setTransferUi("Connection interrupted", "OptiShare will continue from the last confirmed chunk when the direct link returns.", -1);
                }
                return;
            }
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    setDiscoveryText("Wi‑Fi Direct is off. Turn Wi‑Fi on to continue.");
                }
            }
        }
    };

    private final BroadcastReceiver transferReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String event = intent.getStringExtra(TransferService.EXTRA_EVENT);
            String message = intent.getStringExtra(TransferService.EXTRA_MESSAGE);
            int progress = intent.getIntExtra(TransferService.EXTRA_PROGRESS, 0);
            double speed = intent.getDoubleExtra(TransferService.EXTRA_SPEED, 0d);
            long done = intent.getLongExtra(TransferService.EXTRA_DONE, 0L);
            long total = intent.getLongExtra(TransferService.EXTRA_TOTAL, 0L);
            long etaSeconds = intent.getLongExtra(TransferService.EXTRA_ETA_SECONDS, 0L);
            double averageSpeed = intent.getDoubleExtra(TransferService.EXTRA_AVG_SPEED, 0d);
            long durationMs = intent.getLongExtra(TransferService.EXTRA_DURATION_MS, 0L);
            int reconnects = intent.getIntExtra(TransferService.EXTRA_RECONNECTS, 0);
            int completedFileCount = intent.getIntExtra(TransferService.EXTRA_FILE_COUNT, 0);
            long completedTotalBytes = intent.getLongExtra(TransferService.EXTRA_TOTAL_BYTES, 0L);
            String completedRoute = intent.getStringExtra(TransferService.EXTRA_ROUTE);
            if (event == null) return;
            if (!transferStarted && ("incoming".equals(event) || "progress".equals(event) || "security".equals(event))) {
                transferStarted = true;
                activeTransferStartedAt = System.currentTimeMillis();
                showTransferScreen(receiverMode ? "Receiving" : "Sending");
            }
            if ("security".equals(event)) {
                setTransferUi("Secure connection established", message, -1);
            } else if ("trusted_peer".equals(event)) {
                setConnectionUi("TRUSTED DEVICE ✓", Color.rgb(65, 225, 151));
                if (currentScreen == SCREEN_TRANSFER)
                    setTransferUi("Trusted device verified ✓", message, -1);
            } else if ("receiver_ready".equals(event)) {
                setDiscoveryText(message);
                setConnectionUi("READY • DIRECT + WI-FI", Color.rgb(65, 222, 151));
            } else if ("incoming".equals(event)) {
                setTransferUi("Incoming batch", message, 0);
            } else if ("progress".equals(event)) {
                transferPaused=false;
                updatePauseButton(false);
                setTransferUi(receiverMode ? "Receiving…" : "Sending…", message, progress);
                setTransferMetrics(progress, done, total, speed, etaSeconds);
            } else if ("paused".equals(event)) {
                transferPaused=true;
                setConnectionUi("PAUSED", Color.rgb(255,188,70));
                setTransferUi("Transfer paused", message, progress);
                updatePauseButton(true);
            } else if ("pause_unavailable".equals(event)) {
                setTransferUi("Finishing secure setup", message, -1);
            } else if ("reconnecting".equals(event)) {
                setConnectionUi("RECONNECTING…", Color.rgb(255, 188, 70));
                setTransferUi("Reconnecting automatically", message, -1);
            } else if ("file_done".equals(event)) {
                setTransferUi("File verified ✓", message, -1);
            } else if ("completed".equals(event)) {
                setConnectionUi("COMPLETED ✓", Color.rgb(65, 225, 151));
                setTransferUi("Transfer complete ✓", message, 100);
                historyStore.add(new TransferHistoryStore.Entry(
                        System.currentTimeMillis(), receiverMode ? "received" : "sent",
                        connectedPeerName, completedFileCount > 0 ? completedFileCount : selected.size(),
                        completedTotalBytes > 0 ? completedTotalBytes : selectedTotalBytes(), true,
                        durationMs, averageSpeed, completedRoute, reconnects));
                transferStarted = false;
                transferPaused=false;
                updatePauseButton(false);
            } else if ("error".equals(event)) {
                setConnectionUi("TRANSFER ERROR", Color.rgb(255, 92, 102));
                setTransferUi("Transfer could not continue", message, -1);
                historyStore.add(new TransferHistoryStore.Entry(
                        System.currentTimeMillis(), receiverMode ? "received" : "sent",
                        connectedPeerName, selected.size(), selectedTotalBytes(), false));
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        identity = new DeviceIdentity(this);
        historyStore = new TransferHistoryStore(this);
        trustedStore = new TrustedDeviceStore(this);
        routeStore = new RoutePerformanceStore(this);
        senderSessionStore = new SenderSessionStore(this);
        lanDiscovery = new LanDiscovery(this);
        manager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        if (manager != null) {
            channel = manager.initialize(this, getMainLooper(), () -> setDiscoveryText("Nearby service restarted. Try again."));
        }
        requestNotificationPermissionIfUseful();
        showHome();
    }

    private void showHome() {
        currentScreen = SCREEN_HOME;
        receiverMode = false;
        transferStarted = false;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = shell(scroll);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = text("O", 24, Color.WHITE, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(gradient(Color.rgb(28,165,255), Color.rgb(91,73,245), 24));
        top.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(12),0,0,0);
        titleBox.addView(text("OptiShare 2", 29, Color.WHITE, true));
        titleBox.addView(text(identity.name() + " • Private local sharing", 12, Color.rgb(157,198,228), false));
        top.addView(titleBox, new LinearLayout.LayoutParams(0,-2,1));
        Button settings = smallButton("Device");
        settings.setOnClickListener(v -> showDeviceSettings());
        top.addView(settings, new LinearLayout.LayoutParams(dp(86), dp(42)));
        root.addView(top);

        TextView hero = text("Fast. Private. Resumable.", 28, Color.WHITE, true);
        hero.setPadding(0,dp(26),0,dp(5));
        root.addView(hero);
        root.addView(text("Send multiple files without Internet. If the link drops, OptiShare resumes from the last verified chunk instead of starting over.", 14, Color.rgb(177,207,230), false));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button send = bigAction("↑", "SEND", "Choose content", Color.rgb(35,146,255), Color.rgb(53,82,222));
        send.setOnClickListener(v -> showSendSelection());
        Button receive = bigAction("↓", "RECEIVE", "Become visible", Color.rgb(49,205,145), Color.rgb(17,122,91));
        receive.setOnClickListener(v -> showReceive());
        actions.addView(send,new LinearLayout.LayoutParams(0,dp(158),1));
        LinearLayout.LayoutParams receiveLp = new LinearLayout.LayoutParams(0,dp(158),1);
        receiveLp.setMargins(dp(10),0,0,0);
        actions.addView(receive,receiveLp);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1,-2);
        actionsLp.setMargins(0,dp(22),0,0);
        root.addView(actions,actionsLp);

        if(senderSessionStore.exists()){
            LinearLayout resumeCard=card();
            resumeCard.addView(text("Pending transfer",15,Color.WHITE,true));
            resumeCard.addView(text("Confirmed progress is saved. Resume from the last verified chunk instead of starting over.",12,Color.rgb(158,188,211),false));
            Button resume=primary("Resume pending transfer →");resume.setOnClickListener(v->resumePendingTransfer());
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(50));rp.setMargins(0,dp(10),0,0);resumeCard.addView(resume,rp);
            LinearLayout.LayoutParams rcp=new LinearLayout.LayoutParams(-1,-2);rcp.setMargins(0,dp(14),0,0);root.addView(resumeCard,rcp);
        }

        TextView browse = text("Browse by type",18,Color.WHITE,true);
        browse.setPadding(0,dp(24),0,dp(10));
        root.addView(browse);
        root.addView(categoryRow(
                category("▣","Photos",Color.rgb(190,83,255),v -> openInternalGallery("image")),
                category("▶","Videos",Color.rgb(255,78,110),v -> openInternalGallery("video")),
                category("♫","Music",Color.rgb(255,169,50),v -> openExternal("audio/*"))));
        LinearLayout row2 = categoryRow(
                category("A","Apps",Color.rgb(53,203,165),v -> openExternal("application/vnd.android.package-archive")),
                category("≡","Documents",Color.rgb(55,143,255),v -> openExternal("application/*")),
                category("…","Other",Color.rgb(122,140,166),v -> openExternal("*/*")));
        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(-1,-2); r2.setMargins(0,dp(10),0,0); root.addView(row2,r2);

        addHistory(root);

        LinearLayout security = card();
        security.addView(text("Privacy by design",15,Color.WHITE,true));
        security.addView(text("• No account or cloud required\n• Ephemeral ECDH key exchange\n• AES-256-GCM authenticated transfer\n• SHA-256 verification before publishing files",12,Color.rgb(156,185,209),false));
        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(-1,-2); secLp.setMargins(0,dp(16),0,0); root.addView(security,secLp);

        TextView footer = text("Received files → Download/OptiShare/{Photos, Videos, Music, Apps, Documents, Archives, Other}\nDesigned & developed by Kenan Alhennawi",11,Color.rgb(116,165,199),false);
        footer.setGravity(Gravity.CENTER); footer.setPadding(0,dp(18),0,0); root.addView(footer);
        setContentView(scroll);
    }

    private void addHistory(LinearLayout root) {
        List<TransferHistoryStore.Entry> entries = historyStore.load();
        TextView title = text("Recent transfers",18,Color.WHITE,true);
        title.setPadding(0,dp(24),0,dp(10)); root.addView(title);
        LinearLayout card = card();
        if (entries.isEmpty()) {
            card.addView(text("No transfers yet",13,Color.rgb(150,178,202),false));
        } else {
            int count = Math.min(3, entries.size());
            for (int i=0;i<count;i++) {
                TransferHistoryStore.Entry e = entries.get(i);
                String line = ("sent".equals(e.direction) ? "↑ Sent" : "↓ Received") + " • " + e.peer + " • " + (e.success ? "✓" : "Failed");
                card.addView(text(line,13,Color.WHITE,true));
                StringBuilder meta=new StringBuilder();
                meta.append(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(e.time)));
                if(e.totalBytes>0)meta.append(" • ").append(formatBytes(e.totalBytes));
                if(e.averageBytesPerSecond>0)meta.append(" • avg ").append(formatTransferSpeed(e.averageBytesPerSecond));
                if(e.durationMs>0)meta.append(" • ").append(formatHistoryDuration(e.durationMs));
                if(!"unknown".equals(e.route))meta.append(" • ").append(historyRouteLabel(e.route));
                if(e.reconnects>0)meta.append(" • ").append(e.reconnects).append(e.reconnects==1?" reconnect":" reconnects");
                card.addView(text(meta.toString(),11,Color.rgb(133,162,187),false));
            }
        }
        root.addView(card);
    }

    private void showSendSelection() {
        currentScreen = SCREEN_SEND;
        receiverMode = false;
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = shell(scroll);
        addBackHeader(root,"Send","Build one batch from photos, videos, apps and documents");
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        Button photos=smallButton("Photos"); photos.setOnClickListener(v->openInternalGallery("image"));
        Button videos=smallButton("Videos"); videos.setOnClickListener(v->openInternalGallery("video"));
        Button files=smallButton("Files"); files.setOnClickListener(v->openExternal("*/*"));
        tabs.addView(photos,new LinearLayout.LayoutParams(0,dp(46),1));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);p.setMargins(dp(8),0,0,0);tabs.addView(videos,p);
        LinearLayout.LayoutParams p2=new LinearLayout.LayoutParams(0,dp(46),1);p2.setMargins(dp(8),0,0,0);tabs.addView(files,p2);root.addView(tabs);

        TextView count=text(selected.size()+" item"+(selected.size()==1?"":"s")+" selected • "+formatBytes(selectedTotalBytes()),18,Color.WHITE,true);
        count.setPadding(0,dp(18),0,dp(8));root.addView(count);
        LinearLayout selection=card();
        if(selected.isEmpty()) selection.addView(text("Nothing selected yet. Photos and Videos open inside OptiShare; Files opens Android's document picker.",13,Color.rgb(156,181,202),false));
        else {
            int show=Math.min(selected.size(),10);
            for(int i=0;i<show;i++) selection.addView(text("✓ "+displayName(selected.get(i)),13,Color.WHITE,false));
            if(selected.size()>show) selection.addView(text("+ "+(selected.size()-show)+" more",12,Color.rgb(82,196,255),true));
            Button clear=secondaryButton("Clear selection");clear.setOnClickListener(v->{selected.clear();showSendSelection();});
            LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(-1,dp(46));cl.setMargins(0,dp(10),0,0);selection.addView(clear,cl);
        }
        root.addView(selection);
        Button find=primary(selected.isEmpty()?"Select files first":"Find receiving device →");
        find.setEnabled(!selected.isEmpty());find.setAlpha(selected.isEmpty()?.45f:1f);find.setOnClickListener(v->showDiscovery());
        LinearLayout.LayoutParams fl=new LinearLayout.LayoutParams(-1,dp(58));fl.setMargins(0,dp(14),0,0);root.addView(find,fl);
        setContentView(scroll);
    }

    private void openInternalGallery(String type) {
        pendingGalleryType=type;
        if(!hasMediaPermission(type)){requestMediaPermission(type);return;}
        showMediaGallery(type);
    }

    private void showMediaGallery(String type) {
        currentScreen=SCREEN_GALLERY;
        ScrollView outer=new ScrollView(this);
        LinearLayout root=shell(outer);
        addBackHeader(root,"image".equals(type)?"Photos":"Videos","Tap to select multiple items");
        TextView selectedCount=text(selected.size()+" selected",14,Color.rgb(92,202,255),true);selectedCount.setGravity(Gravity.CENTER);root.addView(selectedCount);

        RecyclerView recycler=new RecyclerView(this);
        recycler.setNestedScrollingEnabled(false);
        recycler.setLayoutManager(new GridLayoutManager(this,3));
        Set<Uri> initial=new HashSet<>(selected);
        GalleryAdapter adapter=new GalleryAdapter(initial,set->{
            selected.clear();selected.addAll(set);selectedCount.setText(selected.size()+" selected");
        });
        adapter.replace(new MediaRepository(this).load(type,180,0));
        recycler.setAdapter(adapter);
        root.addView(recycler,new LinearLayout.LayoutParams(-1,dp(760)));

        Button done=primary("Done • "+selected.size()+" selected");done.setOnClickListener(v->{selected.clear();selected.addAll(adapter.selection());showSendSelection();});
        LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(-1,dp(56));dl.setMargins(0,dp(12),0,0);root.addView(done,dl);
        setContentView(outer);
    }

    private void showDiscovery() {
        currentScreen=SCREEN_DISCOVERY;
        receiverMode=false;
        pendingQrAddress=null;pendingQrName=null;
        ScrollView scroll=new ScrollView(this);LinearLayout root=shell(scroll);addBackHeader(root,"Nearby devices",selected.size()+" items • "+formatBytes(selectedTotalBytes()));
        connectionPill=connectionBadge("SEARCHING",Color.rgb(255,194,73));root.addView(connectionPill);
        LinearLayout radar=card();TextView icon=text("◎",76,Color.rgb(80,198,255),true);icon.setGravity(Gravity.CENTER);radar.addView(icon);
        discoveryState=text("Searching for receiving phones…",16,Color.WHITE,true);discoveryState.setGravity(Gravity.CENTER);radar.addView(discoveryState);
        TextView hint=text("SmartRoute chooses Wi-Fi Direct or same-Wi-Fi automatically. "+routeStore.summary()+" • QR remains a fallback.",12,Color.rgb(150,179,202),false);hint.setGravity(Gravity.CENTER);hint.setPadding(0,dp(6),0,0);radar.addView(hint);root.addView(radar);
        LinearLayout qrRow=new LinearLayout(this);qrRow.setOrientation(LinearLayout.HORIZONTAL);
        Button scan=secondaryButton("Scan receiver QR");scan.setOnClickListener(v->startQrScanner());qrRow.addView(scan,new LinearLayout.LayoutParams(0,dp(50),1));
        Button retry=secondaryButton("Search again");retry.setOnClickListener(v->startDiscovery());LinearLayout.LayoutParams rr=new LinearLayout.LayoutParams(0,dp(50),1);rr.setMargins(dp(8),0,0,0);qrRow.addView(retry,rr);
        LinearLayout.LayoutParams qrlp=new LinearLayout.LayoutParams(-1,-2);qrlp.setMargins(0,dp(12),0,0);root.addView(qrRow,qrlp);
        peerList=new LinearLayout(this);peerList.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams pl=new LinearLayout.LayoutParams(-1,-2);pl.setMargins(0,dp(12),0,0);root.addView(peerList,pl);
        setContentView(scroll);startDiscovery();
    }

    private void showReceive() {
        currentScreen=SCREEN_RECEIVE;receiverMode=true;selected.clear();
        if(!ensureLegacyWritePermission()){return;}
        ScrollView scroll=new ScrollView(this);LinearLayout root=shell(scroll);addBackHeader(root,"Receive","Keep this screen open until the direct session is ready");
        connectionPill=connectionBadge("STARTING RECEIVER",Color.rgb(255,194,73));root.addView(connectionPill);
        LinearLayout receiveCard=card();TextView icon=text("◉",84,Color.rgb(65,222,151),true);icon.setGravity(Gravity.CENTER);receiveCard.addView(icon);
        discoveryState=text("Preparing private receiving session…",18,Color.WHITE,true);discoveryState.setGravity(Gravity.CENTER);receiveCard.addView(discoveryState);
        TextView identityLabel=text(identity.name(),14,Color.rgb(88,202,255),true);identityLabel.setGravity(Gravity.CENTER);identityLabel.setTag("receiver_identity");receiveCard.addView(identityLabel);
        ImageView qr=new ImageView(this);qr.setTag("receiver_qr");qr.setAdjustViewBounds(true);receiveCard.addView(qr,new LinearLayout.LayoutParams(-1,dp(260)));
        root.addView(receiveCard);
        Button stop=secondaryButton("Stop receiving");stop.setOnClickListener(v->{stopTransferService();safeRemoveGroup();showHome();});LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(-1,dp(50));sl.setMargins(0,dp(12),0,0);root.addView(stop,sl);
        setContentView(scroll);startReceiverService();startReceiverMode();
    }

    private void showTransferScreen(String title) {
        currentScreen=SCREEN_TRANSFER;
        ScrollView scroll=new ScrollView(this);LinearLayout root=shell(scroll);addBackHeader(root,title,"You can leave this screen; the foreground transfer service keeps running");
        connectionPill=connectionBadge("SECURE SESSION",Color.rgb(65,225,151));root.addView(connectionPill);
        LinearLayout card=card();transferState=text("Preparing transfer…",21,Color.WHITE,true);card.addView(transferState);transferDetail=text("Negotiating encrypted session and resume offsets",13,Color.rgb(158,188,211),false);card.addView(transferDetail);
        TextView percent=text("0%",42,Color.WHITE,true);percent.setTag("transfer_percent");percent.setGravity(Gravity.CENTER);percent.setPadding(0,dp(16),0,dp(4));card.addView(percent);
        transferProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);transferProgress.setMax(100);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(9));pp.setMargins(0,dp(8),0,0);card.addView(transferProgress,pp);
        LinearLayout metrics=new LinearLayout(this);metrics.setOrientation(LinearLayout.HORIZONTAL);metrics.setPadding(0,dp(14),0,0);
        TextView bytes=text("0 B / —",13,Color.rgb(187,215,235),true);bytes.setTag("transfer_bytes");metrics.addView(bytes,new LinearLayout.LayoutParams(0,-2,1));
        TextView speedView=text("— MB/s",13,Color.rgb(89,205,255),true);speedView.setTag("transfer_speed");speedView.setGravity(Gravity.CENTER);metrics.addView(speedView,new LinearLayout.LayoutParams(0,-2,1));
        TextView eta=text("ETA —",13,Color.rgb(187,215,235),true);eta.setTag("transfer_eta");eta.setGravity(Gravity.RIGHT);metrics.addView(eta,new LinearLayout.LayoutParams(0,-2,1));
        card.addView(metrics);root.addView(card);
        if(!receiverMode){
            transferPauseButton=secondaryButton("Pause transfer");
            transferPauseButton.setOnClickListener(v->pauseOrResumeTransfer());
            LinearLayout.LayoutParams pbtn=new LinearLayout.LayoutParams(-1,dp(50));pbtn.setMargins(0,dp(12),0,0);root.addView(transferPauseButton,pbtn);
        }else transferPauseButton=null;
        Button cancel=secondaryButton("Cancel transfer");cancel.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Cancel transfer?").setMessage("Confirmed data will remain resumable until the session is cleared.").setPositiveButton("Cancel transfer",(d,w)->{stopTransferService();showHome();}).setNegativeButton("Keep transferring",null).show());LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(50));cp.setMargins(0,dp(12),0,0);root.addView(cancel,cp);
        setContentView(scroll);
    }

    private void startDiscovery() {
        discoveryHandler.removeCallbacks(discoveryRetry);
        discoveryHandler.removeCallbacks(lanFallbackConnect);
        pendingLanHost=null;pendingLanName=null;
        startLanDiscovery();
        if(!ensureNearbyReady())return;
        discoveryAttempt=0;
        peers.clear();
        renderPeers();
        setConnectionUi("SEARCHING",Color.rgb(255,194,73));
        runDiscoveryPass();
    }

    private void startLanDiscovery() {
        if(lanDiscovery==null||!lanDiscovery.available())return;
        lanDiscovery.stopDiscovery();
        lanDiscovery.discover(new LanDiscovery.Listener(){
            @Override public void onPeer(String name,String host){
                runOnUiThread(()->{
                    if(currentScreen!=SCREEN_DISCOVERY||transferStarted)return;
                    pendingLanName=name;pendingLanHost=host;
                    if(peers.isEmpty()){
                        setDiscoveryText("Found "+name+" on the same Wi-Fi • giving Wi-Fi Direct a moment…");
                        discoveryHandler.removeCallbacks(lanFallbackConnect);
                        discoveryHandler.postDelayed(lanFallbackConnect,routeStore.lanFallbackDelayMillis());
                    }
                });
            }
            @Override public void onStatus(String message){
                if(currentScreen==SCREEN_DISCOVERY&&peers.isEmpty()&&pendingLanHost==null)setDiscoveryText(message);
            }
        });
    }

    private void stopLanDiscovery(){
        discoveryHandler.removeCallbacks(lanFallbackConnect);
        if(lanDiscovery!=null)lanDiscovery.stopDiscovery();
    }

    private void connectViaLan(String name,String host){
        if(host==null||host.trim().isEmpty()||transferStarted)return;
        transferStarted=true;
        activeRoute=RoutePerformanceStore.ROUTE_LAN;
        activeTransferStartedAt=System.currentTimeMillis();
        connectedPeerName=(name==null||name.trim().isEmpty()?"OptiShare device":name)+" • same Wi-Fi";
        discoveryHandler.removeCallbacks(discoveryRetry);
        stopLanDiscovery();
        showTransferScreen("Sending");
        setConnectionUi("SMART ROUTE • SAME WI-FI ✓",Color.rgb(65,225,151));
        setTransferUi("Connecting over local Wi-Fi","Using the encrypted OptiShare transport without Internet",0);
        startSenderService(host);
    }

    private void runDiscoveryPass() {
        if(currentScreen!=SCREEN_DISCOVERY||transferStarted||!ensureNearbyReady())return;
        discoveryAttempt++;
        setDiscoveryText("Searching for receiving phones… • pass "+discoveryAttempt+"/"+MAX_DISCOVERY_ATTEMPTS);
        try{
            manager.discoverPeers(channel,new WifiP2pManager.ActionListener(){
                @Override public void onSuccess(){
                    try{manager.requestPeers(channel,V2Activity.this);}catch(SecurityException ignored){showNearbyPermissionHelp();return;}
                    scheduleDiscoveryRetry();
                }
                @Override public void onFailure(int reason){
                    setDiscoveryText(p2pError("Search pass "+discoveryAttempt+" failed",reason));
                    scheduleDiscoveryRetry();
                }
            });
        }catch(SecurityException e){showNearbyPermissionHelp();}
    }

    private void scheduleDiscoveryRetry(){
        discoveryHandler.removeCallbacks(discoveryRetry);
        if(currentScreen==SCREEN_DISCOVERY&&!transferStarted&&peers.isEmpty()&&discoveryAttempt<MAX_DISCOVERY_ATTEMPTS){
            discoveryHandler.postDelayed(discoveryRetry,DISCOVERY_RETRY_MS);
        }else if(currentScreen==SCREEN_DISCOVERY&&peers.isEmpty()&&discoveryAttempt>=MAX_DISCOVERY_ATTEMPTS){
            setDiscoveryText("No receiver found yet. Keep RECEIVE open, then tap Search again.");
        }
    }

    private void startReceiverMode() {
        if(!ensureNearbyReady())return;safeRemoveGroup();
        try{manager.createGroup(channel,new WifiP2pManager.ActionListener(){@Override public void onSuccess(){setDiscoveryText("READY TO RECEIVE ✓\nWaiting for sender…");setConnectionUi("VISIBLE TO SENDERS",Color.rgb(65,222,151));try{if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){manager.requestDeviceInfo(channel,device->{thisDevice=device;refreshReceiverIdentity();});}else{refreshReceiverIdentity();}manager.requestConnectionInfo(channel,V2Activity.this);}catch(SecurityException ignored){showNearbyPermissionHelp();}}@Override public void onFailure(int reason){setDiscoveryText(p2pError("Receiver could not start",reason));setConnectionUi("RECEIVER ERROR",Color.rgb(255,91,101));}});}catch(SecurityException e){showNearbyPermissionHelp();}
    }

    @Override public void onPeersAvailable(WifiP2pDeviceList list) {
        peers.clear();peers.addAll(list.getDeviceList());Collections.sort(peers,Comparator.comparing(this::deviceName,String.CASE_INSENSITIVE_ORDER));
        if(pendingQrAddress!=null){for(WifiP2pDevice d:peers){if(pendingQrAddress.equalsIgnoreCase(d.deviceAddress)){pendingQrAddress=null;connectTo(d);return;}}}
        renderPeers();if(currentScreen==SCREEN_DISCOVERY){if(peers.isEmpty()){scheduleDiscoveryRetry();}else{discoveryHandler.removeCallbacks(discoveryRetry);discoveryHandler.removeCallbacks(lanFallbackConnect);setDiscoveryText(peers.size()+" Wi-Fi Direct device"+(peers.size()==1?"":"s")+" found ✓");}}
    }

    private void renderPeers() {
        runOnUiThread(()->{if(peerList==null)return;peerList.removeAllViews();if(peers.isEmpty()){LinearLayout empty=card();empty.addView(text("Searching…",14,Color.WHITE,true));empty.addView(text("OptiShare refreshes the nearby list whenever Android reports a change.",12,Color.rgb(147,173,196),false));peerList.addView(empty);return;}for(WifiP2pDevice device:peers){LinearLayout row=card();LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);TextView avatar=text(firstLetter(deviceName(device)),18,Color.WHITE,true);avatar.setGravity(Gravity.CENTER);avatar.setBackground(gradient(Color.rgb(38,151,232),Color.rgb(62,91,220),18));line.addView(avatar,new LinearLayout.LayoutParams(dp(48),dp(48)));LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);names.setPadding(dp(12),0,0,0);names.addView(text(deviceName(device),15,Color.WHITE,true));names.addView(text(deviceStatus(device.status),12,Color.rgb(151,182,205),false));line.addView(names,new LinearLayout.LayoutParams(0,-2,1));Button connect=secondaryButton("Send here");connect.setOnClickListener(v->connectTo(device));line.addView(connect,new LinearLayout.LayoutParams(dp(112),dp(46)));row.addView(line);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(8));peerList.addView(row,lp);}});
    }

    private void connectTo(WifiP2pDevice device) {
        if(!ensureNearbyReady())return;discoveryHandler.removeCallbacks(discoveryRetry);stopLanDiscovery();connectedPeerName=deviceName(device);setConnectionUi("CONNECTING…",Color.rgb(255,194,73));setDiscoveryText("Connecting to "+connectedPeerName+"…");WifiP2pConfig config=new WifiP2pConfig();config.deviceAddress=device.deviceAddress;config.wps.setup=WpsInfo.PBC;config.groupOwnerIntent=0;
        try{manager.connect(channel,config,new WifiP2pManager.ActionListener(){@Override public void onSuccess(){setDiscoveryText("Connection request sent. Waiting for the direct link…");}@Override public void onFailure(int reason){setConnectionUi("CONNECTION FAILED",Color.rgb(255,91,101));setDiscoveryText(p2pError("Connection failed",reason));}});}catch(SecurityException e){showNearbyPermissionHelp();}
    }

    @Override public void onConnectionInfoAvailable(WifiP2pInfo info) {
        if(info==null||!info.groupFormed||info.groupOwnerAddress==null)return;setConnectionUi("CONNECTED ✓",Color.rgb(65,225,151));
        if(receiverMode&&info.isGroupOwner){setDiscoveryText("CONNECTED ✓\nSecure receiver channel is active.");startReceiverService();}
        else if(!receiverMode&&!info.isGroupOwner&&!transferStarted){activeRoute=RoutePerformanceStore.ROUTE_DIRECT;transferStarted=true;stopLanDiscovery();activeTransferStartedAt=System.currentTimeMillis();showTransferScreen("Sending");setConnectionUi("SMART ROUTE • WI-FI DIRECT ✓",Color.rgb(65,225,151));startSenderService(info.groupOwnerAddress.getHostAddress());}
    }

    private void startReceiverService() {
        Intent i=new Intent(this,TransferService.class).setAction(TransferService.ACTION_START_RECEIVER);ContextCompat.startForegroundService(this,i);
    }

    private void startSenderService(String host) {
        ArrayList<String> uris=new ArrayList<>();for(Uri uri:selected)uris.add(uri.toString());Intent i=new Intent(this,TransferService.class).setAction(TransferService.ACTION_SEND);i.putExtra(TransferService.EXTRA_HOST,host);i.putExtra(TransferService.EXTRA_ROUTE,activeRoute);i.putStringArrayListExtra(TransferService.EXTRA_URIS,uris);ContextCompat.startForegroundService(this,i);
    }

    private void pauseOrResumeTransfer(){
        if(transferPaused){resumePendingTransfer();return;}
        startService(new Intent(this,TransferService.class).setAction(TransferService.ACTION_PAUSE));
    }

    private void resumePendingTransfer(){
        if(!senderSessionStore.exists()){showMessage("Resume transfer","No pending outgoing transfer was found.");return;}
        receiverMode=false;transferPaused=false;transferStarted=true;
        showTransferScreen("Resuming");
        setConnectionUi("RESUMING…",Color.rgb(255,188,70));
        setTransferUi("Restoring transfer","Connecting and requesting the receiver's verified resume offsets…",-1);
        Intent i=new Intent(this,TransferService.class).setAction(TransferService.ACTION_RESUME_PENDING);
        ContextCompat.startForegroundService(this,i);
    }

    private void updatePauseButton(boolean paused){runOnUiThread(()->{if(transferPauseButton==null)return;transferPauseButton.setText(paused?"Resume transfer":"Pause transfer");transferPauseButton.setOnClickListener(v->pauseOrResumeTransfer());});}

    private void stopTransferService(){startService(new Intent(this,TransferService.class).setAction(TransferService.ACTION_STOP));}

    private void setTransferUi(String title,String detail,int progress){runOnUiThread(()->{if(transferState!=null)transferState.setText(title);if(transferDetail!=null)transferDetail.setText(detail);if(transferProgress!=null&&progress>=0)transferProgress.setProgress(progress);if(progress>=0){TextView percent=findViewByTag("transfer_percent");if(percent!=null)percent.setText(progress+"%");}});}

    private void setTransferMetrics(int progress,long done,long total,double speed,long etaSeconds){runOnUiThread(()->{TextView percent=findViewByTag("transfer_percent");TextView bytes=findViewByTag("transfer_bytes");TextView speedView=findViewByTag("transfer_speed");TextView eta=findViewByTag("transfer_eta");if(percent!=null)percent.setText(Math.max(0,Math.min(100,progress))+"%");if(bytes!=null)bytes.setText(formatBytes(Math.max(0L,done))+" / "+(total>0?formatBytes(total):"—"));if(speedView!=null)speedView.setText(formatTransferSpeed(speed));if(eta!=null)eta.setText(formatEta(etaSeconds));});}

    private String formatHistoryDuration(long ms){
        if(ms<1000)return ms+" ms";
        long seconds=Math.max(1,Math.round(ms/1000.0));
        if(seconds<60)return seconds+"s";
        return (seconds/60)+"m "+(seconds%60)+"s";
    }
    private String historyRouteLabel(String route){
        if(RoutePerformanceStore.ROUTE_DIRECT.equals(route))return "Wi-Fi Direct";
        if(RoutePerformanceStore.ROUTE_LAN.equals(route))return "same Wi-Fi";
        if("incoming".equals(route))return "received";
        return route;
    }

    private String formatTransferSpeed(double bytesPerSecond){if(bytesPerSecond>=1024d*1024d)return String.format(Locale.US,"%.1f MB/s",bytesPerSecond/(1024d*1024d));if(bytesPerSecond>=1024d)return String.format(Locale.US,"%.0f KB/s",bytesPerSecond/1024d);return bytesPerSecond>0?String.format(Locale.US,"%.0f B/s",bytesPerSecond):"— MB/s";}
    private String formatEta(long seconds){if(seconds<=0)return"ETA —";if(seconds<60)return"ETA "+seconds+"s";long minutes=seconds/60;long remain=seconds%60;return"ETA "+minutes+"m "+remain+"s";}

    private void refreshReceiverIdentity() {
        if(currentScreen!=SCREEN_RECEIVE)return;TextView label=findViewByTag("receiver_identity");ImageView qr=findViewByTag("receiver_qr");String p2pName=thisDevice==null?identity.name():deviceName(thisDevice);if(label!=null)label.setText(p2pName+" • "+identity.name());if(qr!=null&&thisDevice!=null&&thisDevice.deviceAddress!=null){try{qr.setImageBitmap(makeQr("OPTISHARE2|"+thisDevice.deviceAddress+"|"+p2pName,720));}catch(Exception ignored){}}
    }

    @SuppressWarnings("unchecked") private <T extends View>T findViewByTag(String tag){View v=getWindow().getDecorView().findViewWithTag(tag);return(T)v;}

    private void startQrScanner(){ScanOptions options=new ScanOptions();options.setPrompt("Scan the receiver's OptiShare QR");options.setBeepEnabled(false);options.setOrientationLocked(false);options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);qrScanner.launch(options);}

    private void handlePairingQr(String raw){if(raw==null||!raw.startsWith("OPTISHARE2|")){showMessage("Invalid QR","This is not an OptiShare 2 pairing code.");return;}String[] parts=raw.split("\\|",3);if(parts.length<3){showMessage("Invalid QR","Pairing information is incomplete.");return;}pendingQrAddress=parts[1];pendingQrName=parts[2];setDiscoveryText("Receiver identified: "+pendingQrName+". Searching for its direct link…");startDiscovery();}

    private Bitmap makeQr(String value,int size)throws Exception{BitMatrix matrix=new MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,size,size);Bitmap bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.RGB_565);for(int y=0;y<size;y++)for(int x=0;x<size;x++)bitmap.setPixel(x,y,matrix.get(x,y)?Color.BLACK:Color.WHITE);return bitmap;}

    private boolean ensureNearbyReady(){if(manager==null||channel==null){showMessage("Wi‑Fi Direct unavailable","This device does not expose Android Wi‑Fi Direct to OptiShare.");return false;}if(!hasNearbyPermission()){requestNearbyPermission();return false;}if(Build.VERSION.SDK_INT<=32&&!isLocationEnabled()){new AlertDialog.Builder(this).setTitle("Turn on Location").setMessage("Android requires Location services for Wi‑Fi Direct discovery on this Android version. OptiShare does not upload your location.").setPositiveButton("Open settings",(d,w)->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))).setNegativeButton("Cancel",null).show();return false;}return true;}

    private boolean hasNearbyPermission(){if(Build.VERSION.SDK_INT>=33)return checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)==PackageManager.PERMISSION_GRANTED;if(Build.VERSION.SDK_INT>=23)return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;return true;}
    private void requestNearbyPermission(){if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},REQ_NEARBY);else if(Build.VERSION.SDK_INT>=23)requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION},REQ_NEARBY);}
    private boolean hasMediaPermission(String type){if(Build.VERSION.SDK_INT>=33){String permission="image".equals(type)?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_MEDIA_VIDEO;return checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;}if(Build.VERSION.SDK_INT>=23)return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;return true;}
    private void requestMediaPermission(String type){if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{"image".equals(type)?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_MEDIA_VIDEO},REQ_MEDIA);else if(Build.VERSION.SDK_INT>=23)requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},REQ_MEDIA);else showMediaGallery(type);}
    private boolean ensureLegacyWritePermission(){if(Build.VERSION.SDK_INT>=23&&Build.VERSION.SDK_INT<=28&&checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},REQ_LEGACY_WRITE);return false;}return true;}

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);boolean granted=grantResults.length>0&&grantResults[grantResults.length-1]==PackageManager.PERMISSION_GRANTED;if(requestCode==REQ_MEDIA&&granted&&pendingGalleryType!=null)showMediaGallery(pendingGalleryType);if(requestCode==REQ_NEARBY&&granted){if(currentScreen==SCREEN_DISCOVERY)startDiscovery();else if(currentScreen==SCREEN_RECEIVE)startReceiverMode();}if(requestCode==REQ_LEGACY_WRITE&&granted)showReceive();}

    private void requestNotificationPermissionIfUseful(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},2204);}

    private void openExternal(String mime){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType(mime);intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);externalPicker.launch(intent);}
    private void persistReadPermission(Uri uri,int flags){try{getContentResolver().takePersistableUriPermission(uri,flags&Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}}

    private long selectedTotalBytes(){long total=0;for(Uri uri:selected){long size=querySize(uri);if(size>0&&Long.MAX_VALUE-total>size)total+=size;}return total;}
    private long querySize(Uri uri){Cursor c=null;try{c=getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null);if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.SIZE);if(i>=0&&!c.isNull(i))return c.getLong(i);}}catch(Exception ignored){}finally{if(c!=null)c.close();}return 0;}
    private String displayName(Uri uri){Cursor c=null;try{c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null);if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0&&!c.isNull(i))return c.getString(i);}}catch(Exception ignored){}finally{if(c!=null)c.close();}String last=uri.getLastPathSegment();return last==null?"item":last;}

    private void showDeviceSettings(){
        String[] options={"Rename this device","Trusted devices ("+trustedStore.list().size()+")","My security identity","SmartRoute status"};
        new AlertDialog.Builder(this).setTitle("Device & security").setItems(options,(d,which)->{
            if(which==0)editDeviceName(); else if(which==1)showTrustedDevices(); else if(which==2)showMySecurityIdentity(); else showMessage("SmartRoute",routeStore.summary()+"\nLearns from real transfer speed and route reliability.");
        }).setNegativeButton("Close",null).show();
    }

    private void showTrustedDevices(){
        if(!DeviceIdentityKey.supported()){showMessage("Trusted devices","Android 5 keeps manual six-digit verification. Persistent trust is available on Android 6 and newer.");return;}
        List<TrustedDeviceStore.Entry> entries=trustedStore.list();
        if(entries.isEmpty()){showMessage("Trusted devices","No trusted devices yet. On the first secure connection choose ‘Trust this device & confirm’.");return;}
        String[] labels=new String[entries.size()];
        for(int i=0;i<entries.size();i++){TrustedDeviceStore.Entry e=entries.get(i);labels[i]=e.name+"\n"+DeviceIdentityKey.shortFingerprint(e.fingerprint)+(e.autoAccept?" • Auto-accept ON":" • Confirm files");}
        new AlertDialog.Builder(this).setTitle("Trusted devices").setItems(labels,(d,which)->showTrustedDeviceActions(entries.get(which))).setNegativeButton("Close",null).show();
    }

    private void showTrustedDeviceActions(TrustedDeviceStore.Entry entry){
        String auto=entry.autoAccept?"Turn auto-accept OFF":"Turn auto-accept ON";
        new AlertDialog.Builder(this).setTitle(entry.name).setMessage("Fingerprint: "+DeviceIdentityKey.shortFingerprint(entry.fingerprint)+"\nAuto-accept works only after the stored device key signs the new secure session.").setItems(new String[]{auto,"Forget this device"},(d,which)->{
            if(which==0){trustedStore.setAutoAccept(entry.fingerprint,!entry.autoAccept);showTrustedDevices();} else new AlertDialog.Builder(this).setTitle("Forget trusted device?").setMessage("The six-digit code will be required again next time.").setPositiveButton("Forget",(x,w)->{trustedStore.forget(entry.fingerprint);showTrustedDevices();}).setNegativeButton("Cancel",null).show();
        }).setNegativeButton("Back",(d,w)->showTrustedDevices()).show();
    }

    private void showMySecurityIdentity(){
        if(!DeviceIdentityKey.supported()){showMessage("Security identity","Android 5 uses manual security-code verification and does not store a persistent trust key.");return;}
        try{String fp=new DeviceIdentityKey().fingerprint();showMessage("My security identity","Protected by Android Keystore\nFingerprint: "+DeviceIdentityKey.shortFingerprint(fp));}catch(Exception e){showMessage("Security identity","Could not access identity: "+e.getMessage());}
    }

    private void editDeviceName(){final android.widget.EditText input=new android.widget.EditText(this);input.setText(identity.name());input.setSingleLine(true);new AlertDialog.Builder(this).setTitle("Device name").setMessage("This name is used inside OptiShare.").setView(input).setPositiveButton("Save",(d,w)->{try{identity.setName(input.getText().toString());showHome();}catch(Exception e){showMessage("Invalid name",e.getMessage());}}).setNegativeButton("Cancel",null).show();}

    private TextView connectionBadge(String label,int color){TextView v=text(label,13,color,true);v.setGravity(Gravity.CENTER);v.setPadding(dp(12),dp(10),dp(12),dp(10));v.setBackground(round(Color.argb(70,Color.red(color),Color.green(color),Color.blue(color)),14));return v;}
    private void setDiscoveryText(String value){runOnUiThread(()->{if(discoveryState!=null)discoveryState.setText(value);});}
    private void setConnectionUi(String label,int color){runOnUiThread(()->{if(connectionPill==null)return;connectionPill.setText(label);connectionPill.setTextColor(color);connectionPill.setBackground(round(Color.argb(80,Color.red(color),Color.green(color),Color.blue(color)),14));});}
    private void showNearbyPermissionHelp(){showMessage("Nearby permission required","Allow Nearby Wi‑Fi devices. On Android 12 or older, Android also requires Location permission and Location services for Wi‑Fi Direct discovery.");}
    private boolean isLocationEnabled(){LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);if(lm==null)return false;if(Build.VERSION.SDK_INT>=28)return lm.isLocationEnabled();try{return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)||lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);}catch(Exception e){return false;}}
    private void safeRemoveGroup(){if(manager==null||channel==null||!hasNearbyPermission())return;try{manager.removeGroup(channel,new WifiP2pManager.ActionListener(){@Override public void onSuccess(){}@Override public void onFailure(int reason){}});}catch(Exception ignored){}}
    private String p2pError(String prefix,int reason){if(reason==WifiP2pManager.BUSY)return prefix+": Nearby radio is busy. OptiShare will retry when Android releases it.";if(reason==WifiP2pManager.P2P_UNSUPPORTED)return prefix+": Wi‑Fi Direct is not supported on this phone.";return prefix+": Android could not complete the nearby operation ("+reason+").";}
    private String deviceName(WifiP2pDevice d){if(d==null||d.deviceName==null||d.deviceName.trim().isEmpty())return"Android device";return d.deviceName.trim();}
    private String deviceStatus(int status){switch(status){case WifiP2pDevice.CONNECTED:return"Connected";case WifiP2pDevice.INVITED:return"Connecting…";case WifiP2pDevice.AVAILABLE:return"Ready";case WifiP2pDevice.FAILED:return"Unavailable";case WifiP2pDevice.UNAVAILABLE:return"Busy";default:return"Nearby";}}
    private String firstLetter(String value){return value==null||value.isEmpty()?"?":value.substring(0,1).toUpperCase(Locale.US);}
    private String formatBytes(long b){if(b>=1024L*1024*1024)return String.format(Locale.US,"%.2f GB",b/(1024.0*1024*1024));if(b>=1024L*1024)return String.format(Locale.US,"%.2f MB",b/(1024.0*1024));if(b>=1024)return String.format(Locale.US,"%.1f KB",b/1024.0);return b+" B";}

    private LinearLayout shell(ScrollView scroll){LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(22),dp(20),dp(28));root.setBackground(gradient(Color.rgb(5,17,38),Color.rgb(16,48,84),0));scroll.addView(root);return root;}
    private void addBackHeader(LinearLayout root,String title,String subtitle){Button back=smallButton("← Back");back.setOnClickListener(v->{if(currentScreen==SCREEN_GALLERY||currentScreen==SCREEN_DISCOVERY)showSendSelection();else showHome();});root.addView(back,new LinearLayout.LayoutParams(dp(96),dp(44)));TextView t=text(title,27,Color.WHITE,true);t.setPadding(0,dp(18),0,dp(3));root.addView(t);TextView s=text(subtitle,13,Color.rgb(162,194,219),false);s.setPadding(0,0,0,dp(14));root.addView(s);}
    private Button category(String icon,String label,int color,View.OnClickListener listener){Button b=new Button(this);b.setAllCaps(false);b.setText(icon+"\n"+label);b.setTextColor(Color.WHITE);b.setTextSize(14);b.setTypeface(Typeface.DEFAULT_BOLD);b.setBackground(gradient(color,darken(color),18));b.setOnClickListener(listener);return b;}
    private LinearLayout categoryRow(Button a,Button b,Button c){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.addView(a,new LinearLayout.LayoutParams(0,dp(106),1));LinearLayout.LayoutParams p2=new LinearLayout.LayoutParams(0,dp(106),1);p2.setMargins(dp(8),0,0,0);row.addView(b,p2);LinearLayout.LayoutParams p3=new LinearLayout.LayoutParams(0,dp(106),1);p3.setMargins(dp(8),0,0,0);row.addView(c,p3);return row;}
    private Button bigAction(String icon,String title,String sub,int top,int bottom){Button b=new Button(this);b.setAllCaps(false);b.setText(icon+"\n"+title+"\n"+sub);b.setTextColor(Color.WHITE);b.setTextSize(15);b.setTypeface(Typeface.DEFAULT_BOLD);b.setBackground(gradient(top,bottom,22));return b;}
    private Button primary(String label){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextColor(Color.WHITE);b.setTextSize(14);b.setTypeface(Typeface.DEFAULT_BOLD);b.setBackground(gradient(Color.rgb(31,151,255),Color.rgb(52,88,226),16));return b;}
    private Button secondaryButton(String label){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextColor(Color.WHITE);b.setTextSize(13);b.setBackground(round(Color.rgb(24,52,78),14));return b;}
    private Button smallButton(String label){Button b=secondaryButton(label);b.setTextSize(12);return b;}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(16),dp(16),dp(16),dp(16));GradientDrawable g=round(Color.rgb(13,33,56),18);g.setStroke(dp(1),Color.rgb(37,68,96));l.setBackground(g);return l;}
    private TextView text(String value,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private GradientDrawable gradient(int top,int bottom,int radius){GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{top,bottom});g.setCornerRadius(dp(radius));return g;}
    private int darken(int color){return Color.rgb((int)(Color.red(color)*.66),(int)(Color.green(color)*.66),(int)(Color.blue(color)*.66));}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void showMessage(String title,String message){runOnUiThread(()->new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK",null).show());}

    @Override protected void onResume(){super.onResume();IntentFilter p2p=new IntentFilter();p2p.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);p2p.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);p2p.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);p2p.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);ContextCompat.registerReceiver(this,p2pReceiver,p2p,ContextCompat.RECEIVER_NOT_EXPORTED);IntentFilter transfer=new IntentFilter(TransferService.ACTION_EVENT);ContextCompat.registerReceiver(this,transferReceiver,transfer,ContextCompat.RECEIVER_NOT_EXPORTED);}
    @Override protected void onPause(){super.onPause();discoveryHandler.removeCallbacks(discoveryRetry);stopLanDiscovery();try{unregisterReceiver(p2pReceiver);}catch(Exception ignored){}try{unregisterReceiver(transferReceiver);}catch(Exception ignored){}}
    @Override protected void onDestroy(){if(lanDiscovery!=null)lanDiscovery.close();super.onDestroy();}
    @Override public void onBackPressed(){if(currentScreen==SCREEN_HOME)super.onBackPressed();else if(currentScreen==SCREEN_GALLERY||currentScreen==SCREEN_DISCOVERY)showSendSelection();else showHome();}
}
