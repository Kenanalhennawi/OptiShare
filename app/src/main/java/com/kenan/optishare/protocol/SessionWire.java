package com.kenan.optishare.protocol;

import com.kenan.optishare.model.TransferItem;
import com.kenan.optishare.security.CryptoSession;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Framing for OptiShare protocol v2. Every post-handshake application frame is AES-GCM authenticated. */
public final class SessionWire {
    public static final int MAGIC = 0x4F533250; // OS2P
    public static final int VERSION = 6;
    public static final int TYPE_MANIFEST = 1;
    public static final int TYPE_RESUME = 2;
    public static final int TYPE_CHUNK = 3;
    public static final int TYPE_ACK = 4;
    public static final int TYPE_FILE_DONE = 5;
    public static final int TYPE_BATCH_DONE = 6;
    public static final int TYPE_ERROR = 7;
    public static final int TYPE_IDENTITY = 8;
    public static final int TYPE_BENCHMARK_BEGIN = 9;
    public static final int TYPE_BENCHMARK_DATA = 10;
    public static final int TYPE_BENCHMARK_DONE = 11;
    public static final int TYPE_STRIPE_BEGIN = 12;
    public static final int TYPE_STRIPE_DATA = 13;
    public static final int TYPE_STRIPE_DONE = 14;
    /** Sender cannot read this item; receiver discards only that item's partial data. */
    public static final int TYPE_FILE_SKIPPED = 15;
    /** Receiver rejected one completed item (for example, SHA-256 mismatch). */
    public static final int TYPE_FILE_FAILED = 16;
    public static final int TYPE_CAPABILITIES = 17;
    public static final int TYPE_CAPABILITIES_CONFIRM = 18;
    public static final int TYPE_CAPABILITIES_SELECTED = 19;
    public static final int MAX_FRAME = 2 * 1024 * 1024;
    public static final int BENCHMARK_BLOCK_BYTES = 512 * 1024;
    public static final long BENCHMARK_TOTAL_BYTES = 8L * 1024L * 1024L;
    public static final long MAX_BENCHMARK_BYTES = 64L * 1024L * 1024L;
    private static final int SHA256_BYTES = 32;
    private static final int MAX_KEY_BYTES = 4096;
    private static final int MAX_SALT_BYTES = 64;
    private static final int MAX_ID_CHARS = 512;

    private SessionWire() {}

    public static final class Handshake {
        public final CryptoSession crypto;
        public final String securityCode;
        private Handshake(CryptoSession crypto) {
            this.crypto = crypto;
            this.securityCode = crypto.shortCode();
        }
    }

    public static Handshake clientHandshake(DataInputStream in, DataOutputStream out) throws Exception {
        CryptoSession crypto = CryptoSession.create();
        byte[] publicKey = crypto.publicKeyEncoded();
        if (publicKey.length <= 0 || publicKey.length > MAX_KEY_BYTES) {
            throw new IOException("Invalid local public key length");
        }
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(publicKey.length);
        out.write(publicKey);
        out.writeInt(salt.length);
        out.write(salt);
        out.flush();

        int magic = in.readInt();
        int version = in.readInt();
        if (magic != MAGIC || version != VERSION) throw new IOException("OptiShare handshake mismatch");
        int peerLength = positiveLength(in.readInt(), MAX_KEY_BYTES, "peer public key");
        byte[] peer = new byte[peerLength];
        in.readFully(peer);
        crypto.establish(peer, salt);
        return new Handshake(crypto);
    }

    public static Handshake serverHandshake(DataInputStream in, DataOutputStream out) throws Exception {
        int magic = in.readInt();
        int version = in.readInt();
        if (magic != MAGIC || version != VERSION) throw new IOException("OptiShare handshake mismatch");
        int peerLength = positiveLength(in.readInt(), MAX_KEY_BYTES, "peer public key");
        byte[] peer = new byte[peerLength];
        in.readFully(peer);
        int saltLength = positiveLength(in.readInt(), MAX_SALT_BYTES, "handshake salt");
        byte[] salt = new byte[saltLength];
        in.readFully(salt);

        CryptoSession crypto = CryptoSession.create();
        byte[] publicKey = crypto.publicKeyEncoded();
        if (publicKey.length <= 0 || publicKey.length > MAX_KEY_BYTES) {
            throw new IOException("Invalid local public key length");
        }
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(publicKey.length);
        out.write(publicKey);
        out.flush();
        crypto.establish(peer, salt);
        return new Handshake(crypto);
    }

    public static void writeFrame(DataOutputStream out, CryptoSession crypto, int type,
                                  byte[] payload) throws Exception {
        writeFrameInternal(out, crypto, type, payload, true);
    }

    /** Writes an authenticated frame without flushing the outer stream. Used for transfer chunks. */
    public static void writeFrameBuffered(DataOutputStream out, CryptoSession crypto, int type,
                                          byte[] payload) throws Exception {
        writeFrameInternal(out, crypto, type, payload, false);
    }

    private static void writeFrameInternal(DataOutputStream out, CryptoSession crypto, int type,
                                           byte[] payload, boolean flush) throws Exception {
        validateFrameType(type);
        byte[] body = payload == null ? new byte[0] : payload;
        byte[] aad = new byte[]{(byte) type};
        byte[] encrypted = crypto.encrypt(body, aad);
        if (encrypted.length <= 0 || encrypted.length > MAX_FRAME) {
            throw new IOException("Frame too large");
        }
        out.writeByte(type);
        out.writeInt(encrypted.length);
        out.write(encrypted);
        if (flush) out.flush();
    }

    public static Frame readFrame(DataInputStream in, CryptoSession crypto) throws Exception {
        int type = in.readUnsignedByte();
        validateFrameType(type);
        int length = positiveLength(in.readInt(), MAX_FRAME, "encrypted frame");
        byte[] encrypted = new byte[length];
        in.readFully(encrypted);
        byte[] payload = crypto.decrypt(encrypted, new byte[]{(byte) type});
        return new Frame(type, payload);
    }

    public static byte[] encodeIdentity(boolean supported, byte[] publicKey, byte[] signature) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeBoolean(supported);
        if (supported) {
            if (publicKey == null || publicKey.length <= 0 || publicKey.length > MAX_KEY_BYTES) {
                throw new IOException("Invalid identity public key");
            }
            if (signature == null || signature.length <= 0 || signature.length > 2048) {
                throw new IOException("Invalid identity signature");
            }
            out.writeInt(publicKey.length);
            out.write(publicKey);
            out.writeInt(signature.length);
            out.write(signature);
        }
        out.flush();
        return bytes.toByteArray();
    }

    public static Identity decodeIdentity(byte[] payload) throws IOException {
        DataInputStream in = payloadInput(payload);
        boolean supported = in.readBoolean();
        if (!supported) {
            ensureConsumed(in);
            return new Identity(false, null, null);
        }
        int keyLength = positiveLength(in.readInt(), MAX_KEY_BYTES, "identity public key");
        byte[] publicKey = new byte[keyLength];
        in.readFully(publicKey);
        int signatureLength = positiveLength(in.readInt(), 2048, "identity signature");
        byte[] signature = new byte[signatureLength];
        in.readFully(signature);
        ensureConsumed(in);
        return new Identity(true, publicKey, signature);
    }

    public static byte[] encodeManifest(BatchManifest manifest) throws IOException {
        if (manifest == null) throw new IOException("Manifest is required");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF(manifest.getSessionId());
        out.writeLong(manifest.getCreatedAt());
        out.writeInt(manifest.getEntries().size());
        for (BatchManifest.Entry e : manifest.getEntries()) {
            out.writeUTF(e.id);
            out.writeUTF(e.name);
            out.writeUTF(e.mime);
            out.writeLong(e.size);
            out.writeInt(e.category.ordinal());
            out.writeUTF(e.relativePath == null ? "" : e.relativePath);
            out.writeInt(e.sha256.length);
            out.write(e.sha256);
        }
        out.flush();
        return bytes.toByteArray();
    }

    public static BatchManifest decodeManifest(byte[] payload) throws IOException {
        DataInputStream in = payloadInput(payload);
        String sessionId = boundedUtf(in.readUTF(), MAX_ID_CHARS, "session id");
        long created = in.readLong();
        int count = in.readInt();
        if (count <= 0 || count > BatchManifest.MAX_ENTRIES) {
            throw new IOException("Invalid manifest entry count: " + count);
        }
        List<BatchManifest.Entry> entries = new ArrayList<>(count);
        TransferItem.Category[] categories = TransferItem.Category.values();
        for (int i = 0; i < count; i++) {
            String id = boundedUtf(in.readUTF(), MAX_ID_CHARS, "file id");
            String name = in.readUTF();
            String mime = in.readUTF();
            long size = in.readLong();
            if (size < 0) throw new IOException("Negative file size");
            int categoryIndex = in.readInt();
            String relativePath = in.readUTF();
            if (relativePath.isEmpty()) relativePath = null;
            int hashLength = in.readInt();
            if (hashLength != SHA256_BYTES) {
                throw new IOException("Invalid SHA-256 length: " + hashLength);
            }
            byte[] hash = new byte[SHA256_BYTES];
            in.readFully(hash);
            TransferItem.Category category = categoryIndex >= 0 && categoryIndex < categories.length
                    ? categories[categoryIndex] : TransferItem.Category.OTHER;
            try {
                entries.add(new BatchManifest.Entry(id, name, mime, size, category, relativePath, hash));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid manifest metadata", invalid);
            }
        }
        ensureConsumed(in);
        try {
            return new BatchManifest(sessionId, created, entries);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid manifest", invalid);
        }
    }

    public static byte[] encodeOffsets(Map<String, Long> offsets) throws IOException {
        if (offsets == null || offsets.size() > BatchManifest.MAX_ENTRIES) {
            throw new IOException("Invalid resume offsets");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(offsets.size());
        for (Map.Entry<String, Long> e : offsets.entrySet()) {
            String id = boundedUtf(e.getKey(), MAX_ID_CHARS, "resume file id");
            long value = e.getValue() == null ? -1L : e.getValue();
            if (value < 0) throw new IOException("Negative offset");
            out.writeUTF(id);
            out.writeLong(value);
        }
        out.flush();
        return bytes.toByteArray();
    }

    public static Map<String, Long> decodeOffsets(byte[] payload) throws IOException {
        DataInputStream in = payloadInput(payload);
        int count = in.readInt();
        if (count < 0 || count > BatchManifest.MAX_ENTRIES) {
            throw new IOException("Invalid resume entry count: " + count);
        }
        Map<String, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String id = boundedUtf(in.readUTF(), MAX_ID_CHARS, "resume file id");
            long value = in.readLong();
            if (value < 0) throw new IOException("Negative offset");
            if (result.put(id, value) != null) throw new IOException("Duplicate resume file id");
        }
        ensureConsumed(in);
        return result;
    }

    public static byte[] encodeChunk(String fileId, long offset, byte[] data, int length)
            throws IOException {
        boundedUtf(fileId, MAX_ID_CHARS, "chunk file id");
        if (offset < 0) throw new IOException("Negative chunk offset");
        if (data == null || length <= 0 || length > data.length
                || length > ResumableProtocol.DEFAULT_CHUNK_BYTES) {
            throw new IOException("Invalid chunk length: " + length);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(length + 128);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF(fileId);
        out.writeLong(offset);
        out.writeInt(length);
        out.write(data, 0, length);
        out.flush();
        return bytes.toByteArray();
    }

    public static Chunk decodeChunk(byte[] payload) throws IOException {
        DataInputStream in = payloadInput(payload);
        String fileId = boundedUtf(in.readUTF(), MAX_ID_CHARS, "chunk file id");
        long offset = in.readLong();
        if (offset < 0) throw new IOException("Negative chunk offset");
        int length = positiveLength(in.readInt(), ResumableProtocol.DEFAULT_CHUNK_BYTES,
                "chunk payload");
        byte[] data = new byte[length];
        in.readFully(data);
        ensureConsumed(in);
        return new Chunk(fileId, offset, data);
    }

    public static byte[] encodeAck(String fileId, long offset) throws IOException {
        boundedUtf(fileId, MAX_ID_CHARS, "ack file id");
        if (offset < 0) throw new IOException("Negative ACK offset");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF(fileId);
        out.writeLong(offset);
        out.flush();
        return bytes.toByteArray();
    }

    public static Ack decodeAck(byte[] payload) throws IOException {
        DataInputStream in = payloadInput(payload);
        String fileId = boundedUtf(in.readUTF(), MAX_ID_CHARS, "ack file id");
        long offset = in.readLong();
        if (offset < 0) throw new IOException("Negative ACK offset");
        ensureConsumed(in);
        return new Ack(fileId, offset);
    }

    public static byte[] encodeText(String text) {
        String safe = text == null ? "" : text;
        byte[] encoded = safe.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 64 * 1024) {
            throw new IllegalArgumentException("Text payload too large");
        }
        return encoded;
    }

    public static byte[] encodeBenchmarkSize(long bytes) throws IOException {
        if (bytes <= 0 || bytes > MAX_BENCHMARK_BYTES) {
            throw new IOException("Invalid benchmark size: " + bytes);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(8);
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeLong(bytes);
        out.flush();
        return buffer.toByteArray();
    }

    public static long decodeBenchmarkSize(byte[] payload) throws IOException {
        DataInputStream in = payloadInput(payload);
        long bytes = in.readLong();
        if (bytes <= 0 || bytes > MAX_BENCHMARK_BYTES) {
            throw new IOException("Invalid benchmark size: " + bytes);
        }
        ensureConsumed(in);
        return bytes;
    }

    private static DataInputStream payloadInput(byte[] payload) throws IOException {
        if (payload == null) throw new IOException("Missing payload");
        return new DataInputStream(new ByteArrayInputStream(payload));
    }

    private static int positiveLength(int value, int max, String label) throws IOException {
        if (value <= 0 || value > max) throw new IOException("Invalid " + label + " length: " + value);
        return value;
    }

    private static String boundedUtf(String value, int maxChars, String label) throws IOException {
        if (value == null || value.trim().isEmpty() || value.length() > maxChars) {
            throw new IOException("Invalid " + label);
        }
        return value;
    }

    private static void ensureConsumed(DataInputStream in) throws IOException {
        if (in.available() != 0) throw new IOException("Unexpected trailing payload bytes");
    }

    private static void validateFrameType(int type) throws IOException {
        if (type < TYPE_MANIFEST || type > TYPE_CAPABILITIES_SELECTED) {
            throw new IOException("Invalid frame type: " + type);
        }
    }

    public static final class Identity {
        public final boolean supported;
        public final byte[] publicKey;
        public final byte[] signature;
        Identity(boolean supported, byte[] publicKey, byte[] signature) {
            this.supported = supported;
            this.publicKey = publicKey;
            this.signature = signature;
        }
    }

    public static final class Frame {
        public final int type;
        public final byte[] payload;
        Frame(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    public static final class Chunk {
        public final String fileId;
        public final long offset;
        public final byte[] data;
        Chunk(String fileId, long offset, byte[] data) {
            this.fileId = fileId;
            this.offset = offset;
            this.data = data;
        }
    }

    public static final class Ack {
        public final String fileId;
        public final long offset;
        Ack(String fileId, long offset) {
            this.fileId = fileId;
            this.offset = offset;
        }
    }
}
