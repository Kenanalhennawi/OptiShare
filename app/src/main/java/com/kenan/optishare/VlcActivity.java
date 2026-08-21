package com.kenan.optishare;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;

public class VlcActivity extends ComponentActivity {
    private static final int REQ_CAMERA = 501;
    private static final int GRID_W = 80;
    private static final int GRID_H = 45;
    private static final int MARK = 6;
    private static final int BORDER = 2;
    private static final int HEADER_BITS = 96;
    private static final int FRAME_MS = 66;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();

    private ProcessCameraProvider cameraProvider;
    private PreviewView previewView;
    private TextView stateText, metricsText;
    private VlcPatternView patternView;
    private boolean transmitting;
    private int txFrame;

    private long rxStart;
    private long lastFrameAt;
    private int seenFrames;
    private int geometryHits;
    private int validFrames;
    private int crcErrors;
    private int duplicateFrames;
    private int lastDecodedFrame = -1;
    private double lastContrast;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showMenu();
    }

    @Override
    protected void onDestroy() {
        transmitting = false;
        ui.removeCallbacks(txTick);
        if (cameraProvider != null) cameraProvider.unbindAll();
        cameraExecutor.shutdownNow();
        super.onDestroy();
    }

    private TextView tv(String text, int size, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(18, 12, 18, 12);
        return t;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(17);
        return b;
    }

    private void showMenu() {
        transmitting = false;
        ui.removeCallbacks(txTick);
        if (cameraProvider != null) cameraProvider.unbindAll();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(38, 28, 38, 28);
        root.setBackgroundColor(Color.rgb(7,17,31));

        TextView title = tv("OptiShare VLC Lab v0.5", 28, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView expl = tv(
                "Phase 1: prove that phone-to-phone optical decoding is reliable.\n" +
                "Black/white only • large cells • alignment markers • live diagnostics.\n\n" +
                "When Valid Frames rise continuously, we increase density and add file payload.",
                15, Color.rgb(160,205,225));
        expl.setGravity(Gravity.CENTER);
        root.addView(expl);

        Button send = button("Transmit test signal");
        send.setOnClickListener(v -> startTransmitter());
        root.addView(send, new LinearLayout.LayoutParams(-1,-2));

        Button recv = button("Receive / Diagnostics");
        recv.setOnClickListener(v -> startReceiver());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1,-2);
        rp.setMargins(0,16,0,0);
        root.addView(recv, rp);

        TextView credit = tv("Designed & developed by Kenan Alhennawi", 13, Color.rgb(56,189,248));
        credit.setGravity(Gravity.CENTER);
        root.addView(credit);
        setContentView(root);
    }

    private void startTransmitter() {
        transmitting = true;
        txFrame = 0;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1.0f;
        getWindow().setAttributes(lp);

        FrameLayout root = new FrameLayout(this);
        patternView = new VlcPatternView();
        root.addView(patternView, new FrameLayout.LayoutParams(-1,-1));

        TextView info = tv("VLC TEST • tap to stop", 13, Color.WHITE);
        info.setBackgroundColor(0x99000000);
        info.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        root.addView(info, ip);
        info.setOnClickListener(v -> showMenu());

        setContentView(root);
        txTick.run();
    }

    private final Runnable txTick = new Runnable() {
        @Override public void run() {
            if (!transmitting || patternView == null) return;
            txFrame++;
            patternView.setFrame(txFrame);
            ui.postDelayed(this, FRAME_MS);
        }
    };

    private void startReceiver() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        showReceiver();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(requestCode,p,r);
        if (requestCode == REQ_CAMERA && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) showReceiver();
    }

    private void showReceiver() {
        seenFrames = geometryHits = validFrames = crcErrors = duplicateFrames = 0;
        lastDecodedFrame = -1;
        rxStart = System.currentTimeMillis();
        lastFrameAt = rxStart;

        FrameLayout root = new FrameLayout(this);
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(-1,-1));
        root.addView(new GuideView(), new FrameLayout.LayoutParams(-1,-1));

        LinearLayout hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.VERTICAL);
        hud.setPadding(12,8,12,8);
        hud.setBackgroundColor(0xCC07111F);

        stateText = tv("SEARCHING — fit the full sender screen inside the cyan frame", 15, Color.WHITE);
        stateText.setGravity(Gravity.CENTER);
        hud.addView(stateText);

        metricsText = tv("Seen 0 • Geometry 0 • Valid 0 • CRC 0", 13, Color.rgb(170,210,230));
        metricsText.setGravity(Gravity.CENTER);
        hud.addView(metricsText);

        TextView stop = tv("Tap here to stop", 13, Color.rgb(56,189,248));
        stop.setGravity(Gravity.CENTER);
        stop.setOnClickListener(v -> showMenu());
        hud.addView(stop);

        root.addView(hud, new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        setContentView(root);
        bindCamera();
    }

    private void bindCamera() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                cameraProvider = f.get();
                cameraProvider.unbindAll();

                Preview p = new Preview.Builder()
                        .setTargetResolution(new Size(1280,720)).build();
                p.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis a = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280,720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                a.setAnalyzer(cameraExecutor, this::analyze);
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, a);
            } catch (Throwable t) {
                ui.post(() -> stateText.setText("CAMERA ERROR: " + t.getMessage()));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyze(ImageProxy image) {
        try {
            seenFrames++;
            ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
            ByteBuffer y = yPlane.getBuffer();
            int row = yPlane.getRowStride();
            int pix = yPlane.getPixelStride();
            int w = image.getWidth();
            int h = image.getHeight();

            float left = w * 0.05f, top = h * 0.10f, width = w * 0.90f, height = h * 0.80f;
            Sample s = new Sample(y,row,pix,w,h,left,top,width,height);

            double dark = markerMean(s, 1,1);
            double white = markerMean(s, GRID_W-MARK-1,1);
            double dark2 = markerMean(s,1,GRID_H-MARK-1);
            double white2 = markerMean(s,GRID_W-MARK-1,GRID_H-MARK-1);
            double dAvg = (dark+dark2)/2.0;
            double wAvg = (white+white2)/2.0;
            lastContrast = wAvg - dAvg;

            boolean geometry = lastContrast > 55 && dAvg < 120 && wAvg > 135;
            if (!geometry) {
                postDiag("SEARCHING", Color.WHITE);
                return;
            }
            geometryHits++;

            int threshold = (int)((dAvg+wAvg)/2.0);
            byte[] header = new byte[12];
            for (int bit=0; bit<HEADER_BITS; bit++) {
                int cell = dataCellForBit(bit);
                int gx = cell % GRID_W;
                int gy = cell / GRID_W;
                int lum = s.atCell(gx,gy);
                if (lum > threshold) header[bit>>3] |= (byte)(1 << (7-(bit&7)));
            }

            ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
            int magic = b.getInt();
            int frame = b.getInt();
            int inverse = b.getInt();
            if (magic != 0x4F563530 || inverse != ~frame) {
                postDiag("SIGNAL DETECTED • decoding", Color.rgb(255,210,80));
                return;
            }

            CRC32 crc = new CRC32();
            byte[] first8 = new byte[8];
            ByteBuffer.wrap(first8).order(ByteOrder.BIG_ENDIAN).putInt(magic).putInt(frame);
            crc.update(first8);
            int expected = readCrc16(s, threshold);
            if (((int)crc.getValue() & 0xffff) != expected) {
                crcErrors++;
                postDiag("LOCKED • CRC errors", Color.rgb(255,170,80));
                return;
            }

            if (frame == lastDecodedFrame) duplicateFrames++;
            else {
                validFrames++;
                lastDecodedFrame = frame;
            }
            postDiag("LOCKED ✓ optical frames decoding", Color.rgb(80,255,150));
        } catch (Throwable ignored) {
        } finally {
            image.close();
        }
    }

    private int dataCellForBit(int bit) {
        int count=0;
        for (int y=BORDER+MARK; y<GRID_H-BORDER-MARK; y++) {
            for (int x=BORDER+MARK; x<GRID_W-BORDER-MARK; x++) {
                if (count == bit) return y*GRID_W+x;
                count++;
            }
        }
        return 0;
    }

    private int readCrc16(Sample s, int threshold) {
        int v=0;
        int y=GRID_H/2;
        int start=GRID_W/2-8;
        for (int i=0;i<16;i++) {
            v=(v<<1) | (s.atCell(start+i,y)>threshold?1:0);
        }
        return v;
    }

    private double markerMean(Sample s, int gx, int gy) {
        double sum=0; int n=0;
        for (int yy=gy; yy<gy+MARK-2; yy+=2) {
            for (int xx=gx; xx<gx+MARK-2; xx+=2) {
                sum += s.atCell(xx,yy); n++;
            }
        }
        return n==0?0:sum/n;
    }

    private void postDiag(String state, int color) {
        long now=System.currentTimeMillis();
        if (now-lastFrameAt < 100) return;
        lastFrameAt=now;
        double secs=Math.max(0.1,(now-rxStart)/1000.0);
        double fps=seenFrames/secs;
        ui.post(() -> {
            if (stateText == null) return;
            stateText.setText(state);
            stateText.setTextColor(color);
            metricsText.setText(String.format(Locale.US,
                    "Camera %.1f fps • Seen %d • Geometry %d • Valid %d • CRC %d • Dup %d • Contrast %.0f",
                    fps,seenFrames,geometryHits,validFrames,crcErrors,duplicateFrames,lastContrast));
        });
    }

    private final class VlcPatternView extends View {
        private final Bitmap bm = Bitmap.createBitmap(GRID_W,GRID_H,Bitmap.Config.ARGB_8888);
        private final int[] px = new int[GRID_W*GRID_H];
        private final Paint paint = new Paint();

        VlcPatternView() {
            super(VlcActivity.this);
            paint.setAntiAlias(false);
            paint.setFilterBitmap(false);
        }

        void setFrame(int frame) {
            for (int i=0;i<px.length;i++) px[i]=Color.BLACK;

            for (int x=0;x<GRID_W;x++) {
                int c=((x/2)&1)==0?Color.WHITE:Color.BLACK;
                for(int y=0;y<BORDER;y++) px[y*GRID_W+x]=c;
                for(int y=GRID_H-BORDER;y<GRID_H;y++) px[y*GRID_W+x]=c;
            }
            for (int y=0;y<GRID_H;y++) {
                int c=((y/2)&1)==0?Color.WHITE:Color.BLACK;
                for(int x=0;x<BORDER;x++) px[y*GRID_W+x]=c;
                for(int x=GRID_W-BORDER;x<GRID_W;x++) px[y*GRID_W+x]=c;
            }

            fillMarker(1,1,Color.BLACK);
            fillMarker(GRID_W-MARK-1,1,Color.WHITE);
            fillMarker(1,GRID_H-MARK-1,Color.BLACK);
            fillMarker(GRID_W-MARK-1,GRID_H-MARK-1,Color.WHITE);

            byte[] h = new byte[12];
            ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN)
                    .putInt(0x4F563530).putInt(frame).putInt(~frame);

            for(int bit=0;bit<HEADER_BITS;bit++) {
                int cell=dataCellForBit(bit);
                int gx=cell%GRID_W, gy=cell/GRID_W;
                int val=(h[bit>>3]>>(7-(bit&7)))&1;
                px[gy*GRID_W+gx]=val==1?Color.WHITE:Color.BLACK;
            }

            CRC32 crc=new CRC32();
            byte[] first8=new byte[8];
            ByteBuffer.wrap(first8).order(ByteOrder.BIG_ENDIAN).putInt(0x4F563530).putInt(frame);
            crc.update(first8);
            int c16=(int)crc.getValue()&0xffff;
            int sy=GRID_H/2, sx=GRID_W/2-8;
            for(int i=0;i<16;i++) {
                int bit=(c16>>(15-i))&1;
                px[sy*GRID_W+sx+i]=bit==1?Color.WHITE:Color.BLACK;
            }

            int stripe=(frame%(GRID_W-20))+10;
            for(int y=GRID_H-10;y<GRID_H-7;y++) px[y*GRID_W+stripe]=Color.WHITE;

            bm.setPixels(px,0,GRID_W,0,0,GRID_W,GRID_H);
            invalidate();
        }

        private void fillMarker(int x0,int y0,int c) {
            for(int y=y0;y<Math.min(GRID_H,y0+MARK);y++)
                for(int x=x0;x<Math.min(GRID_W,x0+MARK);x++)
                    px[y*GRID_W+x]=c;
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            c.drawBitmap(bm,null,new android.graphics.Rect(0,0,getWidth(),getHeight()),paint);
        }
    }

    private final class GuideView extends View {
        private final Paint p=new Paint();
        GuideView(){super(VlcActivity.this); p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(5);p.setColor(Color.rgb(56,189,248));}
        @Override protected void onDraw(Canvas c){
            float l=getWidth()*0.05f,t=getHeight()*0.10f,r=getWidth()*0.95f,b=getHeight()*0.90f;
            c.drawRect(l,t,r,b,p);
        }
    }

    private static final class Sample {
        final ByteBuffer y; final int row,pix,w,h; final float l,t,ww,hh;
        Sample(ByteBuffer y,int row,int pix,int w,int h,float l,float t,float ww,float hh){
            this.y=y;this.row=row;this.pix=pix;this.w=w;this.h=h;this.l=l;this.t=t;this.ww=ww;this.hh=hh;
        }
        int atCell(int gx,int gy){
            int x=Math.round(l+(gx+0.5f)*ww/GRID_W);
            int yy=Math.round(t+(gy+0.5f)*hh/GRID_H);
            x=Math.max(0,Math.min(w-1,x)); yy=Math.max(0,Math.min(h-1,yy));
            int idx=yy*row+x*pix;
            if(idx<0||idx>=y.limit()) return 0;
            return y.get(idx)&0xff;
        }
    }

    @Override public void onBackPressed(){showMenu();}
}
