package com.kenan.optishare.transfer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import com.kenan.optishare.model.TransferItem;
import com.kenan.optishare.storage.DownloadStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** Native Windows-to-Android encrypted receiver used instead of HTTP by Companion v2. */
final class SecurePcReceiveServer {
    static final int PORT = 49895;
    private static final int BUFFER = 1024 * 1024;
    private static final long MAX_FILE = 2L * 1024L * 1024L * 1024L * 1024L;

    interface TokenSource { String currentToken(); }
    interface Events { void onEvent(String event, String message, int progress); }

    private final Context context;
    private final DownloadStore store;
    private final TokenSource tokens;
    private final Events events;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Semaphore clients = new Semaphore(2);
    private volatile boolean running;
    private volatile ServerSocket server;

    SecurePcReceiveServer(Context context, DownloadStore store, TokenSource tokens, Events events) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.tokens = tokens;
        this.events = events;
    }

    synchronized void start() throws Exception {
        if (running) return;
        server = new ServerSocket(PORT);
        server.setReuseAddress(true);
        running = true;
        executor.execute(this::acceptLoop);
    }

    synchronized void stop() {
        running = false;
        ServerSocket current = server;
        server = null;
        if (current != null) try { current.close(); } catch (Exception ignored) { }
        IncomingApproval.cancel();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                if (!clients.tryAcquire()) { socket.close(); continue; }
                executor.execute(() -> {
                    try { receive(socket); }
                    finally { clients.release(); }
                });
            } catch (Exception error) {
                if (running) events.onEvent("error", "Secure PC receiver stopped: " + safe(error), 0);
            }
        }
    }

    private void receive(Socket socket) {
        byte[] key = null;
        File temp = null;
        try (Socket connection = socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(connection.getInputStream(), BUFFER));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(connection.getOutputStream(), BUFFER))) {
            connection.setSoTimeout(120_000);
            byte[] magic = new byte[PcSecureWire.MAGIC.length];
            in.readFully(magic);
            if (!MessageDigest.isEqual(magic, PcSecureWire.MAGIC)) throw new SecurityException("Invalid secure PC protocol");
            String supplied = readString(in, 512);
            if (!constantTimeEquals(tokens.currentToken(), supplied)) throw new SecurityException("Expired PC discovery session");
            int clientLength = in.readInt();
            if (clientLength < 64 || clientLength > 512) throw new SecurityException("Invalid Windows ECDH key");
            byte[] clientPublic = new byte[clientLength];
            in.readFully(clientPublic);
            byte[] salt = new byte[32];
            in.readFully(salt);
            KeyPair own = PcSecureWire.createEphemeralKeyPair();
            byte[] serverPublic = own.getPublic().getEncoded();
            byte[] shared = PcSecureWire.sharedSecret(own, clientPublic);
            key = PcSecureWire.deriveKey(shared, salt);
            Arrays.fill(shared, (byte) 0);
            out.writeInt(serverPublic.length);
            out.write(serverPublic);
            out.flush();
            String code = PcSecureWire.securityCode(clientPublic, serverPublic, salt);
            String gate = "windows-in:" + code + ":" + System.nanoTime();
            IncomingApproval.begin(gate, "Windows security code: " + code,
                    "Confirm that Windows shows exactly " + code + ".\nThis authorizes an encrypted incoming transfer.");
            boolean androidAccepted = IncomingApproval.await(gate, 120_000L);
            SecureIo secure = new SecureIo(in, out, key);
            String windowsDecision = new String(secure.readClient(), StandardCharsets.US_ASCII);
            secure.writeServer((androidAccepted ? "ACCEPT" : "DECLINE").getBytes(StandardCharsets.US_ASCII));
            if (!androidAccepted || !"ACCEPT".equals(windowsDecision)) throw new SecurityException("Security code confirmation declined");

            byte[] command = secure.readClient();
            if (command.length != 1) throw new SecurityException("Invalid encrypted command");
            if (command[0] == 1) {
                Metadata metadata = parseMetadata(secure.readClient());
                store.ensureCapacity(metadata.size);
                File dir = new File(context.getCacheDir(), "secure_pc_inbox");
                if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("Could not create secure inbox");
                temp = File.createTempFile("incoming-", ".part", dir);
                secure.writeServer(new byte[]{1});
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long remaining = metadata.size;
                long done = 0L;
                try (FileOutputStream file = new FileOutputStream(temp, false)) {
                    while (remaining > 0) {
                        byte[] chunk = secure.readClient();
                        if (chunk.length < 1 || chunk.length > remaining) throw new SecurityException("Invalid encrypted file chunk");
                        file.write(chunk);
                        digest.update(chunk);
                        remaining -= chunk.length;
                        done += chunk.length;
                        events.onEvent("progress", "Encrypted from Windows • " + metadata.name,
                                (int) Math.min(100L, done * 100L / Math.max(1L, metadata.size)));
                    }
                    file.getFD().sync();
                }
                byte[] expected = secure.readClient();
                if (expected.length != 32 || !MessageDigest.isEqual(expected, digest.digest())) throw new SecurityException("SHA-256 verification failed");
                store.publishBrowserFile(temp, metadata.name, metadata.mime);
                if (temp.exists()) temp.delete();
                temp = null;
                secure.writeServer(new byte[]{1});
                events.onEvent("completed", "Encrypted file saved from Windows • SHA-256 verified", 100);
            } else if (command[0] == 2) {
                byte[] text = secure.readClient();
                if (text.length < 1 || text.length > 262_144) throw new SecurityException("Invalid encrypted clipboard size");
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard == null) throw new IllegalStateException("Clipboard unavailable");
                clipboard.setPrimaryClip(ClipData.newPlainText("OptiShare clipboard", new String(text, StandardCharsets.UTF_8)));
                secure.writeServer(new byte[]{1});
                events.onEvent("clipboard", "Encrypted Windows clipboard copied ✓", 100);
            } else throw new SecurityException("Unknown encrypted command");
        } catch (Exception error) {
            if (temp != null && temp.exists()) temp.delete();
            events.onEvent("error", "Secure Windows receive failed: " + safe(error), 0);
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    private static Metadata parseMetadata(byte[] data) throws Exception {
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(data));
        String name = TransferItem.safeName(readString(in, 4096));
        String mime = readString(in, 1024);
        long size = in.readLong();
        if (size < 0 || size > MAX_FILE || in.available() != 0) throw new SecurityException("Invalid encrypted metadata");
        return new Metadata(name, mime, size);
    }

    private static String readString(DataInputStream in, int max) throws Exception {
        int length = in.readInt();
        if (length < 0 || length > max) throw new SecurityException("Invalid metadata length");
        byte[] data = new byte[length];
        in.readFully(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static String safe(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty() ? "secure session error" : message;
    }

    private static final class Metadata {
        final String name, mime;
        final long size;
        Metadata(String name, String mime, long size) { this.name = name; this.mime = mime; this.size = size; }
    }

    private static final class SecureIo {
        final DataInputStream in;
        final DataOutputStream out;
        final byte[] key;
        long received, sent;
        SecureIo(DataInputStream in, DataOutputStream out, byte[] key) { this.in = in; this.out = out; this.key = key; }
        byte[] readClient() throws Exception { int n = in.readInt(); if (n < 30 || n > PcSecureWire.MAX_RECORD_BYTES) throw new SecurityException("Invalid secure record length"); byte[] r = new byte[n]; in.readFully(r); return PcSecureWire.decrypt(key, received++, true, r); }
        void writeServer(byte[] plain) throws Exception { byte[] r = PcSecureWire.encrypt(key, sent++, false, plain); out.writeInt(r.length); out.write(r); out.flush(); }
    }
}
