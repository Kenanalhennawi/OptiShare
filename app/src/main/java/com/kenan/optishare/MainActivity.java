package com.kenan.optishare;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

public class MainActivity extends Activity {
    private static final int REQ_PICK = 1001;
    private static final int REQ_CAMERA = 1002;
    private static final int REQ_SAVE = 1003;
    private static final int CHUNK_SIZE = 700;
    private static final long MAX_FILE_BYTES = 20L * 1024L * 1024L;

    private LinearLayout root;
    private TextView status;
    private ImageView qrImage;
    private ProgressBar progress;
    private final android.os.Handler handler = new android.os.Handler();
    private ArrayList<String> frames;
    private int frameIndex;
    private boolean sending;

    private Camera camera;
    private SurfaceView cameraView;
    private MultiFormatReader reader;
    private String receiveSession;
    private String receiveName;
    private long receiveSize;
    private String receiveSha;
    private int receiveTotal = -1;
    private final Map<Integer, byte[]> receiveChunks = new HashMap<>();
    private byte[] completedBytes;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showHome();
    }

    private TextView text(String s, int sp) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(Color.rgb(25,25,25));
        v.setPadding(0, 12, 0, 12); return v;
    }

    private Button button(String s) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(17); return b;
    }

    private void showHome() {
        stopSender(); stopCamera();
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(40,60,40,40); root.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = text("OptiShare", 30); title.setGravity(Gravity.CENTER); root.addView(title);
        TextView sub = text("Offline optical file transfer\nNo Internet • No Wi‑Fi • No Bluetooth", 16); sub.setGravity(Gravity.CENTER); root.addView(sub);
        Button send = button("Send file"); send.setOnClickListener(v -> pickFile()); root.addView(send, wide());
        Button recv = button("Receive by camera"); recv.setOnClickListener(v -> startReceive()); root.addView(recv, wide());
        status = text("Prototype v0.1 • practical limit: 20 MB", 14); status.setGravity(Gravity.CENTER); root.addView(status);
        setContentView(root);
    }

    private LinearLayout.LayoutParams wide() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,16,0,16); return p; }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("*/*"); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i, REQ_PICK);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req,res,data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_PICK) prepareSend(data.getData());
        if (req == REQ_SAVE && completedBytes != null) saveCompleted(data.getData());
    }

    private void prepareSend(Uri uri) {
        try {
            byte[] bytes = readAll(uri);
            if (bytes.length > MAX_FILE_BYTES) { toast("Prototype limit is 20 MB"); return; }
            String name = queryName(uri);
            String sid = UUID.randomUUID().toString().replace("-", "").substring(0,10);
            String sha = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
            int total = (bytes.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
            frames = new ArrayList<>(total + 1);
            String safeName = android.util.Base64.encodeToString(name.getBytes(StandardCharsets.UTF_8), android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            frames.add("OT1|"+sid+"|M|"+total+"|"+safeName+"|"+bytes.length+"|"+sha);
            for (int n=0;n<total;n++) {
                int from=n*CHUNK_SIZE, to=Math.min(bytes.length,from+CHUNK_SIZE);
                byte[] chunk=Arrays.copyOfRange(bytes,from,to); CRC32 crc=new CRC32(); crc.update(chunk);
                String payload=android.util.Base64.encodeToString(chunk, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
                frames.add("OT1|"+sid+"|D|"+n+"|"+Long.toHexString(crc.getValue())+"|"+payload);
            }
            showSender(name, bytes.length);
        } catch (Exception e) { toast("Could not prepare file: "+e.getMessage()); }
    }

    private void showSender(String name, long size) throws WriterException {
        sending=true; frameIndex=0;
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24,36,24,24); root.setGravity(Gravity.CENTER_HORIZONTAL);
        status=text(name+" • "+human(size),16); status.setGravity(Gravity.CENTER); root.addView(status);
        qrImage=new ImageView(this); root.addView(qrImage,new LinearLayout.LayoutParams(-1,0,1));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(frames.size()); root.addView(progress,wide());
        Button stop=button("Stop / Back"); stop.setOnClickListener(v->showHome()); root.addView(stop,wide());
        setContentView(root);
        WindowManager.LayoutParams lp=getWindow().getAttributes(); lp.screenBrightness=1.0f; getWindow().setAttributes(lp);
        senderTick.run();
    }

    private final Runnable senderTick = new Runnable() {
        @Override public void run() {
            if (!sending || frames==null || frames.isEmpty()) return;
            try {
                qrImage.setImageBitmap(makeQr(frames.get(frameIndex), 900));
                status.setText("Frame "+(frameIndex+1)+" / "+frames.size()+"  •  keep screens facing each other");
                progress.setProgress(frameIndex+1);
                frameIndex=(frameIndex+1)%frames.size();
                handler.postDelayed(this, 260);
            } catch (WriterException e) { toast("QR error"); stopSender(); }
        }
    };

    private void stopSender() { sending=false; handler.removeCallbacks(senderTick); }

    private Bitmap makeQr(String value, int size) throws WriterException {
        BitMatrix m=new com.google.zxing.MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE,size,size);
        Bitmap b=Bitmap.createBitmap(size,size,Bitmap.Config.RGB_565); int[] p=new int[size*size];
        for(int y=0;y<size;y++) for(int x=0;x<size;x++) p[y*size+x]=m.get(x,y)?Color.BLACK:Color.WHITE;
        b.setPixels(p,0,size,0,0,size,size); return b;
    }

    private void startReceive() {
        if (checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA},REQ_CAMERA); return;
        }
        showReceiver();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==REQ_CAMERA && grantResults.length>0 && grantResults[0]==PackageManager.PERMISSION_GRANTED) showReceiver();
        else if(requestCode==REQ_CAMERA) toast("Camera permission is required to receive files");
    }

    private void showReceiver() {
        resetReceiver();
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(16,24,16,16);
        status=text("Point camera at sender QR",16); status.setGravity(Gravity.CENTER); root.addView(status);
        cameraView=new SurfaceView(this); root.addView(cameraView,new LinearLayout.LayoutParams(-1,0,1));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); root.addView(progress,wide());
        Button back=button("Stop / Back"); back.setOnClickListener(v->showHome()); root.addView(back,wide()); setContentView(root);
        reader=new MultiFormatReader();
        Map<DecodeHintType,Object> hints=new HashMap<>(); hints.put(DecodeHintType.TRY_HARDER,Boolean.TRUE); reader.setHints(hints);
        cameraView.getHolder().addCallback(new SurfaceHolder.Callback(){
            public void surfaceCreated(SurfaceHolder h){ openCamera(h); }
            public void surfaceChanged(SurfaceHolder h,int f,int w,int he){}
            public void surfaceDestroyed(SurfaceHolder h){ stopCamera(); }
        });
    }

    private void openCamera(SurfaceHolder holder) {
        try {
            camera=Camera.open(); camera.setDisplayOrientation(90); Camera.Parameters p=camera.getParameters();
            if(p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            Camera.Size best=p.getPreviewSize(); for(Camera.Size s:p.getSupportedPreviewSizes()) if(s.width*s.height>best.width*best.height && s.width<=1920) best=s;
            p.setPreviewSize(best.width,best.height); camera.setParameters(p); camera.setPreviewDisplay(holder);
            camera.setPreviewCallback((data,cam)->decodeFrame(data,cam)); camera.startPreview();
        } catch(Exception e){ toast("Camera error: "+e.getMessage()); }
    }

    private long lastDecode=0;
    private void decodeFrame(byte[] data, Camera cam) {
        long now=System.currentTimeMillis(); if(now-lastDecode<90) return; lastDecode=now;
        Camera.Size s=cam.getParameters().getPreviewSize();
        PlanarYUVLuminanceSource src=new PlanarYUVLuminanceSource(data,s.width,s.height,0,0,s.width,s.height,false);
        try { Result r=reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src))); handlePayload(r.getText()); }
        catch(NotFoundException ignored){} finally { reader.reset(); }
    }

    private void handlePayload(String payload) {
        if(!payload.startsWith("OT1|")) return;
        try {
            String[] a=payload.split("\\|",7); if(a.length<4) return; String sid=a[1],type=a[2];
            if("M".equals(type)) {
                if(a.length<7) return; int total=Integer.parseInt(a[3]);
                if(receiveSession!=null && !receiveSession.equals(sid)) return;
                receiveSession=sid; receiveTotal=total; receiveName=new String(android.util.Base64.decode(a[4], android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP),StandardCharsets.UTF_8); receiveSize=Long.parseLong(a[5]); receiveSha=a[6];
                progress.setMax(receiveTotal); status.setText("Receiving "+receiveName+" • "+receiveChunks.size()+"/"+receiveTotal);
            } else if("D".equals(type)) {
                if(a.length<6 || receiveSession==null || !receiveSession.equals(sid)) return;
                int idx=Integer.parseInt(a[3]); if(idx<0 || idx>=receiveTotal || receiveChunks.containsKey(idx)) return;
                byte[] chunk=android.util.Base64.decode(a[5], android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP); CRC32 crc=new CRC32(); crc.update(chunk);
                if(!Long.toHexString(crc.getValue()).equalsIgnoreCase(a[4])) return;
                receiveChunks.put(idx,chunk); progress.setProgress(receiveChunks.size()); status.setText("Receiving "+receiveName+" • "+receiveChunks.size()+"/"+receiveTotal);
                if(receiveChunks.size()==receiveTotal) finishReceive();
            }
        } catch(Exception ignored){}
    }

    private void finishReceive() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int i=0;i<receiveTotal;i++){ byte[] c=receiveChunks.get(i); if(c==null)return; out.write(c); }
        byte[] all=out.toByteArray(); if(all.length!=receiveSize) { status.setText("Size check failed"); return; }
        String sha=hex(MessageDigest.getInstance("SHA-256").digest(all)); if(!sha.equalsIgnoreCase(receiveSha)){ status.setText("SHA-256 verification failed"); return; }
        completedBytes=all; stopCamera(); status.setText("Verified ✓  Choose where to save "+receiveName);
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT); i.setType("application/octet-stream"); i.putExtra(Intent.EXTRA_TITLE,receiveName); startActivityForResult(i,REQ_SAVE);
    }

    private void saveCompleted(Uri uri) {
        try(OutputStream o=getContentResolver().openOutputStream(uri)){ if(o==null)throw new IOException("No output stream"); o.write(completedBytes); o.flush(); toast("Saved successfully"); completedBytes=null; showHome(); }
        catch(Exception e){ toast("Save failed: "+e.getMessage()); }
    }

    private void resetReceiver(){ receiveSession=null; receiveName=null; receiveSha=null; receiveTotal=-1; receiveChunks.clear(); completedBytes=null; }
    private void stopCamera(){ if(camera!=null){ try{camera.setPreviewCallback(null);camera.stopPreview();camera.release();}catch(Exception ignored){} camera=null; } }

    @Override protected void onPause(){ super.onPause(); if(camera!=null) stopCamera(); }
    @Override public void onBackPressed(){ showHome(); }

    private byte[] readAll(Uri uri) throws IOException {
        try(InputStream in=getContentResolver().openInputStream(uri); ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)throw new IOException("Cannot open file"); byte[] buf=new byte[8192]; int n; long total=0;
            while((n=in.read(buf))!=-1){ total+=n; if(total>MAX_FILE_BYTES)throw new IOException("File exceeds 20 MB prototype limit"); out.write(buf,0,n); } return out.toByteArray();
        }
    }

    private String queryName(Uri uri){ String name="received_file"; Cursor c=null; try{ c=getContentResolver().query(uri,null,null,null,null); if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if(i>=0)name=c.getString(i);} }catch(Exception ignored){}finally{if(c!=null)c.close();} return name; }
    private String human(long b){ if(b<1024)return b+" B"; if(b<1024*1024)return String.format(Locale.US,"%.1f KB",b/1024.0); return String.format(Locale.US,"%.2f MB",b/(1024.0*1024.0)); }
    private static String hex(byte[] b){ StringBuilder s=new StringBuilder(); for(byte x:b)s.append(String.format(Locale.US,"%02x",x)); return s.toString(); }
    private void toast(String s){ runOnUiThread(()->Toast.makeText(this,s,Toast.LENGTH_LONG).show()); }
}
