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

/** Framing for OptiShare 2 transport. Every application frame after handshake is AES-GCM authenticated. */
public final class SessionWire {
    public static final int MAGIC = 0x4F533250; // OS2P
    public static final int VERSION = 2;
    public static final int TYPE_MANIFEST = 1;
    public static final int TYPE_RESUME = 2;
    public static final int TYPE_CHUNK = 3;
    public static final int TYPE_ACK = 4;
    public static final int TYPE_FILE_DONE = 5;
    public static final int TYPE_BATCH_DONE = 6;
    public static final int TYPE_ERROR = 7;
    public static final int MAX_FRAME = 2 * 1024 * 1024;

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
        int peerLength = checkedLength(in.readInt(), 4096);
        byte[] peer = new byte[peerLength];
        in.readFully(peer);
        crypto.establish(peer, salt);
        return new Handshake(crypto);
    }

    public static Handshake serverHandshake(DataInputStream in, DataOutputStream out) throws Exception {
        int magic = in.readInt();
        int version = in.readInt();
        if (magic != MAGIC || version != VERSION) throw new IOException("OptiShare handshake mismatch");
        int peerLength = checkedLength(in.readInt(), 4096);
        byte[] peer = new byte[peerLength];
        in.readFully(peer);
        int saltLength = checkedLength(in.readInt(), 64);
        byte[] salt = new byte[saltLength];
        in.readFully(salt);
        CryptoSession crypto = CryptoSession.create();
        byte[] publicKey = crypto.publicKeyEncoded();
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(publicKey.length);
        out.write(publicKey);
        out.flush();
        crypto.establish(peer, salt);
        return new Handshake(crypto);
    }

    public static void writeFrame(DataOutputStream out, CryptoSession crypto, int type, byte[] payload) throws Exception {
        byte[] body = payload == null ? new byte[0] : payload;
        byte[] aad = new byte[]{(byte) type};
        byte[] encrypted = crypto.encrypt(body, aad);
        if (encrypted.length > MAX_FRAME) throw new IOException("Frame too large");
        out.writeByte(type);
        out.writeInt(encrypted.length);
        out.write(encrypted);
        out.flush();
    }

    public static Frame readFrame(DataInputStream in, CryptoSession crypto) throws Exception {
        int type = in.readUnsignedByte();
        int length = checkedLength(in.readInt(), MAX_FRAME);
        byte[] encrypted = new byte[length];
        in.readFully(encrypted);
        byte[] payload = crypto.decrypt(encrypted, new byte[]{(byte) type});
        return new Frame(type, payload);
    }

    public static byte[] encodeManifest(BatchManifest manifest) throws IOException {
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
            out.writeInt(e.sha256.length);
            out.write(e.sha256);
        }
        out.flush();
        return bytes.toByteArray();
    }

    public static BatchManifest decodeManifest(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        String sessionId = in.readUTF();
        long created = in.readLong();
        int count = checkedLength(in.readInt(), 10000);
        List<BatchManifest.Entry> entries = new ArrayList<>(count);
        TransferItem.Category[] categories = TransferItem.Category.values();
        for (int i = 0; i < count; i++) {
            String id = in.readUTF();
            String name = in.readUTF();
            String mime = in.readUTF();
            long size = in.readLong();
            if (size < 0) throw new IOException("Negative file size");
            int categoryIndex = in.readInt();
            int hashLength = checkedLength(in.readInt(), 128);
            byte[] hash = new byte[hashLength];
            in.readFully(hash);
            TransferItem.Category category = categoryIndex >= 0 && categoryIndex < categories.length ? categories[categoryIndex] : TransferItem.Category.OTHER;
            entries.add(new BatchManifest.Entry(id, name, mime, size, category, hash));
        }
        return new BatchManifest(sessionId, created, entries);
    }

    public static byte[] encodeOffsets(Map<String, Long> offsets) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(offsets.size());
        for (Map.Entry<String, Long> e : offsets.entrySet()) {
            out.writeUTF(e.getKey());
            out.writeLong(e.getValue());
        }
        out.flush();
        return bytes.toByteArray();
    }

    public static Map<String, Long> decodeOffsets(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        int count = checkedLength(in.readInt(), 10000);
        Map<String, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String id = in.readUTF();
            long value = in.readLong();
            if (value < 0) throw new IOException("Negative offset");
            result.put(id, value);
        }
        return result;
    }

    public static byte[] encodeChunk(String fileId, long offset, byte[] data, int length) throws IOException {
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
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        String fileId = in.readUTF();
        long offset = in.readLong();
        int length = checkedLength(in.readInt(), ResumableProtocol.DEFAULT_CHUNK_BYTES + 64 * 1024);
        byte[] data = new byte[length];
        in.readFully(data);
        return new Chunk(fileId, offset, data);
    }

    public static byte[] encodeAck(String fileId, long offset) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF(fileId);
        out.writeLong(offset);
        out.flush();
        return bytes.toByteArray();
    }

    public static Ack decodeAck(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return new Ack(in.readUTF(), in.readLong());
    }

    public static byte[] encodeText(String text) {
        return (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
    }

    private static int checkedLength(int value, int max) throws IOException {
        if (value < 0 || value > max) throw new IOException("Invalid length: " + value);
        return value;
    }

    public static final class Frame {
        public final int type;
        public final byte[] payload;
        Frame(int type, byte[] payload) { this.type = type; this.payload = payload; }
    }

    public static final class Chunk {
        public final String fileId;
        public final long offset;
        public final byte[] data;
        Chunk(String fileId, long offset, byte[] data) { this.fileId = fileId; this.offset = offset; this.data = data; }
    }

    public static final class Ack {
        public final String fileId;
        public final long offset;
        Ack(String fileId, long offset) { this.fileId = fileId; this.offset = offset; }
    }
}
