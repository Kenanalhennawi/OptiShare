from pathlib import Path

source = Path('tools/patch_v22_parallel_file_transfer.py').read_text()
start = source.index('# Route store: bind parallel recommendation to benchmarked trusted identity.')
end = source.index('# Striped engine cancellation + only one completion callback on receiver.')
route = r'''# Route store: bind parallel recommendation to benchmarked trusted identity.
p=Path('app/src/main/java/com/kenan/optishare/transfer/RoutePerformanceStore.java')
s=p.read_text()
old=''' + "'''" + r'''    public void recordParallelBenchmark(double singleBytesPerSecond, double dualBytesPerSecond) {
        if (singleBytesPerSecond <= 0d || dualBytesPerSecond <= 0d) return;
        int gain = ParallelBenchmarkDecision.improvementPercent(singleBytesPerSecond, dualBytesPerSecond);
        int streams = ParallelBenchmarkDecision.recommendTwoStreams(singleBytesPerSecond, dualBytesPerSecond) ? 2 : 1;
        prefs.edit()
                .putLong(PARALLEL_SINGLE, Double.doubleToLongBits(singleBytesPerSecond))
                .putLong(PARALLEL_DUAL, Double.doubleToLongBits(dualBytesPerSecond))
                .putInt(PARALLEL_GAIN, gain)
                .putInt(PARALLEL_STREAMS, streams)
                .putLong(PARALLEL_UPDATED, System.currentTimeMillis())
                .apply();
    }''' + "'''" + r'''
new=''' + "'''" + r'''    public void recordParallelBenchmark(double singleBytesPerSecond, double dualBytesPerSecond, String peerFingerprint) {
        if (singleBytesPerSecond <= 0d || dualBytesPerSecond <= 0d) return;
        int gain = ParallelBenchmarkDecision.improvementPercent(singleBytesPerSecond, dualBytesPerSecond);
        int streams = ParallelBenchmarkDecision.recommendTwoStreams(singleBytesPerSecond, dualBytesPerSecond) ? 2 : 1;
        SharedPreferences.Editor edit = prefs.edit()
                .putLong(PARALLEL_SINGLE, Double.doubleToLongBits(singleBytesPerSecond))
                .putLong(PARALLEL_DUAL, Double.doubleToLongBits(dualBytesPerSecond))
                .putInt(PARALLEL_GAIN, gain)
                .putInt(PARALLEL_STREAMS, streams)
                .putLong(PARALLEL_UPDATED, System.currentTimeMillis());
        if (peerFingerprint != null && !peerFingerprint.trim().isEmpty()) edit.putString("parallel_peer_fingerprint", peerFingerprint);
        else edit.remove("parallel_peer_fingerprint");
        edit.apply();
    }

    public boolean parallelRecommended() { return recommendedStreams() == 2; }
    public String parallelPeerFingerprint() { return prefs.getString("parallel_peer_fingerprint", null); }''' + "'''" + r'''
if old not in s: raise SystemExit('current route parallel method anchor missing')
s=s.replace(old,new,1)
p.write_text(s)

'''
patched = source[:start] + route + source[end:]
exec(compile(patched, 'parallel_patch_v2', 'exec'))
