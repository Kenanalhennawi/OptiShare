package com.kenan.optishare;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioActivity extends Activity {
    private static final int REQ_AUDIO = 601;
    private static final int SR = 48000;
    private static final int SYMBOL_SAMPLES = 384;
    private static final double[] FREQ = {3000.0, 4500.0, 6000.0, 7500.0};
    private static final int PREAMBLE_SYMBOLS = 12;
    private static final int DATA_SYMBOLS = 24;
    private static final int PACKET_SYMBOLS = PREAMBLE_SYMBOLS + DATA_SYMBOLS;
    private static final int PACKET_SAMPLES = PACKET_SYMBOLS * SYMBOL_SAMPLES;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private AudioTrack audioTrack;
    private AudioRecord audioRecord;
    private Thread txThread;
    private Thread rxThread;
    private TextView status;
    private TextView metrics;
    private long rxStarted;
    private int validPackets;
    private int crcErrors;
    private int lastSeq = -1;
    private int duplicates;
    private double lastSnrDb;
    private double lastPeakRatio;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showMenu();
    }

    private TextView tv(String text, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextSize(sp); t.setTextColor(color); t.setPadding(18,14,18,14);
        return t;
    }

    private Button btn(String text) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextSize(17); return b;
    }

    private void showMenu() {
        stopAudio();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER);
        root.setPadding(36,30,36,30); root.setBackgroundColor(Color.rgb(7,17,31));
        TextView title = tv("OptiShare Audio Lab v0.6",28,Color.WHITE); title.setGravity(Gravity.CENTER); root.addView(title);
        TextView desc = tv("Phase 1: verify a phone-to-phone acoustic data channel.\n4-FSK • packet counter • CRC • live SNR diagnostics.\n\nPut the phones 20–50 cm apart. Use ~70% volume.",15,Color.rgb(155,205,230));
        desc.setGravity(Gravity.CENTER); root.addView(desc);
        Button tx = btn("Transmit audio test"); tx.setOnClickListener(v -> startTx()); root.addView(tx,new LinearLayout.LayoutParams(-1,-2));
        Button rx = btn("Receive / Diagnostics"); rx.setOnClickListener(v -> requestRx());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1,-2); rp.setMargins(0,16,0,0); root.addView(rx,rp);
        TextView note = tv("This is a channel benchmark, not file transfer yet.\nIf Valid Packets rises steadily, next step is OFDM for higher throughput.",13,Color.LTGRAY);
        note.setGravity(Gravity.CENTER); root.addView(note);
        TextView credit = tv("Designed & developed by Kenan Alhennawi",13,Color.rgb(56,189,248)); credit.setGravity(Gravity.CENTER); root.addView(credit);
        setContentView(root);
    }

    private void startTx() {
        stopAudio(); running.set(true);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER);
        root.setPadding(32,32,32,32); root.setBackgroundColor(Color.rgb(7,17,31));
        status = tv("TRANSMITTING AUDIO TEST",24,Color.rgb(80,255,150)); status.setGravity(Gravity.CENTER); root.addView(status);
        metrics = tv("Starting…",16,Color.WHITE); metrics.setGravity(Gravity.CENTER); root.addView(metrics);
        Button stop = btn("Stop"); stop.setOnClickListener(v -> showMenu()); root.addView(stop,new LinearLayout.LayoutParams(-1,-2)); setContentView(root);

        int min = AudioTrack.getMinBufferSize(SR,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
        int buffer = Math.max(min,PACKET_SAMPLES*2);
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(buffer*2).setTransferMode(AudioTrack.MODE_STREAM).build();
        try {
            AudioManager am=(AudioManager)getSystemService(AUDIO_SERVICE);
            if(am!=null){int max=am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);am.setStreamVolume(AudioManager.STREAM_MUSIC,Math.max(1,(int)Math.round(max*0.70)),0);}
        } catch(Throwable ignored) {}
        audioTrack.play();

        txThread = new Thread(() -> {
            int seq=0; long started=System.currentTimeMillis();
            while(running.get()){
                short[] packet=buildPacket(seq); int off=0;
                while(running.get()&&off<packet.length){int n=audioTrack.write(packet,off,packet.length-off,AudioTrack.WRITE_BLOCKING);if(n<=0)break;off+=n;}
                seq=(seq+1)&0xffff; final int sent=seq; final double sec=Math.max(0.001,(System.currentTimeMillis()-started)/1000.0);
                ui.post(() -> {if(metrics!=null)metrics.setText(String.format(Locale.US,"Packets sent: %d\nPacket rate: %.1f/s\nCarrier band: 3.0–7.5 kHz",sent,sent/sec));});
            }
        },"OptiShare-Audio-TX"); txThread.start();
    }

    private short[] buildPacket(int seq) {
        int crc=crc16(seq); int[] symbols=new int[PACKET_SYMBOLS];
        for(int i=0;i<PREAMBLE_SYMBOLS;i++)symbols[i]=(i&1)==0?0:3;
        long payload=((long)(seq&0xffff)<<16)|(crc&0xffffL);
        for(int i=0;i<16;i++){int shift=30-(i*2);symbols[PREAMBLE_SYMBOLS+i]=(int)((payload>>shift)&3);}
        for(int i=0;i<8;i++)symbols[PREAMBLE_SYMBOLS+16+i]=symbols[PREAMBLE_SYMBOLS+i];
        short[] pcm=new short[PACKET_SAMPLES]; double amp=0.55*Short.MAX_VALUE;
        for(int s=0;s<PACKET_SYMBOLS;s++){
            double f=FREQ[symbols[s]]; int start=s*SYMBOL_SAMPLES;
            for(int i=0;i<SYMBOL_SAMPLES;i++){
                double edge=1.0; int ramp=24; if(i<ramp)edge=i/(double)ramp; else if(i>SYMBOL_SAMPLES-ramp)edge=(SYMBOL_SAMPLES-i)/(double)ramp;
                pcm[start+i]=(short)(Math.sin(2.0*Math.PI*f*i/SR)*amp*edge);
            }
        }
        return pcm;
    }

    private void requestRx() {
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_AUDIO);return;}
        startRx();
    }

    @Override public void onRequestPermissionsResult(int req,String[] p,int[] r){super.onRequestPermissionsResult(req,p,r);if(req==REQ_AUDIO&&r.length>0&&r[0]==PackageManager.PERMISSION_GRANTED)startRx();}

    private void startRx() {
        stopAudio(); running.set(true); validPackets=crcErrors=duplicates=0; lastSeq=-1; lastSnrDb=lastPeakRatio=0; rxStarted=System.currentTimeMillis();
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER);root.setPadding(28,24,28,24);root.setBackgroundColor(Color.rgb(7,17,31));
        status=tv("SEARCHING FOR AUDIO SIGNAL…",22,Color.WHITE);status.setGravity(Gravity.CENTER);root.addView(status);
        metrics=tv("Valid 0 • CRC 0 • SNR --",15,Color.rgb(160,205,230));metrics.setGravity(Gravity.CENTER);root.addView(metrics);
        Button stop=btn("Stop");stop.setOnClickListener(v -> showMenu());root.addView(stop,new LinearLayout.LayoutParams(-1,-2));setContentView(root);

        int min=AudioRecord.getMinBufferSize(SR,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);int buf=Math.max(min,PACKET_SAMPLES*4);
        try {audioRecord=new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED,SR,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,buf*2);} catch(Throwable ignored) {}
        if(audioRecord==null||audioRecord.getState()!=AudioRecord.STATE_INITIALIZED){if(audioRecord!=null)try{audioRecord.release();}catch(Throwable ignored){}audioRecord=new AudioRecord(MediaRecorder.AudioSource.DEFAULT,SR,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,buf*2);}
        if(audioRecord.getState()!=AudioRecord.STATE_INITIALIZED){status.setText("MICROPHONE INITIALIZATION FAILED");status.setTextColor(Color.RED);return;}
        audioRecord.startRecording();

        rxThread=new Thread(() -> {
            short[] ring=new short[PACKET_SAMPLES*2];int fill=0;short[] tmp=new short[SYMBOL_SAMPLES*4];
            while(running.get()){
                int n=audioRecord.read(tmp,0,tmp.length,AudioRecord.READ_BLOCKING);if(n<=0)continue;
                if(fill+n>ring.length){int keep=Math.min(fill,PACKET_SAMPLES);System.arraycopy(ring,fill-keep,ring,0,keep);fill=keep;}
                System.arraycopy(tmp,0,ring,fill,n);fill+=n;
                while(fill>=PACKET_SAMPLES){
                    DecodeResult dr=decodePacket(ring,0);
                    if(dr.valid){if(dr.seq==lastSeq)duplicates++;else{validPackets++;lastSeq=dr.seq;}lastSnrDb=dr.snrDb;lastPeakRatio=dr.peakRatio;postRxUi(true);int remain=fill-PACKET_SAMPLES;if(remain>0)System.arraycopy(ring,PACKET_SAMPLES,ring,0,remain);fill=remain;}
                    else {if(dr.lookedLikeSignal)crcErrors++;lastSnrDb=dr.snrDb;lastPeakRatio=dr.peakRatio;postRxUi(false);int step=SYMBOL_SAMPLES;int remain=fill-step;System.arraycopy(ring,step,ring,0,remain);fill=remain;}
                }
            }
        },"OptiShare-Audio-RX");rxThread.start();
    }

    private DecodeResult decodePacket(short[] pcm,int base){
        int[] sym=new int[PACKET_SYMBOLS];double signalEnergy=0,noiseEnergy=0,ratioSum=0;
        for(int s=0;s<PACKET_SYMBOLS;s++){ToneScore sc=detectSymbol(pcm,base+s*SYMBOL_SAMPLES);sym[s]=sc.best;signalEnergy+=sc.bestPower;noiseEnergy+=Math.max(1e-9,sc.secondPower);ratioSum+=sc.bestPower/Math.max(1e-9,sc.secondPower);}
        double snr=10.0*Math.log10(Math.max(1e-9,signalEnergy/Math.max(1e-9,noiseEnergy)));double ratio=ratioSum/PACKET_SYMBOLS;
        boolean preamble=true;for(int i=0;i<PREAMBLE_SYMBOLS;i++){int ex=(i&1)==0?0:3;if(sym[i]!=ex){preamble=false;break;}}
        if(!preamble)return new DecodeResult(false,false,-1,snr,ratio);
        long payload=0;for(int i=0;i<16;i++)payload=(payload<<2)|(sym[PREAMBLE_SYMBOLS+i]&3);
        int seq=(int)((payload>>16)&0xffff);int got=(int)(payload&0xffff);int calc=crc16(seq);
        boolean repeatOk=true;for(int i=0;i<8;i++){if(sym[PREAMBLE_SYMBOLS+16+i]!=sym[PREAMBLE_SYMBOLS+i]){repeatOk=false;break;}}
        boolean valid=got==calc&&repeatOk&&ratio>1.35;return new DecodeResult(valid,true,seq,snr,ratio);
    }

    private ToneScore detectSymbol(short[] pcm,int off){
        double best=-1,second=-1;int bestIdx=0;
        for(int k=0;k<FREQ.length;k++){
            double w=2.0*Math.PI*FREQ[k]/SR,re=0,im=0;
            for(int i=0;i<SYMBOL_SAMPLES;i++){double x=pcm[off+i],ang=w*i;re+=x*Math.cos(ang);im-=x*Math.sin(ang);}
            double p=re*re+im*im;if(p>best){second=best;best=p;bestIdx=k;}else if(p>second)second=p;
        }
        if(second<0)second=1;return new ToneScore(bestIdx,best,second);
    }

    private void postRxUi(boolean locked){
        long now=System.currentTimeMillis();double sec=Math.max(0.1,(now-rxStarted)/1000.0);double pps=validPackets/sec;double usefulBps=pps*16.0;
        ui.post(() -> {if(status==null||metrics==null)return;if(locked){status.setText("LOCKED ✓ AUDIO PACKETS DECODING");status.setTextColor(Color.rgb(80,255,150));}else if(lastPeakRatio>1.15){status.setText("SIGNAL DETECTED • SYNCING…");status.setTextColor(Color.rgb(255,210,80));}else{status.setText("SEARCHING FOR AUDIO SIGNAL…");status.setTextColor(Color.WHITE);}metrics.setText(String.format(Locale.US,"Valid packets: %d\nCRC/sync errors: %d\nDuplicates: %d\nSNR proxy: %.1f dB • Peak ratio: %.2f\nPacket rate: %.2f/s • decoded test payload: %.0f bit/s",validPackets,crcErrors,duplicates,lastSnrDb,lastPeakRatio,pps,usefulBps));});
    }

    private int crc16(int seq){int crc=0xffff,value=seq&0xffff;for(int b=1;b>=0;b--){int byt=(value>>(b*8))&0xff;crc^=byt<<8;for(int i=0;i<8;i++)crc=(crc&0x8000)!=0?((crc<<1)^0x1021)&0xffff:(crc<<1)&0xffff;}return crc&0xffff;}

    private void stopAudio(){running.set(false);if(audioTrack!=null){try{audioTrack.pause();}catch(Throwable ignored){}try{audioTrack.flush();}catch(Throwable ignored){}try{audioTrack.stop();}catch(Throwable ignored){}try{audioTrack.release();}catch(Throwable ignored){}audioTrack=null;}if(audioRecord!=null){try{audioRecord.stop();}catch(Throwable ignored){}try{audioRecord.release();}catch(Throwable ignored){}audioRecord=null;}if(txThread!=null){try{txThread.interrupt();}catch(Throwable ignored){}txThread=null;}if(rxThread!=null){try{rxThread.interrupt();}catch(Throwable ignored){}rxThread=null;}}

    @Override public void onBackPressed(){showMenu();}
    @Override protected void onDestroy(){stopAudio();super.onDestroy();}

    private static final class ToneScore{final int best;final double bestPower,secondPower;ToneScore(int best,double bestPower,double secondPower){this.best=best;this.bestPower=bestPower;this.secondPower=secondPower;}}
    private static final class DecodeResult{final boolean valid,lookedLikeSignal;final int seq;final double snrDb,peakRatio;DecodeResult(boolean valid,boolean lookedLikeSignal,int seq,double snrDb,double peakRatio){this.valid=valid;this.lookedLikeSignal=lookedLikeSignal;this.seq=seq;this.snrDb=snrDb;this.peakRatio=peakRatio;}}
}
