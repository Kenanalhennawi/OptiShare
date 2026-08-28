package com.kenan.optishare.transfer;

import android.content.Context;
import android.net.Uri;

import com.kenan.optishare.device.DeviceIdentityKey;
import com.kenan.optishare.model.TransferItem;
import com.kenan.optishare.protocol.BatchManifest;
import com.kenan.optishare.protocol.SessionWire;
import com.kenan.optishare.storage.DownloadStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Experimental two-stream transport for one large trusted LAN file.
 *
 * Each stripe owns a non-overlapping byte range and writes to the same private partial file using
 * RandomAccessFile. The receiver publishes only after both stripes finish and the complete SHA-256
 * matches the normal manifest. Any stripe failure aborts the striped attempt so the caller can fall
 * back to the regular resumable TransferEngine.
 */
public final class StripedTransferEngine {
    public static final long MIN_FILE_BYTES = 32L * 1024L * 1024L;
    private static final int BUFFER = 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int SOCKET_TIMEOUT_MS = 30000;
    private static final long SESSION_TIMEOUT_MS = 120000L;

    public interface Listener {
        void onProgress(long done, long total, double bytesPerSecond);
        void onIncoming(String sessionId, String name, long totalBytes, String fingerprint,
                        Approval approval);
        void onCompleted(String sessionId, Uri publishedUri, long durationMs,
                         double bytesPerSecond);
    }

    public interface Approval { void decide(boolean accepted); }

    private static final ConcurrentHashMap<String, ReceiveState> STATES = new ConcurrentHashMap<>();

    private final Context context;
    private final DownloadStore store;

    public StripedTransferEngine(Context context) {
        this.context = context.getApplicationContext();
        this.store = new DownloadStore(this.context);
    }

    public void send(String host, int port, BatchManifest manifest, TransferItem item,
                     String expectedFingerprint, Listener listener) throws Exception {
        if (manifest == null || item == null || manifest.getEntries().size() != 1) {
            throw new IllegalArgumentException("Striped transfer requires exactly one file");
        }
        BatchManifest.Entry entry = manifest.getEntries().get(0);
        if (entry.size < MIN_FILE_BYTES || entry.size != item.getSize()) {
            throw new IllegalArgumentException("File is not eligible for striped transfer");
        }
        if (expectedFingerprint == null || expectedFingerprint.trim().isEmpty()) {
            throw new SecurityException("Trusted peer identity is required for striped transfer");
        }

        long split = alignSplit(entry.size);
        AtomicLong combined = new AtomicLong(0L);
        long started = System.nanoTime();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> a = pool.submit(() -> {
                try { sendStripe(host, port, manifest, item, entry, 0, 0L, split,
                        expectedFingerprint, combined, started, listener); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            java.util.concurrent.Future<?> b = pool.submit(() -> {
                try { sendStripe(host, port, manifest, item, entry, 1, split, entry.size,
                        expectedFingerprint, combined, started, listener); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            try {
                a.get(SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                b.get(SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception error) {
                a.cancel(true); b.cancel(true);
                Throwable cause = error.getCause();
                if (cause instanceof RuntimeException && cause.getCause() instanceof Exception) {
                    throw (Exception) cause.getCause();
                }
                throw error;
            }
            long durationMs = Math.max(1L, Math.round((System.nanoTime() - started) / 1_000_000.0));
            double speed = entry.size / Math.max(0.001, durationMs / 1000.0);
            listener.onCompleted(manifest.getSessionId(), null, durationMs, speed);
        } finally {
            pool.shutdownNow();
        }
    }

    private void sendStripe(String host, int port, BatchManifest manifest, TransferItem item,
                            BatchManifest.Entry entry, int stripe, long start, long end,
                            String expectedFingerprint, AtomicLong combined, long started,
                            Listener listener) throws Exception {
        try (Socket socket = new Socket()) {
            tune(socket);
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BUFFER));
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BUFFER))) {
                SessionWire.Handshake handshake = SessionWire.clientHandshake(in, out);
                String peer = exchangeClientIdentity(in, out, handshake);
                if (!expectedFingerprint.equals(peer)) throw new SecurityException("Striped peer identity mismatch");
                SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_STRIPE_BEGIN,
                        encodeBegin(manifest.getSessionId(), entry, stripe, start, end));
                SessionWire.Frame ready = SessionWire.readFrame(in, handshake.crypto);
                if (ready.type == SessionWire.TYPE_ERROR) throw new java.io.IOException("Striped transfer declined");
                if (ready.type != SessionWire.TYPE_ACK) throw new java.io.IOException("Striped receiver not ready");

                InputStream raw = context.getContentResolver().openInputStream(item.getUri());
                if (raw == null) throw new java.io.IOException("Cannot open striped source file");
                try (BufferedInputStream source = new BufferedInputStream(raw, BUFFER)) {
                    skipFully(source, start);
                    byte[] buffer = new byte[BUFFER];
                    long offset = start;
                    while (offset < end) {
                        int want = (int) Math.min(buffer.length, end - offset);
                        int n = readAtMost(source, buffer, want);
                        if (n <= 0) throw new java.io.IOException("Unexpected EOF during striped transfer");
                        SessionWire.writeFrameBuffered(out, handshake.crypto, SessionWire.TYPE_STRIPE_DATA,
                                SessionWire.encodeChunk(entry.id, offset, buffer, n));
                        offset += n;
                        long done = combined.addAndGet(n);
                        double seconds = Math.max(0.001, (System.nanoTime() - started) / 1_000_000_000.0);
                        listener.onProgress(done, entry.size, done / seconds);
                    }
                    out.flush();
                }
                SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_STRIPE_DONE,
                        SessionWire.encodeAck(entry.id, end));
                SessionWire.Frame done = SessionWire.readFrame(in, handshake.crypto);
                if (done.type != SessionWire.TYPE_ACK) throw new java.io.IOException("Striped completion not verified");
                SessionWire.Ack ack = SessionWire.decodeAck(done.payload);
                if (!entry.id.equals(ack.fileId) || ack.offset != entry.size) {
                    throw new java.io.IOException("Invalid striped completion acknowledgement");
                }
            }
        }
    }

    public void receive(Socket socket, TrustedDeviceStore trustedStore, Listener listener) throws Exception {
        tune(socket);
        String stateKey = null;
        ReceiveState state = null;
        try (Socket local = socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(local.getInputStream(), BUFFER));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(local.getOutputStream(), BUFFER))) {
            SessionWire.Handshake handshake = SessionWire.serverHandshake(in, out);
            String fingerprint = exchangeServerIdentity(in, out, handshake);
            if (fingerprint == null || !trustedStore.isTrusted(fingerprint)) {
                throw new SecurityException("Striped transfer requires a trusted device");
            }
            SessionWire.Frame beginFrame = SessionWire.readFrame(in, handshake.crypto);
            if (beginFrame.type != SessionWire.TYPE_STRIPE_BEGIN) {
                throw new java.io.IOException("Expected striped transfer begin frame");
            }
            Begin begin = decodeBegin(beginFrame.payload);
            stateKey = begin.sessionId + ":" + begin.fileId;
            final ReceiveState created = new ReceiveState(context, store, begin, fingerprint);
            state = STATES.putIfAbsent(stateKey, created);
            if (state == null) state = created;
            state.validate(begin, fingerprint);
            if (state.approvalStarted.compareAndSet(false, true)) {
                ReceiveState approvalState = state;
                listener.onIncoming(begin.sessionId, begin.name, begin.totalSize, fingerprint,
                        accepted -> approvalState.decide(accepted));
            }
            if (!state.awaitApproval()) {
                SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_ERROR,
                        SessionWire.encodeText("DECLINED"));
                throw new SecurityException("Striped transfer declined");
            }
            SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_ACK,
                    SessionWire.encodeAck("stripe-ready", begin.stripe));

            while (true) {
                SessionWire.Frame frame = SessionWire.readFrame(in, handshake.crypto);
                if (frame.type == SessionWire.TYPE_STRIPE_DATA) {
                    SessionWire.Chunk chunk = SessionWire.decodeChunk(frame.payload);
                    if (!begin.fileId.equals(chunk.fileId)) throw new java.io.IOException("Wrong striped file id");
                    if (chunk.offset < begin.start || chunk.offset + chunk.data.length > begin.end) {
                        throw new java.io.IOException("Striped chunk outside assigned range");
                    }
                    state.write(begin.stripe, chunk.offset, chunk.data);
                    listener.onProgress(state.received.get(), begin.totalSize, state.speed());
                    continue;
                }
                if (frame.type == SessionWire.TYPE_STRIPE_DONE) {
                    SessionWire.Ack declared = SessionWire.decodeAck(frame.payload);
                    if (!begin.fileId.equals(declared.fileId) || declared.offset != begin.end) {
                        throw new java.io.IOException("Invalid striped end marker");
                    }
                    state.finishStripe(begin.stripe);
                    state.awaitPublished();
                    if (state.failure != null) throw state.failure;
                    SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_ACK,
                            SessionWire.encodeAck(begin.fileId, begin.totalSize));
                    if (state.publishedUri != null) {
                        listener.onCompleted(begin.sessionId, state.publishedUri,
                                state.durationMs(), state.speed());
                    }
                    return;
                }
                throw new java.io.IOException("Unexpected striped frame type: " + frame.type);
            }
        } catch (Exception error) {
            if (state != null) state.fail(error);
            throw error;
        } finally {
            if (stateKey != null && state != null && state.finished()) STATES.remove(stateKey, state);
        }
    }

    private static final class ReceiveState {
        final Context context;
        final DownloadStore store;
        final Begin first;
        final String fingerprint;
        final AtomicLong received = new AtomicLong();
        final AtomicInteger completed = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean approvalStarted = new java.util.concurrent.atomic.AtomicBoolean(false);
        final CountDownLatch approval = new CountDownLatch(1);
        final CountDownLatch published = new CountDownLatch(1);
        final Object fileLock = new Object();
        volatile boolean accepted;
        volatile Exception failure;
        volatile Uri publishedUri;
        final long startedNanos = System.nanoTime();

        ReceiveState(Context context, DownloadStore store, Begin first, String fingerprint) throws Exception {
            this.context = context;
            this.store = store;
            this.first = first;
            this.fingerprint = fingerprint;
            File part = store.partialFile(first.sessionId, first.fileId);
            try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) { raf.setLength(first.totalSize); }
        }
        void validate(Begin b, String fp) throws Exception {
            if (!fingerprint.equals(fp) || !first.sameFile(b) || b.stripe < 0 || b.stripe > 1) {
                throw new SecurityException("Striped session metadata mismatch");
            }
        }
        void decide(boolean value) { accepted = value; approval.countDown(); }
        boolean awaitApproval() throws InterruptedException {
            if (!approval.await(90, TimeUnit.SECONDS)) return false;
            return accepted;
        }
        void write(int stripe, long offset, byte[] data) throws Exception {
            if (failure != null) throw failure;
            synchronized (fileLock) {
                try (RandomAccessFile raf = new RandomAccessFile(store.partialFile(first.sessionId, first.fileId), "rw")) {
                    raf.seek(offset); raf.write(data);
                }
            }
            received.addAndGet(data.length);
        }
        void finishStripe(int stripe) throws Exception {
            if (failure != null) throw failure;
            int count = completed.incrementAndGet();
            if (count == 2) {
                try {
                    File part = store.partialFile(first.sessionId, first.fileId);
                    byte[] actual = sha256(part);
                    if (!MessageDigest.isEqual(first.sha256, actual)) {
                        throw new SecurityException("Striped SHA-256 verification failed");
                    }
                    publishedUri = store.publishVerified(first.sessionId, first.fileId, first.name,
                            first.mime, first.category(), first.relativePath);
                } catch (Exception e) {
                    failure = e;
                    store.discard(first.sessionId, first.fileId);
                } finally { published.countDown(); }
            }
        }
        void awaitPublished() throws Exception {
            if (!published.await(120, TimeUnit.SECONDS)) throw new java.io.IOException("Striped publish timed out");
        }
        void fail(Exception e) {
            if (failure == null) failure = e;
            store.discard(first.sessionId, first.fileId);
            approval.countDown(); published.countDown();
        }
        boolean finished() { return published.getCount() == 0; }
        long durationMs() { return Math.max(1L, Math.round((System.nanoTime()-startedNanos)/1_000_000.0)); }
        double speed() { return received.get() / Math.max(0.001, (System.nanoTime()-startedNanos)/1_000_000_000.0); }
    }

    private static final class Begin {
        final String sessionId, fileId, name, mime, relativePath;
        final long totalSize, start, end;
        final int categoryOrdinal, stripe;
        final byte[] sha256;
        Begin(String sessionId, String fileId, String name, String mime, String relativePath,
              long totalSize, long start, long end, int categoryOrdinal, int stripe, byte[] sha256) {
            this.sessionId=sessionId; this.fileId=fileId; this.name=name; this.mime=mime;
            this.relativePath=relativePath; this.totalSize=totalSize; this.start=start; this.end=end;
            this.categoryOrdinal=categoryOrdinal; this.stripe=stripe; this.sha256=sha256;
        }
        boolean sameFile(Begin b) {
            return sessionId.equals(b.sessionId) && fileId.equals(b.fileId) && name.equals(b.name)
                    && totalSize==b.totalSize && categoryOrdinal==b.categoryOrdinal
                    && java.util.Objects.equals(mime,b.mime)
                    && java.util.Objects.equals(relativePath,b.relativePath)
                    && Arrays.equals(sha256,b.sha256);
        }
        TransferItem.Category category() {
            TransferItem.Category[] values=TransferItem.Category.values();
            return categoryOrdinal>=0&&categoryOrdinal<values.length?values[categoryOrdinal]:TransferItem.Category.OTHER;
        }
    }

    private static byte[] encodeBegin(String sessionId, BatchManifest.Entry e, int stripe,
                                      long start, long end) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream out=new DataOutputStream(bytes);
        out.writeUTF(sessionId); out.writeUTF(e.id); out.writeUTF(e.name);
        out.writeUTF(e.mime==null?"":e.mime); out.writeUTF(e.relativePath==null?"":e.relativePath);
        out.writeLong(e.size); out.writeLong(start); out.writeLong(end);
        out.writeInt(e.category==null?TransferItem.Category.OTHER.ordinal():e.category.ordinal());
        out.writeInt(stripe); out.writeInt(e.sha256.length); out.write(e.sha256); out.flush();
        return bytes.toByteArray();
    }

    private static Begin decodeBegin(byte[] payload) throws Exception {
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(payload));
        String session=in.readUTF(), id=in.readUTF(), name=in.readUTF(), mime=in.readUTF(), relative=in.readUTF();
        long total=in.readLong(), start=in.readLong(), end=in.readLong(); int category=in.readInt(), stripe=in.readInt();
        int hashLen=in.readInt(); if(hashLen!=32)throw new java.io.IOException("Invalid striped SHA-256 length");
        byte[] hash=new byte[hashLen]; in.readFully(hash);
        if(in.available()!=0||session.isEmpty()||id.isEmpty()||name.isEmpty()||total<MIN_FILE_BYTES
                ||start<0||end<=start||end>total||stripe<0||stripe>1)throw new java.io.IOException("Invalid striped metadata");
        return new Begin(session,id,name,mime.isEmpty()?null:mime,relative.isEmpty()?null:relative,total,start,end,category,stripe,hash);
    }

    private static String exchangeClientIdentity(DataInputStream in, DataOutputStream out,
                                                  SessionWire.Handshake handshake) throws Exception {
        byte[] challenge=handshake.crypto.sessionFingerprint();
        writeLocalIdentity(out,handshake,challenge);
        SessionWire.Frame frame=SessionWire.readFrame(in,handshake.crypto);
        if(frame.type!=SessionWire.TYPE_IDENTITY)throw new java.io.IOException("Expected peer identity");
        return verifyPeerIdentity(SessionWire.decodeIdentity(frame.payload),challenge);
    }
    private static String exchangeServerIdentity(DataInputStream in, DataOutputStream out,
                                                  SessionWire.Handshake handshake) throws Exception {
        byte[] challenge=handshake.crypto.sessionFingerprint();
        SessionWire.Frame frame=SessionWire.readFrame(in,handshake.crypto);
        if(frame.type!=SessionWire.TYPE_IDENTITY)throw new java.io.IOException("Expected peer identity");
        String peer=verifyPeerIdentity(SessionWire.decodeIdentity(frame.payload),challenge);
        writeLocalIdentity(out,handshake,challenge); return peer;
    }
    private static void writeLocalIdentity(DataOutputStream out,SessionWire.Handshake handshake,byte[] challenge)throws Exception{
        if(!DeviceIdentityKey.supported()){SessionWire.writeFrame(out,handshake.crypto,SessionWire.TYPE_IDENTITY,SessionWire.encodeIdentity(false,null,null));return;}
        DeviceIdentityKey local=new DeviceIdentityKey();
        SessionWire.writeFrame(out,handshake.crypto,SessionWire.TYPE_IDENTITY,SessionWire.encodeIdentity(true,local.publicKeyEncoded(),local.sign(challenge)));
    }
    private static String verifyPeerIdentity(SessionWire.Identity identity,byte[] challenge)throws Exception{
        if(!identity.supported)return null;
        if(!DeviceIdentityKey.verify(identity.publicKey,challenge,identity.signature))throw new SecurityException("Peer identity signature invalid");
        return DeviceIdentityKey.fingerprint(identity.publicKey);
    }

    private static long alignSplit(long size) {
        long half=size/2L; long align=1024L*1024L; long split=(half/align)*align;
        return Math.max(align, Math.min(size-align, split));
    }
    private static void tune(Socket socket)throws Exception{socket.setTcpNoDelay(true);socket.setKeepAlive(true);socket.setSoTimeout(SOCKET_TIMEOUT_MS);try{socket.setSendBufferSize(4*BUFFER);socket.setReceiveBufferSize(4*BUFFER);}catch(Exception ignored){}}
    private static void skipFully(InputStream in,long amount)throws Exception{long left=amount;while(left>0){long n=in.skip(left);if(n>0){left-=n;continue;}if(in.read()<0)throw new java.io.IOException("EOF while seeking striped source");left--;}}
    private static int readAtMost(InputStream in,byte[] b,int want)throws Exception{int total=0;while(total<want){int n=in.read(b,total,want-total);if(n<0)break;total+=n;}return total;}
    private static byte[] sha256(File file)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[BUFFER];try(InputStream in=new java.io.FileInputStream(file)){int n;while((n=in.read(b))!=-1)d.update(b,0,n);}return d.digest();}
}
