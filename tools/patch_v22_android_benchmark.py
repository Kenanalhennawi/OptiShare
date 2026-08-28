from pathlib import Path

ROOT = Path('.')

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one anchor, found {count}')
    return text.replace(old, new, 1)

# SessionWire: add authenticated benchmark frame types and bounded size codec.
p = ROOT / 'app/src/main/java/com/kenan/optishare/protocol/SessionWire.java'
s = p.read_text()
s = replace_once(s,
'''    public static final int TYPE_IDENTITY = 8;\n    public static final int MAX_FRAME = 2 * 1024 * 1024;''',
'''    public static final int TYPE_IDENTITY = 8;\n    public static final int TYPE_BENCHMARK_BEGIN = 9;\n    public static final int TYPE_BENCHMARK_DATA = 10;\n    public static final int TYPE_BENCHMARK_DONE = 11;\n    public static final int MAX_FRAME = 2 * 1024 * 1024;\n    public static final int BENCHMARK_BLOCK_BYTES = 512 * 1024;\n    public static final long BENCHMARK_TOTAL_BYTES = 8L * 1024L * 1024L;\n    public static final long MAX_BENCHMARK_BYTES = 64L * 1024L * 1024L;''', 'SessionWire frame constants')
s = replace_once(s,
'''    public static byte[] encodeText(String text) {\n        String safe = text == null ? "" : text;\n        byte[] encoded = safe.getBytes(StandardCharsets.UTF_8);\n        if (encoded.length > 64 * 1024) {\n            throw new IllegalArgumentException("Text payload too large");\n        }\n        return encoded;\n    }\n\n    private static DataInputStream payloadInput(byte[] payload) throws IOException {''',
'''    public static byte[] encodeText(String text) {\n        String safe = text == null ? "" : text;\n        byte[] encoded = safe.getBytes(StandardCharsets.UTF_8);\n        if (encoded.length > 64 * 1024) {\n            throw new IllegalArgumentException("Text payload too large");\n        }\n        return encoded;\n    }\n\n    public static byte[] encodeBenchmarkSize(long bytes) throws IOException {\n        if (bytes <= 0 || bytes > MAX_BENCHMARK_BYTES) {\n            throw new IOException("Invalid benchmark size: " + bytes);\n        }\n        ByteArrayOutputStream buffer = new ByteArrayOutputStream(8);\n        DataOutputStream out = new DataOutputStream(buffer);\n        out.writeLong(bytes);\n        out.flush();\n        return buffer.toByteArray();\n    }\n\n    public static long decodeBenchmarkSize(byte[] payload) throws IOException {\n        DataInputStream in = payloadInput(payload);\n        long bytes = in.readLong();\n        if (bytes <= 0 || bytes > MAX_BENCHMARK_BYTES) {\n            throw new IOException("Invalid benchmark size: " + bytes);\n        }\n        ensureConsumed(in);\n        return bytes;\n    }\n\n    private static DataInputStream payloadInput(byte[] payload) throws IOException {''', 'SessionWire benchmark codec')
s = replace_once(s,
'''        if (type < TYPE_MANIFEST || type > TYPE_IDENTITY) {''',
'''        if (type < TYPE_MANIFEST || type > TYPE_BENCHMARK_DONE) {''', 'SessionWire type validation')
p.write_text(s)

# TransferEngine: benchmark through the same ECDH/AES-GCM session without publishing a file.
p = ROOT / 'app/src/main/java/com/kenan/optishare/transfer/TransferEngine.java'
s = p.read_text()
s = replace_once(s,
'''        void onCompleted(String sessionId);\n        void onError(String sessionId, Throwable error, boolean resumable);\n    }''',
'''        void onCompleted(String sessionId);\n        void onError(String sessionId, Throwable error, boolean resumable);\n        default void onBenchmarkCompleted(long bytes, long durationMs, double bytesPerSecond) { }\n    }''', 'TransferEngine listener')
benchmark_method = r'''
    /** Measures the authenticated encrypted Android-to-Android transport without creating a file. */
    public void benchmark(Socket socket, Listener listener) throws Exception {
        tuneSocket(socket);
        try (DataInputStream in = new DataInputStream(
                     new BufferedInputStream(socket.getInputStream(), STREAM_BUFFER));
             DataOutputStream out = new DataOutputStream(
                     new BufferedOutputStream(socket.getOutputStream(), STREAM_BUFFER))) {
            SessionWire.Handshake handshake = SessionWire.clientHandshake(in, out);
            String peerFingerprint = exchangeClientIdentity(in, out, handshake);
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

'''
s = replace_once(s,
'''    public void receive(Socket socket, Listener listener) throws Exception {''',
benchmark_method + '''    public void receive(Socket socket, Listener listener) throws Exception {''', 'TransferEngine benchmark insertion')
s = replace_once(s,
'''            SessionWire.Frame manifestFrame = SessionWire.readFrame(in, handshake.crypto);\n            if (manifestFrame.type != SessionWire.TYPE_MANIFEST) {\n                throw new IOException("Expected transfer manifest");\n            }\n            BatchManifest manifest = SessionWire.decodeManifest(manifestFrame.payload);''',
'''            SessionWire.Frame manifestFrame = SessionWire.readFrame(in, handshake.crypto);\n            if (manifestFrame.type == SessionWire.TYPE_BENCHMARK_BEGIN) {\n                receiveBenchmark(in, out, handshake, manifestFrame, listener);\n                return;\n            }\n            if (manifestFrame.type != SessionWire.TYPE_MANIFEST) {\n                throw new IOException("Expected transfer manifest");\n            }\n            BatchManifest manifest = SessionWire.decodeManifest(manifestFrame.payload);''', 'TransferEngine receive benchmark branch')
receive_helper = r'''
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

'''
s = replace_once(s,
'''    private String exchangeClientIdentity(DataInputStream in, DataOutputStream out,''',
receive_helper + '''    private String exchangeClientIdentity(DataInputStream in, DataOutputStream out,''', 'TransferEngine receive helper')
p.write_text(s)

# TransferService: add sender action and events; receiver already uses TransferEngine.receive.
p = ROOT / 'app/src/main/java/com/kenan/optishare/transfer/TransferService.java'
s = p.read_text()
s = replace_once(s,
'''    public static final String ACTION_SEND = "com.kenan.optishare.action.SEND";''',
'''    public static final String ACTION_SEND = "com.kenan.optishare.action.SEND";\n    public static final String ACTION_BENCHMARK = "com.kenan.optishare.action.BENCHMARK";''', 'TransferService action')
s = replace_once(s,
'''        } else if (ACTION_SEND.equals(action)) {\n            startSender(intent);\n        } else if (ACTION_RESUME_PENDING.equals(action)) {''',
'''        } else if (ACTION_SEND.equals(action)) {\n            startSender(intent);\n        } else if (ACTION_BENCHMARK.equals(action)) {\n            startBenchmark(intent);\n        } else if (ACTION_RESUME_PENDING.equals(action)) {''', 'TransferService dispatch')
s = replace_once(s,
'''            @Override public void onCompleted(String sessionId) {\n                String summary = benchmarkSummary("Received");\n                updateNotification("Transfer complete", summary, 100, false);\n                broadcastCompleted(summary, sessionId, "incoming");\n            }\n\n            @Override public void onError''',
'''            @Override public void onCompleted(String sessionId) {\n                String summary = benchmarkSummary("Received");\n                updateNotification("Transfer complete", summary, 100, false);\n                broadcastCompleted(summary, sessionId, "incoming");\n            }\n\n            @Override public void onBenchmarkCompleted(long bytes, long durationMs, double bytesPerSecond) {\n                String summary = "Encrypted Android speed test • " + formatBytes(bytes)\n                        + " in " + formatElapsed(durationMs / 1000.0) + " • " + formatSpeed(bytesPerSecond);\n                updateNotification("Speed test complete", summary, 100, false);\n                broadcastBenchmarkCompleted(summary, bytes, durationMs, bytesPerSecond, "incoming");\n            }\n\n            @Override public void onError''', 'TransferService receiver benchmark callback')
start_benchmark = r'''
    private void startBenchmark(Intent intent) {
        final String host = intent.getStringExtra(EXTRA_HOST);
        String requestedRoute = intent.getStringExtra(EXTRA_ROUTE);
        currentRoute = RoutePerformanceStore.ROUTE_LAN.equals(requestedRoute)
                ? RoutePerformanceStore.ROUTE_LAN : RoutePerformanceStore.ROUTE_DIRECT;
        if (host == null || host.trim().isEmpty()) {
            broadcast("benchmark_error", "Missing Android receiver for speed test", 0, 0, null);
            stopSelf();
            return;
        }
        if (!running.compareAndSet(false, true)) return;
        activeManifest = null;
        activeItems = null;
        activePeerFingerprint = null;
        resetMetrics();
        updateNotification("Android speed test", "Measuring encrypted local throughput", 0, true);
        broadcast("benchmark_started", "Testing the encrypted Android route…", 0, 0, null);
        executor.execute(() -> {
            try {
                Socket socket = new Socket();
                activeSocket = socket;
                socket.connect(new InetSocketAddress(host, PORT), 8000);
                new TransferEngine(this).benchmark(socket, new TransferEngine.Listener() {
                    @Override public boolean onPeerIdentity(String fingerprint) {
                        activePeerFingerprint = fingerprint;
                        boolean trusted = trustedStore.isTrusted(fingerprint);
                        if (trusted) broadcast("trusted_peer", "Trusted device verified ✓", 0, 0, null);
                        return trusted;
                    }

                    @Override public void onSecurityCode(String code) {
                        requireSecurityConfirmation(code, null);
                    }

                    @Override public void onIncomingBatch(BatchManifest manifest) { }
                    @Override public boolean acceptIncomingBatch(BatchManifest manifest) { return true; }
                    @Override public void onProgress(String sessionId, String fileId, String fileName,
                                                     long done, long total, long batchDone,
                                                     long batchTotal, double bytesPerSecond) { }
                    @Override public void onFileCompleted(String sessionId, String fileId, Uri publishedUri) { }
                    @Override public void onCompleted(String sessionId) { }
                    @Override public void onError(String sessionId, Throwable error, boolean resumable) { }

                    @Override public void onBenchmarkCompleted(long bytes, long durationMs,
                                                               double bytesPerSecond) {
                        routeStore.recordSuccess(currentRoute, bytesPerSecond);
                        String summary = "Encrypted Android speed test • " + formatBytes(bytes)
                                + " in " + formatElapsed(durationMs / 1000.0)
                                + " • " + formatSpeed(bytesPerSecond)
                                + " • " + routeLabel(currentRoute);
                        updateNotification("Speed test complete", summary, 100, false);
                        broadcastBenchmarkCompleted(summary, bytes, durationMs,
                                bytesPerSecond, currentRoute);
                    }
                });
            } catch (Exception error) {
                routeStore.recordFailure(currentRoute);
                String message = safe(error);
                if (message.toLowerCase(Locale.US).contains("invalid frame type")) {
                    message = "The other OptiShare version does not support Android speed test yet";
                }
                broadcast("benchmark_error", message, 0, 0, null);
                updateNotification("Speed test failed", message, 0, false);
            } finally {
                running.set(false);
                closeSocket();
                stopForeground(false);
                stopSelf();
            }
        });
    }

'''
s = replace_once(s,
'''    private void startPendingSender(SenderSessionStore.Pending pending) {''',
start_benchmark + '''    private void startPendingSender(SenderSessionStore.Pending pending) {''', 'TransferService benchmark sender')
broadcast_benchmark = r'''
    private void broadcastBenchmarkCompleted(String message, long bytes, long durationMs,
                                             double speed, String route) {
        Intent intent = new Intent(ACTION_EVENT);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_EVENT, "benchmark_completed");
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_PROGRESS, 100);
        intent.putExtra(EXTRA_SPEED, speed);
        intent.putExtra(EXTRA_DONE, bytes);
        intent.putExtra(EXTRA_TOTAL, bytes);
        intent.putExtra(EXTRA_TOTAL_BYTES, bytes);
        intent.putExtra(EXTRA_DURATION_MS, durationMs);
        intent.putExtra(EXTRA_ROUTE, route == null ? "unknown" : route);
        sendBroadcast(intent);
    }

'''
s = replace_once(s,
'''    private void broadcastProgress(String message, int progress, double speed, String session,''',
broadcast_benchmark + '''    private void broadcastProgress(String message, int progress, double speed, String session,''', 'TransferService benchmark broadcast')
p.write_text(s)

# V2Activity: expose Speed test on Android peers and display the measured result.
p = ROOT / 'app/src/main/java/com/kenan/optishare/V2Activity.java'
s = p.read_text()
s = replace_once(s,
'''    private boolean pcTransferMode;''',
'''    private boolean pcTransferMode;\n    private boolean benchmarkMode;''', 'V2 benchmark state')
s = replace_once(s,
'''            } else if ("text_received".equals(event)) {''',
'''            } else if ("benchmark_started".equals(event)) {\n                setConnectionUi("ENCRYPTED SPEED TEST", Color.rgb(89,205,255));\n                setTransferUi("Measuring Android route", message, 0);\n            } else if ("benchmark_completed".equals(event)) {\n                setConnectionUi("SPEED TEST ✓", Color.rgb(65,225,151));\n                setTransferUi("Speed test complete ✓", message, 100);\n                setTransferMetrics(100, completedTotalBytes, completedTotalBytes, speed, 0);\n                transferStarted = false;\n            } else if ("benchmark_error".equals(event)) {\n                setConnectionUi("SPEED TEST FAILED", Color.rgb(255,92,102));\n                setTransferUi("Speed test could not finish", message, -1);\n                transferStarted = false;\n            } else if ("text_received".equals(event)) {''', 'V2 benchmark events')
old_loop_start = s.index('            for(WifiP2pDevice device:peers){')
old_loop_end = s.index('        });\n    }\n\n    private void connectTo(WifiP2pDevice device)', old_loop_start)
new_loop = '''            for(WifiP2pDevice device:peers){\n                LinearLayout row=card();\n                LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);\n                TextView avatar=text(firstLetter(deviceName(device)),18,Color.WHITE,true);avatar.setGravity(Gravity.CENTER);avatar.setBackground(gradient(Color.rgb(38,151,232),Color.rgb(62,91,220),18));\n                line.addView(avatar,new LinearLayout.LayoutParams(dp(48),dp(48)));\n                LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);names.setPadding(dp(12),0,0,0);\n                names.addView(text(deviceName(device),15,Color.WHITE,true));\n                names.addView(text(deviceStatus(device.status)+" • encrypted Android peer",12,Color.rgb(151,182,205),false));\n                line.addView(names,new LinearLayout.LayoutParams(0,-2,1));row.addView(line);\n                LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);actions.setPadding(0,dp(10),0,0);\n                Button test=secondaryButton("Speed test");test.setOnClickListener(v->benchmarkDevice(device));\n                Button connect=secondaryButton("Send here");connect.setOnClickListener(v->connectTo(device));\n                actions.addView(test,new LinearLayout.LayoutParams(0,dp(46),1));\n                LinearLayout.LayoutParams sendLp=new LinearLayout.LayoutParams(0,dp(46),1);sendLp.setMargins(dp(8),0,0,0);actions.addView(connect,sendLp);\n                row.addView(actions);\n                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(8));peerList.addView(row,lp);\n            }\n'''
s = s[:old_loop_start] + new_loop + s[old_loop_end:]
benchmark_connect = r'''
    private void benchmarkDevice(WifiP2pDevice device) {
        benchmarkMode=true;pcTransferMode=true;stopPcDiscovery();
        if(!ensureNearbyReady())return;
        discoveryHandler.removeCallbacks(discoveryRetry);stopLanDiscovery();
        connectedPeerName=deviceName(device);setConnectionUi("CONNECTING FOR TEST…",Color.rgb(89,205,255));
        setDiscoveryText("Connecting to "+connectedPeerName+" for encrypted speed test…");
        WifiP2pConfig config=new WifiP2pConfig();config.deviceAddress=device.deviceAddress;config.wps.setup=WpsInfo.PBC;config.groupOwnerIntent=0;
        try{manager.connect(channel,config,new WifiP2pManager.ActionListener(){
            @Override public void onSuccess(){setDiscoveryText("Speed-test connection request sent…");}
            @Override public void onFailure(int reason){benchmarkMode=false;pcTransferMode=false;setConnectionUi("CONNECTION FAILED",Color.rgb(255,91,101));setDiscoveryText(p2pError("Speed-test connection failed",reason));}
        });}catch(SecurityException e){benchmarkMode=false;pcTransferMode=false;showNearbyPermissionHelp();}
    }

'''
s = replace_once(s,
'''    private void connectTo(WifiP2pDevice device) {\n        pcTransferMode=false;stopPcDiscovery();''',
benchmark_connect + '''    private void connectTo(WifiP2pDevice device) {\n        benchmarkMode=false;pcTransferMode=false;stopPcDiscovery();''', 'V2 benchmark connect')
s = replace_once(s,
'''    @Override public void onConnectionInfoAvailable(WifiP2pInfo info) {\n        if(info==null||!info.groupFormed||info.groupOwnerAddress==null)return;setConnectionUi("CONNECTED ✓",Color.rgb(65,225,151));\n        if(receiverMode&&info.isGroupOwner){setDiscoveryText("CONNECTED ✓\\nSecure receiver channel is active.");startReceiverService();}\n        else if(!receiverMode&&!info.isGroupOwner&&!transferStarted){activeRoute=RoutePerformanceStore.ROUTE_DIRECT;transferStarted=true;stopLanDiscovery();activeTransferStartedAt=System.currentTimeMillis();showTransferScreen("Sending");setConnectionUi("SMART ROUTE • WI-FI DIRECT ✓",Color.rgb(65,225,151));startSenderService(info.groupOwnerAddress.getHostAddress());}\n    }''',
'''    @Override public void onConnectionInfoAvailable(WifiP2pInfo info) {\n        if(info==null||!info.groupFormed||info.groupOwnerAddress==null)return;setConnectionUi("CONNECTED ✓",Color.rgb(65,225,151));\n        if(receiverMode&&info.isGroupOwner){setDiscoveryText("CONNECTED ✓\\nSecure receiver channel is active.");startReceiverService();}\n        else if(!receiverMode&&!info.isGroupOwner&&!transferStarted){\n            activeRoute=RoutePerformanceStore.ROUTE_DIRECT;transferStarted=true;stopLanDiscovery();activeTransferStartedAt=System.currentTimeMillis();\n            if(benchmarkMode){\n                pcTransferMode=true;showTransferScreen("Android speed test");setConnectionUi("ENCRYPTED SPEED TEST",Color.rgb(89,205,255));\n                setTransferUi("Preparing 8 MB speed test","Uses the same ECDH/AES-GCM transport as Android file transfer. No test file is saved.",0);\n                startBenchmarkService(info.groupOwnerAddress.getHostAddress());\n            }else{\n                showTransferScreen("Sending");setConnectionUi("SMART ROUTE • WI-FI DIRECT ✓",Color.rgb(65,225,151));startSenderService(info.groupOwnerAddress.getHostAddress());\n            }\n        }\n    }''', 'V2 connection info benchmark')
s = replace_once(s,
'''    private void startSenderService(String host) {''',
'''    private void startBenchmarkService(String host) {\n        Intent i=new Intent(this,TransferService.class).setAction(TransferService.ACTION_BENCHMARK);\n        i.putExtra(TransferService.EXTRA_HOST,host);i.putExtra(TransferService.EXTRA_ROUTE,activeRoute);\n        ContextCompat.startForegroundService(this,i);\n    }\n\n    private void startSenderService(String host) {''', 'V2 benchmark service launcher')
s = replace_once(s,
'''        Button cancel=secondaryButton("Cancel transfer");cancel.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Cancel transfer?").setMessage("Confirmed data will remain resumable until the session is cleared.").setPositiveButton("Cancel transfer",(d,w)->{stopTransferService();showHome();}).setNegativeButton("Keep transferring",null).show());LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(50));cp.setMargins(0,dp(12),0,0);root.addView(cancel,cp);''',
'''        if(benchmarkMode){\n            Button back=secondaryButton("Back to nearby devices");back.setOnClickListener(v->{stopTransferService();benchmarkMode=false;pcTransferMode=false;transferStarted=false;showDiscovery();});LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(50));cp.setMargins(0,dp(12),0,0);root.addView(back,cp);\n        }else{\n            Button cancel=secondaryButton("Cancel transfer");cancel.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Cancel transfer?").setMessage("Confirmed data will remain resumable until the session is cleared.").setPositiveButton("Cancel transfer",(d,w)->{stopTransferService();showHome();}).setNegativeButton("Keep transferring",null).show());LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(50));cp.setMargins(0,dp(12),0,0);root.addView(cancel,cp);\n        }''', 'V2 benchmark back action')
p.write_text(s)

# SessionWire tests for bounded benchmark metadata.
p = ROOT / 'app/src/test/java/com/kenan/optishare/protocol/SessionWireTest.java'
s = p.read_text()
s = replace_once(s,
'''    @Test public void decodeOffsetsRejectsNegativeOffset() throws Exception {''',
'''    @Test public void benchmarkSizeRoundTripIsBounded() throws Exception {\n        assertEquals(SessionWire.BENCHMARK_TOTAL_BYTES,\n                SessionWire.decodeBenchmarkSize(SessionWire.encodeBenchmarkSize(SessionWire.BENCHMARK_TOTAL_BYTES)));\n        try {\n            SessionWire.encodeBenchmarkSize(SessionWire.MAX_BENCHMARK_BYTES + 1L);\n            fail("Expected IOException");\n        } catch (IOException expected) {\n            // expected\n        }\n    }\n\n    @Test public void decodeOffsetsRejectsNegativeOffset() throws Exception {''', 'SessionWire benchmark test')
p.write_text(s)

print('Android encrypted speed test patch applied')
