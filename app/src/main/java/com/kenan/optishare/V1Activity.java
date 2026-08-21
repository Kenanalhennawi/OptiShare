package com.kenan.optishare;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class V1Activity extends ComponentActivity implements
        WifiP2pManager.PeerListListener, WifiP2pManager.ConnectionInfoListener {

    private static final int REQ_NEARBY = 1001;
    private static final int PORT = 8988;
    private static final int MAGIC_BATCH = 0x4F503130;
    private static final int MODE_HOME = 0;
    private static final int MODE_SEND = 1;
    private static final int MODE_RECEIVE = 2;

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final List<WifiP2pDevice> peers = new ArrayList<>();
    private final List<Uri> selectedFiles = new ArrayList<>();
    private final Handler handler = new Handler();

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private WifiP2pDevice thisDevice;
    private int mode = MODE_HOME;
    private String sessionToken;
    private String qrTargetAddress;
    private String qrTargetName;

    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private volatile boolean socketStarting;
    private volatile boolean receiveLoopRunning;

    private LinearLayout screen;
    private LinearLayout peerList;
    private TextView headerStatus;
    private TextView headerDetail;
    private TextView transferTitle;
    private TextView transferDetail;
    private TextView receivedLocation;
    private View statusDot;
    private ProgressBar progress;

    private final Runnable autoDiscovery = new Runnable() {
        @Override public void run() {
            if (mode != MODE_SEND) return;
            discoverPeers(false);
            handler.postDelayed(this, 2800);
        }
    };

    private final ActivityResultLauncher<String[]> multiPicker =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris == null || uris.isEmpty()) return;
                selectedFiles.clear();
                selectedFiles.addAll(uris);
                mode = MODE_SEND;
                showSendScreen();
                startAutoDiscovery();
            });

    private final ActivityResultLauncher<ScanOptions> qrScanner =
            registerForActivityResult(new ScanContract(), result -> {
                if (result == null || result.getContents() == null) return;
                handlePairQr(result.getContents());
            });

    private final BroadcastReceiver p2pReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                thisDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if (mode == MODE_RECEIVE) updateReceiverQr();
                return;
            }
            if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                if (canUseP2p()) {
                    try { manager.requestPeers(channel, V1Activity.this); }
                    catch (SecurityException e) { showPermissionProblem(e); }
                }
                return;
            }
            if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if (networkInfo != null && networkInfo.isConnected()) {
                    setConnectionState("Connected", "Opening private transfer channel…", 2);
                    try { manager.requestConnectionInfo(channel, V1Activity.this); }
                    catch (SecurityException e) { showPermissionProblem(e); }
                } else if (mode != MODE_HOME) {
                    closeSocket();
                    if (mode == MODE_RECEIVE) setConnectionState("Ready to receive", "Waiting for a sender nearby", 1);
                    else setConnectionState("Searching", "Looking for a receiving phone", 1);
                }
                return;
            }
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    setConnectionState("Wi‑Fi Direct is off", "Turn on Wi‑Fi to continue", 3);
                }
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        manager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        if (manager != null) {
            channel = manager.initialize(this, getMainLooper(), () ->
                    setConnectionState("Connection service restarted", "Reopen OptiShare if discovery stops", 3));
        }
        buildShell();
        requestPermissionsIfNeeded();
        showHome();
    }

    private void buildShell() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(24));
        root.setBackground(makeGradient(Color.rgb(5, 13, 28), Color.rgb(10, 34, 61), 0));
        scroll.addView(root);

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = text("O", 22, Color.WHITE, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(round(Color.rgb(40, 168, 255), 18));
        brandRow.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout brandText = new LinearLayout(this);
        brandText.setOrientation(LinearLayout.VERTICAL);
        brandText.setPadding(dp(12), 0, 0, 0);
        brandText.addView(text("OptiShare", 27, Color.WHITE, true));
        brandText.addView(text("Fast local sharing", 12, Color.rgb(149, 187, 215), false));
        brandRow.addView(brandText, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(brandRow);

        LinearLayout connection = card(Color.rgb(15, 34, 55));
        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        LinearLayout.LayoutParams dot = new LinearLayout.LayoutParams(dp(11), dp(11));
        dot.setMargins(0, 0, dp(10), 0);
        stateRow.addView(statusDot, dot);
        LinearLayout stateText = new LinearLayout(this);
        stateText.setOrientation(LinearLayout.VERTICAL);
        headerStatus = text("Ready", 16, Color.WHITE, true);
        headerDetail = text("Choose Send or Receive", 12, Color.rgb(150, 176, 199), false);
        stateText.addView(headerStatus);
        stateText.addView(headerDetail);
        stateRow.addView(stateText, new LinearLayout.LayoutParams(0, -2, 1));
        connection.addView(stateRow);
        LinearLayout.LayoutParams connParams = new LinearLayout.LayoutParams(-1, -2);
        connParams.setMargins(0, dp(18), 0, 0);
        root.addView(connection, connParams);

        screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(0, dp(16), 0, 0);
        root.addView(screen, sp);

        LinearLayout transferCard = card(Color.rgb(12, 27, 45));
        transferTitle = text("No active transfer", 15, Color.WHITE, true);
        transferDetail = text("Transfer speed and progress will appear here.", 12, Color.rgb(141, 166, 190), false);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        transferCard.addView(transferTitle);
        transferCard.addView(transferDetail);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(7));
        progressParams.setMargins(0, dp(10), 0, 0);
        transferCard.addView(progress, progressParams);
        LinearLayout.LayoutParams transferParams = new LinearLayout.LayoutParams(-1, -2);
        transferParams.setMargins(0, dp(16), 0, 0);
        root.addView(transferCard, transferParams);

        receivedLocation = text("Received files: Internal storage/Download/OptiShare", 12, Color.rgb(103, 202, 255), false);
        receivedLocation.setPadding(0, dp(14), 0, 0);
        root.addView(receivedLocation);
        TextView footer = text("No Internet required  •  Designed & developed by Kenan Alhennawi", 11, Color.rgb(107, 150, 181), false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(20), 0, 0);
        root.addView(footer);
        setContentView(scroll);
    }

    private void showHome() {
        stopAutoDiscovery();
        mode = MODE_HOME;
        selectedFiles.clear();
        qrTargetAddress = null;
        qrTargetName = null;
        sessionToken = null;
        closeSocket();
        safeRemoveGroup();
        screen.removeAllViews();

        screen.addView(text("Share anything nearby", 25, Color.WHITE, true));
        TextView subtitle = text("A simple flow: choose files, choose a device, send.", 13, Color.rgb(154, 181, 205), false);
        subtitle.setPadding(0, dp(4), 0, dp(16));
        screen.addView(subtitle);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button send = actionButton("↑", "Send", "Multiple files", Color.rgb(48, 164, 255), Color.rgb(23, 91, 154));
        send.setOnClickListener(v -> multiPicker.launch(new String[]{"*/*"}));
        Button receive = actionButton("↓", "Receive", "From nearby", Color.rgb(94, 208, 147), Color.rgb(32, 114, 72));
        receive.setOnClickListener(v -> startReceiveMode());
        actions.addView(send, new LinearLayout.LayoutParams(0, dp(152), 1));
        LinearLayout.LayoutParams receiveLp = new LinearLayout.LayoutParams(0, dp(152), 1);
        receiveLp.setMargins(dp(10), 0, 0, 0);
        actions.addView(receive, receiveLp);
        screen.addView(actions);

        LinearLayout recent = card(Color.rgb(13, 31, 50));
        recent.addView(text("Received files", 15, Color.WHITE, true));
        recent.addView(text("Saved to Download/OptiShare and visible in your normal Files app.", 12, Color.rgb(145, 171, 194), false));
        LinearLayout buttons = new LinearLayout(this);
        Button open = secondaryButton("Open Downloads");
        open.setOnClickListener(v -> openDownloads());
        buttons.addView(open, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button info = secondaryButton("Show location");
        info.setOnClickListener(v -> showReceivedFiles());
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        infoLp.setMargins(dp(8), 0, 0, 0);
        buttons.addView(info, infoLp);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(0, dp(12), 0, 0);
        recent.addView(buttons, bp);
        LinearLayout.LayoutParams recentLp = new LinearLayout.LayoutParams(-1, -2);
        recentLp.setMargins(0, dp(12), 0, 0);
        screen.addView(recent, recentLp);

        setConnectionState("Ready", "Choose Send or Receive", 0);
        resetTransferCard();
    }

    private void showSendScreen() {
        screen.removeAllViews();
        screen.addView(text("Send", 23, Color.WHITE, true));
        long total = selectedTotalSize();
        TextView summary = text(selectedFiles.size() + " selected • " + formatBytes(total), 13, Color.rgb(151, 184, 209), false);
        summary.setPadding(0, dp(3), 0, dp(10));
        screen.addView(summary);

        LinearLayout selectionCard = card(Color.rgb(14, 34, 55));
        int shown = Math.min(5, selectedFiles.size());
        for (int i = 0; i < shown; i++) {
            Uri uri = selectedFiles.get(i);
            selectionCard.addView(fileRow(queryName(uri), querySize(uri)));
        }
        if (selectedFiles.size() > shown) selectionCard.addView(text("+ " + (selectedFiles.size() - shown) + " more files", 12, Color.rgb(110, 200, 255), true));
        screen.addView(selectionCard);

        LinearLayout searchHeader = new LinearLayout(this);
        searchHeader.setGravity(Gravity.CENTER_VERTICAL);
        searchHeader.addView(text("Nearby receivers", 16, Color.WHITE, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button qr = tinyButton("Scan QR");
        qr.setOnClickListener(v -> scanReceiverQr());
        searchHeader.addView(qr, new LinearLayout.LayoutParams(dp(108), dp(42)));
        LinearLayout.LayoutParams sh = new LinearLayout.LayoutParams(-1, -2);
        sh.setMargins(0, dp(16), 0, dp(8));
        screen.addView(searchHeader, sh);

        RadarView radar = new RadarView(this);
        screen.addView(radar, new LinearLayout.LayoutParams(-1, dp(170)));
        peerList = new LinearLayout(this);
        peerList.setOrientation(LinearLayout.VERTICAL);
        screen.addView(peerList);
        showSearchingPlaceholder();

        Button addMore = secondaryButton("Choose files again");
        addMore.setOnClickListener(v -> multiPicker.launch(new String[]{"*/*"}));
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-1, dp(48));
        addLp.setMargins(0, dp(12), 0, 0);
        screen.addView(addMore, addLp);
        setConnectionState("Searching", "Looking for receiving phones nearby", 1);
    }

    private void startReceiveMode() {
        if (!prerequisitesReady(true)) return;
        stopAutoDiscovery();
        mode = MODE_RECEIVE;
        sessionToken = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.US);
        screen.removeAllViews();
        TextView title = text("Ready to receive", 23, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        screen.addView(title);
        TextView subtitle = text("Keep this screen open. Senders can find you automatically or scan your QR.", 13, Color.rgb(151, 184, 209), false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(dp(16), dp(4), dp(16), dp(14));
        screen.addView(subtitle);
        screen.addView(new RadarView(this), new LinearLayout.LayoutParams(-1, dp(150)));
        TextView waiting = text("Creating receiving session…", 14, Color.rgb(103, 202, 255), true);
        waiting.setGravity(Gravity.CENTER);
        waiting.setTag("receiver_status");
        screen.addView(waiting);
        Button cancel = secondaryButton("Stop receiving");
        cancel.setOnClickListener(v -> showHome());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(48));
        cp.setMargins(0, dp(14), 0, 0);
        screen.addView(cancel, cp);
        setConnectionState("Starting receiver", "Preparing direct connection", 1);

        try {
            manager.createGroup(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    setConnectionState("Ready to receive", "Waiting for sender", 1);
                    try { manager.requestDeviceInfo(channel, device -> { thisDevice = device; updateReceiverQr(); }); }
                    catch (SecurityException e) { showPermissionProblem(e); }
                }
                @Override public void onFailure(int reason) { handleP2pFailure("Could not start receiver", reason); }
            });
        } catch (SecurityException e) { showPermissionProblem(e); }
    }

    private void updateReceiverQr() {
        if (mode != MODE_RECEIVE || thisDevice == null || thisDevice.deviceAddress == null) return;
        runOnUiThread(() -> {
            TextView status = screen.findViewWithTag("receiver_status");
            if (status != null) status.setText("Visible as " + deviceName(thisDevice));
            View old = screen.findViewWithTag("receiver_qr");
            if (old != null) screen.removeView(old);
            try {
                String payload = "OPTISHARE10|" + sessionToken + "|" + thisDevice.deviceAddress + "|" + deviceName(thisDevice);
                ImageView image = new ImageView(this);
                image.setTag("receiver_qr");
                image.setImageBitmap(makeQr(payload, 720));
                image.setAdjustViewBounds(true);
                image.setPadding(dp(12), dp(12), dp(12), dp(12));
                image.setBackground(round(Color.WHITE, 18));
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, dp(280));
                ip.setMargins(0, dp(12), 0, 0);
                int index = Math.max(0, screen.getChildCount() - 1);
                screen.addView(image, index, ip);
            } catch (Exception ignored) {}
        });
    }

    private void startAutoDiscovery() { stopAutoDiscovery(); handler.post(autoDiscovery); }
    private void stopAutoDiscovery() { handler.removeCallbacks(autoDiscovery); }

    private void discoverPeers(boolean userInitiated) {
        if (mode != MODE_SEND || !prerequisitesReady(userInitiated)) return;
        try {
            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    setConnectionState("Searching", "Scanning nearby receiving phones", 1);
                    try { manager.requestPeers(channel, V1Activity.this); }
                    catch (SecurityException e) { showPermissionProblem(e); }
                }
                @Override public void onFailure(int reason) {
                    if (userInitiated || reason != WifiP2pManager.BUSY) handleP2pFailure("Search failed", reason);
                }
            });
        } catch (SecurityException e) { showPermissionProblem(e); }
    }

    @Override public void onPeersAvailable(WifiP2pDeviceList list) {
        peers.clear(); peers.addAll(list.getDeviceList());
        if (mode != MODE_SEND) return;
        WifiP2pDevice qrMatch = findQrTarget();
        if (qrMatch != null) { qrTargetAddress = null; connectTo(qrMatch); return; }
        renderPeerList();
    }

    private WifiP2pDevice findQrTarget() {
        if (qrTargetAddress == null) return null;
        for (WifiP2pDevice d : peers) if (qrTargetAddress.equalsIgnoreCase(d.deviceAddress)) return d;
        if (qrTargetName != null) for (WifiP2pDevice d : peers) if (qrTargetName.equalsIgnoreCase(deviceName(d))) return d;
        return null;
    }

    private void renderPeerList() {
        runOnUiThread(() -> {
            if (peerList == null) return;
            peerList.removeAllViews();
            for (WifiP2pDevice device : peers) {
                if (device.status == WifiP2pDevice.UNAVAILABLE) continue;
                LinearLayout row = card(Color.rgb(13, 31, 50));
                LinearLayout top = new LinearLayout(this);
                top.setGravity(Gravity.CENTER_VERTICAL);
                TextView avatar = text(firstLetter(deviceName(device)), 17, Color.WHITE, true);
                avatar.setGravity(Gravity.CENTER);
                avatar.setBackground(round(Color.rgb(36, 132, 207), 16));
                top.addView(avatar, new LinearLayout.LayoutParams(dp(46), dp(46)));
                LinearLayout labels = new LinearLayout(this);
                labels.setOrientation(LinearLayout.VERTICAL);
                labels.setPadding(dp(12), 0, 0, 0);
                labels.addView(text(deviceName(device), 15, Color.WHITE, true));
                labels.addView(text(deviceStatus(device.status), 12, Color.rgb(140, 170, 195), false));
                top.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                Button connect = tinyButton("Send here");
                connect.setOnClickListener(v -> connectTo(device));
                top.addView(connect, new LinearLayout.LayoutParams(dp(110), dp(44)));
                row.addView(top);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.setMargins(0, 0, 0, dp(8));
                peerList.addView(row, rp);
            }
            if (peerList.getChildCount() == 0) showSearchingPlaceholder();
        });
    }

    private void showSearchingPlaceholder() {
        if (peerList == null) return;
        peerList.removeAllViews();
        LinearLayout placeholder = card(Color.rgb(12, 28, 46));
        TextView t = text("Searching automatically…", 14, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        placeholder.addView(t);
        TextView s = text("Open Receive on the other phone. It should appear here automatically.", 12, Color.rgb(141, 169, 193), false);
        s.setGravity(Gravity.CENTER);
        s.setPadding(dp(8), dp(4), dp(8), 0);
        placeholder.addView(s);
        peerList.addView(placeholder);
    }

    private void connectTo(WifiP2pDevice device) {
        stopAutoDiscovery();
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.groupOwnerIntent = 0;
        config.wps.setup = WpsInfo.PBC;
        setConnectionState("Connecting", deviceName(device), 1);
        try {
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { setConnectionState("Connection request sent", "Waiting for Android to establish the direct link", 1); }
                @Override public void onFailure(int reason) { handleP2pFailure("Connection failed", reason); startAutoDiscovery(); }
            });
        } catch (SecurityException e) { showPermissionProblem(e); startAutoDiscovery(); }
    }

    @Override public void onConnectionInfoAvailable(WifiP2pInfo info) {
        if (!info.groupFormed || info.groupOwnerAddress == null) return;
        setConnectionState("Direct link established", (info.isGroupOwner ? "Receiver" : "Sender") + " • opening transfer channel", 2);
        if (info.isGroupOwner) startSocketServer(); else startSocketClient(info.groupOwnerAddress.getHostAddress());
    }

    private void startSocketServer() {
        if (socketStarting || isSocketReady()) return;
        socketStarting = true;
        io.execute(() -> {
            try (ServerSocket server = new ServerSocket(PORT)) { server.setReuseAddress(true); installSocket(server.accept()); }
            catch (Exception e) { setConnectionState("Transfer channel error", safe(e), 3); }
            finally { socketStarting = false; }
        });
    }

    private void startSocketClient(String host) {
        if (socketStarting || isSocketReady()) return;
        socketStarting = true;
        io.execute(() -> {
            try {
                Thread.sleep(300);
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, PORT), 10000);
                installSocket(s);
            } catch (Exception e) { setConnectionState("Transfer channel error", safe(e), 3); }
            finally { socketStarting = false; }
        });
    }

    private synchronized void installSocket(Socket s) throws Exception {
        closeSocket();
        socket = s;
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 256 * 1024));
        output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 256 * 1024));
        setConnectionState("Connected ✓", "Private transfer channel is ready", 2);
        if (mode == MODE_SEND && !selectedFiles.isEmpty()) io.execute(this::sendBatch);
        else if (mode == MODE_RECEIVE && !receiveLoopRunning) { receiveLoopRunning = true; io.execute(this::receiveLoop); }
    }

    private void sendBatch() {
        try {
            List<FileMeta> metas = prepareMetadata(selectedFiles);
            long totalBytes = 0;
            for (FileMeta m : metas) totalBytes += m.size;
            final long batchTotal = totalBytes;
            runOnUiThread(() -> { transferTitle.setText("Preparing " + metas.size() + " files"); transferDetail.setText(formatBytes(batchTotal)); progress.setProgress(0); });
            synchronized (this) {
                output.writeInt(MAGIC_BATCH);
                output.writeInt(metas.size());
                for (FileMeta meta : metas) { output.writeUTF(meta.name); output.writeLong(meta.size); output.writeInt(meta.sha256.length); output.write(meta.sha256); }
                output.flush();
                boolean accepted = input.readBoolean();
                if (!accepted) { runOnUiThread(() -> { transferTitle.setText("Transfer declined"); transferDetail.setText("The receiving phone declined this batch."); }); return; }
                long sentAll = 0;
                long started = System.nanoTime();
                byte[] buffer = new byte[512 * 1024];
                for (int index = 0; index < selectedFiles.size(); index++) {
                    Uri uri = selectedFiles.get(index);
                    FileMeta meta = metas.get(index);
                    try (InputStream raw = getContentResolver().openInputStream(uri); BufferedInputStream in = new BufferedInputStream(raw, 512 * 1024)) {
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            output.write(buffer, 0, n);
                            sentAll += n;
                            updateProgress("Sending " + (index + 1) + "/" + metas.size(), meta.name, sentAll, batchTotal, started);
                        }
                    }
                }
                output.flush();
            }
            runOnUiThread(() -> { transferTitle.setText("Sent ✓"); transferDetail.setText(metas.size() + " files • " + formatBytes(batchTotal)); progress.setProgress(1000); });
        } catch (Exception e) { runOnUiThread(() -> { transferTitle.setText("Send failed"); transferDetail.setText(safe(e)); }); }
    }

    private void receiveLoop() {
        try {
            while (socket != null && !socket.isClosed()) {
                int magic;
                try { magic = input.readInt(); } catch (EOFException eof) { break; }
                if (magic != MAGIC_BATCH) throw new IllegalStateException("Invalid OptiShare stream");
                int count = input.readInt();
                if (count < 1 || count > 1000) throw new IllegalStateException("Invalid file count");
                List<FileMeta> metas = new ArrayList<>();
                long batchTotal = 0;
                for (int i = 0; i < count; i++) {
                    String name = sanitize(input.readUTF());
                    long size = input.readLong();
                    int hashLength = input.readInt();
                    if (size < 0 || hashLength < 16 || hashLength > 128) throw new IllegalStateException("Invalid metadata");
                    byte[] hash = new byte[hashLength]; input.readFully(hash);
                    metas.add(new FileMeta(name, size, hash)); batchTotal += size;
                }
                boolean accepted = askIncomingBatch(metas, batchTotal);
                output.writeBoolean(accepted); output.flush();
                if (!accepted) continue;
                long receivedAll = 0;
                long started = System.nanoTime();
                for (int i = 0; i < metas.size(); i++) {
                    FileMeta meta = metas.get(i);
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    SaveTarget target = openSaveTarget(meta.name);
                    byte[] buffer = new byte[512 * 1024];
                    long remaining = meta.size;
                    try (OutputStream fileOut = new BufferedOutputStream(target.output, 512 * 1024)) {
                        while (remaining > 0) {
                            int want = (int) Math.min(buffer.length, remaining);
                            int n = input.read(buffer, 0, want);
                            if (n < 0) throw new EOFException("Connection ended during transfer");
                            fileOut.write(buffer, 0, n); digest.update(buffer, 0, n); remaining -= n; receivedAll += n;
                            updateProgress("Receiving " + (i + 1) + "/" + metas.size(), meta.name, receivedAll, batchTotal, started);
                        }
                        fileOut.flush();
                    }
                    if (!Arrays.equals(meta.sha256, digest.digest())) { deleteTarget(target); throw new IllegalStateException("Verification failed for " + meta.name); }
                    finishTarget(target);
                }
                final long doneBytes = batchTotal;
                runOnUiThread(() -> { transferTitle.setText("Received ✓"); transferDetail.setText(metas.size() + " files • " + formatBytes(doneBytes) + " • Saved to Download/OptiShare"); progress.setProgress(1000); receivedLocation.setText("Received files: Internal storage/Download/OptiShare ✓"); });
            }
        } catch (Exception e) {
            if (socket != null && !socket.isClosed()) runOnUiThread(() -> { transferTitle.setText("Transfer interrupted"); transferDetail.setText(safe(e)); });
        } finally { receiveLoopRunning = false; }
    }

    private boolean askIncomingBatch(List<FileMeta> metas, long totalBytes) throws InterruptedException {
        final Object lock = new Object();
        final boolean[] answer = new boolean[]{false};
        final boolean[] finished = new boolean[]{false};
        runOnUiThread(() -> {
            StringBuilder message = new StringBuilder();
            message.append(metas.size()).append(metas.size() == 1 ? " file" : " files").append(" • ").append(formatBytes(totalBytes)).append("\n\n");
            int shown = Math.min(5, metas.size());
            for (int i = 0; i < shown; i++) message.append("• ").append(metas.get(i).name).append("\n");
            if (metas.size() > shown) message.append("• +").append(metas.size() - shown).append(" more");
            new AlertDialog.Builder(this)
                    .setTitle("Incoming files")
                    .setMessage(message.toString())
                    .setPositiveButton("Accept", (d, w) -> { synchronized (lock) { answer[0] = true; finished[0] = true; lock.notifyAll(); } })
                    .setNegativeButton("Decline", (d, w) -> { synchronized (lock) { finished[0] = true; lock.notifyAll(); } })
                    .setOnCancelListener(d -> { synchronized (lock) { finished[0] = true; lock.notifyAll(); } })
                    .show();
        });
        synchronized (lock) { while (!finished[0]) lock.wait(); }
        return answer[0];
    }

    private List<FileMeta> prepareMetadata(List<Uri> uris) throws Exception {
        List<FileMeta> result = new ArrayList<>();
        for (Uri uri : uris) {
            long size = querySize(uri); if (size < 0) size = measure(uri);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[512 * 1024];
            try (InputStream in = getContentResolver().openInputStream(uri)) { int n; while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n); }
            result.add(new FileMeta(queryName(uri), size, digest.digest()));
        }
        return result;
    }

    private void updateProgress(String phase, String filename, long done, long total, long startedNanos) {
        double seconds = Math.max(0.001, (System.nanoTime() - startedNanos) / 1_000_000_000.0);
        double mbPerSec = (done / (1024.0 * 1024.0)) / seconds;
        int p = total <= 0 ? 0 : (int) Math.min(1000, done * 1000L / total);
        runOnUiThread(() -> { transferTitle.setText(phase + " • " + filename); transferDetail.setText(String.format(Locale.US, "%s / %s  •  %.2f MB/s  •  %d%%", formatBytes(done), formatBytes(total), mbPerSec, p / 10)); progress.setProgress(p); });
    }

    private void scanReceiverQr() {
        ScanOptions options = new ScanOptions();
        options.setPrompt("Scan the receiver's OptiShare QR");
        options.setBeepEnabled(false);
        options.setOrientationLocked(false);
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        qrScanner.launch(options);
    }

    private void handlePairQr(String raw) {
        if (mode != MODE_SEND || selectedFiles.isEmpty()) { showMessage("Select files first", "Tap Send, select one or more files, then scan the receiver QR."); return; }
        if (raw == null || !raw.startsWith("OPTISHARE10|")) { showMessage("Invalid QR", "This is not an OptiShare receiver QR."); return; }
        String[] parts = raw.split("\\|", 4);
        if (parts.length < 4) { showMessage("Invalid QR", "The pairing information is incomplete."); return; }
        qrTargetAddress = parts[2]; qrTargetName = parts[3];
        setConnectionState("Receiver identified", qrTargetName + " • connecting automatically", 1);
        discoverPeers(true);
    }

    private Bitmap makeQr(String value, int size) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
        return bitmap;
    }

    private boolean prerequisitesReady(boolean showUi) {
        if (manager == null || channel == null) { if (showUi) showMessage("Wi‑Fi Direct unavailable", "This phone does not expose Android Wi‑Fi Direct to OptiShare."); return false; }
        if (!hasPermission()) { requestPermissionsIfNeeded(); return false; }
        if (Build.VERSION.SDK_INT <= 32 && !isLocationEnabled()) {
            if (showUi) new AlertDialog.Builder(this).setTitle("Turn on Location").setMessage("Android requires Location services for nearby Wi‑Fi Direct discovery on this version. No location data is sent anywhere.").setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))).setNegativeButton("Cancel", null).show();
            return false;
        }
        return true;
    }

    private boolean canUseP2p() { return manager != null && channel != null && hasPermission(); }
    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= 33) return checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    private void requestPermissionsIfNeeded() {
        if (hasPermission()) return;
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQ_NEARBY);
        else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_NEARBY);
    }
    private boolean isLocationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
        try { return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER); }
        catch (Exception e) { return false; }
    }

    private void showPermissionProblem(Throwable e) {
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("Nearby connection blocked")
                .setMessage("Android blocked Wi‑Fi Direct (" + safe(e) + ").\n\nCheck Wi‑Fi and Nearby devices permission, then retry.")
                .setPositiveButton("App settings", (d, w) -> { Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS); i.setData(Uri.parse("package:" + getPackageName())); startActivity(i); })
                .setNegativeButton("Close", null).show());
    }

    private void handleP2pFailure(String title, int reason) {
        String detail = reason == WifiP2pManager.BUSY ? "Wi‑Fi Direct is busy. Wait a moment and retry." : reason == WifiP2pManager.P2P_UNSUPPORTED ? "Wi‑Fi Direct is not supported by this device." : "Android Wi‑Fi Direct error " + reason + ". Check Wi‑Fi and nearby permissions.";
        setConnectionState(title, detail, 3);
    }

    private void setConnectionState(String title, String detail, int state) {
        runOnUiThread(() -> {
            if (headerStatus == null) return;
            headerStatus.setText(title); headerDetail.setText(detail);
            int color = state == 2 ? Color.rgb(63, 220, 143) : state == 1 ? Color.rgb(255, 193, 70) : state == 3 ? Color.rgb(255, 91, 101) : Color.rgb(117, 143, 167);
            statusDot.setBackground(round(color, 30));
        });
    }

    private void resetTransferCard() { transferTitle.setText("No active transfer"); transferDetail.setText("Transfer speed and progress will appear here."); progress.setProgress(0); }

    private LinearLayout fileRow(String name, long size) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(5), 0, dp(5));
        TextView icon = text("F", 13, Color.WHITE, true); icon.setGravity(Gravity.CENTER); icon.setBackground(round(Color.rgb(46, 128, 192), 12)); row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL); labels.setPadding(dp(10), 0, 0, 0); labels.addView(text(name, 13, Color.WHITE, true)); labels.addView(text(size >= 0 ? formatBytes(size) : "Unknown size", 11, Color.rgb(137, 164, 188), false)); row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private LinearLayout card(int color) { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(16), dp(16), dp(16), dp(16)); GradientDrawable bg = round(color, 18); bg.setStroke(dp(1), Color.rgb(28, 54, 78)); l.setBackground(bg); return l; }
    private Button actionButton(String icon, String title, String sub, int topColor, int bottomColor) { Button b = new Button(this); b.setAllCaps(false); b.setGravity(Gravity.CENTER); b.setText(icon + "\n" + title + "\n" + sub); b.setTextColor(Color.WHITE); b.setTextSize(15); b.setTypeface(Typeface.DEFAULT_BOLD); b.setBackground(makeGradient(topColor, bottomColor, 18)); return b; }
    private Button secondaryButton(String label) { Button b = new Button(this); b.setAllCaps(false); b.setText(label); b.setTextColor(Color.WHITE); b.setTextSize(13); b.setBackground(round(Color.rgb(24, 49, 71), 14)); return b; }
    private Button tinyButton(String label) { Button b = secondaryButton(label); b.setPadding(dp(8), 0, dp(8), 0); return b; }
    private TextView text(String value, int sp, int color, boolean bold) { TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private GradientDrawable round(int color, int radiusDp) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radiusDp)); return g; }
    private GradientDrawable makeGradient(int top, int bottom, int radiusDp) { GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom}); g.setCornerRadius(dp(radiusDp)); return g; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String firstLetter(String name) { return name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.US); }
    private String deviceName(WifiP2pDevice d) { return d == null || d.deviceName == null || d.deviceName.trim().isEmpty() ? "Android device" : d.deviceName.trim(); }
    private String deviceStatus(int status) { switch (status) { case WifiP2pDevice.CONNECTED: return "Connected"; case WifiP2pDevice.INVITED: return "Connecting…"; case WifiP2pDevice.AVAILABLE: return "Ready to receive"; case WifiP2pDevice.UNAVAILABLE: return "Busy"; default: return "Nearby"; } }

    private long selectedTotalSize() { long total = 0; for (Uri uri : selectedFiles) { long s = querySize(uri); if (s > 0) total += s; } return total; }
    private String queryName(Uri uri) { Cursor c = null; try { c = getContentResolver().query(uri, null, null, null, null); if (c != null && c.moveToFirst()) { int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (idx >= 0) { String n = c.getString(idx); if (n != null && !n.isEmpty()) return sanitize(n); } } } finally { if (c != null) c.close(); } return "file.bin"; }
    private long querySize(Uri uri) { Cursor c = null; try { c = getContentResolver().query(uri, null, null, null, null); if (c != null && c.moveToFirst()) { int idx = c.getColumnIndex(OpenableColumns.SIZE); if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx); } } finally { if (c != null) c.close(); } return -1; }
    private long measure(Uri uri) throws Exception { long total = 0; byte[] buffer = new byte[256 * 1024]; try (InputStream in = getContentResolver().openInputStream(uri)) { int n; while ((n = in.read(buffer)) != -1) total += n; } return total; }
    private String sanitize(String name) { return name.replace("/", "_").replace("\\", "_").replace("\u0000", "_"); }
    private String formatBytes(long bytes) { if (bytes < 1024) return bytes + " B"; if (bytes < 1024L * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0); if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0)); return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0)); }

    private SaveTarget openSaveTarget(String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues(); values.put(MediaStore.Downloads.DISPLAY_NAME, name); values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream"); values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OptiShare"); values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values); if (uri == null) throw new IllegalStateException("Could not create Downloads entry");
            OutputStream out = getContentResolver().openOutputStream(uri); if (out == null) throw new IllegalStateException("Could not open Downloads file"); return new SaveTarget(uri, null, out);
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OptiShare"); if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create Download/OptiShare"); File file = uniqueFile(dir, name); return new SaveTarget(null, file, new FileOutputStream(file));
    }
    private void finishTarget(SaveTarget target) { if (Build.VERSION.SDK_INT >= 29 && target.uri != null) { ContentValues values = new ContentValues(); values.put(MediaStore.Downloads.IS_PENDING, 0); getContentResolver().update(target.uri, values, null, null); } }
    private void deleteTarget(SaveTarget target) { try { if (target.uri != null) getContentResolver().delete(target.uri, null, null); else if (target.file != null) target.file.delete(); } catch (Exception ignored) {} }
    private File uniqueFile(File dir, String name) { File first = new File(dir, name); if (!first.exists()) return first; int dot = name.lastIndexOf('.'); String base = dot > 0 ? name.substring(0, dot) : name; String ext = dot > 0 ? name.substring(dot) : ""; for (int i = 1; i < 10000; i++) { File f = new File(dir, base + " (" + i + ")" + ext); if (!f.exists()) return f; } return new File(dir, System.currentTimeMillis() + "-" + name); }

    private void openDownloads() { try { Intent intent = new Intent(Intent.ACTION_VIEW); intent.setData(Uri.parse("content://com.android.providers.downloads.documents/root/downloads")); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent); } catch (Exception e) { Intent fallback = new Intent(Intent.ACTION_OPEN_DOCUMENT); fallback.setType("*/*"); fallback.addCategory(Intent.CATEGORY_OPENABLE); startActivity(fallback); } }
    private void showReceivedFiles() { showMessage("Received files", "Internal storage\n→ Download\n→ OptiShare\n\nFiles remain visible from your normal Files app."); }
    private void showMessage(String title, String message) { runOnUiThread(() -> new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()); }
    private String safe(Throwable e) { if (e == null) return "Unknown error"; String m = e.getMessage(); return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m; }
    private boolean isSocketReady() { Socket s = socket; return s != null && s.isConnected() && !s.isClosed() && input != null && output != null; }
    private synchronized void closeSocket() { try { if (input != null) input.close(); } catch (Exception ignored) {} try { if (output != null) output.close(); } catch (Exception ignored) {} try { if (socket != null) socket.close(); } catch (Exception ignored) {} input = null; output = null; socket = null; socketStarting = false; receiveLoopRunning = false; }
    private void safeRemoveGroup() { if (manager == null || channel == null || !hasPermission()) return; try { manager.removeGroup(channel, new WifiP2pManager.ActionListener() { @Override public void onSuccess() {} @Override public void onFailure(int reason) {} }); } catch (Exception ignored) {} }

    @Override protected void onResume() { super.onResume(); IntentFilter filter = new IntentFilter(); filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION); filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION); filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION); filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION); ContextCompat.registerReceiver(this, p2pReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED); }
    @Override protected void onPause() { super.onPause(); try { unregisterReceiver(p2pReceiver); } catch (Exception ignored) {} }
    @Override protected void onDestroy() { stopAutoDiscovery(); closeSocket(); io.shutdownNow(); super.onDestroy(); }
    @Override public void onBackPressed() { if (mode == MODE_HOME) super.onBackPressed(); else showHome(); }

    private final class RadarView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint center = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Handler anim = new Handler(getMainLooper());
        private float phase = 0f;
        private final Runnable tick = new Runnable() {
            @Override public void run() { phase += 0.03f; if (phase > 1f) phase = 0f; invalidate(); if (getWindowToken() != null) anim.postDelayed(this, 32); }
        };
        RadarView(Context context) { super(context); ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(dp(2)); ring.setColor(Color.rgb(55, 170, 245)); center.setColor(Color.rgb(77, 196, 255)); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); anim.removeCallbacks(tick); anim.post(tick); }
        @Override protected void onDetachedFromWindow() { anim.removeCallbacks(tick); super.onDetachedFromWindow(); }
        @Override protected void onDraw(Canvas canvas) { super.onDraw(canvas); float cx = getWidth()/2f, cy = getHeight()/2f, max = Math.min(getWidth(), getHeight())*0.43f; for (int i=0;i<3;i++) { float f=(phase+i/3f)%1f; ring.setAlpha((int)(190*(1f-f))); canvas.drawCircle(cx,cy,max*f,ring); } center.setAlpha(255); canvas.drawCircle(cx,cy,dp(8),center); center.setAlpha(55); canvas.drawCircle(cx,cy,dp(22),center); }
    }

    private static final class FileMeta { final String name; final long size; final byte[] sha256; FileMeta(String name, long size, byte[] sha256) { this.name = name; this.size = size; this.sha256 = sha256; } }
    private static final class SaveTarget { final Uri uri; final File file; final OutputStream output; SaveTarget(Uri uri, File file, OutputStream output) { this.uri = uri; this.file = file; this.output = output; } }
}
