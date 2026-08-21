package com.kenan.optishare;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;

public class MainActivity extends ComponentActivity {
    private static final int REQ_PICK = 1001;
    private static final int REQ_CAMERA = 1002;
    private static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;
    private static final int GRID_W = 224;
    private static final int GRID_H = 126;
    private static final int BORDER = 2;
    private static final int DATA_W = GRID_W - BORDER * 2;
    private static final int DATA_H = GRID_H - BORDER * 2;
    private static final int DATA_CELLS = DATA_W * DATA_H;
    private static final int RAW_BYTES_PER_FRAME = (DATA_CELLS * 6) / 8;
    private static final int HEADER_BYTES = 31;
    private static final int PAYLOAD_BYTES = RAW_BYTES_PER_FRAME - HEADER_BYTES;
    private static final int DATA_PER_PARITY_GROUP = 8;
    private static final long FRAME_INTERVAL_MS = 33L;
    private static final byte[] FRAME_MAGIC = new byte[]{'O','P','4','0'};
    private static final byte[] STREAM_MAGIC = new byte[]{'O','S','M','1'};
    private static final int[] LEVELS = new int[]{28, 94, 161, 228};

    private final android.os.Handler handler = new android.os.Handler();
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private OpticalView opticalView;
    private boolean sending;
    private byte[] sendStream;
    private int sendSession;
    private int sendDataFrames;
    private int sendScheduleIndex;
    private String sendName;
    private long sendStartedAt;
    private ProcessCameraProvider cameraProvider;
    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView receiveStatus;
    private ProgressBar receiveProgress;
    private final ReceiverState receiver = new ReceiverState();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showHome();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopSender();
        stopCamera();
        cameraExecutor.shutdown();
    }

    private void hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    private void showSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private TextView label(String text, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setPadding(12, 10, 12, 10);
        return v;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(17);
        return b;
    }

    private LinearLayout.LayoutParams wide() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(8, 8, 8, 8);
        return p;
    }

    private void showHome() {
        stopSender();
        stopCamera();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 18, 28, 18);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(7, 17, 31));

        TextView title = label("OptiShare", 30, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = label(
                "Optical Modem v0.4 • No QR • No Internet • No Wi‑Fi • No Bluetooth\n" +
                "Design target: 4 MB in ≤10 seconds • ~500 KB/s+",
                15, Color.rgb(148, 208, 232));
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button send = button("Send file");
        send.setOnClickListener(v -> pickFile());
        Button receive = button("Receive optical");
        receive.setOnClickListener(v -> startReceive());
        row.addView(send, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(receive, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(row, wide());

        Button files = button("Received files");
        files.setOnClickListener(v -> showReceivedFiles());
        root.addView(files, wide());

        TextView tech = label(
                String.format(Locale.US,
                        "Optical frame: %dx%d cells • 6 bits/cell • payload ≈ %.1f KB/frame • target 30 fps\n" +
                        "1 XOR parity frame per %d data frames • SHA‑256 end verification",
                        GRID_W, GRID_H, PAYLOAD_BYTES / 1024.0, DATA_PER_PARITY_GROUP),
                13, Color.rgb(170, 184, 203));
        tech.setGravity(Gravity.CENTER);
        root.addView(tech);

        TextView credit = label("Designed & developed by Kenan Alhennawi • © 2026", 13, Color.rgb(56, 189, 248));
        credit.setGravity(Gravity.CENTER);
        root.addView(credit);
        setContentView(root);
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            new Thread(() -> {
                try { prepareSend(uri); }
                catch (Exception e) { runOnUiThread(() -> toast("Could not prepare file: " + e.getMessage())); }
            }).start();
        }
    }

    private void prepareSend(Uri uri) throws Exception {
        byte[] fileBytes = readAll(uri);
        String fileName = queryName(uri);
        byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 4096) throw new IOException("File name is too long");
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(fileBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream(fileBytes.length + 128);
        out.write(STREAM_MAGIC);
        writeU16(out, nameBytes.length);
        out.write(nameBytes);
        writeLong(out, fileBytes.length);
        out.write(sha);
        out.write(fileBytes);
        sendStream = out.toByteArray();
        sendName = fileName;
        sendSession = new Random().nextInt();
        sendDataFrames = (sendStream.length + PAYLOAD_BYTES - 1) / PAYLOAD_BYTES;
        sendScheduleIndex = 0;
        runOnUiThread(this::showSender);
    }

    private void showSender() {
        hideSystemUi();
        sending = true;
        sendStartedAt = System.currentTimeMillis();
        opticalView = new OpticalView();
        opticalView.setOnClickListener(v -> showHome());
        setContentView(opticalView);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1.0f;
        getWindow().setAttributes(lp);
        senderTick.run();
    }

    private final Runnable senderTick = new Runnable() {
        @Override public void run() {
            if (!sending || sendStream == null || opticalView == null) return;
            try {
                int groups = (sendDataFrames + DATA_PER_PARITY_GROUP - 1) / DATA_PER_PARITY_GROUP;
                int scheduleSize = sendDataFrames + groups;
                int slot = sendScheduleIndex % scheduleSize;
                int cursor = 0;
                int group = 0;
                boolean found = false;
                while (group < groups && !found) {
                    int groupStart = group * DATA_PER_PARITY_GROUP;
                    int groupCount = Math.min(DATA_PER_PARITY_GROUP, sendDataFrames - groupStart);
                    if (slot >= cursor && slot < cursor + groupCount) {
                        int dataIndex = groupStart + (slot - cursor);
                        opticalView.setPacket(makeDataPacket(dataIndex));
                        found = true;
                    } else {
                        cursor += groupCount;
                        if (slot == cursor) {
                            opticalView.setPacket(makeParityPacket(group));
                            found = true;
                        }
                        cursor++;
                    }
                    group++;
                }
                sendScheduleIndex++;
                handler.postDelayed(this, FRAME_INTERVAL_MS);
            } catch (Exception e) {
                toast("Optical sender error: " + e.getMessage());
                showHome();
            }
        }
    };

    private void stopSender() {
        sending = false;
        handler.removeCallbacks(senderTick);
        sendStream = null;
        opticalView = null;
    }

    private byte[] makeDataPacket(int index) {
        int from = index * PAYLOAD_BYTES;
        int len = Math.min(PAYLOAD_BYTES, sendStream.length - from);
        byte[] payload = Arrays.copyOfRange(sendStream, from, from + len);
        return makePacket((byte) 0, index, payload);
    }

    private byte[] makeParityPacket(int group) {
        byte[] parity = new byte[PAYLOAD_BYTES];
        int start = group * DATA_PER_PARITY_GROUP;
        int end = Math.min(sendDataFrames, start + DATA_PER_PARITY_GROUP);
        for (int i = start; i < end; i++) {
            int from = i * PAYLOAD_BYTES;
            int len = Math.min(PAYLOAD_BYTES, sendStream.length - from);
            for (int j = 0; j < len; j++) parity[j] ^= sendStream[from + j];
        }
        return makePacket((byte) 1, group, parity);
    }

    private byte[] makePacket(byte flags, int index, byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload);
        ByteBuffer h = ByteBuffer.allocate(HEADER_BYTES + payload.length).order(ByteOrder.BIG_ENDIAN);
        h.put(FRAME_MAGIC);
        h.put(flags);
        h.putInt(sendSession);
        h.putInt(index);
        h.putInt(sendDataFrames);
        h.putShort((short) payload.length);
        h.putLong(sendStream.length);
        h.putInt((int) crc.getValue());
        h.put(payload);
        return h.array();
    }

    private void startReceive() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        showReceiver();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_CAMERA && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) showReceiver();
        else if (requestCode == REQ_CAMERA) toast("Camera permission is required");
    }

    private void showReceiver() {
        stopCamera();
        hideSystemUi();
        receiver.reset();
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        root.addView(previewView, new android.widget.FrameLayout.LayoutParams(-1, -1));
        overlayView = new OverlayView();
        root.addView(overlayView, new android.widget.FrameLayout.LayoutParams(-1, -1));
        LinearLayout hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.VERTICAL);
        hud.setPadding(18, 8, 18, 8);
        hud.setBackgroundColor(0x9907111F);
        receiveStatus = label("Align the sender screen exactly inside the cyan frame", 14, Color.WHITE);
        receiveStatus.setGravity(Gravity.CENTER);
        hud.addView(receiveStatus);
        receiveProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        receiveProgress.setMax(1000);
        hud.addView(receiveProgress, new LinearLayout.LayoutParams(-1, 18));
        TextView back = label("Tap here to stop", 13, Color.rgb(56, 189, 248));
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> showHome());
        hud.addView(back);
        android.widget.FrameLayout.LayoutParams hp = new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(hud, hp);
        setContentView(root);
        bindCamera();
    }

    private void bindCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                cameraProvider.unbindAll();
                Preview preview = new Preview.Builder().setTargetResolution(new Size(1920, 1080)).build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1920, 1080))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeOpticalFrame);
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (Exception e) {
                toast("Camera start failed: " + e.getMessage());
                showHome();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void stopCamera() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
    }

    private void analyzeOpticalFrame(ImageProxy image) {
        try {
            if (image.getFormat() != android.graphics.ImageFormat.YUV_420_888 || image.getPlanes().length < 3) return;
            int rotation = image.getImageInfo().getRotationDegrees();
            if (rotation == 90 || rotation == 270) return;
            YuvSampler sampler = new YuvSampler(image);
            OpticalGeometry g = OpticalGeometry.fromImage(image.getWidth(), image.getHeight());
            int[][] observed = new int[3][4];
            for (int i = 0; i < 4; i++) observed[0][i] = sampler.rgbAt(g.cellX(BORDER + i), g.cellY(0))[0];
            for (int i = 0; i < 4; i++) observed[1][i] = sampler.rgbAt(g.cellX(BORDER + 4 + i), g.cellY(0))[1];
            for (int i = 0; i < 4; i++) observed[2][i] = sampler.rgbAt(g.cellX(BORDER + 8 + i), g.cellY(0))[2];
            int headerSymbols = (HEADER_BYTES * 8 + 5) / 6;
            byte[] header = decodeBytes(sampler, g, observed, headerSymbols, HEADER_BYTES);
            if (!startsWith(header, FRAME_MAGIC)) return;
            ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
            hb.position(4);
            byte flags = hb.get();
            int session = hb.getInt();
            int index = hb.getInt();
            int totalData = hb.getInt();
            int payloadLen = hb.getShort() & 0xffff;
            long totalStream = hb.getLong();
            int expectedCrc = hb.getInt();
            if (totalData <= 0 || totalData > 100000) return;
            if (payloadLen < 0 || payloadLen > PAYLOAD_BYTES) return;
            if (totalStream <= 0 || totalStream > MAX_FILE_BYTES + 8192) return;
            if (flags == 0 && (index < 0 || index >= totalData)) return;
            if (flags == 1 && (index < 0 || index > (totalData + DATA_PER_PARITY_GROUP - 1) / DATA_PER_PARITY_GROUP)) return;
            int packetBytes = HEADER_BYTES + payloadLen;
            int symbols = (packetBytes * 8 + 5) / 6;
            byte[] packet = decodeBytes(sampler, g, observed, symbols, packetBytes);
            byte[] payload = Arrays.copyOfRange(packet, HEADER_BYTES, packet.length);
            CRC32 crc = new CRC32();
            crc.update(payload);
            if ((int) crc.getValue() != expectedCrc) return;
            receiver.accept(flags, session, index, totalData, totalStream, payload);
        } catch (Throwable ignored) {
        } finally {
            image.close();
        }
    }

    private byte[] decodeBytes(YuvSampler sampler, OpticalGeometry g, int[][] observed, int symbolsNeeded, int bytesNeeded) {
        byte[] out = new byte[bytesNeeded];
        int bitPos = 0;
        for (int s = 0; s < symbolsNeeded; s++) {
            int dx = s % DATA_W;
            int dy = s / DATA_W;
            int gx = BORDER + dx;
            int gy = BORDER + dy;
            int[] rgb = sampler.rgbAt(g.cellX(gx), g.cellY(gy));
            int r = nearestLevel(rgb[0], observed[0]);
            int gg = nearestLevel(rgb[1], observed[1]);
            int b = nearestLevel(rgb[2], observed[2]);
            int symbol = (r << 4) | (gg << 2) | b;
            for (int k = 5; k >= 0; k--) {
                if (bitPos >= bytesNeeded * 8) return out;
                int bit = (symbol >> k) & 1;
                int byteIndex = bitPos >> 3;
                int shift = 7 - (bitPos & 7);
                out[byteIndex] |= (byte) (bit << shift);
                bitPos++;
            }
        }
        return out;
    }

    private int nearestLevel(int value, int[] levels) {
        int best = 0;
        int bestD = Integer.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            int d = Math.abs(value - levels[i]);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private final class ReceiverState {
        int session;
        boolean active;
        int totalData;
        long totalStream;
        long startedAt;
        final Map<Integer, byte[]> chunks = new HashMap<>();
        final Map<Integer, byte[]> parity = new HashMap<>();

        synchronized void reset() {
            active = false;
            chunks.clear();
            parity.clear();
            totalData = 0;
            totalStream = 0;
            startedAt = 0;
        }

        synchronized void accept(byte flags, int incomingSession, int index, int incomingTotal, long incomingStream, byte[] payload) {
            if (!active || session != incomingSession) {
                reset();
                active = true;
                session = incomingSession;
                totalData = incomingTotal;
                totalStream = incomingStream;
                startedAt = System.currentTimeMillis();
            }
            if (incomingTotal != totalData || incomingStream != totalStream) return;
            if (flags == 0) {
                chunks.putIfAbsent(index, payload);
                tryRecover(index / DATA_PER_PARITY_GROUP);
            } else {
                parity.putIfAbsent(index, payload);
                tryRecover(index);
            }
            updateReceiveUi();
            if (chunks.size() == totalData) finish();
        }

        private void tryRecover(int group) {
            byte[] p = parity.get(group);
            if (p == null) return;
            int start = group * DATA_PER_PARITY_GROUP;
            int end = Math.min(totalData, start + DATA_PER_PARITY_GROUP);
            int missing = -1;
            int missingCount = 0;
            for (int i = start; i < end; i++) {
                if (!chunks.containsKey(i)) { missing = i; missingCount++; }
            }
            if (missingCount != 1) return;
            byte[] recovered = Arrays.copyOf(p, p.length);
            for (int i = start; i < end; i++) {
                if (i == missing) continue;
                byte[] c = chunks.get(i);
                if (c == null) return;
                for (int j = 0; j < c.length; j++) recovered[j] ^= c[j];
            }
            int expected = PAYLOAD_BYTES;
            if (missing == totalData - 1) expected = (int) (totalStream - (long) missing * PAYLOAD_BYTES);
            if (expected <= 0 || expected > PAYLOAD_BYTES) return;
            chunks.put(missing, Arrays.copyOf(recovered, expected));
        }

        private void updateReceiveUi() {
            int count = chunks.size();
            int progress = totalData == 0 ? 0 : (int) ((count * 1000L) / totalData);
            long elapsed = Math.max(1, System.currentTimeMillis() - startedAt);
            long bytes = Math.min(totalStream, (long) count * PAYLOAD_BYTES);
            double kbps = (bytes / 1024.0) / (elapsed / 1000.0);
            runOnUiThread(() -> {
                if (receiveProgress != null) receiveProgress.setProgress(progress);
                if (receiveStatus != null) receiveStatus.setText(String.format(Locale.US, "Frames %d/%d • %.0f KB/s • parity recovery active", count, totalData, kbps));
            });
        }

        private void finish() {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream((int) totalStream);
                for (int i = 0; i < totalData; i++) {
                    byte[] c = chunks.get(i);
                    if (c == null) return;
                    out.write(c);
                }
                byte[] stream = Arrays.copyOf(out.toByteArray(), (int) totalStream);
                parseAndSave(stream);
            } catch (Exception e) {
                runOnUiThread(() -> toast("Receive finish failed: " + e.getMessage()));
            }
        }
    }

    private void parseAndSave(byte[] stream) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(stream).order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[4];
        b.get(magic);
        if (!Arrays.equals(magic, STREAM_MAGIC)) throw new IOException("Stream header mismatch");
        int nameLen = b.getShort() & 0xffff;
        if (nameLen <= 0 || nameLen > 4096 || b.remaining() < nameLen + 40) throw new IOException("Invalid metadata");
        byte[] nameBytes = new byte[nameLen];
        b.get(nameBytes);
        String name = new String(nameBytes, StandardCharsets.UTF_8).replace("/", "_").replace("\\", "_");
        long fileLen = b.getLong();
        byte[] expectedSha = new byte[32];
        b.get(expectedSha);
        if (fileLen < 0 || fileLen > b.remaining()) throw new IOException("Invalid file length");
        byte[] fileBytes = new byte[(int) fileLen];
        b.get(fileBytes);
        byte[] actualSha = MessageDigest.getInstance("SHA-256").digest(fileBytes);
        if (!Arrays.equals(expectedSha, actualSha)) throw new IOException("SHA-256 verification failed");
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "OptiShare Received");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create received folder");
        File target = uniqueFile(dir, name);
        try (FileOutputStream fos = new FileOutputStream(target)) { fos.write(fileBytes); }
        long elapsed = Math.max(1, System.currentTimeMillis() - receiver.startedAt);
        double seconds = elapsed / 1000.0;
        double kbps = (fileBytes.length / 1024.0) / seconds;
        receiver.reset();
        runOnUiThread(() -> {
            stopCamera();
            toast(String.format(Locale.US, "Received %s in %.1fs • %.0f KB/s", target.getName(), seconds, kbps));
            showReceivedFiles();
        });
    }

    private File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            File n = new File(dir, base + " (" + i + ")" + ext);
            if (!n.exists()) return n;
        }
        return new File(dir, System.currentTimeMillis() + "-" + name);
    }

    private void showReceivedFiles() {
        stopCamera();
        stopSender();
        showSystemUi();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 18, 28, 18);
        root.setBackgroundColor(Color.rgb(7, 17, 31));
        TextView title = label("Received files", 26, Color.WHITE);
        root.addView(title);
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "OptiShare Received");
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            list.addView(label("No received files yet.", 15, Color.LTGRAY));
        } else {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : files) {
                TextView item = label(f.getName() + "  •  " + human(f.length()), 15, Color.WHITE);
                item.setBackgroundColor(0x2218A5E8);
                list.addView(item, wide());
            }
        }
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button back = button("Back");
        back.setOnClickListener(v -> showHome());
        root.addView(back, wide());
        setContentView(root);
    }

    private final class OpticalView extends View {
        private final Bitmap bitmap = Bitmap.createBitmap(GRID_W, GRID_H, Bitmap.Config.ARGB_8888);
        private final int[] pixels = new int[GRID_W * GRID_H];
        private final Paint paint = new Paint();
        private byte[] packet;
        OpticalView() { super(MainActivity.this); paint.setFilterBitmap(false); paint.setAntiAlias(false); setBackgroundColor(Color.BLACK); }
        void setPacket(byte[] value) { packet = value; render(); invalidate(); }
        private void render() {
            Arrays.fill(pixels, Color.BLACK);
            for (int x = 0; x < GRID_W; x++) {
                int c = ((x / 2) & 1) == 0 ? Color.WHITE : Color.BLACK;
                for (int y = 0; y < BORDER; y++) pixels[y * GRID_W + x] = c;
                for (int y = GRID_H - BORDER; y < GRID_H; y++) pixels[y * GRID_W + x] = c;
            }
            for (int y = 0; y < GRID_H; y++) {
                int c = ((y / 2) & 1) == 0 ? Color.WHITE : Color.BLACK;
                for (int x = 0; x < BORDER; x++) pixels[y * GRID_W + x] = c;
                for (int x = GRID_W - BORDER; x < GRID_W; x++) pixels[y * GRID_W + x] = c;
            }
            for (int i = 0; i < 4; i++) pixels[BORDER + i] = Color.rgb(LEVELS[i], 128, 128);
            for (int i = 0; i < 4; i++) pixels[BORDER + 4 + i] = Color.rgb(128, LEVELS[i], 128);
            for (int i = 0; i < 4; i++) pixels[BORDER + 8 + i] = Color.rgb(128, 128, LEVELS[i]);
            if (packet != null) {
                int totalBits = packet.length * 8;
                int bitPos = 0;
                for (int cell = 0; cell < DATA_CELLS; cell++) {
                    int symbol = 0;
                    for (int k = 0; k < 6; k++) {
                        symbol <<= 1;
                        if (bitPos < totalBits) {
                            int bi = bitPos >> 3;
                            int shift = 7 - (bitPos & 7);
                            symbol |= (packet[bi] >> shift) & 1;
                        }
                        bitPos++;
                    }
                    int r = LEVELS[(symbol >> 4) & 3];
                    int g = LEVELS[(symbol >> 2) & 3];
                    int b = LEVELS[symbol & 3];
                    int dx = cell % DATA_W;
                    int dy = cell / DATA_W;
                    pixels[(BORDER + dy) * GRID_W + (BORDER + dx)] = Color.rgb(r, g, b);
                }
            }
            bitmap.setPixels(pixels, 0, GRID_W, 0, 0, GRID_W, GRID_H);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setFilterBitmap(false);
            canvas.drawBitmap(bitmap, null, new android.graphics.Rect(0, 0, getWidth(), getHeight()), paint);
        }
    }

    private final class OverlayView extends View {
        private final Paint border = new Paint();
        private final Paint text = new Paint();
        OverlayView() {
            super(MainActivity.this);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(5f);
            border.setColor(Color.rgb(56, 189, 248));
            text.setColor(Color.WHITE);
            text.setTextSize(28f);
            text.setAntiAlias(true);
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float mx = getWidth() * 0.04f;
            float my = getHeight() * 0.04f;
            c.drawRect(mx, my, getWidth() - mx, getHeight() - my, border);
            c.drawText("Fit sender screen inside this frame", mx + 12, my + 36, text);
        }
    }

    private static final class OpticalGeometry {
        final float x0, y0, w, h;
        OpticalGeometry(float x0, float y0, float w, float h) { this.x0=x0; this.y0=y0; this.w=w; this.h=h; }
        static OpticalGeometry fromImage(int width, int height) {
            return new OpticalGeometry(width * 0.04f, height * 0.04f, width * 0.92f, height * 0.92f);
        }
        int cellX(int gx) { return Math.max(0, Math.round(x0 + (gx + 0.5f) * w / GRID_W)); }
        int cellY(int gy) { return Math.max(0, Math.round(y0 + (gy + 0.5f) * h / GRID_H)); }
    }

    private static final class YuvSampler {
        private final ImageProxy image;
        private final ByteBuffer y, u, v;
        private final int yRow, yPixel, uRow, uPixel, vRow, vPixel;
        YuvSampler(ImageProxy image) {
            this.image = image;
            ImageProxy.PlaneProxy[] p = image.getPlanes();
            y=p[0].getBuffer(); u=p[1].getBuffer(); v=p[2].getBuffer();
            yRow=p[0].getRowStride(); yPixel=p[0].getPixelStride();
            uRow=p[1].getRowStride(); uPixel=p[1].getPixelStride();
            vRow=p[2].getRowStride(); vPixel=p[2].getPixelStride();
        }
        int[] rgbAt(int x, int yy) {
            x=Math.min(Math.max(x,0),image.getWidth()-1);
            yy=Math.min(Math.max(yy,0),image.getHeight()-1);
            int yi=yy*yRow+x*yPixel;
            int ux=x/2, uy=yy/2;
            int ui=uy*uRow+ux*uPixel;
            int vi=uy*vRow+ux*vPixel;
            int Y=y.get(yi)&0xff;
            int U=(u.get(ui)&0xff)-128;
            int V=(v.get(vi)&0xff)-128;
            int r=clamp((int)(Y+1.402f*V));
            int g=clamp((int)(Y-0.344136f*U-0.714136f*V));
            int b=clamp((int)(Y+1.772f*U));
            return new int[]{r,g,b};
        }
        private static int clamp(int x){ return x<0?0:Math.min(x,255); }
    }

    private byte[] readAll(Uri uri) throws IOException {
        try (InputStream in=getContentResolver().openInputStream(uri); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            if (in==null) throw new IOException("Cannot open file");
            byte[] buf=new byte[64*1024];
            int n; long total=0;
            while((n=in.read(buf))!=-1){
                total+=n;
                if(total>MAX_FILE_BYTES) throw new IOException("Prototype limit is 64 MB");
                out.write(buf,0,n);
            }
            return out.toByteArray();
        }
    }

    private String queryName(Uri uri) {
        String name="received_file.bin";
        Cursor c=null;
        try{
            c=getContentResolver().query(uri,null,null,null,null);
            if(c!=null&&c.moveToFirst()){
                int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if(i>=0) name=c.getString(i);
            }
        } catch(Exception ignored){} finally { if(c!=null)c.close(); }
        return name;
    }

    private static boolean startsWith(byte[] a, byte[] prefix){
        if(a.length<prefix.length)return false;
        for(int i=0;i<prefix.length;i++)if(a[i]!=prefix[i])return false;
        return true;
    }
    private static void writeU16(ByteArrayOutputStream out,int value){out.write((value>>>8)&0xff);out.write(value&0xff);}
    private static void writeLong(ByteArrayOutputStream out,long value){for(int i=7;i>=0;i--)out.write((int)((value>>>(i*8))&0xff));}
    private static String human(long b){if(b<1024)return b+" B";if(b<1024*1024)return String.format(Locale.US,"%.1f KB",b/1024.0);return String.format(Locale.US,"%.2f MB",b/(1024.0*1024.0));}
    private void toast(String text){runOnUiThread(()->Toast.makeText(this,text,Toast.LENGTH_LONG).show());}
    @Override public void onBackPressed(){showHome();}
}
