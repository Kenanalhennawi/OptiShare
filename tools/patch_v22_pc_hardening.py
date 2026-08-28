from pathlib import Path
p=Path('app/src/main/java/com/kenan/optishare/transfer/PcTransferService.java')
s=p.read_text()

def once(old,new):
    global s
    if old not in s: raise SystemExit('anchor not found: '+old[:100])
    s=s.replace(old,new,1)

once('import android.database.Cursor;\n', 'import android.database.Cursor;\nimport android.content.res.AssetFileDescriptor;\n')

once('''                    try (InputStream source = new BufferedInputStream(getContentResolver().openInputStream(item.uri), BUFFER)) {
                        if (source == null) throw new IllegalStateException("Cannot open " + item.name);
                        while (fileDone < item.size) {''',
'''                    InputStream raw = getContentResolver().openInputStream(item.uri);
                    if (raw == null) throw new IllegalStateException("Cannot open " + item.name);
                    try (InputStream source = new BufferedInputStream(raw, BUFFER)) {
                        while (fileDone < item.size) {''')

once('''        String name = "file.bin";
        long size = 0L;
''', '''        String name = "file.bin";
        long size = -1L;
''')

once('''                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = Math.max(0L, cursor.getLong(sizeIndex));
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        String mime = getContentResolver().getType(uri);
''', '''                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        if (size < 0L) {
            AssetFileDescriptor descriptor = null;
            try {
                descriptor = getContentResolver().openAssetFileDescriptor(uri, "r");
                if (descriptor != null && descriptor.getLength() >= 0L) size = descriptor.getLength();
            } catch (Exception ignored) {
            } finally {
                if (descriptor != null) try { descriptor.close(); } catch (Exception ignored) { }
            }
        }
        if (size < 0L) {
            throw new IllegalArgumentException("Could not determine file size for Windows transfer: " + name);
        }
        String mime = getContentResolver().getType(uri);
''')

p.write_text(s)
