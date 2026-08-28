from pathlib import Path

# SenderSessionStore: persist accumulated data-transfer time across pause/resume.
p=Path('app/src/main/java/com/kenan/optishare/transfer/SenderSessionStore.java')
s=p.read_text()
s=s.replace('''        public final List<TransferItem> items;\n        public final BatchManifest manifest;''','''        public final List<TransferItem> items;\n        public final BatchManifest manifest;\n        public final long elapsedDataMs;''',1)
s=s.replace('''        Pending(String host, String peerAddress, String route, List<TransferItem> items,\n                BatchManifest manifest) {\n            this.host = host;\n            this.peerAddress = peerAddress;\n            this.route = route;\n            this.items = items;\n            this.manifest = manifest;\n        }''','''        Pending(String host, String peerAddress, String route, List<TransferItem> items,\n                BatchManifest manifest, long elapsedDataMs) {\n            this.host = host;\n            this.peerAddress = peerAddress;\n            this.route = route;\n            this.items = items;\n            this.manifest = manifest;\n            this.elapsedDataMs = Math.max(0L, elapsedDataMs);\n        }''',1)
s=s.replace('''        root.put("createdAt", manifest.getCreatedAt());\n        JSONArray array = new JSONArray();''','''        root.put("createdAt", manifest.getCreatedAt());\n        root.put("elapsedDataMs", 0L);\n        JSONArray array = new JSONArray();''',1)
s=s.replace('''            long createdAt = root.getLong("createdAt");\n            JSONArray array = root.getJSONArray("files");''','''            long createdAt = root.getLong("createdAt");\n            long elapsedDataMs = Math.max(0L, root.optLong("elapsedDataMs", 0L));\n            JSONArray array = root.getJSONArray("files");''',1)
s=s.replace('''            return new Pending(host, peerAddress, route, items,\n                    new BatchManifest(sessionId, createdAt, entries));''','''            return new Pending(host, peerAddress, route, items,\n                    new BatchManifest(sessionId, createdAt, entries), elapsedDataMs);''',1)
anchor='''    public synchronized boolean exists() {'''
method='''    public synchronized void updateElapsedDataMs(long elapsedDataMs) {\n        if (!file.exists()) return;\n        try {\n            StringBuilder raw = new StringBuilder();\n            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {\n                String line;\n                while ((line = reader.readLine()) != null) raw.append(line);\n            }\n            JSONObject root = new JSONObject(raw.toString());\n            root.put("elapsedDataMs", Math.max(0L, elapsedDataMs));\n            File temp = new File(file.getParentFile(), file.getName() + ".tmp");\n            try (FileWriter writer = new FileWriter(temp, false)) {\n                writer.write(root.toString());\n                writer.flush();\n            }\n            if (file.exists() && !file.delete()) throw new IllegalStateException("Could not replace pending session metrics");\n            if (!temp.renameTo(file)) throw new IllegalStateException("Could not commit pending session metrics");\n        } catch (Exception ignored) {\n            // Transfer remains resumable even if timing telemetry cannot be persisted.\n        }\n    }\n\n'''
if anchor not in s: raise SystemExit('sender store metrics anchor missing')
s=s.replace(anchor,method+anchor,1)
p.write_text(s)

# TransferService: accumulate data-transfer duration over pause/resume instead of resetting it.
p=Path('app/src/main/java/com/kenan/optishare/transfer/TransferService.java')
s=p.read_text()
s=s.replace('''    private volatile long dataTransferStartedNanos;\n    private volatile int reconnectCount;''','''    private volatile long dataTransferStartedNanos;\n    private volatile long accumulatedDataTransferMs;\n    private volatile boolean resumedSession;\n    private volatile int reconnectCount;''',1)
s=s.replace('''        dataTransferStartedNanos = 0L;\n        reconnectCount = 0;''','''        dataTransferStartedNanos = 0L;\n        accumulatedDataTransferMs = 0L;\n        resumedSession = false;\n        reconnectCount = 0;''',1)
# Receiver reset occurrence should also clear the new fields.
s=s.replace('''        dataTransferStartedNanos = 0L;\n        reconnectCount = 0;\n        latestBatchDone = 0L;''','''        dataTransferStartedNanos = 0L;\n        accumulatedDataTransferMs = 0L;\n        resumedSession = false;\n        reconnectCount = 0;\n        latestBatchDone = 0L;''',1)
# Restore timing before starting pending sender.
old='''        currentRoute = RoutePerformanceStore.ROUTE_LAN.equals(pending.route)\n                ? RoutePerformanceStore.ROUTE_LAN : RoutePerformanceStore.ROUTE_DIRECT;\n        resetMetrics();\n        executor.execute(() -> {'''
new='''        currentRoute = RoutePerformanceStore.ROUTE_LAN.equals(pending.route)\n                ? RoutePerformanceStore.ROUTE_LAN : RoutePerformanceStore.ROUTE_DIRECT;\n        resetMetrics();\n        accumulatedDataTransferMs = Math.max(0L, pending.elapsedDataMs);\n        resumedSession = accumulatedDataTransferMs > 0L;\n        executor.execute(() -> {'''
if old not in s: raise SystemExit('pending sender timing anchor missing')
s=s.replace(old,new,1)
# Pause: persist elapsed before zeroing/running false.
old='''        int p = percent(latestBatchDone, activeManifest.totalBytes());\n        running.set(false);\n        if (stripedActive && activeStripedEngine != null) activeStripedEngine.cancel();'''
new='''        int p = percent(latestBatchDone, activeManifest.totalBytes());\n        accumulatedDataTransferMs = currentAccumulatedDataMs();\n        dataTransferStartedNanos = 0L;\n        senderStore.updateElapsedDataMs(accumulatedDataTransferMs);\n        running.set(false);\n        if (stripedActive && activeStripedEngine != null) activeStripedEngine.cancel();'''
if old not in s: raise SystemExit('pause timing anchor missing')
s=s.replace(old,new,1)
# Failure: persist timing for later manual resume.
old='''        } else {\n            routeStore.recordFailure(currentRoute);\n            broadcast("error", safe(error) + " — session kept for resume", 0, 0, session);'''
new='''        } else {\n            accumulatedDataTransferMs = currentAccumulatedDataMs();\n            dataTransferStartedNanos = 0L;\n            senderStore.updateElapsedDataMs(accumulatedDataTransferMs);\n            routeStore.recordFailure(currentRoute);\n            broadcast("error", safe(error) + " — session kept for resume", 0, 0, session);'''
if old not in s: raise SystemExit('failure timing anchor missing')
s=s.replace(old,new,1)
# Broadcast completion duration should use accumulated data transfer duration.
old='''        long timingStart = dataTransferStartedNanos != 0L ? dataTransferStartedNanos : activeTransferStartedNanos;\n        long durationMs = timingStart == 0L ? 0L\n                : Math.max(0L, Math.round((System.nanoTime() - timingStart) / 1_000_000.0));'''
new='''        long durationMs = dataElapsedMsForSummary();'''
if old not in s: raise SystemExit('completion duration anchor missing')
s=s.replace(old,new,1)
# Average speed should use accumulated duration.
old='''    private double averageBytesPerSecond() {\n        long total = activeManifest == null ? latestBatchDone : activeManifest.totalBytes();\n        long timingStart = dataTransferStartedNanos != 0L ? dataTransferStartedNanos : activeTransferStartedNanos;\n        double seconds = timingStart == 0L ? 0d : Math.max(0.001, (System.nanoTime() - timingStart) / 1_000_000_000.0);\n        return total <= 0 || seconds <= 0 ? latestSpeed : total / seconds;\n    }'''
new='''    private double averageBytesPerSecond() {\n        long total = activeManifest == null ? latestBatchDone : activeManifest.totalBytes();\n        double seconds = Math.max(0.001, dataElapsedMsForSummary() / 1000.0);\n        return total <= 0 ? latestSpeed : total / seconds;\n    }\n\n    private long currentAccumulatedDataMs() {\n        long current = dataTransferStartedNanos == 0L ? 0L\n                : Math.max(0L, Math.round((System.nanoTime() - dataTransferStartedNanos) / 1_000_000.0));\n        return Math.max(0L, accumulatedDataTransferMs + current);\n    }\n\n    private long dataElapsedMsForSummary() {\n        long dataMs = currentAccumulatedDataMs();\n        if (dataMs > 0L) return dataMs;\n        return activeTransferStartedNanos == 0L ? 0L\n                : Math.max(0L, Math.round((System.nanoTime() - activeTransferStartedNanos) / 1_000_000.0));\n    }'''
if old not in s: raise SystemExit('average speed timing anchor missing')
s=s.replace(old,new,1)
# Summary should use accumulated duration and label resumed sessions.
old='''        long timingStart = dataTransferStartedNanos != 0L ? dataTransferStartedNanos : activeTransferStartedNanos;\n        double seconds = timingStart == 0L ? 0d\n                : Math.max(0.001, (System.nanoTime() - timingStart) / 1_000_000_000.0);\n        double average = averageBytesPerSecond();\n        StringBuilder value = new StringBuilder();\n        value.append(verb).append(" ").append(formatBytes(total))\n                .append(" in ").append(formatElapsed(seconds))\n                .append(" • avg ").append(formatSpeed(average));'''
new='''        double seconds = Math.max(0.001, dataElapsedMsForSummary() / 1000.0);\n        double average = averageBytesPerSecond();\n        StringBuilder value = new StringBuilder();\n        value.append(verb).append(" ").append(formatBytes(total))\n                .append(" in ").append(formatElapsed(seconds))\n                .append(" • avg ").append(formatSpeed(average));\n        if (resumedSession) value.append(" • resumed");'''
if old not in s: raise SystemExit('summary timing anchor missing')
s=s.replace(old,new,1)
p.write_text(s)
print('resume metrics patch applied')
