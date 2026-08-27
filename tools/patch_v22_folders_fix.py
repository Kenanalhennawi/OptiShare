from pathlib import Path

exec(Path('tools/patch_v22_folders.py').read_text(), {'__name__': '__main__'})

p = Path('app/src/main/java/com/kenan/optishare/V2Activity.java')
s = p.read_text()
old = 'category("▤","Folder",Color.rgb(122,140,166),v -> openFolder())));'
new = 'category("▤","Folder",Color.rgb(122,140,166),v -> openFolder()));'
if old not in s:
    raise RuntimeError('folder tile syntax anchor not found')
p.write_text(s.replace(old, new, 1))
