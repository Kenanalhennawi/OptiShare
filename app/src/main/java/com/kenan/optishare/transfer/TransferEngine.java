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

/**
 * Encrypted, resumable, multi-file transfer engine.
 *
 * A 1 MiB durable checkpoint balances modern Wi-Fi Direct throughput with bounded retransmission
 * after a disconnect. Every acknowledged checkpoint has been written, fsynced and persisted by
 * the receiver before the ACK is returned to the sender.
 */
public final class TransferEngine {
    public interface Listener {
        void onSecurityCode(String code);
        void onIncomingBatch(BatchManifest manifest);
        boolean acceptIncomingBatch(BatchManifest manifest);
        void onProgress(String sessionId, String fileId, String fileName, long done, long total,
                        long batchDone, long batchTotal, double bytesPerSecond);
        void onFileCompleted(String sessionId, String fileId, Uri publishedUri);
        void onCompleted(String sessionId);
        void onError(String sessionId, Throwable error, boolean resumable);
    }

    private static final int CHUNK = ResumableProtocol.DEFAULT_CHUNK_BYTES; // 1 MiB
    private static final int STREAM_BUFFER = 2 * 1024 * 1024;
    private final Context context;
    private final ResumeStore resumeStore;
    private final DownloadStore downloadStore;

    public TransferEngine(Context context) {
        this.context = context.getApplicationContext();
        this.resumeStore = new ResumeStore(this.context);
        this.downloadStore = new DownloadStore(this.context);
    }

    public void send(Socket socket, BatchManifest manifest, List<TransferItem> items,
                     Listener listener) throws Exception {
        if (manifest.getEntries().size() != items.size()) {
            throw new IllegalArgumentException("Manifest/items mismatch");
        }
        tuneSocket(socket);
        try (DataInputStream in = new DataInputStream(
                     new BufferedInputStream(socket.getInputStream(), STREAM_BUFFER));
             DataOutputStream out = new DataOutputStream(
                     new BufferedOutputStream(socket.getOutputStream(), STREAM_BUFFER))) {
            SessionWire.Handshake handshake = SessionWire.clientHandshake(in, out);
            listener.onSecurityCode(handshake.securityCode);
            SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_MANIFEST,
                    SessionWire.encodeManifest(manifest));
            SessionWire.Frame resumeFrame = SessionWire.readFrame(in, handshake.crypto);
            if (resumeFrame.type == SessionWire.TYPE_ERROR) {
                throw new IOException("Receiver declined transfer");
            }
            if (resumeFrame.type != SessionWire.TYPE_RESUME) {
                throw new IOException("Expected resume response");
            }
            Map<String, Long> receiverOffsets = SessionWire.decodeOffsets(resumeFrame.payload);

            long batchTotal = manifest.totalBytes();
            long confirmedBefore = 0;
            for (BatchManifest.Entry entry : manifest.getEntries()) {
                confirmedBefore += Math.min(entry.size, value(receiverOffsets, entry.id));
            }

            for (int i = 0; i < items.size(); i++) {
                TransferItem item = items.get(i);
                BatchManifest.Entry entry = manifest.getEntries().get(i);
                long requested = Math.min(value(receiverOffsets, entry.id), entry.size);
                long offset = requested == entry.size
                        ? entry.size
                        : ResumableProtocol.alignToChunkBoundary(requested, CHUNK);
                long sent = offset;
                long started = System.nanoTime();
                long batchBase = confirmedBefore - requested + offset;

                if (offset < entry.size) {
                    InputStream raw = context.getContentResolver().openInputStream(item.getUri());
                    if (raw == null) throw new IOException("Cannot open source file: " + entry.name);
                    try (BufferedInputStream source = new BufferedInputStream(raw, STREAM_BUFFER)) {
                        skipFully(source, offset);
                        byte[] buffer = new byte[CHUNK];
                        while (sent < entry.size) {
                            int want = (int) Math.min(buffer.length, entry.size - sent);
                            int n = readAtMost(source, buffer, want);
                            if (n <= 0) {
                                throw new IOException("Unexpected end of source file: " + entry.name);
                            }
                            SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_CHUNK,
                                    SessionWire.encodeChunk(entry.id, sent, buffer, n));
                            SessionWire.Frame ackFrame = SessionWire.readFrame(in, handshake.crypto);
                            if (ackFrame.type != SessionWire.TYPE_ACK) {
                                throw new IOException("Expected chunk acknowledgement");
                            }
                            SessionWire.Ack ack = SessionWire.decodeAck(ackFrame.payload);
                            if (!entry.id.equals(ack.fileId) || ack.offset != sent + n) {
                                throw new IOException("Invalid acknowledgement");
                            }
                            sent = ack.offset;
                            double seconds = Math.max(0.001,
                                    (System.nanoTime() - started) / 1_000_000_000.0);
                            listener.onProgress(manifest.getSessionId(), entry.id, entry.name,
                                    sent, entry.size, batchBase + sent - offset, batchTotal,
                                    (sent - offset) / seconds);
                        }
                    }
                }

                SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_FILE_DONE,
                        SessionWire.encodeText(entry.id));
                SessionWire.Frame verifiedFrame = SessionWire.readFrame(in, handshake.crypto);
                if (verifiedFrame.type != SessionWire.TYPE_ACK) {
                    throw new IOException("Receiver did not verify completed file");
                }
                SessionWire.Ack verified = SessionWire.decodeAck(verifiedFrame.payload);
                if (!entry.id.equals(verified.fileId) || verified.offset != entry.size) {
                    throw new IOException("Invalid file completion acknowledgement");
                }
                confirmedBefore = batchBase + entry.size - offset;
            }

            SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_BATCH_DONE,
                    SessionWire.encodeText(manifest.getSessionId()));
            SessionWire.Frame batchAckFrame = SessionWire.readFrame(in, handshake.crypto);
            if (batchAckFrame.type != SessionWire.TYPE_ACK) {
                throw new IOException("Receiver did not acknowledge batch completion");
            }
            SessionWire.Ack batchAck = SessionWire.decodeAck(batchAckFrame.payload);
            if (!manifest.getSessionId().equals(batchAck.fileId)) {
                throw new IOException("Invalid batch completion acknowledgement");
            }
            listener.onCompleted(manifest.getSessionId());
        } catch (Exception e) {
            listener.onError(manifest.getSessionId(), e, isResumable(e));
            throw e;
        }
    }

    public void receive(Socket socket, Listener listener) throws Exception {
        tuneSocket(socket);
        String sessionId = "unknown";
        try (DataInputStream in = new DataInputStream(
                     new BufferedInputStream(socket.getInputStream(), STREAM_BUFFER));
             DataOutputStream out = new DataOutputStream(
                     new BufferedOutputStream(socket.getOutputStream(), STREAM_BUFFER))) {
            SessionWire.Handshake handshake = SessionWire.serverHandshake(in, out);
            listener.onSecurityCode(handshake.securityCode);
            SessionWire.Frame manifestFrame = SessionWire.readFrame(in, handshake.crypto);
            if (manifestFrame.type != SessionWire.TYPE_MANIFEST) {
                throw new IOException("Expected transfer manifest");
            }
            BatchManifest manifest = SessionWire.decodeManifest(manifestFrame.payload);
            sessionId = manifest.getSessionId();
            listener.onIncomingBatch(manifest);
            if (!listener.acceptIncomingBatch(manifest)) {
                SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_ERROR,
                        SessionWire.encodeText("DECLINED"));
                return;
            }

            ResumeState saved = resumeStore.load(sessionId);
            Map<String, Long> offsets = new LinkedHashMap<>();
            for (BatchManifest.Entry e : manifest.getEntries()) {
                long verified = downloadStore.verifiedLength(sessionId, e.id);
                long safe;
                if (verified == e.size) {
                    safe = e.size;
                } else {
                    long disk = downloadStore.partialLength(sessionId, e.id);
                    long persisted = saved.getConfirmedOffset(e.id);
                    safe = ResumableProtocol.alignToChunkBoundary(Math.min(disk, persisted), CHUNK);
                    File partial = downloadStore.partialFile(sessionId, e.id);
                    if (partial.exists() && partial.length() != safe) truncate(partial, safe);
                }
                offsets.put(e.id, safe);
            }
            SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_RESUME,
                    SessionWire.encodeOffsets(offsets));

            Map<String, BatchManifest.Entry> byId = new LinkedHashMap<>();
            Map<String, Long> confirmed = new LinkedHashMap<>(offsets);
            Map<String, Long> starts = new LinkedHashMap<>(offsets);
            Map<String, Long> timers = new LinkedHashMap<>();
            long batchTotal = manifest.totalBytes();
            long baseAlreadyConfirmed = 0;
            for (BatchManifest.Entry e : manifest.getEntries()) {
                byId.put(e.id, e);
                baseAlreadyConfirmed += offsets.get(e.id);
            }

            while (true) {
                SessionWire.Frame frame = SessionWire.readFrame(in, handshake.crypto);
                if (frame.type == SessionWire.TYPE_CHUNK) {
                    SessionWire.Chunk chunk = SessionWire.decodeChunk(frame.payload);
                    BatchManifest.Entry entry = byId.get(chunk.fileId);
                    if (entry == null) throw new IOException("Unknown file id");
                    if (downloadStore.isVerified(sessionId, entry.id, entry.size)) {
                        throw new IOException("Received data for already verified file");
                    }
                    long expected = value(confirmed, entry.id);
                    if (chunk.offset != expected) throw new IOException("Unexpected chunk offset");
                    if (chunk.data.length <= 0 || chunk.data.length > CHUNK) {
                        throw new IOException("Invalid chunk size");
                    }
                    if (chunk.offset + chunk.data.length > entry.size) {
                        throw new IOException("Chunk exceeds file size");
                    }

                    // Durable-before-ACK invariant: append -> fsync -> persist checkpoint -> ACK.
                    try (FileOutputStream target = downloadStore.openPartial(sessionId, entry.id, true)) {
                        target.write(chunk.data);
                        target.getFD().sync();
                    }
                    long next = chunk.offset + chunk.data.length;
                    confirmed.put(entry.id, next);
                    resumeStore.save(new ResumeState(sessionId, System.currentTimeMillis(), confirmed));
                    SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_ACK,
                            SessionWire.encodeAck(entry.id, next));

                    if (!timers.containsKey(entry.id)) timers.put(entry.id, System.nanoTime());
                    long startOffset = value(starts, entry.id);
                    double seconds = Math.max(0.001,
                            (System.nanoTime() - timers.get(entry.id)) / 1_000_000_000.0);
                    long newlyTransferred = 0;
                    for (Map.Entry<String, Long> c : confirmed.entrySet()) {
                        newlyTransferred += Math.max(0,
                                c.getValue() - value(starts, c.getKey()));
                    }
                    listener.onProgress(sessionId, entry.id, entry.name, next, entry.size,
                            baseAlreadyConfirmed + newlyTransferred, batchTotal,
                            (next - startOffset) / seconds);
                } else if (frame.type == SessionWire.TYPE_FILE_DONE) {
                    String fileId = new String(frame.payload,
                            java.nio.charset.StandardCharsets.UTF_8);
                    BatchManifest.Entry entry = byId.get(fileId);
                    if (entry == null) throw new IOException("Unknown completed file id");

                    if (downloadStore.isVerified(sessionId, fileId, entry.size)) {
                        confirmed.put(fileId, entry.size);
                        SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_ACK,
                                SessionWire.encodeAck(fileId, entry.size));
                        continue;
                    }

                    File partial = downloadStore.partialFile(sessionId, fileId);
                    if (partial.length() != entry.size) {
                        throw new IOException("Incomplete file at completion");
                    }
                    byte[] actual = sha256(partial);
                    if (!java.util.Arrays.equals(actual, entry.sha256)) {
                        downloadStore.discard(sessionId, fileId);
                        confirmed.put(fileId, 0L);
                        resumeStore.save(new ResumeState(sessionId,
                                System.currentTimeMillis(), confirmed));
                        throw new IOException("SHA-256 verification failed: " + entry.name);
                    }
                    Uri published = downloadStore.publishVerified(sessionId, fileId,
                            entry.name, entry.mime, entry.category);
                    confirmed.put(fileId, entry.size);
                    resumeStore.save(new ResumeState(sessionId,
                            System.currentTimeMillis(), confirmed));
                    listener.onFileCompleted(sessionId, fileId, published);
                    SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_ACK,
                            SessionWire.encodeAck(fileId, entry.size));
                } else if (frame.type == SessionWire.TYPE_BATCH_DONE) {
                    SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_ACK,
                            SessionWire.encodeAck(sessionId, manifest.totalBytes()));
                    resumeStore.clear(sessionId);
                    downloadStore.clearSession(sessionId);
                    listener.onCompleted(sessionId);
                    return;
                } else if (frame.type == SessionWire.TYPE_ERROR) {
                    throw new IOException("Remote error: " + new String(frame.payload,
                            java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    throw new IOException("Unexpected frame type: " + frame.type);
                }
            }
        } catch (Exception e) {
            listener.onError(sessionId, e, isResumable(e));
            throw e;
        }
    }

    public BatchManifest buildManifest(List<TransferItem> items) throws Exception {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("No transfer items");
        if (items.size() > 10_000) throw new IllegalArgumentException("Too many transfer items");

        List<BatchManifest.Entry> entries = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        byte[] buffer = new byte[STREAM_BUFFER];
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
            if (read != item.getSize()) {
                throw new IOException("File size changed while preparing transfer: " + item.getName());
            }
            entries.add(new BatchManifest.Entry(item.getId(), item.getName(),
                    item.getMimeType(), item.getSize(), item.getCategory(), digest.digest()));
        }
        return new BatchManifest(entries);
    }

    private static void tuneSocket(Socket socket) throws IOException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        try { socket.setSendBufferSize(STREAM_BUFFER); } catch (Exception ignored) { }
        try { socket.setReceiveBufferSize(STREAM_BUFFER); } catch (Exception ignored) { }
        socket.setSoTimeout(30_000);
    }

    private static boolean isResumable(Exception error) {
        if (error instanceof SecurityException) return false;
        String message = error.getMessage();
        if (message == null) return true;
        String lower = message.toLowerCase(java.util.Locale.US);
        return !lower.contains("declined")
                && !lower.contains("invalid optishare")
                && !lower.contains("unsupported optishare")
                && !lower.contains("sha-256 verification failed");
    }

    private static long value(Map<String, Long> map, String key) {
        Long v = map.get(key);
        return v == null ? 0L : v;
    }

    private static byte[] sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[STREAM_BUFFER];
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return digest.digest();
    }

    private static void truncate(File file, long length) throws IOException {
        if (!file.exists()) return;
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(length);
        }
    }

    private static void skipFully(InputStream in, long amount) throws IOException {
        long remaining = amount;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
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
