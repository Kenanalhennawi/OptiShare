from pathlib import Path

p = Path('app/src/main/java/com/kenan/optishare/transfer/TransferEngine.java')
s = p.read_text(encoding='utf-8').replace('\r\n','\n')
s = s.replace('    private static final int CHECKPOINT_CHUNKS = 4;\n', '', 1)
old = '''                int chunksAwaitingCheckpoint = 0;\n\n                if (offset < entry.size) {'''
new = '''                int chunksAwaitingCheckpoint = 0;\n                int checkpointChunks = ResumableProtocol.checkpointChunksForFile(entry.size);\n\n                if (offset < entry.size) {'''
if old not in s:
    raise SystemExit('sender checkpoint anchor not found')
s = s.replace(old, new, 1)
s = s.replace('chunksAwaitingCheckpoint >= CHECKPOINT_CHUNKS',
              'chunksAwaitingCheckpoint >= checkpointChunks', 1)
old2 = '''                        boolean checkpoint = chunksSinceCheckpoint >= CHECKPOINT_CHUNKS\n                                || next == entry.size;'''
new2 = '''                        boolean checkpoint = chunksSinceCheckpoint >= ResumableProtocol.checkpointChunksForFile(entry.size)\n                                || next == entry.size;'''
if old2 not in s:
    raise SystemExit('receiver checkpoint anchor not found')
s = s.replace(old2, new2, 1)
p.write_text(s, encoding='utf-8')
print('adaptive checkpoint engine patched')
