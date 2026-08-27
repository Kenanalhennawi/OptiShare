package com.kenan.optishare;

import android.Manifest;
import android.annotation.SuppressLint;
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
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.os.Build;
import android.os.Bundle;
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
import com.kenan.optishare.connection.P2pConnectionCoordinator;
import com.kenan.optishare.device.DeviceIdentity;
import com.kenan.optishare.transfer.TransferService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Consumer-facing OptiShare 3 send/receive flow. */
public final class V3TransferActivity extends ComponentActivity implements P2pConnectionCoordinator.Listener {
    public static final String EXTRA_MODE = "mode";
    public static final String MODE_SEND = "send";
    public static final String MODE_RECEIVE = "receive";

    private static final String STATE_MODE = "mode";
    private static final String STATE_SELECTED = "selected";
    private static final String STATE_TRANSFER_STARTED = "transfer_started";
    private static final String STATE_PEER = "peer";
    private static final int REQ_NEARBY = 3201;
    private static final int REQ_WRITE = 3202;
    private static final int REQ_NOTIFICATIONS = 3203;

    private final ArrayList<Uri> selected = new ArrayList<>();
    private final ArrayList<WifiP2pDevice> peers = new ArrayList<>();

    private String mode;
    private P2pConnectionCoordinator coordinator;
    private DeviceIdentity identity;
    private boolean transferStarted;
    private String connectedPeer = "Nearby device";

    private TextView statusTitle;
    private TextView statusDetail;
    private View statusDot;
    private LinearLayout contentArea;
    private LinearLayout peerList;
    private ProgressBar progress;
    private TextView progressTitle;
    private TextView progressDetail;
    private ImageView receiverQr;

    private final ActivityResultLauncher<Intent> mediaPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                ArrayList<String> raw = result.getData().getStringArrayListExtra(MediaPickerActivity.EXTRA_SELECTED);
                mergeRawUris(raw);
                renderSelection();
            });

    private final ActivityResultLauncher<Intent> appPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                ArrayList<String> raw = result.getData().getStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED);
                mergeRawUris(raw);
                renderSelection();
            });

    private final ActivityResultLauncher<Intent> externalPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Intent data = result.getData();
                int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        Uri uri = data.getClipData().getItemAt(i).getUri();
                        persist(uri, flags);
                        addUnique(uri);
                    }
                } else if (data.getData() != null) {
                    persist(data.getData(), flags);
                    addUnique(data.getData());
                }
                renderSelection();
            });

    private final ActivityResultLauncher<ScanOptions> qrScanner =
            registerForActivityResult(new ScanContract(), result -> {
                if (result == null || result.getContents() == null) return;
                String raw = result.getContents();
                if (!raw.startsWith("OPTISHARE3|")) {
                    showMessage("Invalid pairing code", "This QR was not created by an OptiShare 3 receiver.");
                    return;
                }
                String[] parts = raw.split("\\|", 3);
                if (parts.length < 3) {
                    showMessage("Invalid pairing code", "The QR pairing information is incomplete.");
                    return;
                }
                setStatus("Receiver identified", "Finding " + parts[2] + " and connecting automatically…", 1);
                coordinator.connectWhenFound(parts[1], parts[2]);
            });

    private final BroadcastReceiver transferReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String event = intent.getStringExtra(TransferService.EXTRA_EVENT);
            String message = intent.getStringExtra(TransferService.EXTRA_MESSAGE);
            int value = intent.getIntExtra(TransferService.EXTRA_PROGRESS, 0);
            if (event == null) return;

            if (!transferStarted && ("security_confirm".equals(event) || "incoming".equals(event) || "progress".equals(event))) {
                transferStarted = true;
                renderTransfer();
            }

            switch (event) {
                case "security_confirm": updateTransfer("Verify security code", message, -1); break;
                case "security": updateTransfer("Secure session verified ✓", message, -1); break;
                case "incoming": updateTransfer("Incoming files", message, 0); break;
                case "progress": updateTransfer(MODE_RECEIVE.equals(mode) ? "Receiving…" : "Sending…", message, value); break;
                case "reconnecting":
                    setStatus("Reconnecting automatically", "Confirmed progress is safe. OptiShare is restoring the link…", 1);
                    updateTransfer("Connection interrupted", message, -1);
                    break;
                case "file_done": updateTransfer("File verified ✓", message, -1); break;
                case "completed":
                    setStatus("Transfer complete ✓", MODE_RECEIVE.equals(mode) ? "Files are ready in your Received Library" : "All selected files were sent", 2);
                    updateTransfer("Completed ✓", message, 100);
                    if (MODE_RECEIVE.equals(mode)) addReceivedButton();
                    break;
                case "declined": setStatus("Transfer declined", message == null ? "The request was not accepted" : message, 3); break;
                case "error":
                    setStatus("Transfer paused", message == null ? "The transfer could not continue" : message, 3);
                    updateTransfer("Needs attention", message, -1);
                    break;
                default: break;
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        identity = new DeviceIdentity(this);
        coordinator = new P2pConnectionCoordinator(this, this);
        if (state != null) {
            mode = state.getString(STATE_MODE, MODE_SEND);
            ArrayList<String> raw = state.getStringArrayList(STATE_SELECTED);
            mergeRawUris(raw);
            transferStarted = state.getBoolean(STATE_TRANSFER_STARTED, false);
            connectedPeer = state.getString(STATE_PEER, "Nearby device");
        } else {
            mode = getIntent().getStringExtra(EXTRA_MODE);
            if (!MODE_RECEIVE.equals(mode)) mode = MODE_SEND;
        }
        requestNotificationPermission();
        buildShell();
        if (transferStarted) {
            renderTransfer();
            setStatus("Transfer active", "Restoring the current transfer view…", 1);
        } else if (MODE_RECEIVE.equals(mode)) startReceiveFlow();
        else renderSelection();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_MODE, mode);
        ArrayList<String> raw = new ArrayList<>();
        for (Uri uri : selected) raw.add(uri.toString());
        outState.putStringArrayList(STATE_SELECTED, raw);
        outState.putBoolean(STATE_TRANSFER_STARTED, transferStarted);
        outState.putString(STATE_PEER, connectedPeer);
    }

    private void buildShell() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(24));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Color.rgb(5, 14, 28), Color.rgb(8, 29, 51), Color.rgb(18, 19, 54)}));
        scroll.addView(root);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = secondary("‹");
        back.setTextSize(22);
        back.setOnClickListener(v -> handleBack());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, 0, 0);
        labels.addView(text(MODE_RECEIVE.equals(mode) ? "Receive" : "Send", 24, Color.WHITE, true));
        labels.addView(text(identity.name(), 11, Color.rgb(145, 188, 219), false));
        header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);
        LinearLayout stateCard = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        row.addView(statusDot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        LinearLayout stateText = new LinearLayout(this);
        stateText.setOrientation(LinearLayout.VERTICAL);
        stateText.setPadding(dp(12), 0, 0, 0);
        statusTitle = text("Ready", 16, Color.WHITE, true);
        statusDetail = text("Preparing OptiShare…", 12, Color.rgb(148, 177, 201), false);
        stateText.addView(statusTitle);
        stateText.addView(statusDetail);
        row.addView(stateText, new LinearLayout.LayoutParams(0, -2, 1));
        stateCard.addView(row);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(0, dp(16), 0, 0);
        root.addView(stateCard, sp);
        contentArea = new LinearLayout(this);
        contentArea.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, dp(16), 0, 0);
        root.addView(contentArea, cp);
        setContentView(scroll);
        setStatus("Ready", MODE_RECEIVE.equals(mode) ? "Preparing receiver…" : "Choose what you want to send", 0);
    }

    private void renderSelection() {
        mode = MODE_SEND;
        contentArea.removeAllViews();
        setStatus("Choose content", selected.isEmpty() ? "Select one or many items" : "Your transfer batch is ready to edit", 0);
        TextView title = text("What do you want to send?", 22, Color.WHITE, true);
        contentArea.addView(title);
        TextView sub = text("Browse media and installed apps inside OptiShare, or add any document/file.", 12, Color.rgb(148, 180, 204), false);
        sub.setPadding(0, dp(4), 0, dp(14));
        contentArea.addView(sub);
        LinearLayout grid1 = new LinearLayout(this); grid1.setOrientation(LinearLayout.HORIZONTAL);
        Button photos = category("▣", "Photos", Color.rgb(29, 151, 255)); photos.setOnClickListener(v -> openMedia("image"));
        Button videos = category("▶", "Videos", Color.rgb(126, 91, 246)); videos.setOnClickListener(v -> openMedia("video"));
        grid1.addView(photos, new LinearLayout.LayoutParams(0, dp(118), 1));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, dp(118), 1); vp.setMargins(dp(10), 0, 0, 0); grid1.addView(videos, vp); contentArea.addView(grid1);
        LinearLayout grid2 = new LinearLayout(this); grid2.setOrientation(LinearLayout.HORIZONTAL);
        Button music = category("♫", "Music", Color.rgb(35, 190, 146)); music.setOnClickListener(v -> openMedia("audio"));
        Button apps = category("A", "Apps", Color.rgb(236, 108, 76)); apps.setOnClickListener(v -> appPicker.launch(new Intent(this, AppPickerActivity.class)));
        grid2.addView(music, new LinearLayout.LayoutParams(0, dp(118), 1));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, dp(118), 1); ap.setMargins(dp(10), 0, 0, 0); grid2.addView(apps, ap);
        LinearLayout.LayoutParams g2 = new LinearLayout.LayoutParams(-1, -2); g2.setMargins(0, dp(10), 0, 0); contentArea.addView(grid2, g2);
        Button files = category("▤", "Documents & files", Color.rgb(245, 158, 54)); files.setOnClickListener(v -> openExternal("*/*"));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, dp(98)); fp.setMargins(0, dp(10), 0, 0); contentArea.addView(files, fp);
        LinearLayout batch = card();
        batch.addView(text(selected.size() + " item" + (selected.size() == 1 ? "" : "s") + " • " + formatBytes(selectedBytes()), 16, Color.WHITE, true));
        if (selected.isEmpty()) batch.addView(text("Nothing selected yet", 12, Color.rgb(137, 166, 190), false));
        else {
            for (int i = 0; i < Math.min(6, selected.size()); i++) batch.addView(text("✓ " + displayName(selected.get(i)), 12, Color.rgb(219, 232, 242), false));
            if (selected.size() > 6) batch.addView(text("+ " + (selected.size() - 6) + " more", 12, Color.rgb(91, 203, 255), true));
            Button clear = secondary("Clear selection"); clear.setOnClickListener(v -> { selected.clear(); renderSelection(); });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, dp(46)); cl.setMargins(0, dp(10), 0, 0); batch.addView(clear, cl);
        }
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2); bp.setMargins(0, dp(14), 0, 0); contentArea.addView(batch, bp);
        Button continueButton = primary(selected.isEmpty() ? "Select content first" : "Find receiving device →");
        continueButton.setEnabled(!selected.isEmpty()); continueButton.setAlpha(selected.isEmpty() ? .45f : 1f); continueButton.setOnClickListener(v -> startDiscoveryFlow());
        LinearLayout.LayoutParams cont = new LinearLayout.LayoutParams(-1, dp(60)); cont.setMargins(0, dp(14), 0, 0); contentArea.addView(continueButton, cont);
    }

    private void startDiscoveryFlow() {
        if (!ensureNearbyReady()) return;
        contentArea.removeAllViews(); setStatus("Searching nearby", "Keep Receive open on the other phone. Search retries automatically.", 1);
        LinearLayout visual = card(); TextView radar = text("◎", 74, Color.rgb(89, 207, 255), true); radar.setGravity(Gravity.CENTER); visual.addView(radar);
        TextView hint = text("Looking for OptiShare receivers…", 15, Color.WHITE, true); hint.setGravity(Gravity.CENTER); visual.addView(hint); contentArea.addView(visual);
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        Button qr = secondary("Scan receiver QR"); qr.setOnClickListener(v -> scanQr()); actions.addView(qr, new LinearLayout.LayoutParams(0, dp(50), 1));
        Button retry = secondary("Restart search"); retry.setOnClickListener(v -> coordinator.startSender());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(50), 1); rp.setMargins(dp(8), 0, 0, 0); actions.addView(retry, rp);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2); actionsLp.setMargins(0, dp(10), 0, 0); contentArea.addView(actions, actionsLp);
        peerList = new LinearLayout(this); peerList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2); pl.setMargins(0, dp(12), 0, 0); contentArea.addView(peerList, pl); renderPeers(); coordinator.startSender();
    }

    private void startReceiveFlow() {
        if (!ensureLegacyWrite() || !ensureNearbyReady()) return;
        contentArea.removeAllViews(); setStatus("Preparing receiver", "OptiShare is creating a private nearby session", 1);
        LinearLayout receiveCard = card(); TextView mark = text("◉", 78, Color.rgb(61, 220, 151), true); mark.setGravity(Gravity.CENTER); receiveCard.addView(mark);
        TextView title = text("Ready for nearby transfers", 19, Color.WHITE, true); title.setGravity(Gravity.CENTER); receiveCard.addView(title);
        TextView device = text(identity.name(), 13, Color.rgb(91, 204, 255), true); device.setGravity(Gravity.CENTER); device.setTag("device_label"); receiveCard.addView(device);
        receiverQr = new ImageView(this); receiverQr.setAdjustViewBounds(true); receiverQr.setContentDescription("OptiShare receiver pairing QR code"); receiverQr.setPadding(dp(24), dp(18), dp(24), dp(10));
        receiveCard.addView(receiverQr, new LinearLayout.LayoutParams(-1, dp(300))); contentArea.addView(receiveCard);
        TextView explain = text("The sender should see this phone automatically. QR is only a fallback when Android discovery is slow.", 12, Color.rgb(147, 177, 200), false);
        explain.setGravity(Gravity.CENTER); explain.setPadding(dp(10), dp(12), dp(10), 0); contentArea.addView(explain); coordinator.startReceiver();
    }

    private void renderTransfer() {
        contentArea.removeAllViews(); LinearLayout transferCard = card();
        progressTitle = text(MODE_RECEIVE.equals(mode) ? "Receiving" : "Sending", 22, Color.WHITE, true); transferCard.addView(progressTitle);
        progressDetail = text("Preparing encrypted session…", 13, Color.rgb(150, 182, 205), false); transferCard.addView(progressDetail);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(9)); pp.setMargins(0, dp(14), 0, 0); transferCard.addView(progress, pp); contentArea.addView(transferCard);
        Button cancel = secondary("Cancel transfer");
        cancel.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Cancel transfer?").setMessage("This will clear this pending transfer session. Files already completed remain valid.").setPositiveButton("Cancel transfer", (d, w) -> cancelTransfer()).setNegativeButton("Keep transferring", null).show());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(50)); cp.setMargins(0, dp(12), 0, 0); contentArea.addView(cancel, cp);
    }

    private void cancelTransfer() { startService(new Intent(this, TransferService.class).setAction(TransferService.ACTION_STOP)); coordinator.releaseGroup(); transferStarted = false; finish(); }
    private void updateTransfer(String title, String detail, int p) { runOnUiThread(() -> { if (progressTitle != null) progressTitle.setText(title); if (progressDetail != null) progressDetail.setText(detail == null ? "" : detail); if (progress != null && p >= 0) progress.setProgress(p); }); }
    private void addReceivedButton() { runOnUiThread(() -> { Button open = primary("Open received files"); open.setOnClickListener(v -> startActivity(new Intent(this, ReceivedActivity.class))); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(58)); lp.setMargins(0, dp(12), 0, 0); contentArea.addView(open, lp); }); }

    @Override public void onState(P2pConnectionCoordinator.State state, String message, int attempt) {
        int kind = (state == P2pConnectionCoordinator.State.CONNECTED || state == P2pConnectionCoordinator.State.ADVERTISING) ? 2 : (state == P2pConnectionCoordinator.State.FAILED ? 3 : 1);
        String title;
        switch (state) { case PREPARING: title = "Preparing"; break; case ADVERTISING: title = "Ready to receive"; break; case SEARCHING: title = "Searching"; break; case FOUND: title = "Devices found"; break; case CONNECTING: title = "Connecting"; break; case NEGOTIATING: title = "Securing connection"; break; case CONNECTED: title = "Connected ✓"; break; case RETRYING: title = "Retrying automatically"; break; case FAILED: title = "Connection needs attention"; break; default: title = "OptiShare"; }
        setStatus(title, message, kind);
    }

    @Override public void onPeers(List<WifiP2pDevice> list) { peers.clear(); if (list != null) peers.addAll(list); renderPeers(); }

    @Override public void onConnected(WifiP2pInfo info, String peerName) {
        connectedPeer = peerName == null || peerName.trim().isEmpty() ? "Nearby device" : peerName;
        if (info == null || !info.groupFormed || info.groupOwnerAddress == null) return;
        transferStarted = true; renderTransfer();
        if (MODE_RECEIVE.equals(mode) && info.isGroupOwner) {
            Intent intent = new Intent(this, TransferService.class).setAction(TransferService.ACTION_START_RECEIVER).putExtra(TransferService.EXTRA_PEER_NAME, connectedPeer);
            ContextCompat.startForegroundService(this, intent);
        } else if (MODE_SEND.equals(mode) && !info.isGroupOwner) {
            ArrayList<String> raw = new ArrayList<>(); for (Uri uri : selected) raw.add(uri.toString());
            Intent intent = new Intent(this, TransferService.class).setAction(TransferService.ACTION_SEND).putExtra(TransferService.EXTRA_HOST, info.groupOwnerAddress.getHostAddress()).putExtra(TransferService.EXTRA_PEER_NAME, connectedPeer).putStringArrayListExtra(TransferService.EXTRA_URIS, raw);
            ContextCompat.startForegroundService(this, intent);
        } else setStatus("Connection role mismatch", "Retry the nearby connection. The receiving phone must own the direct session.", 3);
    }

    @Override public void onThisDevice(WifiP2pDevice device) {
        if (!MODE_RECEIVE.equals(mode) || device == null || device.deviceAddress == null) return;
        runOnUiThread(() -> { try { if (receiverQr != null) receiverQr.setImageBitmap(makeQr("OPTISHARE3|" + device.deviceAddress + "|" + identity.name(), 720)); TextView label = findViewByTag("device_label"); if (label != null) label.setText(identity.name() + " • " + safeName(device)); } catch (Exception ignored) { } });
    }

    private void renderPeers() {
        runOnUiThread(() -> {
            if (peerList == null) return; peerList.removeAllViews();
            if (peers.isEmpty()) { LinearLayout empty = card(); empty.addView(text("Searching automatically…", 14, Color.WHITE, true)); empty.addView(text("No receiver yet. Keep Receive open on the other phone.", 12, Color.rgb(141, 171, 194), false)); peerList.addView(empty); return; }
            for (WifiP2pDevice device : peers) {
                LinearLayout deviceCard = card(); LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
                TextView avatar = text(firstLetter(safeName(device)), 18, Color.WHITE, true); avatar.setGravity(Gravity.CENTER); avatar.setBackground(round(Color.rgb(38, 139, 211), 18)); row.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
                LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL); labels.setPadding(dp(12), 0, 0, 0); labels.addView(text(safeName(device), 15, Color.WHITE, true)); labels.addView(text(peerStatus(device.status), 11, Color.rgb(143, 177, 201), false)); row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                Button connect = secondary("Connect"); connect.setOnClickListener(v -> coordinator.connect(device)); row.addView(connect, new LinearLayout.LayoutParams(dp(105), dp(46))); deviceCard.addView(row);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 0, 0, dp(8)); peerList.addView(deviceCard, lp);
            }
        });
    }

    private void openMedia(String type) { Intent intent = new Intent(this, MediaPickerActivity.class); intent.putExtra(MediaPickerActivity.EXTRA_TYPE, type); ArrayList<String> initial = new ArrayList<>(); for (Uri uri : selected) initial.add(uri.toString()); intent.putStringArrayListExtra(MediaPickerActivity.EXTRA_INITIAL, initial); mediaPicker.launch(intent); }
    private void openExternal(String mime) { Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType(mime); intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); externalPicker.launch(intent); }
    private void scanQr() { ScanOptions options = new ScanOptions(); options.setPrompt("Scan the receiver's OptiShare QR"); options.setBeepEnabled(false); options.setOrientationLocked(false); options.setDesiredBarcodeFormats(ScanOptions.QR_CODE); qrScanner.launch(options); }

    private boolean ensureNearbyReady() {
        if (!coordinator.available()) { showMessage("Nearby sharing unavailable", "This device does not expose Android Wi-Fi Direct."); return false; }
        if (!hasNearbyPermission()) { requestNearbyPermission(); return false; }
        if (Build.VERSION.SDK_INT <= 32 && !locationEnabled()) { new AlertDialog.Builder(this).setTitle("Turn on Location").setMessage("Android requires Location services for Wi-Fi Direct discovery on this Android version. OptiShare does not upload your location.").setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))).setNegativeButton("Cancel", null).show(); return false; }
        return true;
    }
    private boolean hasNearbyPermission() { if (Build.VERSION.SDK_INT >= 33) return checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED; return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED; }
    private void requestNearbyPermission() { if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQ_NEARBY); else requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, REQ_NEARBY); }
    private boolean ensureLegacyWrite() { if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE); return false; } return true; }
    private boolean locationEnabled() { LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE); if (manager == null) return false; if (Build.VERSION.SDK_INT >= 28) return manager.isLocationEnabled(); try { return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER); } catch (Exception e) { return false; } }
    private void requestNotificationPermission() { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); if (requestCode == REQ_NEARBY && hasNearbyPermission()) { if (MODE_RECEIVE.equals(mode)) startReceiveFlow(); else startDiscoveryFlow(); } else if (requestCode == REQ_WRITE && ensureLegacyWrite() && MODE_RECEIVE.equals(mode)) startReceiveFlow(); }

    private void setStatus(String title, String detail, int kind) { runOnUiThread(() -> { if (statusTitle == null) return; statusTitle.setText(title); statusDetail.setText(detail == null ? "" : detail); int color = kind == 2 ? Color.rgb(63, 220, 147) : kind == 3 ? Color.rgb(255, 91, 101) : kind == 1 ? Color.rgb(255, 193, 70) : Color.rgb(116, 145, 168); statusDot.setBackground(round(color, 20)); }); }
    private void mergeRawUris(ArrayList<String> raw) { if (raw == null) return; for (String value : raw) { try { addUnique(Uri.parse(value)); } catch (Exception ignored) { } } }
    private void addUnique(Uri uri) { if (uri != null && !selected.contains(uri)) selected.add(uri); }
    private void persist(Uri uri, int flags) { try { getContentResolver().takePersistableUriPermission(uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { } }

    private String displayName(Uri uri) {
        Cursor cursor = null;
        try { cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null); if (cursor != null && cursor.moveToFirst()) { int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (index >= 0 && !cursor.isNull(index)) return cursor.getString(index); } }
        catch (Exception ignored) { } finally { if (cursor != null) cursor.close(); }
        String path = uri.getLastPathSegment(); return path == null ? "file" : path;
    }

    private long selectedBytes() {
        long total = 0;
        for (Uri uri : selected) { Cursor cursor = null; try { cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null); if (cursor != null && cursor.moveToFirst()) { int index = cursor.getColumnIndex(OpenableColumns.SIZE); if (index >= 0 && !cursor.isNull(index)) total += Math.max(0, cursor.getLong(index)); } } catch (Exception ignored) { } finally { if (cursor != null) cursor.close(); } }
        return total;
    }

    private static String safeName(WifiP2pDevice device) { return device == null || device.deviceName == null || device.deviceName.trim().isEmpty() ? "Android device" : device.deviceName.trim(); }
    private static String firstLetter(String value) { return value == null || value.isEmpty() ? "?" : value.substring(0, 1).toUpperCase(Locale.US); }
    private static String peerStatus(int status) { if (status == WifiP2pDevice.CONNECTED) return "Connected"; if (status == WifiP2pDevice.INVITED) return "Invitation sent"; if (status == WifiP2pDevice.AVAILABLE) return "Available"; if (status == WifiP2pDevice.UNAVAILABLE) return "Busy"; return "Nearby"; }
    private static String formatBytes(long bytes) { if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024)); if (bytes >= 1024L * 1024) return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024)); if (bytes >= 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0); return bytes + " B"; }

    private Bitmap makeQr(String value, int size) throws Exception { BitMatrix matrix = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size); Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565); for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE); return bitmap; }
    @SuppressWarnings("unchecked") private <T extends View> T findViewByTag(String tag) { View view = getWindow().getDecorView().findViewWithTag(tag); return (T) view; }
    private LinearLayout card() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(dp(16), dp(16), dp(16), dp(16)); GradientDrawable background = round(Color.rgb(13, 32, 52), 20); background.setStroke(dp(1), Color.rgb(31, 61, 87)); layout.setBackground(background); return layout; }
    private Button category(String icon, String label, int color) { Button button = new Button(this); button.setAllCaps(false); button.setGravity(Gravity.CENTER); button.setText(icon + "\n" + label); button.setTextColor(Color.WHITE); button.setTextSize(16); button.setTypeface(Typeface.DEFAULT_BOLD); GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{color, darken(color)}); background.setCornerRadius(dp(22)); button.setBackground(background); return button; }
    private Button primary(String label) { Button button = new Button(this); button.setAllCaps(false); button.setText(label); button.setTextColor(Color.WHITE); button.setTextSize(15); button.setTypeface(Typeface.DEFAULT_BOLD); GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Color.rgb(30, 156, 255), Color.rgb(78, 75, 230)}); background.setCornerRadius(dp(18)); button.setBackground(background); return button; }
    private Button secondary(String label) { Button button = new Button(this); button.setAllCaps(false); button.setText(label); button.setTextColor(Color.WHITE); button.setTextSize(13); button.setBackground(round(Color.rgb(23, 49, 72), 16)); return button; }
    private TextView text(String value, int sp, int color, boolean bold) { TextView text = new TextView(this); text.setText(value); text.setTextSize(sp); text.setTextColor(color); if (bold) text.setTypeface(Typeface.DEFAULT_BOLD); return text; }
    private GradientDrawable round(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    private int darken(int color) { return Color.rgb((int) (Color.red(color) * .62), (int) (Color.green(color) * .62), (int) (Color.blue(color) * .62)); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void showMessage(String title, String message) { new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show(); }

    private void handleBack() {
        if (transferStarted) {
            new AlertDialog.Builder(this)
                    .setTitle("Transfer is active")
                    .setMessage("The transfer continues in the background. You can return to the home screen without cancelling it.")
                    .setPositiveButton("Go home", (d, w) -> finish())
                    .setNegativeButton("Stay", null)
                    .show();
        } else {
            coordinator.stop();
            coordinator.releaseGroup();
            finish();
        }
    }

    @SuppressLint("MissingSuperCall")
    @Override public void onBackPressed() {
        handleBack();
    }

    @Override protected void onResume() { super.onResume(); IntentFilter filter = new IntentFilter(TransferService.ACTION_EVENT); ContextCompat.registerReceiver(this, transferReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED); }
    @Override protected void onPause() { super.onPause(); try { unregisterReceiver(transferReceiver); } catch (Exception ignored) { } }
    @Override protected void onDestroy() { if (coordinator != null) coordinator.stop(); super.onDestroy(); }
}
