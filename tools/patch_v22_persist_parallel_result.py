from pathlib import Path
p=Path('app/src/main/java/com/kenan/optishare/transfer/TransferService.java')
s=p.read_text()
needle='''                int gain = ParallelBenchmarkDecision.improvementPercent(single.bytesPerSecond, dualSpeed);\n                boolean recommendDual = ParallelBenchmarkDecision.recommendTwoStreams(single.bytesPerSecond, dualSpeed);\n                String summary = "1 stream " + formatSpeed(single.bytesPerSecond)'''
replacement='''                int gain = ParallelBenchmarkDecision.improvementPercent(single.bytesPerSecond, dualSpeed);\n                boolean recommendDual = ParallelBenchmarkDecision.recommendTwoStreams(single.bytesPerSecond, dualSpeed);\n                routeStore.recordParallelBenchmark(single.bytesPerSecond, dualSpeed);\n                String summary = "1 stream " + formatSpeed(single.bytesPerSecond)'''
if s.count(needle)!=1: raise SystemExit('parallel result anchor mismatch')
s=s.replace(needle,replacement,1)
p.write_text(s)
print('parallel benchmark persistence patch applied')
