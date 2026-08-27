package com.kenan.optishare.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Wire helpers for OptiShare protocol v2 resume negotiation. */
public final class ResumableProtocol {
    public static final int MAGIC = 0x4F533230; // OS20
    public static final int VERSION = 2;

    /**
     * v2.2 fast-transfer chunk size.
     *
     * 1536 KiB keeps each encrypted SessionWire frame comfortably below the existing 2 MiB frame
     * ceiling while reducing per-frame AES-GCM, allocation and socket overhead by roughly one third
     * compared with the previous 1 MiB chunks. Four-chunk durable checkpoints now cover up to
     * 6 MiB, which also reduces fsync/ACK stalls without making resume granularity too coarse.
     */
    public static final int DEFAULT_CHUNK_BYTES = 1536 * 1024;

    private ResumableProtocol() {}

    public static void writeResumeRequest(DataOutputStream out, ResumeState local) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeUTF(local.getSessionId());
        out.writeInt(local.getConfirmedOffsets().size());
        for (Map.Entry<String, Long> entry : local.getConfirmedOffsets().entrySet()) {
            out.writeUTF(entry.getKey());
            out.writeLong(entry.getValue());
        }
        out.flush();
    }

    public static ResumeState readResumeRequest(DataInputStream in) throws IOException {
        int magic = in.readInt();
        int version = in.readInt();
        if (magic != MAGIC) throw new IOException("Invalid OptiShare resume magic");
        if (version != VERSION) throw new IOException("Unsupported OptiShare protocol version: " + version);
        String sessionId = in.readUTF();
        int count = in.readInt();
        if (count < 0 || count > 100000) throw new IOException("Invalid resume entry count");
        Map<String, Long> offsets = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String fileId = in.readUTF();
            long offset = in.readLong();
            if (offset < 0) throw new IOException("Invalid negative resume offset");
            offsets.put(fileId, offset);
        }
        return new ResumeState(sessionId, System.currentTimeMillis(), offsets);
    }

    /**
     * Chooses the safest mutually-confirmed offset. The sender may have written more bytes than the
     * receiver durably stored, so resume always starts from the lower side's confirmed value.
     */
    public static long negotiateOffset(long senderConfirmed, long receiverConfirmed, long fileSize) {
        if (senderConfirmed < 0 || receiverConfirmed < 0 || fileSize < 0) {
            throw new IllegalArgumentException("offsets and fileSize must be >= 0");
        }
        return Math.min(fileSize, Math.min(senderConfirmed, receiverConfirmed));
    }

    public static long alignToChunkBoundary(long offset, int chunkBytes) {
        if (offset < 0 || chunkBytes <= 0) throw new IllegalArgumentException();
        return (offset / chunkBytes) * chunkBytes;
    }
}
