package com.kenan.optishare.transfer;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

/** Transitional Android/Windows v1 framing, isolated for golden-vector interoperability tests. */
public final class PcWire {
    public static final byte[] MAGIC = "OPTISHARE-PC-1\n".getBytes(StandardCharsets.US_ASCII);
    public static final int COMPLETION_MARKER = 0x0F7152E2;

    private PcWire() { }

    public static void writeString(DataOutputStream out, String value, int maxBytes) throws Exception {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw new IllegalArgumentException("Metadata too long");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    static byte[] metadataVector(String name, String relativePath, String mime, long size) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeString(out, name, 4096);
        writeString(out, relativePath, 8192);
        writeString(out, mime, 1024);
        out.writeLong(size);
        out.flush();
        return bytes.toByteArray();
    }
}
