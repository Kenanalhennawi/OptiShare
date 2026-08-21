package com.kenan.optishare;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
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
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class V2Activity extends ComponentActivity implements
        WifiP2pManager.PeerListListener,
        WifiP2pManager.ConnectionInfoListener {

    private static final int REQ_MEDIA = 2101;
    private static final int REQ_NEARBY = 2102;
    private static final int SCREEN_HOME = 0;
    private static final int SCREEN_GALLERY = 1;
    private static final int SCREEN_SEND = 2;
    private static final int SCREEN_DISCOVERY = 3;
    private static final int SCREEN_RECEIVE = 4;

    private final List<Uri> selected = new ArrayList<>();
    private final List<WifiP2pDevice> peers = new ArrayList<>();

    private int currentScreen = SCREEN_HOME;
    private String pendingGalleryType;
    private String currentGalleryType;

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private WifiP2pDevice thisDevice;

    private LinearLayout peerList;
    private TextView discoveryState;
    private TextView connectionPill;

    private final ActivityResultLauncher<Intent> externalPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Intent data = result.getData();
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        Uri uri = data.getClipData().getItemAt(i).getUri();
                        if (!selected.contains(uri)) selected.add(uri);
                    }
                } else if (data.getData() != null) {
                    Uri uri = data.getData();
                    if (!selected.contains(uri)) selected.add(uri);
                }
                showSendSelection();
            });

    private final BroadcastReceiver p2pReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                thisDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
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
                } else if (currentScreen == SCREEN_DISCOVERY || currentScreen == SCREEN_RECEIVE) {
                    setConnectionUi("NOT CONNECTED", Color.rgb(255, 188, 70));
                }
                return;
            }

            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    setDiscoveryText("Wi‑Fi Direct is off. Turn Wi‑Fi on to search nearby devices.");
                }
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        manager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        if (manager != null) {
            channel = manager.initialize(this, getMainLooper(), () ->
                    setDiscoveryText("Wi‑Fi Direct service restarted. Try Search again."));
        }
        showHome();
    }

    private void showHome() {
        currentScreen = SCREEN_HOME;
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = shell(scroll);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = text("O", 24, Color.WHITE, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(gradient(Color.rgb(28, 165, 255), Color.rgb(70, 87, 245), 24));
        top.addView(logo, new LinearLayout.LayoutParams(dp(50), dp(50)));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(12), 0, 0, 0);
        titleBox.addView(text("OptiShare 2", 28, Color.WHITE, true));
        titleBox.addView(text("Share smarter. Stay local.", 13, Color.rgb(160, 199, 228), false));
        top.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top);

        TextView hero = text("Everything you want to share,\ninside one app.", 28, Color.WHITE, true);
        hero.setPadding(0, dp(28), 0, dp(8));
        root.addView(hero);
        root.addView(text("Browse photos and videos inside OptiShare, select many items, then discover the receiving phone nearby.", 14, Color.rgb(176, 207, 230), false));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button send = bigAction("↑", "SEND", "Choose content", Color.rgb(35, 146, 255), Color.rgb(52, 83, 220));
        send.setOnClickListener(v -> showSendSelection());
        Button receive = bigAction("↓", "RECEIVE", "Make this phone visible", Color.rgb(49, 205, 145), Color.rgb(18, 122, 92));
        receive.setOnClickListener(v -> showReceive());
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(0, dp(154), 1);
        LinearLayout.LayoutParams receiveLp = new LinearLayout.LayoutParams(0, dp(154), 1);
        receiveLp.setMargins(dp(10), 0, 0, 0);
        actions.addView(send, sendLp);
        actions.addView(receive, receiveLp);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
        actionsLp.setMargins(0, dp(22), 0, 0);
        root.addView(actions, actionsLp);

        TextView browse = text("Browse inside OptiShare", 18, Color.WHITE, true);
        browse.setPadding(0, dp(24), 0, dp(10));
        root.addView(browse);

        root.addView(categoryRow(
                category("▣", "Photos", Color.rgb(190, 83, 255), v -> openInternalGallery("image")),
                category("▶", "Videos", Color.rgb(255, 78, 110), v -> openInternalGallery("video")),
                category("♫", "Music", Color.rgb(255, 169, 50), v -> openExternal("audio/*"))));

        LinearLayout row2 = categoryRow(
                category("A", "Apps", Color.rgb(53, 203, 165), v -> openExternal("application/vnd.android.package-archive")),
                category("≡", "Documents", Color.rgb(55, 143, 255), v -> openExternal("application/*")),
                category("…", "Other", Color.rgb(122, 140, 166), v -> openExternal("*/*")));
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(-1, -2);
        row2Lp.setMargins(0, dp(10), 0, 0);
        root.addView(row2, row2Lp);

        LinearLayout info = card();
        info.addView(text("How connection works", 15, Color.WHITE, true));
        info.addView(text("1. Receiver taps RECEIVE\n2. Sender selects files\n3. Sender sees the receiver in Nearby devices\n4. Tap the device → CONNECTED ✓", 13, Color.rgb(160, 188, 211), false));
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(-1, -2);
        infoLp.setMargins(0, dp(22), 0, 0);
        root.addView(info, infoLp);

        TextView footer = text("Received content will be organized under Download/OptiShare by type.\nDesigned & developed by Kenan Alhennawi", 11, Color.rgb(120, 167, 199), false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(18), 0, 0);
        root.addView(footer);
        setContentView(scroll);
    }

    private void showSendSelection() {
        currentScreen = SCREEN_SEND;
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = shell(scroll);
        addBackHeader(root, "Send", "Select content from inside OptiShare");

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        Button photos = smallButton("Photos");
        photos.setOnClickListener(v -> openInternalGallery("image"));
        Button videos = smallButton("Videos");
        videos.setOnClickListener(v -> openInternalGallery("video"));
        Button files = smallButton("Files");
        files.setOnClickListener(v -> openExternal("*/*"));
        tabs.addView(photos, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        tabLp.setMargins(dp(8), 0, 0, 0);
        tabs.addView(videos, tabLp);
        LinearLayout.LayoutParams tabLp2 = new LinearLayout.LayoutParams(0, dp(46), 1);
        tabLp2.setMargins(dp(8), 0, 0, 0);
        tabs.addView(files, tabLp2);
        root.addView(tabs);

        TextView count = text(selected.size() + " selected", 18, Color.WHITE, true);
        count.setPadding(0, dp(18), 0, dp(8));
        root.addView(count);

        LinearLayout selection = card();
        if (selected.isEmpty()) {
            selection.addView(text("Nothing selected yet. Open Photos or Videos above and tap thumbnails to select them.", 13, Color.rgb(156, 181, 202), false));
        } else {
            int show = Math.min(selected.size(), 10);
            for (int i = 0; i < show; i++) {
                selection.addView(text("✓ " + displayName(selected.get(i)), 13, Color.WHITE, false));
            }
            if (selected.size() > show) selection.addView(text("+ " + (selected.size() - show) + " more", 12, Color.rgb(82, 196, 255), true));
        }
        root.addView(selection);

        Button find = primary(selected.isEmpty() ? "Select files first" : "Find receiving device  →");
        find.setEnabled(!selected.isEmpty());
        find.setAlpha(selected.isEmpty() ? 0.45f : 1f);
        find.setOnClickListener(v -> showDiscovery());
        LinearLayout.LayoutParams findLp = new LinearLayout.LayoutParams(-1, dp(56));
        findLp.setMargins(0, dp(14), 0, 0);
        root.addView(find, findLp);
        setContentView(scroll);
    }

    private void openInternalGallery(String type) {
        pendingGalleryType = type;
        if (!hasMediaPermission(type)) {
            requestMediaPermission(type);
            return;
        }
        showMediaGallery(type);
    }

    private void showMediaGallery(String type) {
        currentScreen = SCREEN_GALLERY;
        currentGalleryType = type;
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = shell(scroll);
        addBackHeader(root, "image".equals(type) ? "Photos" : "Videos", "Tap items to select multiple");

        TextView selectedCount = text(selected.size() + " selected", 14, Color.rgb(92, 202, 255), true);
        selectedCount.setGravity(Gravity.CENTER);
        root.addView(selectedCount);

        List<MediaItem> media = loadMedia(type);
        if (media.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("No " + ("image".equals(type) ? "photos" : "videos") + " found on this phone.", 14, Color.WHITE, true));
            empty.addView(text("If you denied media permission, allow it in Android Settings and reopen this screen.", 12, Color.rgb(150, 175, 197), false));
            root.addView(empty);
        } else {
            GridLayout grid = new GridLayout(this);
            grid.setColumnCount(3);
            int cellWidth = (getResources().getDisplayMetrics().widthPixels - dp(52)) / 3;

            for (MediaItem item : media) {
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(dp(3), dp(3), dp(3), dp(5));
                updateMediaCellBackground(cell, selected.contains(item.uri));

                ImageView image = new ImageView(this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setImageURI(item.uri);
                cell.addView(image, new LinearLayout.LayoutParams(cellWidth - dp(8), cellWidth - dp(8)));

                TextView label = text(shortName(item.name), 10, Color.WHITE, false);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(1);
                cell.addView(label, new LinearLayout.LayoutParams(cellWidth - dp(8), dp(28)));

                cell.setOnClickListener(v -> {
                    if (selected.contains(item.uri)) selected.remove(item.uri);
                    else selected.add(item.uri);
                    updateMediaCellBackground(cell, selected.contains(item.uri));
                    selectedCount.setText(selected.size() + " selected");
                });

                grid.addView(cell, new GridLayout.LayoutParams(new GridLayout.Spec(GridLayout.UNDEFINED), new GridLayout.Spec(GridLayout.UNDEFINED)) {{
                    width = cellWidth;
                    height = cellWidth + dp(34);
                }});
            }
            root.addView(grid);
        }

        Button done = primary("Done • " + selected.size() + " selected");
        done.setOnClickListener(v -> showSendSelection());
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(-1, dp(56));
        doneLp.setMargins(0, dp(14), 0, 0);
        root.addView(done, doneLp);
        setContentView(scroll);
    }

    private void showDiscovery() {
        currentScreen = SCREEN_DISCOVERY;
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = shell(scroll);
        addBackHeader(root, "Nearby devices", selected.size() + " items ready to send");

        connectionPill = text("NOT CONNECTED", 13, Color.rgb(255, 190, 70), true);
        connectionPill.setGravity(Gravity.CENTER);
        connectionPill.setPadding(dp(12), dp(10), dp(12), dp(10));
        connectionPill.setBackground(round(Color.rgb(54, 43, 25), 14));
        root.addView(connectionPill);

        LinearLayout radar = card();
        TextView icon = text("◎", 72, Color.rgb(80, 198, 255), true);
        icon.setGravity(Gravity.CENTER);
        radar.addView(icon);
        discoveryState = text("Searching for receiving phones…", 15, Color.WHITE, true);
        discoveryState.setGravity(Gravity.CENTER);
        radar.addView(discoveryState);
        TextView hint = text("On the other phone, open OptiShare and tap RECEIVE.", 12, Color.rgb(150, 179, 202), false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(6), 0, 0);
        radar.addView(hint);
        LinearLayout.LayoutParams radarLp = new LinearLayout.LayoutParams(-1, -2);
        radarLp.setMargins(0, dp(12), 0, 0);
        root.addView(radar, radarLp);

        peerList = new LinearLayout(this);
        peerList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams peersLp = new LinearLayout.LayoutParams(-1, -2);
        peersLp.setMargins(0, dp(12), 0, 0);
        root.addView(peerList, peersLp);

        Button retry = secondaryButton("Search again");
        retry.setOnClickListener(v -> startDiscovery());
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(-1, dp(50));
        retryLp.setMargins(0, dp(12), 0, 0);
        root.addView(retry, retryLp);
        setContentView(scroll);

        startDiscovery();
    }

    private void showReceive() {
        currentScreen = SCREEN_RECEIVE;
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = shell(scroll);
        addBackHeader(root, "Receive", "Make this phone discoverable to OptiShare senders");

        connectionPill = text("STARTING RECEIVER", 13, Color.rgb(255, 194, 73), true);
        connectionPill.setGravity(Gravity.CENTER);
        connectionPill.setPadding(dp(12), dp(10), dp(12), dp(10));
        connectionPill.setBackground(round(Color.rgb(54, 43, 25), 14));
        root.addView(connectionPill);

        LinearLayout card = card();
        TextView icon = text("◉", 82, Color.rgb(65, 222, 151), true);
        icon.setGravity(Gravity.CENTER);
        card.addView(icon);
        discoveryState = text("Preparing receiver…", 18, Color.WHITE, true);
        discoveryState.setGravity(Gravity.CENTER);
        card.addView(discoveryState);
        TextView desc = text("Keep this screen open. The sender should see this phone under Nearby devices.", 13, Color.rgb(153, 182, 206), false);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(dp(8), dp(8), dp(8), 0);
        card.addView(desc);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.setMargins(0, dp(12), 0, 0);
        root.addView(card, cardLp);
        setContentView(scroll);

        startReceiverMode();
    }

    private void startDiscovery() {
        if (!ensureNearbyReady()) return;
        peers.clear();
        renderPeers();
        setDiscoveryText("Searching for receiving phones…");
        setConnectionUi("SEARCHING", Color.rgb(255, 194, 73));
        try {
            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    setDiscoveryText("Searching… keep the receiver screen open on the other phone.");
                    try { manager.requestPeers(channel, V2Activity.this); }
                    catch (SecurityException ignored) { showNearbyPermissionHelp(); }
                }
                @Override public void onFailure(int reason) {
                    setDiscoveryText(p2pError("Search failed", reason));
                }
            });
        } catch (SecurityException e) {
            showNearbyPermissionHelp();
        }
    }

    private void startReceiverMode() {
        if (!ensureNearbyReady()) return;
        safeRemoveGroup();
        try {
            manager.createGroup(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    setDiscoveryText("READY TO RECEIVE ✓\nWaiting for sender…");
                    setConnectionUi("VISIBLE TO SENDERS", Color.rgb(65, 222, 151));
                    try {
                        manager.requestDeviceInfo(channel, device -> thisDevice = device);
                    } catch (SecurityException ignored) { }
                }
                @Override public void onFailure(int reason) {
                    setDiscoveryText(p2pError("Receiver could not start", reason));
                    setConnectionUi("RECEIVER ERROR", Color.rgb(255, 91, 101));
                }
            });
        } catch (SecurityException e) {
            showNearbyPermissionHelp();
        }
    }

    @Override public void onPeersAvailable(WifiP2pDeviceList list) {
        peers.clear();
        peers.addAll(list.getDeviceList());
        Collections.sort(peers, Comparator.comparing(this::deviceName, String.CASE_INSENSITIVE_ORDER));
        renderPeers();
        if (currentScreen == SCREEN_DISCOVERY) {
            setDiscoveryText(peers.isEmpty() ? "No receivers found yet. Make sure the other phone is on RECEIVE." : peers.size() + " nearby device" + (peers.size() == 1 ? "" : "s") + " found");
        }
    }

    private void renderPeers() {
        runOnUiThread(() -> {
            if (peerList == null) return;
            peerList.removeAllViews();
            if (peers.isEmpty()) {
                LinearLayout empty = card();
                empty.addView(text("No devices yet", 14, Color.WHITE, true));
                empty.addView(text("OptiShare is actively searching. Receiver must stay on its RECEIVE screen.", 12, Color.rgb(147, 173, 196), false));
                peerList.addView(empty);
                return;
            }

            for (WifiP2pDevice device : peers) {
                LinearLayout row = card();
                LinearLayout line = new LinearLayout(this);
                line.setGravity(Gravity.CENTER_VERTICAL);
                TextView avatar = text(firstLetter(deviceName(device)), 18, Color.WHITE, true);
                avatar.setGravity(Gravity.CENTER);
                avatar.setBackground(gradient(Color.rgb(38, 151, 232), Color.rgb(62, 91, 220), 18));
                line.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));

                LinearLayout names = new LinearLayout(this);
                names.setOrientation(LinearLayout.VERTICAL);
                names.setPadding(dp(12), 0, 0, 0);
                names.addView(text(deviceName(device), 15, Color.WHITE, true));
                names.addView(text(deviceStatus(device.status), 12, Color.rgb(151, 182, 205), false));
                line.addView(names, new LinearLayout.LayoutParams(0, -2, 1));

                Button connect = secondaryButton("Connect");
                connect.setOnClickListener(v -> connectTo(device));
                line.addView(connect, new LinearLayout.LayoutParams(dp(100), dp(46)));
                row.addView(line);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                rowLp.setMargins(0, 0, 0, dp(8));
                peerList.addView(row, rowLp);
            }
        });
    }

    private void connectTo(WifiP2pDevice device) {
        if (!ensureNearbyReady()) return;
        setConnectionUi("CONNECTING…", Color.rgb(255, 194, 73));
        setDiscoveryText("Connecting to " + deviceName(device) + "…");
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = WpsInfo.PBC;
        config.groupOwnerIntent = 0;
        try {
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    setDiscoveryText("Connection request sent. Waiting for Android to complete the link…");
                }
                @Override public void onFailure(int reason) {
                    setConnectionUi("CONNECTION FAILED", Color.rgb(255, 91, 101));
                    setDiscoveryText(p2pError("Connection failed", reason));
                }
            });
        } catch (SecurityException e) {
            showNearbyPermissionHelp();
        }
    }

    @Override public void onConnectionInfoAvailable(WifiP2pInfo info) {
        if (info != null && info.groupFormed) {
            setConnectionUi("CONNECTED ✓", Color.rgb(65, 225, 151));
            if (currentScreen == SCREEN_DISCOVERY) {
                setDiscoveryText("Connected successfully. " + selected.size() + " selected item" + (selected.size() == 1 ? "" : "s") + " ready for transfer.");
            } else if (currentScreen == SCREEN_RECEIVE) {
                setDiscoveryText("CONNECTED ✓\nSender is connected to this phone.");
            }
        }
    }

    private boolean ensureNearbyReady() {
        if (manager == null || channel == null) {
            showMessage("Wi‑Fi Direct unavailable", "This device does not expose Android Wi‑Fi Direct to OptiShare.");
            return false;
        }
        if (!hasNearbyPermission()) {
            requestNearbyPermission();
            return false;
        }
        if (Build.VERSION.SDK_INT <= 32 && !isLocationEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("Turn on Location")
                    .setMessage("Android requires Location services for Wi‑Fi Direct discovery on this Android version. OptiShare does not upload your location.")
                    .setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                    .setNegativeButton("Cancel", null)
                    .show();
            return false;
        }
        return true;
    }

    private boolean hasNearbyPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNearbyPermission() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQ_NEARBY);
        else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_NEARBY);
    }

    private boolean hasMediaPermission(String type) {
        if (Build.VERSION.SDK_INT >= 33) {
            String permission = "image".equals(type) ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_MEDIA_VIDEO;
            return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= 23) return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        return true;
    }

    private void requestMediaPermission(String type) {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"image".equals(type) ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_MEDIA_VIDEO}, REQ_MEDIA);
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_MEDIA);
        } else {
            showMediaGallery(type);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_MEDIA && granted && pendingGalleryType != null) showMediaGallery(pendingGalleryType);
        if (requestCode == REQ_NEARBY && granted) {
            if (currentScreen == SCREEN_DISCOVERY) startDiscovery();
            else if (currentScreen == SCREEN_RECEIVE) startReceiverMode();
        }
    }

    private List<MediaItem> loadMedia(String type) {
        List<MediaItem> items = new ArrayList<>();
        Uri collection = "image".equals(type) ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED};
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(collection, projection, null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC");
            if (cursor == null) return items;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            while (cursor.moveToNext() && items.size() < 500) {
                long id = cursor.getLong(idCol);
                String name = cursor.getString(nameCol);
                long size = cursor.getLong(sizeCol);
                Uri uri = ContentUris.withAppendedId(collection, id);
                items.add(new MediaItem(uri, name == null ? "media" : name, size));
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return items;
    }

    private void openExternal(String mime) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType(mime);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        externalPicker.launch(intent);
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        String last = uri.getLastPathSegment();
        return last == null ? "item" : last;
    }

    private void updateMediaCellBackground(View cell, boolean selectedNow) {
        GradientDrawable bg = round(selectedNow ? Color.rgb(31, 91, 145) : Color.rgb(13, 33, 56), 14);
        bg.setStroke(dp(selectedNow ? 3 : 1), selectedNow ? Color.rgb(75, 207, 255) : Color.rgb(34, 62, 88));
        cell.setBackground(bg);
    }

    private String shortName(String value) {
        if (value == null) return "media";
        return value.length() <= 18 ? value : value.substring(0, 15) + "…";
    }

    private void setDiscoveryText(String value) {
        runOnUiThread(() -> { if (discoveryState != null) discoveryState.setText(value); });
    }

    private void setConnectionUi(String label, int color) {
        runOnUiThread(() -> {
            if (connectionPill == null) return;
            connectionPill.setText(label);
            connectionPill.setTextColor(color);
            connectionPill.setBackground(round(Color.argb(80, Color.red(color), Color.green(color), Color.blue(color)), 14));
        });
    }

    private void showNearbyPermissionHelp() {
        showMessage("Nearby permission required", "Allow Nearby Wi‑Fi devices for OptiShare. On Android 12 or older, Location permission and Location services are required by Android for Wi‑Fi Direct discovery.");
    }

    private boolean isLocationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
        try { return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER); }
        catch (Exception e) { return false; }
    }

    private void safeRemoveGroup() {
        if (manager == null || channel == null || !hasNearbyPermission()) return;
        try {
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { }
                @Override public void onFailure(int reason) { }
            });
        } catch (Exception ignored) { }
    }

    private String p2pError(String prefix, int reason) {
        if (reason == WifiP2pManager.BUSY) return prefix + ": Wi‑Fi Direct is busy. Wait a few seconds and retry.";
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) return prefix + ": Wi‑Fi Direct is not supported on this phone.";
        return prefix + ": Android Wi‑Fi Direct error " + reason + ".";
    }

    private String deviceName(WifiP2pDevice d) {
        if (d == null || d.deviceName == null || d.deviceName.trim().isEmpty()) return "Android device";
        return d.deviceName.trim();
    }

    private String deviceStatus(int status) {
        switch (status) {
            case WifiP2pDevice.CONNECTED: return "Connected";
            case WifiP2pDevice.INVITED: return "Connecting…";
            case WifiP2pDevice.AVAILABLE: return "Available";
            case WifiP2pDevice.FAILED: return "Unavailable";
            case WifiP2pDevice.UNAVAILABLE: return "Busy";
            default: return "Nearby";
        }
    }

    private String firstLetter(String value) {
        return value == null || value.isEmpty() ? "?" : value.substring(0, 1).toUpperCase(Locale.US);
    }

    private ScrollView newScroll() {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        return s;
    }

    private LinearLayout shell(ScrollView scroll) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(28));
        root.setBackground(gradient(Color.rgb(5, 17, 38), Color.rgb(16, 48, 84), 0));
        scroll.addView(root);
        return root;
    }

    private void addBackHeader(LinearLayout root, String title, String subtitle) {
        Button back = smallButton("← Back");
        back.setOnClickListener(v -> {
            if (currentScreen == SCREEN_GALLERY) showSendSelection();
            else if (currentScreen == SCREEN_DISCOVERY) showSendSelection();
            else showHome();
        });
        root.addView(back, new LinearLayout.LayoutParams(dp(96), dp(44)));
        TextView t = text(title, 27, Color.WHITE, true);
        t.setPadding(0, dp(18), 0, dp(3));
        root.addView(t);
        TextView s = text(subtitle, 13, Color.rgb(162, 194, 219), false);
        s.setPadding(0, 0, 0, dp(14));
        root.addView(s);
    }

    private Button category(String icon, String label, int color, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(icon + "\n" + label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(gradient(color, darken(color), 18));
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout categoryRow(Button a, Button b, Button c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(a, new LinearLayout.LayoutParams(0, dp(104), 1));
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(104), 1); p2.setMargins(dp(8), 0, 0, 0);
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(0, dp(104), 1); p3.setMargins(dp(8), 0, 0, 0);
        row.addView(b, p2);
        row.addView(c, p3);
        return row;
    }

    private Button bigAction(String icon, String title, String sub, int top, int bottom) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(icon + "\n" + title + "\n" + sub);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(gradient(top, bottom, 22));
        return b;
    }

    private Button primary(String label) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(gradient(Color.rgb(31, 151, 255), Color.rgb(52, 88, 226), 16));
        return b;
    }

    private Button secondaryButton(String label) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setBackground(round(Color.rgb(24, 52, 78), 14));
        return b;
    }

    private Button smallButton(String label) {
        Button b = secondaryButton(label);
        b.setTextSize(12);
        return b;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable g = round(Color.rgb(13, 33, 56), 18);
        g.setStroke(dp(1), Color.rgb(37, 68, 96));
        l.setBackground(g);
        return l;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private GradientDrawable gradient(int top, int bottom, int radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{top, bottom});
        g.setCornerRadius(dp(radius));
        return g;
    }

    private int darken(int color) {
        return Color.rgb((int)(Color.red(color) * .66), (int)(Color.green(color) * .66), (int)(Color.blue(color) * .66));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showMessage(String title, String message) {
        runOnUiThread(() -> new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show());
    }

    @Override protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        ContextCompat.registerReceiver(this, p2pReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(p2pReceiver); } catch (Exception ignored) { }
    }

    @Override public void onBackPressed() {
        if (currentScreen == SCREEN_HOME) super.onBackPressed();
        else if (currentScreen == SCREEN_GALLERY || currentScreen == SCREEN_DISCOVERY) showSendSelection();
        else showHome();
    }

    private static final class MediaItem {
        final Uri uri;
        final String name;
        final long size;
        MediaItem(Uri uri, String name, long size) {
            this.uri = uri;
            this.name = name;
            this.size = size;
        }
    }
}
