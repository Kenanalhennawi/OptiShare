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
import android.util.SparseArray;
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
import com.kenan.optishare.storage.FolderSelection;
import com.kenan.optishare.storage.FolderTransferQueue;
import com.kenan.optishare.storage.TextTransferStore;
import com.kenan.optishare.storage.InstalledAppExporter;
import com.kenan.optishare.transfer.LanDiscovery;
import com.kenan.optishare.transfer.PcDiscovery;
import com.kenan.optishare.transfer.PcTransferService;
import com.kenan.optishare.transfer.BrowserReceiveService;
import com.kenan.optishare.transfer.AdaptiveRouteOrchestrator;
import com.kenan.optishare.transfer.RoutePerformanceStore;
import com.kenan.optishare.transfer.SenderSessionStore;
import com.kenan.optishare.transfer.TransferService;
import com.kenan.optishare.ui.GalleryAdapter;
import com.kenan.optishare.ui.UiText;

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

    /** Frozen while Android 2.2 is completed and validated as a standalone release. */
    private static final boolean ENABLE_PC_COMPANION = false;
    private static final int REQ_MEDIA = 2101;
    private static final int REQ_NEARBY = 2102;
    private static final int REQ_LEGACY_WRITE = 2103;
    private static final int SCREEN_HOME = 0;
    private static final int SCREEN_GALLERY = 1;
    private static final int SCREEN_SEND = 2;
    private static final int SCREEN_DISCOVERY = 3;
    private static final int SCREEN_RECEIVE = 4;
    private static final int SCREEN_TRANSFER = 5;
    private static final int SCREEN_SETTINGS = 6;

    private final List<Uri> selected = new ArrayList<>();
    private final List<WifiP2pDevice> peers = new ArrayList<>();
    private final List<PcDiscovery.Peer> pcPeers = new ArrayList<>();
    private int currentScreen = SCREEN_HOME;
    private int galleryReturnScreen = SCREEN_HOME;
    private String pendingGalleryType;
    private String pendingQrAddress;
    private String pendingQrName;
    private String connectedPeerName = "Nearby device";
    private boolean receiverMode;
    private boolean transferStarted;
    private boolean browserMode;
    private boolean pcTransferMode;
    private boolean benchmarkMode;

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private WifiP2pDevice thisDevice;

    private LinearLayout peerList;
    private TextView discoveryState;
    private TextView connectionPill;
    private TextView transferState;
    private TextView transferDetail;
    private ProgressBar transferProgress;
    private LinearLayout transferQueueList;
    private final Set<Integer> completedQueueIndexes = new HashSet<>();
    private final Set<Integer> failedQueueIndexes = new HashSet<>();
    private final Set<Integer> removedQueueIndexes = new HashSet<>();
    private final SparseArray<String> failedQueueReasons = new SparseArray<>();
    private int liveQueueIndex = -1;
    private String liveQueueName;
    private long liveQueueDone;
    private long liveQueueTotal;
    private Button transferPauseButton;
    private Button transferCancelButton;
    private boolean transferPaused;

    private DeviceIdentity identity;
    private TransferHistoryStore historyStore;
    private TrustedDeviceStore trustedStore;
    private RoutePerformanceStore routeStore;
    private SenderSessionStore senderSessionStore;
    private LanDiscovery lanDiscovery;
    private PcDiscovery pcDiscovery;
    private String activeRoute = RoutePerformanceStore.ROUTE_DIRECT;
    private String pendingLanHost;
    private String pendingLanName;
    private long activeTransferStartedAt;
    private final Handler discoveryHandler = new Handler(Looper.getMainLooper());
    private WifiP2pDevice pendingP2pDevice;
    private final Runnable p2pConnectTimeout=()->{
        if(currentScreen!=SCREEN_DISCOVERY||transferStarted||pendingP2pDevice==null)return;
        String name=deviceName(pendingP2pDevice);
        try{manager.cancelConnect(channel,new WifiP2pManager.ActionListener(){@Override public void onSuccess(){}@Override public void onFailure(int reason){}});}catch(Exception ignored){}
        pendingP2pDevice=null;
        if(pendingLanHost!=null&&!pendingLanHost.trim().isEmpty()){
            if(benchmarkMode){
                activeRoute=RoutePerformanceStore.ROUTE_LAN;transferStarted=true;pcTransferMode=true;
                showTransferScreen("Android speed test");setConnectionUi("SAME WI-FI SPEED TEST",Color.rgb(89,205,255));
                setTransferUi("Direct link timed out — testing same Wi-Fi","Still uses the encrypted OptiShare ECDH/AES-GCM transport.",0);
                startBenchmarkService(pendingLanHost);
            }else{
                setDiscoveryText("Direct link timed out. Switching to same Wi-Fi…");
                connectViaLan(pendingLanName==null?name:pendingLanName,pendingLanHost);
            }
        }else{
            benchmarkMode=false;pcTransferMode=false;
            setConnectionUi("DIRECT LINK TIMEOUT",Color.rgb(255,91,101));
            setDiscoveryText("Could not establish Wi-Fi Direct with "+name+". Keep RECEIVE open on the other phone, then tap Search again. Same-Wi-Fi fallback will be used automatically when available.");
            scheduleDiscoveryRetry();
        }
    };
    private int discoveryAttempt;
    private static final int MAX_DISCOVERY_ATTEMPTS = 8;
    private static final long DISCOVERY_RETRY_MS = 3500L;
    private final Runnable discoveryRetry = () -> {
        if (currentScreen == SCREEN_DISCOVERY && !transferStarted && peers.isEmpty()) {
            runDiscoveryPass();
        }
    };
    private final Runnable lanFallbackConnect = () -> {
        if (currentScreen == SCREEN_DISCOVERY && !transferStarted
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

    private final ActivityResultLauncher<Intent> folderPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null
                        || result.getData().getData() == null) return;
                Uri tree = result.getData().getData();
                int flags = result.getData().getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try { getContentResolver().takePersistableUriPermission(
                        tree, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (Exception ignored) { }
                try {
                    List<com.kenan.optishare.model.TransferItem> files = FolderSelection.collect(this, tree);
                    for (com.kenan.optishare.model.TransferItem item : files) {
                        if (!selected.contains(item.getUri())) selected.add(item.getUri());
                    }
                    FolderTransferQueue.addAll(files);
                    showSendSelection();
                    showMessage("Folder ready", files.size()
                            + " files selected with folder structure preserved.");
                } catch (Exception error) {
                    showMessage("Folder could not be opened", error.getMessage());
                }
            });

    private final ActivityResultLauncher<Intent> appPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                ArrayList<String> packages = result.getData().getStringArrayListExtra(AppPickerActivity.EXTRA_PACKAGES);
                if (packages == null || packages.isEmpty()) return;
                try {
                    List<com.kenan.optishare.model.TransferItem> apps = InstalledAppExporter.export(this, packages);
                    for (com.kenan.optishare.model.TransferItem item : apps) {
                        if (!selected.contains(item.getUri())) selected.add(item.getUri());
                    }
                    FolderTransferQueue.addAll(apps);
                    showSendSelection();
                } catch (Exception error) {
                    showMessage("Could not prepare apps", error.getMessage());
                }
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
            String activeFileName = intent.getStringExtra(TransferService.EXTRA_FILE_NAME);
            long activeFileDone = intent.getLongExtra(TransferService.EXTRA_FILE_DONE, 0L);
            long activeFileTotal = intent.getLongExtra(TransferService.EXTRA_FILE_TOTAL, 0L);
            int activeFileIndex = intent.getIntExtra(TransferService.EXTRA_FILE_INDEX, -1);
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
                updateLiveQueue(activeFileIndex, activeFileName, activeFileDone, activeFileTotal, false);
            } else if ("paused".equals(event)) {
                transferPaused=true;
                setConnectionUi("PAUSED", Color.rgb(255,188,70));
                setTransferUi("Transfer paused", message, progress);
                updatePauseButton(true);
                renderLiveQueue(liveQueueIndex, liveQueueName, liveQueueDone, liveQueueTotal);
            } else if ("pause_unavailable".equals(event)) {
                setTransferUi("Finishing secure setup", message, -1);
            } else if ("reconnecting".equals(event)) {
                setConnectionUi("RECONNECTING…", Color.rgb(255, 188, 70));
                setTransferUi("Reconnecting automatically", message, -1);
            } else if ("route_switched".equals(event)) {
                activeRoute=RoutePerformanceStore.ROUTE_LAN;
                setConnectionUi("SMART ROUTE • SAME WI-FI ✓", Color.rgb(65,225,151));
                setTransferUi("Route changed safely", message, -1);
            } else if ("parallel_started".equals(event)) {
                setConnectionUi("SMART ROUTE • 2 STREAMS", Color.rgb(89,205,255));
                setTransferUi("Accelerated encrypted transfer", message, -1);
            } else if ("parallel_fallback".equals(event)) {
                setConnectionUi("SMART ROUTE • RELIABLE STREAM", Color.rgb(255,188,70));
                setTransferUi("Acceleration fallback", message, -1);
            } else if ("benchmark_started".equals(event)) {
                setConnectionUi("ENCRYPTED SPEED TEST", Color.rgb(89,205,255));
                setTransferUi("Measuring Android route", message, 0);
            } else if ("benchmark_completed".equals(event)) {
                setConnectionUi("SPEED TEST ✓", Color.rgb(65,225,151));
                setTransferUi("Speed test complete ✓", message, 100);
                setTransferMetrics(100, completedTotalBytes, completedTotalBytes, speed, 0);
                transferStarted = false;
            } else if ("benchmark_error".equals(event)) {
                setConnectionUi("SPEED TEST FAILED", Color.rgb(255,92,102));
                setTransferUi("Speed test could not finish", message, -1);
                transferStarted = false;
            } else if ("text_received".equals(event)) {
                android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                if(clipboard!=null&&message!=null)clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OptiShare text",message));
                setTransferUi("Text received & copied ✓","The received text is now in your clipboard.",-1);
            } else if ("file_done".equals(event)) {
                setTransferUi("File verified ✓", message, -1);
                updateLiveQueue(activeFileIndex, activeFileName, activeFileTotal, activeFileTotal, true);
            } else if ("file_failed".equals(event)) {
                if(activeFileIndex>=0){
                    completedQueueIndexes.remove(activeFileIndex);
                    failedQueueIndexes.add(activeFileIndex);
                    failedQueueReasons.put(activeFileIndex,message);
                }
                setTransferUi("One file failed — continuing",
                        (activeFileName==null?"File "+(activeFileIndex+1):activeFileName)+" • "+message, -1);
                renderLiveQueue(activeFileIndex,activeFileName,0L,activeFileTotal);
            } else if ("completed".equals(event)) {
                boolean partial=!failedQueueIndexes.isEmpty();
                setConnectionUi(partial?"COMPLETED WITH FAILED FILES":"COMPLETED ✓",
                        partial?Color.rgb(255,188,70):Color.rgb(65, 225, 151));
                TextView screenTitle=findViewByTag("transfer_screen_title");
                if(screenTitle!=null)screenTitle.setText(UiText.get(V2Activity.this,partial?"Queue finished":"Transfer complete"));
                setTransferUi(partial?"Other files completed":"Transfer complete ✓",
                        partial?failedQueueIndexes.size()+" file(s) need retry • "+message:message,100);
                historyStore.add(new TransferHistoryStore.Entry(
                        System.currentTimeMillis(), receiverMode ? "received" : "sent",
                        connectedPeerName, Math.max(0,(completedFileCount > 0 ? completedFileCount : selected.size())-failedQueueIndexes.size()),
                        completedTotalBytes > 0 ? completedTotalBytes : selectedTotalBytes(), !partial,
                        durationMs, averageSpeed, completedRoute, reconnects));
                transferStarted = false;
                pcTransferMode=false;
                transferPaused=false;
                if(transferPauseButton!=null)transferPauseButton.setVisibility(View.GONE);
                if(transferCancelButton!=null){
                    transferCancelButton.setText(UiText.get(V2Activity.this,partial&&!receiverMode?"Retry failed files →":"Done"));
                    transferCancelButton.setOnClickListener(v->{if(partial&&!receiverMode)retryFailedFiles();else showHome();});
                }
                updatePauseButton(false);
            } else if ("error".equals(event)) {
                setConnectionUi("TRANSFER ERROR", Color.rgb(255, 92, 102));
                setTransferUi("Transfer could not continue", message, -1);
                if (!receiverMode && senderSessionStore.exists() && transferCancelButton != null) {
                    transferCancelButton.setText(UiText.get(V2Activity.this,"Retry / resume →"));
                    transferCancelButton.setOnClickListener(v -> resumePendingTransfer());
                }
                historyStore.add(new TransferHistoryStore.Entry(
                        System.currentTimeMillis(), receiverMode ? "received" : "sent",
                        connectedPeerName, selected.size(), selectedTotalBytes(), false));
            }
        }
    };

    private final BroadcastReceiver browserReceiver = new BroadcastReceiver() {
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
            }else if("clipboard".equals(event)){
                setDiscoveryText(message);
                setConnectionUi("CLIPBOARD COPIED ✓",Color.rgb(65,225,151));
            }else if("benchmark".equals(event)){
                setDiscoveryText(message);
                setConnectionUi("SPEED TEST ✓",Color.rgb(89,205,255));
            }else if("error".equals(event)){
                setDiscoveryText(message);setConnectionUi("BROWSER ERROR",Color.rgb(255,92,102));
            }else if("stopped".equals(event)){
                browserMode=false;refreshReceiverIdentity();
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
        pcDiscovery = new PcDiscovery(this);
        manager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        if (manager != null) {
            channel = manager.initialize(this, getMainLooper(), () -> setDiscoveryText("Nearby service restarted. Try again."));
        }
        requestNotificationPermissionIfUseful();
        if(!handleInboundShare(getIntent())) showHome();
    }

    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        setIntent(intent);
        handleInboundShare(intent);
    }

    private boolean handleInboundShare(Intent intent){
        if(intent==null)return false;
        String action=intent.getAction();
        if(!Intent.ACTION_SEND.equals(action)&&!Intent.ACTION_SEND_MULTIPLE.equals(action))return false;
        selected.clear();FolderTransferQueue.clear();
        try{
            if(Intent.ACTION_SEND_MULTIPLE.equals(action)){
                ArrayList<Uri> streams;
                if(Build.VERSION.SDK_INT>=33)streams=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM,Uri.class);
                else streams=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if(streams!=null)for(Uri uri:streams)if(uri!=null&&!selected.contains(uri))selected.add(uri);
            }else{
                Uri stream;
                if(Build.VERSION.SDK_INT>=33)stream=intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri.class);
                else stream=intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if(stream!=null)selected.add(stream);
                CharSequence sharedText=intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if(stream==null&&sharedText!=null&&sharedText.length()>0){
                    com.kenan.optishare.model.TransferItem item=TextTransferStore.create(this,sharedText);
                    selected.add(item.getUri());FolderTransferQueue.add(item);
                }
            }
        }catch(Exception error){showMessage("Could not import shared content",error.getMessage());return true;}
        if(selected.isEmpty()){showMessage("Nothing to share","OptiShare did not receive a file, link or text from the other app.");showHome();return true;}
        showSendSelection();return true;
    }

    private void showHome() {
        stopPcDiscovery();
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
        Button settings = smallButton("Settings");
        settings.setOnClickListener(v -> showDeviceSettings());
        top.addView(settings, new LinearLayout.LayoutParams(dp(98), dp(42)));
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
                category("♫","Music",Color.rgb(255,169,50),v -> openInternalGallery("audio"))));
        LinearLayout row2 = categoryRow(
                category("A","Apps",Color.rgb(53,203,165),v -> openInstalledApps()),
                category("≡","Documents",Color.rgb(55,143,255),v -> openDocuments()),
                category("▤","Folder",Color.rgb(122,140,166),v -> openFolder()));
        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(-1,-2); r2.setMargins(0,dp(10),0,0); root.addView(row2,r2);
        LinearLayout row3 = categoryRow(
                category("T","Text",Color.rgb(89,190,255),v -> showTextComposer(null)),
                category("▣","Clipboard",Color.rgb(81,210,157),v -> addClipboardToQueue()),
                category("…","Other",Color.rgb(122,140,166),v -> openExternal("*/*")));
        LinearLayout.LayoutParams r3 = new LinearLayout.LayoutParams(-1,-2); r3.setMargins(0,dp(10),0,0); root.addView(row3,r3);

        addHistory(root);

        Button receivedFiles = primary("Received files center →");
        receivedFiles.setOnClickListener(v -> startActivity(new Intent(this, ReceivedFilesActivity.class)));
        LinearLayout.LayoutParams receivedLp = new LinearLayout.LayoutParams(-1, dp(50));
        receivedLp.setMargins(0, dp(12), 0, 0);
        root.addView(receivedFiles, receivedLp);

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
        LinearLayout addRow=new LinearLayout(this);addRow.setOrientation(LinearLayout.HORIZONTAL);
        Button folder=smallButton("Folder");folder.setOnClickListener(v->openFolder());
        Button textBtn=smallButton("Text");textBtn.setOnClickListener(v->showTextComposer(null));
        Button clipBtn=smallButton("Clipboard");clipBtn.setOnClickListener(v->addClipboardToQueue());
        addRow.addView(folder,new LinearLayout.LayoutParams(0,dp(46),1));
        LinearLayout.LayoutParams ar1=new LinearLayout.LayoutParams(0,dp(46),1);ar1.setMargins(dp(8),0,0,0);addRow.addView(textBtn,ar1);
        LinearLayout.LayoutParams ar2=new LinearLayout.LayoutParams(0,dp(46),1);ar2.setMargins(dp(8),0,0,0);addRow.addView(clipBtn,ar2);
        LinearLayout.LayoutParams arp=new LinearLayout.LayoutParams(-1,-2);arp.setMargins(0,dp(8),0,0);root.addView(addRow,arp);

        Button find=primary(selected.isEmpty()?"Select content first":"Send selected • Find device →");
        find.setEnabled(!selected.isEmpty());find.setAlpha(selected.isEmpty()?.45f:1f);find.setOnClickListener(v->showDiscovery());
        LinearLayout.LayoutParams topSend=new LinearLayout.LayoutParams(-1,dp(58));topSend.setMargins(0,dp(12),0,0);root.addView(find,topSend);

        TextView count=text(selected.size()+" item"+(selected.size()==1?"":"s")+" selected • "+formatBytes(selectedTotalBytes()),18,Color.WHITE,true);
        count.setPadding(0,dp(18),0,dp(8));root.addView(count);
        LinearLayout selection=card();
        if(selected.isEmpty()) selection.addView(text("Nothing selected yet. Photos and Videos open inside OptiShare; Files opens Android's document picker.",13,Color.rgb(156,181,202),false));
        else {
            selection.addView(text("Transfer queue • drag-free controls keep ordering predictable",12,Color.rgb(115,196,255),true));
            int show=Math.min(selected.size(),40);
            for(int i=0;i<show;i++) selection.addView(queueSelectionRow(i));
            if(selected.size()>show) selection.addView(text("+ "+(selected.size()-show)+" more queued",12,Color.rgb(82,196,255),true));
            Button clear=secondaryButton("Clear selection");clear.setOnClickListener(v->{selected.clear();FolderTransferQueue.clear();showSendSelection();});
            LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(-1,dp(46));cl.setMargins(0,dp(10),0,0);selection.addView(clear,cl);
        }
        root.addView(selection);
        setContentView(scroll);
    }

    private View queueSelectionRow(int index){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(5),0,dp(5));
        TextView label=text((index+1)+". "+displayName(selected.get(index)),12,Color.WHITE,false);label.setMaxLines(2);row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        Button up=smallButton("↑");up.setEnabled(index>0);up.setAlpha(index>0?1f:.35f);up.setOnClickListener(v->moveQueueItem(index,index-1));row.addView(up,new LinearLayout.LayoutParams(dp(42),dp(40)));
        Button down=smallButton("↓");down.setEnabled(index<selected.size()-1);down.setAlpha(index<selected.size()-1?1f:.35f);down.setOnClickListener(v->moveQueueItem(index,index+1));LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(dp(42),dp(40));dl.setMargins(dp(5),0,0,0);row.addView(down,dl);
        Button remove=smallButton("×");remove.setOnClickListener(v->removeQueueItem(index));LinearLayout.LayoutParams rl=new LinearLayout.LayoutParams(dp(42),dp(40));rl.setMargins(dp(5),0,0,0);row.addView(remove,rl);
        return row;
    }

    private void moveQueueItem(int from,int to){
        if(from<0||to<0||from>=selected.size()||to>=selected.size()||from==to)return;
        Uri item=selected.remove(from);selected.add(to,item);showSendSelection();
    }

    private void removeQueueItem(int index){
        if(index<0||index>=selected.size())return;selected.remove(index);showSendSelection();
    }

    private void updateLiveQueue(int index,String name,long done,long total,boolean complete){
        liveQueueIndex=index;liveQueueName=name;liveQueueDone=done;liveQueueTotal=total;
        if(index>=0&&complete)completedQueueIndexes.add(index);
        renderLiveQueue(index,name,done,total);
    }

    private void renderLiveQueue(int activeIndex,String activeName,long activeDone,long activeTotal){
        if(transferQueueList==null)return;transferQueueList.removeAllViews();
        if(receiverMode&&selected.isEmpty()&&activeIndex<0){transferQueueList.addView(text("Waiting for incoming manifest…",12,Color.rgb(151,181,205),false));return;}
        int count=receiverMode?Math.max(activeIndex+1,completedQueueIndexes.isEmpty()?0:(Collections.max(completedQueueIndexes)+1)):selected.size();
        if(count==0&&activeIndex>=0)count=activeIndex+1;
        int show=Math.min(count,20);
        for(int i=0;i<show;i++){
            if(removedQueueIndexes.contains(i))continue;
            boolean completed=completedQueueIndexes.contains(i);boolean active=i==activeIndex&&!completed;
            boolean failed=failedQueueIndexes.contains(i);
            String name=(!receiverMode&&i<selected.size())?displayName(selected.get(i)):(active&&activeName!=null?activeName:"File "+(i+1));
            String state=failed?"Failed • "+failedQueueReasons.get(i,"Retry available"):completed?"✓ Completed":active?(transferPaused?"Paused":"Sending")+" • "+(activeTotal>0?formatBytes(activeDone)+" / "+formatBytes(activeTotal):"Starting…"):"Waiting";
            int color=failed?Color.rgb(255,92,102):completed?Color.rgb(65,225,151):active?Color.rgb(89,205,255):Color.rgb(151,181,205);
            if(failed&&!receiverMode&&i<selected.size())transferQueueList.addView(failedQueueRow(i,name,state,color));
            else transferQueueList.addView(text((i+1)+". "+name+"   "+state,11,color,active||completed));
        }
        if(count>show)transferQueueList.addView(text("+ "+(count-show)+" more files",11,Color.rgb(122,158,185),false));
    }

    private View failedQueueRow(int index,String name,String state,int color){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label=text((index+1)+". "+name+"   "+state,11,color,true);
        row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        Button retry=smallButton("Retry");retry.setOnClickListener(v->retryQueueFile(index));
        row.addView(retry,new LinearLayout.LayoutParams(dp(72),dp(40)));
        Button remove=smallButton("×");remove.setOnClickListener(v->{removedQueueIndexes.add(index);renderLiveQueue(liveQueueIndex,liveQueueName,liveQueueDone,liveQueueTotal);});
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(dp(42),dp(40));rp.setMargins(dp(5),0,0,0);row.addView(remove,rp);
        return row;
    }

    private void retryQueueFile(int index){
        if(index<0||index>=selected.size())return;
        Uri retry=selected.get(index);selected.clear();selected.add(retry);
        List<com.kenan.optishare.model.TransferItem> rich=FolderTransferQueue.snapshot();
        FolderTransferQueue.clear();
        for(com.kenan.optishare.model.TransferItem item:rich)if(retry.equals(item.getUri()))FolderTransferQueue.add(item);
        showSendSelection();
    }

    private void retryFailedFiles(){
        List<Uri> retry=new ArrayList<>();
        for(int i=0;i<selected.size();i++)if(failedQueueIndexes.contains(i)&&!removedQueueIndexes.contains(i))retry.add(selected.get(i));
        selected.clear();selected.addAll(retry);showSendSelection();
    }

    private void openInternalGallery(String type) {
        pendingGalleryType=type;
        galleryReturnScreen=currentScreen==SCREEN_SEND?SCREEN_SEND:SCREEN_HOME;
        if(!hasMediaPermission(type)){requestMediaPermission(type);return;}
        showMediaGallery(type);
    }

    private void showMediaGallery(String type) {
        currentScreen=SCREEN_GALLERY;
        ScrollView outer=new ScrollView(this);
        LinearLayout root=shell(outer);
        String galleryTitle="image".equals(type)?"Photos":"video".equals(type)?"Videos":"Music";
        addBackHeader(root,galleryTitle,"Tap to select multiple items");
        TextView selectedCount=text(selected.size()+" selected",14,Color.rgb(92,202,255),true);selectedCount.setGravity(Gravity.CENTER);root.addView(selectedCount);

        RecyclerView recycler=new RecyclerView(this);
        recycler.setNestedScrollingEnabled(false);
        recycler.setLayoutManager(new GridLayoutManager(this,"audio".equals(type)?2:3));
        Set<Uri> initial=new HashSet<>(selected);
        GalleryAdapter adapter=new GalleryAdapter(type,initial,set->{
            selectedCount.setText((selected.size()+Math.max(0,set.size()-initial.size()))+" queued");
        });
        adapter.replace(new MediaRepository(this).load(type,180,0));
        recycler.setAdapter(adapter);
        root.addView(recycler,new LinearLayout.LayoutParams(-1,dp(760)));

        Button done=primary("Add selected to queue");done.setOnClickListener(v->{for(Uri uri:adapter.selection())if(!selected.contains(uri))selected.add(uri);showSendSelection();});
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
        TextView hint=text("Verified OptiShare phones appear here automatically. You can also scan the QR shown on the receiving phone.",12,Color.rgb(150,179,202),false);hint.setGravity(Gravity.CENTER);hint.setPadding(0,dp(6),0,0);radar.addView(hint);root.addView(radar);
        LinearLayout qrRow=new LinearLayout(this);qrRow.setOrientation(LinearLayout.HORIZONTAL);
        Button scan=secondaryButton("Scan receiver QR");scan.setOnClickListener(v->startQrScanner());qrRow.addView(scan,new LinearLayout.LayoutParams(0,dp(50),1));
        Button retry=secondaryButton("Search again");retry.setOnClickListener(v->startDiscovery());LinearLayout.LayoutParams rr=new LinearLayout.LayoutParams(0,dp(50),1);rr.setMargins(dp(8),0,0,0);qrRow.addView(retry,rr);
        LinearLayout.LayoutParams qrlp=new LinearLayout.LayoutParams(-1,-2);qrlp.setMargins(0,dp(12),0,0);root.addView(qrRow,qrlp);
        peerList=new LinearLayout(this);peerList.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams pl=new LinearLayout.LayoutParams(-1,-2);pl.setMargins(0,dp(12),0,0);root.addView(peerList,pl);
        setContentView(scroll);startDiscovery();
    }

    private void showReceive() {
        currentScreen=SCREEN_RECEIVE;receiverMode=true;selected.clear();FolderTransferQueue.clear();
        if(!ensureLegacyWritePermission()){return;}
        ScrollView scroll=new ScrollView(this);LinearLayout root=shell(scroll);addBackHeader(root,"Receive","Keep this screen open until the direct session is ready");
        connectionPill=connectionBadge("STARTING RECEIVER",Color.rgb(255,194,73));root.addView(connectionPill);
        LinearLayout receiveCard=card();TextView icon=text("◉",84,Color.rgb(65,222,151),true);icon.setGravity(Gravity.CENTER);receiveCard.addView(icon);
        discoveryState=text("Preparing private receiving session…",18,Color.WHITE,true);discoveryState.setGravity(Gravity.CENTER);receiveCard.addView(discoveryState);
        TextView identityLabel=text(identity.name(),14,Color.rgb(88,202,255),true);identityLabel.setGravity(Gravity.CENTER);identityLabel.setTag("receiver_identity");receiveCard.addView(identityLabel);
        ImageView qr=new ImageView(this);qr.setTag("receiver_qr");qr.setAdjustViewBounds(true);receiveCard.addView(qr,new LinearLayout.LayoutParams(-1,dp(260)));
        root.addView(receiveCard);
        if(ENABLE_PC_COMPANION){Button browser=secondaryButton("Receive from browser / PC");browser.setOnClickListener(v->startBrowserReceive());LinearLayout.LayoutParams bl=new LinearLayout.LayoutParams(-1,dp(50));bl.setMargins(0,dp(12),0,0);root.addView(browser,bl);}
        root.addView(text("Keep this screen open while the sender connects. Android-to-Android transfers use authenticated ECDH and AES-GCM encryption.",11,Color.rgb(150,179,202),false));
        Button stop=secondaryButton("Stop receiving");stop.setOnClickListener(v->{stopTransferService();stopBrowserReceive();safeRemoveGroup();showHome();});LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(-1,dp(50));sl.setMargins(0,dp(12),0,0);root.addView(stop,sl);
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
        TextView eta=text("ETA —",13,Color.rgb(187,215,235),true);eta.setTag("transfer_eta");eta.setGravity(Gravity.END);metrics.addView(eta,new LinearLayout.LayoutParams(0,-2,1));
        card.addView(metrics);root.addView(card);
        if(!benchmarkMode){
            LinearLayout queueCard=card();
            TextView queueTitle=text(receiverMode?"Receiving files":"Transfer queue",15,Color.WHITE,true);queueCard.addView(queueTitle);
            transferQueueList=new LinearLayout(this);transferQueueList.setOrientation(LinearLayout.VERTICAL);transferQueueList.setPadding(0,dp(8),0,0);queueCard.addView(transferQueueList);
            completedQueueIndexes.clear();failedQueueIndexes.clear();removedQueueIndexes.clear();failedQueueReasons.clear();
            liveQueueIndex=-1;liveQueueName=null;liveQueueDone=0L;liveQueueTotal=0L;renderLiveQueue(-1,null,0L,0L);
            LinearLayout.LayoutParams qlp=new LinearLayout.LayoutParams(-1,-2);qlp.setMargins(0,dp(12),0,0);root.addView(queueCard,qlp);
        }else transferQueueList=null;
        if(!receiverMode&&!pcTransferMode){
            transferPauseButton=secondaryButton("Pause transfer");
            transferPauseButton.setOnClickListener(v->pauseOrResumeTransfer());
            LinearLayout.LayoutParams pbtn=new LinearLayout.LayoutParams(-1,dp(50));pbtn.setMargins(0,dp(12),0,0);root.addView(transferPauseButton,pbtn);
        }else transferPauseButton=null;
        if(benchmarkMode){
            transferCancelButton=null;
            Button back=secondaryButton("Back to nearby devices");back.setOnClickListener(v->{stopTransferService();benchmarkMode=false;pcTransferMode=false;transferStarted=false;showDiscovery();});LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(50));cp.setMargins(0,dp(12),0,0);root.addView(back,cp);
        }else{
            transferCancelButton=secondaryButton("Cancel transfer");transferCancelButton.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(R.string.cancel_transfer_title).setMessage(R.string.cancel_transfer_message).setPositiveButton(R.string.cancel_transfer,(d,w)->{stopTransferService();showHome();}).setNegativeButton(R.string.keep_transferring,null).show());LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(50));cp.setMargins(0,dp(12),0,0);root.addView(transferCancelButton,cp);
        }
        setContentView(scroll);
    }

    private void startDiscovery() {
        discoveryHandler.removeCallbacks(discoveryRetry);
        discoveryHandler.removeCallbacks(lanFallbackConnect);
        pendingLanHost=null;pendingLanName=null;
        startLanDiscovery();
        startPcDiscovery();
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
                    renderPeers();
                    setConnectionUi("OPTISHARE FOUND",Color.rgb(65,225,151));
                    setDiscoveryText("Verified OptiShare receiver found • choose Speed test or Send here below.");
                    discoveryHandler.removeCallbacks(lanFallbackConnect);
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

    private void startPcDiscovery(){
        if(!ENABLE_PC_COMPANION)return;
        if(pcDiscovery==null||!pcDiscovery.available())return;
        pcPeers.clear();
        pcDiscovery.discover(new PcDiscovery.Listener(){
            @Override public void onPc(PcDiscovery.Peer peer){
                runOnUiThread(()->{
                    if(currentScreen!=SCREEN_DISCOVERY||transferStarted)return;
                    for(PcDiscovery.Peer existing:pcPeers)if(existing.id().equals(peer.id()))return;
                    pcPeers.add(peer);
                    renderPeers();
                    setDiscoveryText("Found "+peer.name+" • Windows Companion ready");
                });
            }
            @Override public void onStatus(String message){
                if(currentScreen==SCREEN_DISCOVERY&&peers.isEmpty()&&pcPeers.isEmpty()&&pendingLanHost==null)setDiscoveryText(message);
            }
        });
    }

    private void stopPcDiscovery(){if(pcDiscovery!=null)pcDiscovery.stop();}

    private void connectToPc(PcDiscovery.Peer peer){
        if(peer==null||transferStarted)return;
        transferStarted=true;pcTransferMode=true;receiverMode=false;
        activeRoute="pc-local";activeTransferStartedAt=System.currentTimeMillis();
        connectedPeerName=peer.name+" • Windows PC";
        discoveryHandler.removeCallbacks(discoveryRetry);stopLanDiscovery();stopPcDiscovery();
        showTransferScreen("Sending to Windows");
        setConnectionUi("PC LOCAL ROUTE ✓",Color.rgb(89,205,255));
        setTransferUi("Connecting to "+peer.name,peer.protocolVersion>=2
                ?"Secure PC route • ECDH + AES-256-GCM + SHA-256"
                :"Legacy local PC route • session token + SHA-256 verification",0);
        ArrayList<String> uris=new ArrayList<>();for(Uri uri:selected)uris.add(uri.toString());
        Intent i=new Intent(this,PcTransferService.class).setAction(PcTransferService.ACTION_SEND_PC);
        i.putExtra(PcTransferService.EXTRA_HOST,peer.host);i.putExtra(PcTransferService.EXTRA_PORT,peer.port);
        i.putExtra(PcTransferService.EXTRA_TOKEN,peer.token);i.putExtra(PcTransferService.EXTRA_PROTOCOL,peer.protocolVersion);
        i.putStringArrayListExtra(PcTransferService.EXTRA_URIS,uris);
        ContextCompat.startForegroundService(this,i);
    }

    private void benchmarkViaLan(String name,String host){
        if(host==null||host.trim().isEmpty())return;
        benchmarkMode=true;pcTransferMode=true;transferStarted=true;receiverMode=false;
        discoveryHandler.removeCallbacks(p2pConnectTimeout);pendingP2pDevice=null;
        connectedPeerName=(name==null||name.trim().isEmpty())?"OptiShare device":name;
        activeRoute=RoutePerformanceStore.ROUTE_LAN;activeTransferStartedAt=System.currentTimeMillis();
        stopPcDiscovery();stopLanDiscovery();
        showTransferScreen("Android speed test");setConnectionUi("SAME WI-FI SPEED TEST",Color.rgb(89,205,255));
        setTransferUi("Preparing 8 MB encrypted speed test","Using the verified OptiShare same-Wi-Fi route. No test file is saved.",0);
        startBenchmarkService(host);
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
        if(!ensureNearbyReady())return;
        final Runnable[] createHolder=new Runnable[1];
        createHolder[0]=()->{
            try{manager.createGroup(channel,new WifiP2pManager.ActionListener(){
                @Override public void onSuccess(){setDiscoveryText("READY TO RECEIVE ✓\nWaiting for sender…");setConnectionUi("VISIBLE TO SENDERS",Color.rgb(65,222,151));try{if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){manager.requestDeviceInfo(channel,device->{thisDevice=device;refreshReceiverIdentity();});}else{refreshReceiverIdentity();}manager.requestConnectionInfo(channel,V2Activity.this);}catch(SecurityException ignored){showNearbyPermissionHelp();}}
                @Override public void onFailure(int reason){if(reason==WifiP2pManager.BUSY&&currentScreen==SCREEN_RECEIVE&&receiverMode){discoveryHandler.postDelayed(createHolder[0],700);}else{setDiscoveryText(p2pError("Receiver could not start",reason));setConnectionUi("RECEIVER ERROR",Color.rgb(255,91,101));}}
            });}catch(SecurityException e){showNearbyPermissionHelp();}
        };
        try{manager.removeGroup(channel,new WifiP2pManager.ActionListener(){@Override public void onSuccess(){discoveryHandler.postDelayed(createHolder[0],250);}@Override public void onFailure(int reason){discoveryHandler.postDelayed(createHolder[0],250);}});}catch(Exception ignored){discoveryHandler.postDelayed(createHolder[0],250);}
    }

    @Override public void onPeersAvailable(WifiP2pDeviceList list) {
        peers.clear();peers.addAll(list.getDeviceList());Collections.sort(peers,Comparator.comparing(this::deviceName,String.CASE_INSENSITIVE_ORDER));
        if(pendingQrAddress!=null){for(WifiP2pDevice d:peers){if(pendingQrAddress.equalsIgnoreCase(d.deviceAddress)){pendingQrAddress=null;connectTo(d);return;}}}
        renderPeers();if(currentScreen==SCREEN_DISCOVERY){if(peers.isEmpty()){scheduleDiscoveryRetry();}else{discoveryHandler.removeCallbacks(discoveryRetry);discoveryHandler.removeCallbacks(lanFallbackConnect);setDiscoveryText(peers.size()+" Wi-Fi Direct device"+(peers.size()==1?"":"s")+" found ✓");}}
    }

    private void renderPeers() {
        runOnUiThread(()->{
            if(peerList==null)return;
            peerList.removeAllViews();
            if(peers.isEmpty()&&pcPeers.isEmpty()){
                LinearLayout empty=card();empty.addView(text("Searching…",14,Color.WHITE,true));
                empty.addView(text("Looking for verified OptiShare Android receivers on this network.",12,Color.rgb(147,173,196),false));
                peerList.addView(empty);return;
            }
            if(pendingLanHost!=null&&!pendingLanHost.trim().isEmpty()){
                LinearLayout row=card();LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);
                TextView avatar=text("OS",13,Color.WHITE,true);avatar.setGravity(Gravity.CENTER);avatar.setBackground(gradient(Color.rgb(43,196,126),Color.rgb(31,137,213),18));
                line.addView(avatar,new LinearLayout.LayoutParams(dp(48),dp(48)));
                LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);names.setPadding(dp(12),0,0,0);
                String lanName=(pendingLanName==null||pendingLanName.trim().isEmpty())?"OptiShare device":pendingLanName;
                names.addView(text(lanName,15,Color.WHITE,true));
                names.addView(text("Verified OptiShare • encrypted same-Wi-Fi route",12,Color.rgb(151,205,184),false));
                line.addView(names,new LinearLayout.LayoutParams(0,-2,1));row.addView(line);
                LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);actions.setPadding(0,dp(10),0,0);
                Button test=secondaryButton("Speed test");test.setOnClickListener(v->benchmarkViaLan(lanName,pendingLanHost));
                Button send=secondaryButton("Send here");send.setOnClickListener(v->connectViaLan(lanName,pendingLanHost));
                actions.addView(test,new LinearLayout.LayoutParams(0,dp(46),1));
                LinearLayout.LayoutParams sendLp=new LinearLayout.LayoutParams(0,dp(46),1);sendLp.setMargins(dp(8),0,0,0);actions.addView(send,sendLp);
                row.addView(actions);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(8));peerList.addView(row,lp);
            }
            for(PcDiscovery.Peer pc:pcPeers){
                LinearLayout row=card();LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);
                TextView avatar=text("PC",14,Color.WHITE,true);avatar.setGravity(Gravity.CENTER);avatar.setBackground(gradient(Color.rgb(39,178,255),Color.rgb(84,82,222),18));
                line.addView(avatar,new LinearLayout.LayoutParams(dp(48),dp(48)));
                LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);names.setPadding(dp(12),0,0,0);
                names.addView(text(pc.name,15,Color.WHITE,true));names.addView(text("Windows Companion • same network",12,Color.rgb(151,182,205),false));
                line.addView(names,new LinearLayout.LayoutParams(0,-2,1));Button connect=secondaryButton("Send here");connect.setOnClickListener(v->connectToPc(pc));
                line.addView(connect,new LinearLayout.LayoutParams(dp(112),dp(46)));row.addView(line);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(8));peerList.addView(row,lp);
            }
            boolean showUnverifiedP2p=pendingLanHost==null||pendingLanHost.trim().isEmpty();
            if(!showUnverifiedP2p&&!peers.isEmpty()){
                TextView hidden=text(peers.size()+" unverified Wi-Fi Direct device"+(peers.size()==1?"":"s")+" hidden while verified OptiShare is available",11,Color.rgb(126,157,181),false);
                hidden.setPadding(0,dp(4),0,dp(10));peerList.addView(hidden);
            }
            if(showUnverifiedP2p)for(WifiP2pDevice device:peers){
                LinearLayout row=card();
                LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);
                TextView avatar=text(firstLetter(deviceName(device)),18,Color.WHITE,true);avatar.setGravity(Gravity.CENTER);avatar.setBackground(gradient(Color.rgb(38,151,232),Color.rgb(62,91,220),18));
                line.addView(avatar,new LinearLayout.LayoutParams(dp(48),dp(48)));
                LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);names.setPadding(dp(12),0,0,0);
                names.addView(text(deviceName(device),15,Color.WHITE,true));
                names.addView(text(deviceStatus(device.status)+" • Wi-Fi Direct candidate",12,Color.rgb(151,182,205),false));
                line.addView(names,new LinearLayout.LayoutParams(0,-2,1));row.addView(line);
                TextView note=text("Not verified as OptiShare • use the verified OptiShare card above or scan the receiver QR",11,Color.rgb(142,166,187),false);
                note.setPadding(0,dp(10),0,0);row.addView(note);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(8));peerList.addView(row,lp);
            }
        });
    }


    private void benchmarkDevice(WifiP2pDevice device) {
        benchmarkMode=true;pcTransferMode=true;stopPcDiscovery();
        if(!ensureNearbyReady())return;
        discoveryHandler.removeCallbacks(discoveryRetry);pendingP2pDevice=device;
        connectedPeerName=deviceName(device);setConnectionUi("CONNECTING FOR TEST…",Color.rgb(89,205,255));
        setDiscoveryText("Connecting to "+connectedPeerName+" for encrypted speed test…");
        WifiP2pConfig config=new WifiP2pConfig();config.deviceAddress=device.deviceAddress;config.wps.setup=WpsInfo.PBC;config.groupOwnerIntent=0;
        Runnable go=()->{try{manager.connect(channel,config,new WifiP2pManager.ActionListener(){@Override public void onSuccess(){setDiscoveryText("Speed-test request sent. Waiting up to 12 seconds for the direct link…");discoveryHandler.removeCallbacks(p2pConnectTimeout);discoveryHandler.postDelayed(p2pConnectTimeout,12000);}@Override public void onFailure(int reason){pendingP2pDevice=null;if(pendingLanHost!=null&&!pendingLanHost.trim().isEmpty()){benchmarkViaLan(pendingLanName,pendingLanHost);return;}benchmarkMode=false;pcTransferMode=false;setConnectionUi("CONNECTION FAILED",Color.rgb(255,91,101));setDiscoveryText(p2pError("Speed-test connection failed",reason));}});}catch(SecurityException e){benchmarkMode=false;pcTransferMode=false;showNearbyPermissionHelp();}};
        try{manager.cancelConnect(channel,new WifiP2pManager.ActionListener(){@Override public void onSuccess(){go.run();}@Override public void onFailure(int reason){go.run();}});}catch(Exception e){go.run();}
    }

    private void connectTo(WifiP2pDevice device) {
        benchmarkMode=false;pcTransferMode=false;stopPcDiscovery();
        if(!ensureNearbyReady())return;discoveryHandler.removeCallbacks(discoveryRetry);pendingP2pDevice=device;connectedPeerName=deviceName(device);setConnectionUi("CONNECTING…",Color.rgb(255,194,73));setDiscoveryText("Connecting to "+connectedPeerName+"…");WifiP2pConfig config=new WifiP2pConfig();config.deviceAddress=device.deviceAddress;config.wps.setup=WpsInfo.PBC;config.groupOwnerIntent=0;
        Runnable go=()->{try{manager.connect(channel,config,new WifiP2pManager.ActionListener(){@Override public void onSuccess(){setDiscoveryText("Connection request sent. Waiting up to 12 seconds for the direct link…");discoveryHandler.removeCallbacks(p2pConnectTimeout);discoveryHandler.postDelayed(p2pConnectTimeout,12000);}@Override public void onFailure(int reason){pendingP2pDevice=null;if(pendingLanHost!=null&&!pendingLanHost.trim().isEmpty()){connectViaLan(pendingLanName,pendingLanHost);return;}setConnectionUi("CONNECTION FAILED",Color.rgb(255,91,101));setDiscoveryText(p2pError("Connection failed",reason));}});}catch(SecurityException e){showNearbyPermissionHelp();}};
        try{manager.cancelConnect(channel,new WifiP2pManager.ActionListener(){@Override public void onSuccess(){go.run();}@Override public void onFailure(int reason){go.run();}});}catch(Exception e){go.run();}
    }

    @Override public void onConnectionInfoAvailable(WifiP2pInfo info) {
        if(info==null||!info.groupFormed||info.groupOwnerAddress==null)return;
        discoveryHandler.removeCallbacks(p2pConnectTimeout);pendingP2pDevice=null;setConnectionUi("CONNECTED ✓",Color.rgb(65,225,151));
        if(receiverMode&&info.isGroupOwner){setDiscoveryText("CONNECTED ✓\nSecure receiver channel is active.");startReceiverService();}
        else if(!receiverMode&&!info.isGroupOwner&&!transferStarted){
            activeRoute=RoutePerformanceStore.ROUTE_DIRECT;transferStarted=true;stopLanDiscovery();activeTransferStartedAt=System.currentTimeMillis();
            if(benchmarkMode){
                pcTransferMode=true;showTransferScreen("Android speed test");setConnectionUi("ENCRYPTED SPEED TEST",Color.rgb(89,205,255));
                setTransferUi("Preparing 8 MB speed test","Uses the same ECDH/AES-GCM transport as Android file transfer. No test file is saved.",0);
                startBenchmarkService(info.groupOwnerAddress.getHostAddress());
            }else{
                showTransferScreen("Sending");setConnectionUi("SMART ROUTE • WI-FI DIRECT ✓",Color.rgb(65,225,151));startSenderService(info.groupOwnerAddress.getHostAddress());
            }
        }
    }

    private void startBrowserReceive(){
        Intent i=new Intent(this,BrowserReceiveService.class).setAction(BrowserReceiveService.ACTION_START);
        ContextCompat.startForegroundService(this,i);
        setDiscoveryText("Starting local browser receiver…");
    }

    private void stopBrowserReceive(){
        try{startService(new Intent(this,BrowserReceiveService.class).setAction(BrowserReceiveService.ACTION_STOP));}catch(Exception ignored){}
        browserMode=false;
    }

    private void startReceiverService() {
        Intent i=new Intent(this,TransferService.class).setAction(TransferService.ACTION_START_RECEIVER);ContextCompat.startForegroundService(this,i);
    }

    private void startBenchmarkService(String host) {
        Intent i=new Intent(this,TransferService.class).setAction(TransferService.ACTION_BENCHMARK);
        i.putExtra(TransferService.EXTRA_HOST,host);i.putExtra(TransferService.EXTRA_ROUTE,activeRoute);
        ContextCompat.startForegroundService(this,i);
    }

    private void startSenderService(String host) {
        ArrayList<String> uris=new ArrayList<>();for(Uri uri:selected)uris.add(uri.toString());Intent i=new Intent(this,TransferService.class).setAction(TransferService.ACTION_SEND);i.putExtra(TransferService.EXTRA_HOST,host);i.putExtra(TransferService.EXTRA_ROUTE,activeRoute);String fallback=AdaptiveRouteOrchestrator.verifiedLanFallback(activeRoute,pendingLanHost);if(fallback!=null)i.putExtra(TransferService.EXTRA_FALLBACK_HOST,fallback);i.putStringArrayListExtra(TransferService.EXTRA_URIS,uris);ContextCompat.startForegroundService(this,i);
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

    private void updatePauseButton(boolean paused){runOnUiThread(()->{if(transferPauseButton==null||transferPauseButton.getVisibility()!=View.VISIBLE)return;transferPauseButton.setText(UiText.get(this,paused?"Resume transfer":"Pause transfer"));transferPauseButton.setOnClickListener(v->pauseOrResumeTransfer());});}

    private void stopTransferService(){startService(new Intent(this,TransferService.class).setAction(TransferService.ACTION_STOP));try{startService(new Intent(this,PcTransferService.class).setAction(PcTransferService.ACTION_STOP_PC));}catch(Exception ignored){}}

    private void setTransferUi(String title,String detail,int progress){runOnUiThread(()->{if(transferState!=null)transferState.setText(UiText.get(this,title));if(transferDetail!=null)transferDetail.setText(UiText.get(this,detail));if(transferProgress!=null&&progress>=0)transferProgress.setProgress(progress);if(progress>=0){TextView percent=findViewByTag("transfer_percent");if(percent!=null)percent.setText(progress+"%");}});}

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
        if("pc-local".equals(route))return "Windows PC";
        if("incoming".equals(route))return "received";
        return route;
    }

    private String formatTransferSpeed(double bytesPerSecond){if(bytesPerSecond>=1024d*1024d)return String.format(Locale.US,"%.1f MB/s",bytesPerSecond/(1024d*1024d));if(bytesPerSecond>=1024d)return String.format(Locale.US,"%.0f KB/s",bytesPerSecond/1024d);return bytesPerSecond>0?String.format(Locale.US,"%.0f B/s",bytesPerSecond):"— MB/s";}
    private String formatEta(long seconds){if(seconds<=0)return"ETA —";if(seconds<60)return"ETA "+seconds+"s";long minutes=seconds/60;long remain=seconds%60;return"ETA "+minutes+"m "+remain+"s";}

    private void refreshReceiverIdentity() {
        if(currentScreen!=SCREEN_RECEIVE||browserMode)return;TextView label=findViewByTag("receiver_identity");ImageView qr=findViewByTag("receiver_qr");String p2pName=thisDevice==null?identity.name():deviceName(thisDevice);if(label!=null)label.setText(p2pName+" • "+identity.name());if(qr!=null&&thisDevice!=null&&thisDevice.deviceAddress!=null){try{qr.setImageBitmap(makeQr("OPTISHARE2|"+thisDevice.deviceAddress+"|"+p2pName,720));}catch(Exception ignored){}}
    }

    @SuppressWarnings("unchecked") private <T extends View>T findViewByTag(String tag){View v=getWindow().getDecorView().findViewWithTag(tag);return(T)v;}

    private void startQrScanner(){ScanOptions options=new ScanOptions();options.setPrompt("Scan the receiver's OptiShare QR");options.setBeepEnabled(false);options.setOrientationLocked(true);options.setCaptureActivity(PortraitQrCaptureActivity.class);options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);qrScanner.launch(options);}

    private void handlePairingQr(String raw){if(raw==null||!raw.startsWith("OPTISHARE2|")){showMessage("Invalid QR","This is not an OptiShare 2 pairing code.");return;}String[] parts=raw.split("\\|",3);if(parts.length<3){showMessage("Invalid QR","Pairing information is incomplete.");return;}pendingQrAddress=parts[1];pendingQrName=parts[2];setDiscoveryText("Receiver identified: "+pendingQrName+". Searching for its direct link…");startDiscovery();}

    private Bitmap makeQr(String value,int size)throws Exception{BitMatrix matrix=new MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,size,size);Bitmap bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.RGB_565);for(int y=0;y<size;y++)for(int x=0;x<size;x++)bitmap.setPixel(x,y,matrix.get(x,y)?Color.BLACK:Color.WHITE);return bitmap;}

    private boolean ensureNearbyReady(){if(manager==null||channel==null){showMessage("Wi‑Fi Direct unavailable","This device does not expose Android Wi‑Fi Direct to OptiShare.");return false;}if(!hasNearbyPermission()){requestNearbyPermission();return false;}if(Build.VERSION.SDK_INT<=32&&!isLocationEnabled()){new AlertDialog.Builder(this).setTitle(R.string.turn_on_location).setMessage(R.string.location_required_message).setPositiveButton(R.string.open_settings,(d,w)->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))).setNegativeButton(R.string.cancel,null).show();return false;}return true;}

    private boolean hasNearbyPermission(){if(Build.VERSION.SDK_INT>=33)return checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)==PackageManager.PERMISSION_GRANTED;if(Build.VERSION.SDK_INT>=23)return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;return true;}
    private void requestNearbyPermission(){if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},REQ_NEARBY);else if(Build.VERSION.SDK_INT>=23)requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION},REQ_NEARBY);}
    private String mediaPermission(String type){return "image".equals(type)?Manifest.permission.READ_MEDIA_IMAGES:"audio".equals(type)?Manifest.permission.READ_MEDIA_AUDIO:Manifest.permission.READ_MEDIA_VIDEO;}
    private boolean hasMediaPermission(String type){if(Build.VERSION.SDK_INT>=33)return checkSelfPermission(mediaPermission(type))==PackageManager.PERMISSION_GRANTED;if(Build.VERSION.SDK_INT>=23)return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;return true;}
    private void requestMediaPermission(String type){if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{mediaPermission(type)},REQ_MEDIA);else if(Build.VERSION.SDK_INT>=23)requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},REQ_MEDIA);else showMediaGallery(type);}
    private boolean ensureLegacyWritePermission(){if(Build.VERSION.SDK_INT>=23&&Build.VERSION.SDK_INT<=28&&checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},REQ_LEGACY_WRITE);return false;}return true;}

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);boolean granted=grantResults.length>0&&grantResults[grantResults.length-1]==PackageManager.PERMISSION_GRANTED;if(requestCode==REQ_MEDIA&&granted&&pendingGalleryType!=null)showMediaGallery(pendingGalleryType);if(requestCode==REQ_NEARBY&&granted){if(currentScreen==SCREEN_DISCOVERY)startDiscovery();else if(currentScreen==SCREEN_RECEIVE)startReceiverMode();}if(requestCode==REQ_LEGACY_WRITE&&granted)showReceive();}

    private void requestNotificationPermissionIfUseful(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},2204);}

    private void showTextComposer(String initial){
        final android.widget.EditText input=new android.widget.EditText(this);
        input.setMinLines(5);input.setMaxLines(12);input.setGravity(Gravity.TOP|Gravity.START);
        input.setText(initial==null?"":initial);input.setHint("Type or paste text to send securely");
        new AlertDialog.Builder(this).setTitle(R.string.send_text).setView(input)
                .setPositiveButton(R.string.add_to_queue,(d,w)->{
                    try{com.kenan.optishare.model.TransferItem item=TextTransferStore.create(this,input.getText());
                        if(!selected.contains(item.getUri()))selected.add(item.getUri());
                        FolderTransferQueue.add(item);showSendSelection();}
                    catch(Exception e){showMessage("Text not added",e.getMessage());}
                }).setNegativeButton(R.string.cancel,null).show();
    }

    private void addClipboardToQueue(){
        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(clipboard==null||!clipboard.hasPrimaryClip()||clipboard.getPrimaryClip()==null||clipboard.getPrimaryClip().getItemCount()==0){showMessage("Clipboard is empty","Copy some text first, then try again.");return;}
        CharSequence value=clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        if(value==null||value.length()==0){showMessage("Clipboard has no text","The current clipboard item cannot be sent as text.");return;}
        try{com.kenan.optishare.model.TransferItem item=TextTransferStore.create(this,value,"Clipboard");if(!selected.contains(item.getUri()))selected.add(item.getUri());FolderTransferQueue.add(item);showSendSelection();}
        catch(Exception e){showMessage("Clipboard not added",e.getMessage());}
    }

    private void openFolder(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                |Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                |Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        folderPicker.launch(intent);
    }

    private void openInstalledApps(){appPicker.launch(new Intent(this,AppPickerActivity.class));}

    private void openExternal(String mime){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType(mime);intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);externalPicker.launch(intent);}
    private void openDocuments(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("*/*");intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/pdf","text/plain","text/csv","application/rtf","application/msword","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/vnd.ms-powerpoint","application/vnd.openxmlformats-officedocument.presentationml.presentation","application/vnd.oasis.opendocument.text","application/vnd.oasis.opendocument.spreadsheet"});intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);externalPicker.launch(intent);}
    private void persistReadPermission(Uri uri,int flags){try{getContentResolver().takePersistableUriPermission(uri,flags&Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}}

    private long selectedTotalBytes(){long total=0;for(Uri uri:selected){long size=querySize(uri);if(size>0&&Long.MAX_VALUE-total>size)total+=size;}return total;}
    private long querySize(Uri uri){Cursor c=null;try{c=getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null);if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.SIZE);if(i>=0&&!c.isNull(i))return c.getLong(i);}}catch(Exception ignored){}finally{if(c!=null)c.close();}return 0;}
    private String displayName(Uri uri){Cursor c=null;try{c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null);if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0&&!c.isNull(i))return c.getString(i);}}catch(Exception ignored){}finally{if(c!=null)c.close();}String last=uri.getLastPathSegment();return last==null?"item":last;}

    private void showDeviceSettings(){
        currentScreen=SCREEN_SETTINGS;
        ScrollView scroll=new ScrollView(this);LinearLayout root=shell(scroll);
        addBackHeader(root,"Settings","Device, received content and app information");
        LinearLayout device=card();device.addView(text("This device",16,Color.WHITE,true));device.addView(text(identity.name(),13,Color.rgb(151,190,218),false));
        Button rename=secondaryButton("Rename device");rename.setOnClickListener(v->editDeviceName());LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(48));rp.setMargins(0,dp(10),0,0);device.addView(rename,rp);
        Button trusted=secondaryButton("Trusted devices • "+trustedStore.list().size());trusted.setOnClickListener(v->showTrustedDevices());LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,dp(48));tp.setMargins(0,dp(8),0,0);device.addView(trusted,tp);root.addView(device);
        LinearLayout content=card();content.addView(text("Received content",16,Color.WHITE,true));content.addView(text("Files are sorted in Download/OptiShare. Text and clipboard items arrive as readable .txt files in the Text folder.",12,Color.rgb(151,190,218),false));
        Button received=secondaryButton("Open received files");received.setOnClickListener(v->startActivity(new Intent(this,ReceivedFilesActivity.class)));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(48));cp.setMargins(0,dp(10),0,0);content.addView(received,cp);LinearLayout.LayoutParams contentLp=new LinearLayout.LayoutParams(-1,-2);contentLp.setMargins(0,dp(12),0,0);root.addView(content,contentLp);
        LinearLayout about=card();about.addView(text("About OptiShare",16,Color.WHITE,true));about.addView(text("Version "+appVersion()+"\nPrivate Android-to-Android sharing. No account, advertising or analytics.",12,Color.rgb(151,190,218),false));about.addView(text("Designed & developed by Kenan Alhennawi",11,Color.rgb(91,189,255),true));LinearLayout.LayoutParams aboutLp=new LinearLayout.LayoutParams(-1,-2);aboutLp.setMargins(0,dp(12),0,0);root.addView(about,aboutLp);
        setContentView(scroll);
    }

    private String appVersion(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception ignored){return "2.2";}}

    private void showTrustedDevices(){
        if(!DeviceIdentityKey.supported()){showMessage("Trusted devices","Android 5 keeps manual six-digit verification. Persistent trust is available on Android 6 and newer.");return;}
        List<TrustedDeviceStore.Entry> entries=trustedStore.list();
        if(entries.isEmpty()){showMessage("Trusted devices","No trusted devices yet. On the first secure connection choose ‘Trust this device & confirm’.");return;}
        String[] labels=new String[entries.size()];
        for(int i=0;i<entries.size();i++){TrustedDeviceStore.Entry e=entries.get(i);labels[i]=e.name+"\n"+DeviceIdentityKey.shortFingerprint(e.fingerprint)+" • "+getString(e.autoAccept?R.string.auto_accept_on:R.string.confirm_files);}
        new AlertDialog.Builder(this).setTitle(R.string.trusted_devices).setItems(labels,(d,which)->showTrustedDeviceActions(entries.get(which))).setNegativeButton(R.string.close,null).show();
    }

    private void showTrustedDeviceActions(TrustedDeviceStore.Entry entry){
        String auto=getString(entry.autoAccept?R.string.auto_accept_turn_off:R.string.auto_accept_turn_on);
        new AlertDialog.Builder(this).setTitle(entry.name).setMessage(getString(R.string.fingerprint_details,DeviceIdentityKey.shortFingerprint(entry.fingerprint))).setItems(new String[]{auto,getString(R.string.forget_device)},(d,which)->{
            if(which==0){trustedStore.setAutoAccept(entry.fingerprint,!entry.autoAccept);showTrustedDevices();} else new AlertDialog.Builder(this).setTitle(R.string.forget_device_title).setMessage(R.string.forget_device_message).setPositiveButton(R.string.forget,(x,w)->{trustedStore.forget(entry.fingerprint);showTrustedDevices();}).setNegativeButton(R.string.cancel,null).show();
        }).setNegativeButton(R.string.back_plain,(d,w)->showTrustedDevices()).show();
    }

    private void showMySecurityIdentity(){
        if(!DeviceIdentityKey.supported()){showMessage("Security identity","Android 5 uses manual security-code verification and does not store a persistent trust key.");return;}
        try{String fp=new DeviceIdentityKey().fingerprint();showMessage("My security identity","Protected by Android Keystore\nFingerprint: "+DeviceIdentityKey.shortFingerprint(fp));}catch(Exception e){showMessage("Security identity","Could not access identity: "+e.getMessage());}
    }

    private void editDeviceName(){final android.widget.EditText input=new android.widget.EditText(this);input.setText(identity.name());input.setSingleLine(true);new AlertDialog.Builder(this).setTitle(R.string.device_name).setMessage(R.string.device_name_help).setView(input).setPositiveButton(R.string.save,(d,w)->{try{identity.setName(input.getText().toString());showDeviceSettings();}catch(Exception e){showMessage(getString(R.string.invalid_name),e.getMessage());}}).setNegativeButton(R.string.cancel,null).show();}

    private TextView connectionBadge(String label,int color){TextView v=text(label,13,color,true);v.setGravity(Gravity.CENTER);v.setPadding(dp(12),dp(10),dp(12),dp(10));v.setBackground(round(Color.argb(70,Color.red(color),Color.green(color),Color.blue(color)),14));return v;}
    private void setDiscoveryText(String value){runOnUiThread(()->{if(discoveryState!=null)discoveryState.setText(UiText.get(this,value));});}
    private void setConnectionUi(String label,int color){runOnUiThread(()->{if(connectionPill==null)return;connectionPill.setText(UiText.get(this,label));connectionPill.setTextColor(color);connectionPill.setBackground(round(Color.argb(80,Color.red(color),Color.green(color),Color.blue(color)),14));});}
    private void showNearbyPermissionHelp(){showMessage("Nearby permission required","Allow Nearby Wi‑Fi devices. On Android 12 or older, Android also requires Location permission and Location services for Wi‑Fi Direct discovery.");}
    private boolean isLocationEnabled(){LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);if(lm==null)return false;if(Build.VERSION.SDK_INT>=28)return lm.isLocationEnabled();try{return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)||lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);}catch(Exception e){return false;}}
    private void safeRemoveGroup(){if(manager==null||channel==null||!hasNearbyPermission())return;try{manager.removeGroup(channel,new WifiP2pManager.ActionListener(){@Override public void onSuccess(){}@Override public void onFailure(int reason){}});}catch(Exception ignored){}}
    private String p2pError(String prefix,int reason){if(reason==WifiP2pManager.BUSY)return prefix+": Nearby radio is busy. OptiShare will retry when Android releases it.";if(reason==WifiP2pManager.P2P_UNSUPPORTED)return prefix+": Wi‑Fi Direct is not supported on this phone.";return prefix+": Android could not complete the nearby operation ("+reason+").";}
    private String deviceName(WifiP2pDevice d){if(d==null||d.deviceName==null||d.deviceName.trim().isEmpty())return"Android device";return d.deviceName.trim();}
    private String deviceStatus(int status){switch(status){case WifiP2pDevice.CONNECTED:return"Connected";case WifiP2pDevice.INVITED:return"Connecting…";case WifiP2pDevice.AVAILABLE:return"Ready";case WifiP2pDevice.FAILED:return"Unavailable";case WifiP2pDevice.UNAVAILABLE:return"Busy";default:return"Nearby";}}
    private String firstLetter(String value){return value==null||value.isEmpty()?"?":value.substring(0,1).toUpperCase(Locale.US);}
    private String formatBytes(long b){if(b>=1024L*1024*1024)return String.format(Locale.US,"%.2f GB",b/(1024.0*1024*1024));if(b>=1024L*1024)return String.format(Locale.US,"%.2f MB",b/(1024.0*1024));if(b>=1024)return String.format(Locale.US,"%.1f KB",b/1024.0);return b+" B";}

    private LinearLayout shell(ScrollView scroll){LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(22),dp(20),dp(28));root.setBackground(gradient(Color.rgb(5,17,38),Color.rgb(16,48,84),0));scroll.addView(root);return root;}
    private void addBackHeader(LinearLayout root,String title,String subtitle){Button back=smallButton("← Back");back.setOnClickListener(v->navigateBack());root.addView(back,new LinearLayout.LayoutParams(dp(96),dp(44)));TextView t=text(title,27,Color.WHITE,true);if(currentScreen==SCREEN_TRANSFER)t.setTag("transfer_screen_title");t.setPadding(0,dp(18),0,dp(3));root.addView(t);TextView s=text(subtitle,13,Color.rgb(162,194,219),false);s.setPadding(0,0,0,dp(14));root.addView(s);}

    private void navigateBack(){if(currentScreen==SCREEN_GALLERY){if(galleryReturnScreen==SCREEN_SEND)showSendSelection();else showHome();}else if(currentScreen==SCREEN_DISCOVERY)showSendSelection();else showHome();}
    private Button category(String icon,String label,int color,View.OnClickListener listener){Button b=new Button(this);b.setAllCaps(false);b.setText(icon+"\n"+UiText.get(this,label));b.setTextColor(Color.WHITE);b.setTextSize(14);b.setTypeface(Typeface.DEFAULT_BOLD);b.setBackground(gradient(color,darken(color),18));b.setOnClickListener(listener);return b;}
    private LinearLayout categoryRow(Button a,Button b,Button c){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.addView(a,new LinearLayout.LayoutParams(0,dp(106),1));LinearLayout.LayoutParams p2=new LinearLayout.LayoutParams(0,dp(106),1);p2.setMargins(dp(8),0,0,0);row.addView(b,p2);LinearLayout.LayoutParams p3=new LinearLayout.LayoutParams(0,dp(106),1);p3.setMargins(dp(8),0,0,0);row.addView(c,p3);return row;}
    private Button bigAction(String icon,String title,String sub,int top,int bottom){Button b=new Button(this);b.setAllCaps(false);b.setText(icon+"\n"+UiText.get(this,title)+"\n"+UiText.get(this,sub));b.setTextColor(Color.WHITE);b.setTextSize(15);b.setTypeface(Typeface.DEFAULT_BOLD);b.setBackground(gradient(top,bottom,22));return b;}
    private Button primary(String label){Button b=new Button(this);b.setAllCaps(false);b.setText(UiText.get(this,label));b.setTextColor(Color.WHITE);b.setTextSize(14);b.setTypeface(Typeface.DEFAULT_BOLD);b.setBackground(gradient(Color.rgb(31,151,255),Color.rgb(52,88,226),16));return b;}
    private Button secondaryButton(String label){Button b=new Button(this);b.setAllCaps(false);b.setText(UiText.get(this,label));b.setTextColor(Color.WHITE);b.setTextSize(13);b.setBackground(round(Color.rgb(24,52,78),14));return b;}
    private Button smallButton(String label){Button b=secondaryButton(label);b.setTextSize(12);return b;}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(16),dp(16),dp(16),dp(16));GradientDrawable g=round(Color.rgb(13,33,56),18);g.setStroke(dp(1),Color.rgb(37,68,96));l.setBackground(g);return l;}
    private TextView text(String value,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(UiText.get(this,value));t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private GradientDrawable gradient(int top,int bottom,int radius){GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{top,bottom});g.setCornerRadius(dp(radius));return g;}
    private int darken(int color){return Color.rgb((int)(Color.red(color)*.66),(int)(Color.green(color)*.66),(int)(Color.blue(color)*.66));}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void showMessage(String title,String message){runOnUiThread(()->new AlertDialog.Builder(this).setTitle(UiText.get(this,title)).setMessage(UiText.get(this,message)).setPositiveButton(R.string.ok,null).show());}

    @Override protected void onResume(){super.onResume();IntentFilter p2p=new IntentFilter();p2p.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);p2p.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);p2p.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);p2p.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);ContextCompat.registerReceiver(this,p2pReceiver,p2p,ContextCompat.RECEIVER_NOT_EXPORTED);IntentFilter transfer=new IntentFilter(TransferService.ACTION_EVENT);ContextCompat.registerReceiver(this,transferReceiver,transfer,ContextCompat.RECEIVER_NOT_EXPORTED);IntentFilter browser=new IntentFilter(BrowserReceiveService.ACTION_EVENT);ContextCompat.registerReceiver(this,browserReceiver,browser,ContextCompat.RECEIVER_NOT_EXPORTED);}
    @Override protected void onPause(){super.onPause();discoveryHandler.removeCallbacks(discoveryRetry);discoveryHandler.removeCallbacks(p2pConnectTimeout);pendingP2pDevice=null;stopLanDiscovery();try{unregisterReceiver(p2pReceiver);}catch(Exception ignored){}try{unregisterReceiver(transferReceiver);}catch(Exception ignored){}try{unregisterReceiver(browserReceiver);}catch(Exception ignored){}}
    @Override protected void onDestroy(){if(lanDiscovery!=null)lanDiscovery.close();super.onDestroy();}
    @Override public void onBackPressed(){if(currentScreen==SCREEN_HOME)super.onBackPressed();else navigateBack();}
}
