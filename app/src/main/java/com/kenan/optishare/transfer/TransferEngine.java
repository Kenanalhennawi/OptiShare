package com.kenan.optishare.transfer;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import com.kenan.optishare.model.TransferItem;
import com.kenan.optishare.protocol.BatchManifest;
import com.kenan.optishare.protocol.ResumeState;
import com.kenan.optishare.protocol.ResumeStore;
import com.kenan.optishare.protocol.ResumableProtocol;
import com.kenan.optishare.protocol.SessionWire;
import com.kenan.optishare.storage.DownloadStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Core transfer engine. UI and discovery are intentionally kept outside this class. */
public final class TransferEngine {
    public interface Listener {
        void onSecurityCode(String code);
        void onIncomingBatch(BatchManifest manifest);
        boolean acceptIncomingBatch(BatchManifest manifest);
        void onProgress(String sessionId, String fileId, String fileName, long done, long total, long batchDone, long batchTotal, double bytesPerSecond);
        void onFileCompleted(String sessionId, String fileId, Uri publishedUri);
        void onCompleted(String sessionId);
        void onError(String sessionId, Throwable error, boolean resumable);
    }

    private static final int CHUNK = 256 * 1024;
    private final Context context;
    private final ResumeStore resumeStore;
    private final DownloadStore downloadStore;

    public TransferEngine(Context context) {
        this.context = context.getApplicationContext();
        this.resumeStore = new ResumeStore(this.context);
        this.downloadStore = new DownloadStore(this.context);
    }

    public void send(Socket socket, BatchManifest manifest, List<TransferItem> items, Listener listener) throws Exception {
        if (manifest.getEntries().size() != items.size()) throw new IllegalArgumentException("Manifest/items mismatch");
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 512 * 1024));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 512 * 1024))) {
            SessionWire.Handshake handshake = SessionWire.clientHandshake(in, out);
            listener.onSecurityCode(handshake.securityCode);
            SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_MANIFEST, SessionWire.encodeManifest(manifest));
            SessionWire.Frame resumeFrame = SessionWire.readFrame(in, handshake.crypto);
            if (resumeFrame.type != SessionWire.TYPE_RESUME) throw new IOException("Expected resume response");
            Map<String, Long> receiverOffsets = SessionWire.decodeOffsets(resumeFrame.payload);

            long batchTotal = manifest.totalBytes();
            long completedBefore = 0;
            for (BatchManifest.Entry entry : manifest.getEntries()) {
                completedBefore += Math.min(entry.size, receiverOffsets.containsKey(entry.id) ? receiverOffsets.get(entry.id) : 0L);
            }

            for (int i = 0; i < items.size(); i++) {
                TransferItem item = items.get(i);
                BatchManifest.Entry entry = manifest.getEntries().get(i);
                long requested = receiverOffsets.containsKey(entry.id) ? receiverOffsets.get(entry.id) : 0L;
                long offset = ResumableProtocol.alignToChunkBoundary(Math.min(requested, entry.size), CHUNK);
                long sent = offset;
                long started = System.nanoTime();
                long batchBase = completedBefore - requested + offset;
                try (InputStream raw = context.getContentResolver().openInputStream(item.getUri());
                     BufferedInputStream source = new BufferedInputStream(raw, 512 * 1024)) {
                    skipFully(source, offset);
                    byte[] buffer = new byte[CHUNK];
                    while (sent < entry.size) {
                        int want = (int) Math.min(buffer.length, entry.size - sent);
                        int n = readAtMost(source, buffer, want);
                        if (n <= 0) throw new IOException("Unexpected end of source file: " + entry.name);
                        SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_CHUNK, SessionWire.encodeChunk(entry.id, sent, buffer, n));
                        SessionWire.Frame ackFrame = SessionWire.readFrame(in, handshake.crypto);
                        if (ackFrame.type != SessionWire.TYPE_ACK) throw new IOException("Expected chunk acknowledgement");
                        SessionWire.Ack ack = SessionWire.decodeAck(ackFrame.payload);
                        if (!entry.id.equals(ack.fileId) || ack.offset < sent + n) throw new IOException("Invalid acknowledgement");
                        sent = ack.offset;
                        double seconds = Math.max(0.001, (System.nanoTime() - started) / 1_000_000_000.0);
                        listener.onProgress(manifest.getSessionId(), entry.id, entry.name, sent, entry.size, batchBase + sent - offset, batchTotal, (sent - offset) / seconds);
                    }
                }
                SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_FILE_DONE, SessionWire.encodeText(entry.id));
                completedBefore = batchBase + entry.size - offset;
            }
            SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_BATCH_DONE, SessionWire.encodeText(manifest.getSessionId()));
            listener.onCompleted(manifest.getSessionId());
        } catch (Throwable t) {
            listener.onError(manifest.getSessionId(), t, true);
            throw t;
        }
    }

    public void receive(Socket socket, Listener listener) throws Exception {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        String sessionId = "unknown";
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 512 * 1024));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 512 * 1024))) {
            SessionWire.Handshake handshake = SessionWire.serverHandshake(in, out);
            listener.onSecurityCode(handshake.securityCode);
            SessionWire.Frame manifestFrame = SessionWire.readFrame(in, handshake.crypto);
            if (manifestFrame.type != SessionWire.TYPE_MANIFEST) throw new IOException("Expected transfer manifest");
            BatchManifest manifest = SessionWire.decodeManifest(manifestFrame.payload);
            sessionId = manifest.getSessionId();
            listener.onIncomingBatch(manifest);
            if (!listener.acceptIncomingBatch(manifest)) {
                SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_ERROR, SessionWire.encodeText("DECLINED"));
                return;
            }

            ResumeState saved = resumeStore.load(sessionId);
            Map<String, Long> offsets = new LinkedHashMap<>();
            for (BatchManifest.Entry e : manifest.getEntries()) {
                long disk = downloadStore.partialLength(sessionId, e.id);
                long persisted = saved.getConfirmedOffset(e.id);
                long safe = ResumableProtocol.alignToChunkBoundary(Math.min(disk, persisted == 0 ? disk : persisted), CHUNK);
                if (disk != safe) truncate(downloadStore.partialFile(sessionId, e.id), safe);
                offsets.put(e.id, Math.min(safe, e.size));
            }
            SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_RESUME, SessionWire.encodeOffsets(offsets));

            Map<String, BatchManifest.Entry> byId = new LinkedHashMap<>();
            long batchTotal = manifest.totalBytes();
            long batchDone = 0;
            for (BatchManifest.Entry e : manifest.getEntries()) {
                byId.put(e.id, e);
                batchDone += offsets.get(e.id);
            }
            Map<String, Long> confirmed = new LinkedHashMap<>(offsets);
            Map<String, Long> starts = new LinkedHashMap<>(offsets);
            Map<String, Long> timers = new LinkedHashMap<>();

            while (true) {
                SessionWire.Frame frame = SessionWire.readFrame(in, handshake.crypto);
                if (frame.type == SessionWire.TYPE_CHUNK) {
                    SessionWire.Chunk chunk = SessionWire.decodeChunk(frame.payload);
                    BatchManifest.Entry entry = byId.get(chunk.fileId);
                    if (entry == null) throw new IOException("Unknown file id");
                    long expected = confirmed.containsKey(entry.id) ? confirmed.get(entry.id) : 0L;
                    if (chunk.offset != expected) throw new IOException("Unexpected chunk offset");
                    if (chunk.offset + chunk.data.length > entry.size) throw new IOException("Chunk exceeds file size");
                    try (FileOutputStream target = downloadStore.openPartial(sessionId, entry.id, true)) {
                        target.write(chunk.data);
                        target.getFD().sync();
                    }
                    long next = chunk.offset + chunk.data.length;
                    confirmed.put(entry.id, next);
                    ResumeState state = new ResumeState(sessionId, System.currentTimeMillis(), confirmed);
                    resumeStore.save(state);
                    SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_ACK, SessionWire.encodeAck(entry.id, next));
                    if (!timers.containsKey(entry.id)) timers.put(entry.id, System.nanoTime());
                    long startOffset = starts.get(entry.id);
                    double seconds = Math.max(0.001, (System.nanoTime() - timers.get(entry.id)) / 1_000_000_000.0);
                    long effectiveBatchDone = batchDone + (next - startOffset);
                    listener.onProgress(sessionId, entry.id, entry.name, next, entry.size, effectiveBatchDone, batchTotal, (next - startOffset) / seconds);
                } else if (frame.type == SessionWire.TYPE_FILE_DONE) {
                    String fileId = new String(frame.payload, java.nio.charset.StandardCharsets.UTF_8);
                    BatchManifest.Entry entry = byId.get(fileId);
                    if (entry == null) throw new IOException("Unknown completed file id");
                    File partial = downloadStore.partialFile(sessionId, fileId);
                    if (partial.length() != entry.size) throw new IOException("Incomplete file at completion");
                    byte[] actual = sha256(partial);
                    if (!java.util.Arrays.equals(actual, entry.sha256)) {
                        downloadStore.discard(sessionId, fileId);
                        confirmed.put(fileId, 0L);
                        resumeStore.save(new ResumeState(sessionId, System.currentTimeMillis(), confirmed));
                        throw new IOException("SHA-256 verification failed: " + entry.name);
                    }
                    Uri published = downloadStore.publishVerified(sessionId, fileId, entry.name, entry.mime, entry.category);
                    batchDone += entry.size - starts.get(fileId);
                    confirmed.put(fileId, entry.size);
                    resumeStore.save(new ResumeState(sessionId, System.currentTimeMillis(), confirmed));
                    listener.onFileCompleted(sessionId, fileId, published);
                } else if (frame.type == SessionWire.TYPE_BATCH_DONE) {
                    resumeStore.clear(sessionId);
                    downloadStore.clearSession(sessionId);
                    listener.onCompleted(sessionId);
                    return;
                } else if (frame.type == SessionWire.TYPE_ERROR) {
                    throw new IOException("Remote error: " + new String(frame.payload, java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    throw new IOException("Unexpected frame type: " + frame.type);
                }
            }
        } catch (Throwable t) {
            listener.onError(sessionId, t, true);
            throw t;
        }
    }

    public BatchManifest buildManifest(List<TransferItem> items) throws Exception {
        List<BatchManifest.Entry> entries = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        byte[] buffer = new byte[512 * 1024];
        for (TransferItem item : items) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long read = 0;
            try (InputStream in = resolver.openInputStream(item.getUri())) {
                if (in == null) throw new IOException("Cannot open " + item.getName());
                int n;
                while ((n = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, n);
                    read += n;
                }
            }
            if (read != item.getSize()) throw new IOException("File size changed while preparing transfer: " + item.getName());
            entries.add(new BatchManifest.Entry(item.getId(), item.getName(), item.getMimeType(), item.getSize(), item.getCategory(), digest.digest()));
        }
        return new BatchManifest(entries);
    }

    private static byte[] sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[512 * 1024];
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return digest.digest();
    }

    private static void truncate(File file, long length) throws IOException {
        if (!file.exists()) return;
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) { raf.setLength(length); }
    }

    private static void skipFully(InputStream in, long amount) throws IOException {
        long remaining = amount;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) { remaining -= skipped; continue; }
            if (in.read() == -1) throw new IOException("Unexpected EOF while seeking source");
            remaining--;
        }
    }

    private static int readAtMost(InputStream in, byte[] buffer, int want) throws IOException {
        int total = 0;
        while (total < want) {
            int n = in.read(buffer, total, want - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }
}
