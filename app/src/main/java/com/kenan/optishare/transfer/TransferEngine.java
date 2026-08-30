package com.kenan.optishare.transfer;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import com.kenan.optishare.device.DeviceIdentityKey;
import com.kenan.optishare.model.TransferItem;
import com.kenan.optishare.protocol.BatchManifest;
import com.kenan.optishare.protocol.CapabilityNegotiation;
import com.kenan.optishare.protocol.ResumeState;
import com.kenan.optishare.protocol.ResumeStore;
import com.kenan.optishare.protocol.ResumableProtocol;
import com.kenan.optishare.protocol.SessionWire;
import com.kenan.optishare.settings.AppSettings;
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
 * Files are streamed in 1 MiB authenticated chunks. Instead of forcing a disk fsync and network
 * round-trip after every chunk, OptiShare 2.1 pipelines up to four chunks and then creates one
 * durable checkpoint. A checkpoint is ACKed only after the receiver has fsynced the partial file
 * and persisted the safe resume offset. This keeps resume correctness while avoiding the stop/start
 * throughput pattern of the original 2.0 implementation.
 */
public final class TransferEngine {
    public interface Listener {
        void onSecurityCode(String code);
        boolean onPeerIdentity(String fingerprint);
        void onIncomingBatch(BatchManifest manifest);
        boolean acceptIncomingBatch(BatchManifest manifest);
        void onProgress(String sessionId, String fileId, String fileName, long done, long total,
                        long batchDone, long batchTotal, double bytesPerSecond);
        void onFileCompleted(String sessionId, String fileId, Uri publishedUri);
        default void onFileFailed(String sessionId, String fileId, String fileName,
                                  String reason) { }
        void onCompleted(String sessionId);
        void onError(String sessionId, Throwable error, boolean resumable);
        default void onBenchmarkCompleted(long bytes, long durationMs, double bytesPerSecond) { }
    }

    private static final int CHUNK = ResumableProtocol.DEFAULT_CHUNK_BYTES;
    private static final int STREAM_BUFFER = 4 * 1024 * 1024;
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
            String peerFingerprint = exchangeClientIdentity(in, out, handshake);
            CapabilityNegotiation.clientExchange(in, out, handshake.crypto);
            boolean trusted = peerFingerprint != null && listener.onPeerIdentity(peerFingerprint);
            if (!trusted) listener.onSecurityCode(handshake.securityCode);
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
                int chunksAwaitingCheckpoint = 0;
                int checkpointChunks = ResumableProtocol.checkpointChunksForFile(entry.size);

                try {
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
                            long chunkOffset = sent;
                            SessionWire.writeFrameBuffered(out, handshake.crypto, SessionWire.TYPE_CHUNK,
                                    SessionWire.encodeChunk(entry.id, chunkOffset, buffer, n));
                            sent += n;
                            chunksAwaitingCheckpoint++;

                            boolean checkpoint = chunksAwaitingCheckpoint >= checkpointChunks
                                    || sent == entry.size;
                            if (checkpoint) {
                                out.flush();
                                SessionWire.Frame ackFrame = SessionWire.readFrame(in, handshake.crypto);
                                if (ackFrame.type != SessionWire.TYPE_ACK) {
                                    throw new IOException("Expected checkpoint acknowledgement");
                                }
                                SessionWire.Ack ack = SessionWire.decodeAck(ackFrame.payload);
                                if (!entry.id.equals(ack.fileId) || ack.offset != sent) {
                                    throw new IOException("Invalid checkpoint acknowledgement");
                                }
                                chunksAwaitingCheckpoint = 0;
                            }

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
                    if (verifiedFrame.type == SessionWire.TYPE_FILE_FAILED) {
                        listener.onFileFailed(manifest.getSessionId(), entry.id, entry.name,
                                new String(verifiedFrame.payload, java.nio.charset.StandardCharsets.UTF_8));
                        confirmedBefore = batchBase;
                        if (!new AppSettings(context).continueAfterFileFailure()) {
                            throw new IOException("File failed and queue continuation is disabled: " + entry.name);
                        }
                        continue;
                    }
                    if (verifiedFrame.type != SessionWire.TYPE_ACK) {
                        throw new IOException("Receiver did not verify completed file");
                    }
                    SessionWire.Ack verified = SessionWire.decodeAck(verifiedFrame.payload);
                    if (!entry.id.equals(verified.fileId) || verified.offset != entry.size) {
                        throw new IOException("Invalid file completion acknowledgement");
                    }
                    confirmedBefore = batchBase + entry.size - offset;
                } catch (IOException sourceError) {
                    // Transport failures must resume the session. Only local content read failures
                    // can be isolated without risking encrypted stream desynchronisation.
                    if (!isLocalSourceFailure(sourceError)) throw sourceError;
                    SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_FILE_SKIPPED,
                            SessionWire.encodeText(entry.id));
                    SessionWire.Frame skippedAck = SessionWire.readFrame(in, handshake.crypto);
                    if (skippedAck.type != SessionWire.TYPE_ACK) throw sourceError;
                    listener.onFileFailed(manifest.getSessionId(), entry.id, entry.name,
                            sourceError.getMessage());
                    confirmedBefore = batchBase;
                    if (!new AppSettings(context).continueAfterFileFailure()) throw sourceError;
                }
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


    /** Measures the authenticated encrypted Android-to-Android transport without creating a file. */
    public void benchmark(Socket socket, Listener listener) throws Exception {
        tuneSocket(socket);
        try (DataInputStream in = new DataInputStream(
                     new BufferedInputStream(socket.getInputStream(), STREAM_BUFFER));
             DataOutputStream out = new DataOutputStream(
                     new BufferedOutputStream(socket.getOutputStream(), STREAM_BUFFER))) {
            SessionWire.Handshake handshake = SessionWire.clientHandshake(in, out);
            String peerFingerprint = exchangeClientIdentity(in, out, handshake);
            CapabilityNegotiation.clientExchange(in, out, handshake.crypto);
            boolean trusted = peerFingerprint != null && listener.onPeerIdentity(peerFingerprint);
            if (!trusted) listener.onSecurityCode(handshake.securityCode);

            final long total = SessionWire.BENCHMARK_TOTAL_BYTES;
            SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_BENCHMARK_BEGIN,
                    SessionWire.encodeBenchmarkSize(total));
            SessionWire.Frame ready = SessionWire.readFrame(in, handshake.crypto);
            if (ready.type != SessionWire.TYPE_ACK) {
                throw new IOException("Peer does not support encrypted speed test");
            }
            SessionWire.Ack readyAck = SessionWire.decodeAck(ready.payload);
            if (!"benchmark-ready".equals(readyAck.fileId) || readyAck.offset != 0L) {
                throw new IOException("Invalid benchmark ready acknowledgement");
            }

            byte[] block = new byte[SessionWire.BENCHMARK_BLOCK_BYTES];
            for (int i = 0; i < block.length; i++) block[i] = (byte) (i * 31 + 17);
            long sent = 0L;
            long started = System.nanoTime();
            while (sent < total) {
                int length = (int) Math.min(block.length, total - sent);
                if (length == block.length) {
                    SessionWire.writeFrameBuffered(out, handshake.crypto,
                            SessionWire.TYPE_BENCHMARK_DATA, block);
                } else {
                    byte[] tail = java.util.Arrays.copyOf(block, length);
                    SessionWire.writeFrameBuffered(out, handshake.crypto,
                            SessionWire.TYPE_BENCHMARK_DATA, tail);
                }
                sent += length;
            }
            SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_BENCHMARK_DONE,
                    SessionWire.encodeBenchmarkSize(sent));
            SessionWire.Frame result = SessionWire.readFrame(in, handshake.crypto);
            if (result.type != SessionWire.TYPE_ACK) {
                throw new IOException("Peer did not acknowledge speed test");
            }
            SessionWire.Ack ack = SessionWire.decodeAck(result.payload);
            if (!"benchmark".equals(ack.fileId) || ack.offset != total) {
                throw new IOException("Invalid benchmark acknowledgement");
            }
            long durationMs = Math.max(1L,
                    Math.round((System.nanoTime() - started) / 1_000_000.0));
            double bytesPerSecond = total / Math.max(0.001, durationMs / 1000.0);
            listener.onBenchmarkCompleted(total, durationMs, bytesPerSecond);
        } catch (Exception error) {
            listener.onError("benchmark", error, false);
            throw error;
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
            String peerFingerprint = exchangeServerIdentity(in, out, handshake);
            CapabilityNegotiation.serverExchange(in, out, handshake.crypto);
            boolean trusted = peerFingerprint != null && listener.onPeerIdentity(peerFingerprint);
            if (!trusted) listener.onSecurityCode(handshake.securityCode);
            SessionWire.Frame manifestFrame = SessionWire.readFrame(in, handshake.crypto);
            if (manifestFrame.type == SessionWire.TYPE_BENCHMARK_BEGIN) {
                receiveBenchmark(in, out, handshake, manifestFrame, listener);
                return;
            }
            if (manifestFrame.type != SessionWire.TYPE_MANIFEST) {
                throw new IOException("Expected transfer manifest");
            }
            BatchManifest manifest = SessionWire.decodeManifest(manifestFrame.payload);
            sessionId = manifest.getSessionId();

            ResumeState saved = resumeStore.load(sessionId);
            Map<String, Long> offsets = new LinkedHashMap<>();
            long remainingToReceive = 0L;
            long largestUnverifiedFile = 0L;
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
                    remainingToReceive = saturatingAdd(remainingToReceive, e.size - safe);
                    largestUnverifiedFile = Math.max(largestUnverifiedFile, e.size);
                }
                offsets.put(e.id, safe);
            }
            downloadStore.ensureCapacity(saturatingAdd(remainingToReceive, largestUnverifiedFile));

            listener.onIncomingBatch(manifest);
            if (!listener.acceptIncomingBatch(manifest)) {
                SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_ERROR,
                        SessionWire.encodeText("DECLINED"));
                return;
            }

            SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_RESUME,
                    SessionWire.encodeOffsets(offsets));

            Map<String, BatchManifest.Entry> byId = new LinkedHashMap<>();
            Map<String, Long> confirmed = new LinkedHashMap<>(offsets);
            Map<String, Long> received = new LinkedHashMap<>(offsets);
            Map<String, Long> starts = new LinkedHashMap<>(offsets);
            Map<String, Long> timers = new LinkedHashMap<>();
            long batchTotal = manifest.totalBytes();
            long baseAlreadyConfirmed = 0;
            for (BatchManifest.Entry e : manifest.getEntries()) {
                byId.put(e.id, e);
                baseAlreadyConfirmed = saturatingAdd(baseAlreadyConfirmed, offsets.get(e.id));
            }

            FileOutputStream activeTarget = null;
            String activeFileId = null;
            int chunksSinceCheckpoint = 0;
            try {
                while (true) {
                    SessionWire.Frame frame = SessionWire.readFrame(in, handshake.crypto);
                    if (frame.type == SessionWire.TYPE_CHUNK) {
                        SessionWire.Chunk chunk = SessionWire.decodeChunk(frame.payload);
                        BatchManifest.Entry entry = byId.get(chunk.fileId);
                        if (entry == null) throw new IOException("Unknown file id");
                        if (downloadStore.isVerified(sessionId, entry.id, entry.size)) {
                            throw new IOException("Received data for already verified file");
                        }
                        long expected = value(received, entry.id);
                        if (chunk.offset != expected) throw new IOException("Unexpected chunk offset");
                        if (chunk.data.length <= 0 || chunk.data.length > CHUNK) {
                            throw new IOException("Invalid chunk size");
                        }
                        if (chunk.offset + chunk.data.length > entry.size) {
                            throw new IOException("Chunk exceeds file size");
                        }

                        if (activeTarget == null) {
                            activeTarget = downloadStore.openPartial(sessionId, entry.id, true);
                            activeFileId = entry.id;
                            chunksSinceCheckpoint = 0;
                        } else if (!entry.id.equals(activeFileId)) {
                            throw new IOException("Sender changed files before completing checkpoint");
                        }

                        activeTarget.write(chunk.data);
                        long next = chunk.offset + chunk.data.length;
                        received.put(entry.id, next);
                        chunksSinceCheckpoint++;

                        boolean checkpoint = chunksSinceCheckpoint >= ResumableProtocol.checkpointChunksForFile(entry.size)
                                || next == entry.size;
                        if (checkpoint) {
                            activeTarget.getFD().sync();
                            confirmed.put(entry.id, next);
                            resumeStore.save(new ResumeState(sessionId,
                                    System.currentTimeMillis(), confirmed));
                            SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_ACK,
                                    SessionWire.encodeAck(entry.id, next));
                            chunksSinceCheckpoint = 0;
                        }

                        if (!timers.containsKey(entry.id)) timers.put(entry.id, System.nanoTime());
                        long startOffset = value(starts, entry.id);
                        double seconds = Math.max(0.001,
                                (System.nanoTime() - timers.get(entry.id)) / 1_000_000_000.0);
                        long newlyTransferred = 0;
                        for (Map.Entry<String, Long> c : received.entrySet()) {
                            newlyTransferred = saturatingAdd(newlyTransferred,
                                    Math.max(0, c.getValue() - value(starts, c.getKey())));
                        }
                        listener.onProgress(sessionId, entry.id, entry.name, next, entry.size,
                                saturatingAdd(baseAlreadyConfirmed, newlyTransferred), batchTotal,
                                (next - startOffset) / seconds);
                    } else if (frame.type == SessionWire.TYPE_FILE_DONE) {
                        String fileId = new String(frame.payload,
                                java.nio.charset.StandardCharsets.UTF_8);
                        BatchManifest.Entry entry = byId.get(fileId);
                        if (entry == null) throw new IOException("Unknown completed file id");

                        if (activeTarget != null) {
                            if (!fileId.equals(activeFileId)) {
                                throw new IOException("Completed file does not match active stream");
                            }
                            activeTarget.getFD().sync();
                            long durable = value(received, fileId);
                            confirmed.put(fileId, durable);
                            resumeStore.save(new ResumeState(sessionId,
                                    System.currentTimeMillis(), confirmed));
                            activeTarget.close();
                            activeTarget = null;
                            activeFileId = null;
                            chunksSinceCheckpoint = 0;
                        }

                        if (downloadStore.isVerified(sessionId, fileId, entry.size)) {
                            confirmed.put(fileId, entry.size);
                            received.put(fileId, entry.size);
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
                            received.put(fileId, 0L);
                            resumeStore.save(new ResumeState(sessionId,
                                    System.currentTimeMillis(), confirmed));
                            String reason = "SHA-256 verification failed";
                            listener.onFileFailed(sessionId, fileId, entry.name, reason);
                            SessionWire.writeFrame(out, handshake.crypto,
                                    SessionWire.TYPE_FILE_FAILED,
                                    SessionWire.encodeText(reason));
                            continue;
                        }
                        Uri published = downloadStore.publishVerified(sessionId, fileId,
                                entry.name, entry.mime, entry.category, entry.relativePath);
                        confirmed.put(fileId, entry.size);
                        received.put(fileId, entry.size);
                        resumeStore.save(new ResumeState(sessionId,
                                System.currentTimeMillis(), confirmed));
                        listener.onFileCompleted(sessionId, fileId, published);
                        SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_ACK,
                                SessionWire.encodeAck(fileId, entry.size));
                    } else if (frame.type == SessionWire.TYPE_FILE_SKIPPED) {
                        String fileId = new String(frame.payload,
                                java.nio.charset.StandardCharsets.UTF_8);
                        BatchManifest.Entry entry = byId.get(fileId);
                        if (entry == null) throw new IOException("Unknown skipped file id");
                        if (activeTarget != null) {
                            if (!fileId.equals(activeFileId)) {
                                throw new IOException("Skipped file does not match active stream");
                            }
                            activeTarget.close();
                            activeTarget = null;
                            activeFileId = null;
                            chunksSinceCheckpoint = 0;
                        }
                        downloadStore.discard(sessionId, fileId);
                        confirmed.put(fileId, 0L);
                        received.put(fileId, 0L);
                        resumeStore.save(new ResumeState(sessionId,
                                System.currentTimeMillis(), confirmed));
                        listener.onFileFailed(sessionId, fileId, entry.name,
                                "Sender could not read this file");
                        SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_ACK,
                                SessionWire.encodeAck(fileId, 0L));
                    } else if (frame.type == SessionWire.TYPE_BATCH_DONE) {
                        if (activeTarget != null) {
                            throw new IOException("Batch completed with an open file stream");
                        }
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
            } finally {
                if (activeTarget != null) {
                    try { activeTarget.close(); } catch (Exception ignored) { }
                }
            }
        } catch (Exception e) {
            listener.onError(sessionId, e, isResumable(e));
            throw e;
        }
    }

    static boolean isLocalSourceFailure(IOException error) {
        String message = error.getMessage();
        return message != null && (message.startsWith("Cannot open source file:")
                || message.startsWith("Unexpected end of source file:"));
    }


    private void receiveBenchmark(DataInputStream in, DataOutputStream out,
                                  SessionWire.Handshake handshake, SessionWire.Frame begin,
                                  Listener listener) throws Exception {
        long expected = SessionWire.decodeBenchmarkSize(begin.payload);
        SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_ACK,
                SessionWire.encodeAck("benchmark-ready", 0L));
        long received = 0L;
        long started = System.nanoTime();
        while (true) {
            SessionWire.Frame frame = SessionWire.readFrame(in, handshake.crypto);
            if (frame.type == SessionWire.TYPE_BENCHMARK_DATA) {
                int length = frame.payload == null ? 0 : frame.payload.length;
                if (length <= 0 || length > SessionWire.BENCHMARK_BLOCK_BYTES) {
                    throw new IOException("Invalid benchmark data block");
                }
                if (received > expected - length) {
                    throw new IOException("Benchmark data exceeds declared size");
                }
                received += length;
                continue;
            }
            if (frame.type == SessionWire.TYPE_BENCHMARK_DONE) {
                long declared = SessionWire.decodeBenchmarkSize(frame.payload);
                if (declared != expected || received != expected) {
                    throw new IOException("Incomplete benchmark payload");
                }
                long durationMs = Math.max(1L,
                        Math.round((System.nanoTime() - started) / 1_000_000.0));
                double bytesPerSecond = received / Math.max(0.001, durationMs / 1000.0);
                SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_ACK,
                        SessionWire.encodeAck("benchmark", received));
                listener.onBenchmarkCompleted(received, durationMs, bytesPerSecond);
                return;
            }
            throw new IOException("Unexpected frame during speed test: " + frame.type);
        }
    }

    private String exchangeClientIdentity(DataInputStream in, DataOutputStream out,
                                          SessionWire.Handshake handshake) throws Exception {
        byte[] challenge = handshake.crypto.sessionFingerprint();
        writeLocalIdentity(out, handshake, challenge);
        SessionWire.Frame frame = SessionWire.readFrame(in, handshake.crypto);
        if (frame.type != SessionWire.TYPE_IDENTITY) throw new IOException("Expected peer identity");
        return verifyPeerIdentity(SessionWire.decodeIdentity(frame.payload), challenge);
    }

    private String exchangeServerIdentity(DataInputStream in, DataOutputStream out,
                                          SessionWire.Handshake handshake) throws Exception {
        byte[] challenge = handshake.crypto.sessionFingerprint();
        SessionWire.Frame frame = SessionWire.readFrame(in, handshake.crypto);
        if (frame.type != SessionWire.TYPE_IDENTITY) throw new IOException("Expected peer identity");
        String peer = verifyPeerIdentity(SessionWire.decodeIdentity(frame.payload), challenge);
        writeLocalIdentity(out, handshake, challenge);
        return peer;
    }

    private static void writeLocalIdentity(DataOutputStream out, SessionWire.Handshake handshake,
                                           byte[] challenge) throws Exception {
        if (!DeviceIdentityKey.supported()) {
            SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_IDENTITY,
                    SessionWire.encodeIdentity(false, null, null));
            return;
        }
        DeviceIdentityKey local = new DeviceIdentityKey();
        SessionWire.writeFrame(out, handshake.crypto, SessionWire.TYPE_IDENTITY,
                SessionWire.encodeIdentity(true, local.publicKeyEncoded(), local.sign(challenge)));
    }

    private static String verifyPeerIdentity(SessionWire.Identity identity, byte[] challenge)
            throws Exception {
        if (!identity.supported) return null;
        if (!DeviceIdentityKey.verify(identity.publicKey, challenge, identity.signature)) {
            throw new SecurityException("Peer device identity signature is invalid");
        }
        return DeviceIdentityKey.fingerprint(identity.publicKey);
    }

    public BatchManifest buildManifest(List<TransferItem> items) throws Exception {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("No transfer items");
        if (items.size() > BatchManifest.MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many transfer items");
        }

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
                throw new IOException("File size changed while preparing transfer: "
                        + item.getName());
            }
            entries.add(new BatchManifest.Entry(item.getId(), item.getName(),
                    item.getMimeType(), item.getSize(), item.getCategory(),
                    item.getRelativePath(), digest.digest()));
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
                && !lower.contains("sha-256 verification failed")
                && !lower.contains("not enough storage space");
    }

    private static long value(Map<String, Long> map, String key) {
        Long v = map.get(key);
        return v == null ? 0L : v;
    }

    private static long saturatingAdd(long a, long b) {
        if (a < 0 || b < 0) return Long.MAX_VALUE;
        return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b;
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
