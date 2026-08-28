from pathlib import Path

exec(Path('tools/patch_v22_browser_receive.py').read_text(), {'__name__': '__main__'})

p = Path('app/src/main/java/com/kenan/optishare/V2Activity.java')
s = p.read_text()
old = '''label.setText(url+"
One-time local session • phone approval required");'''
new = 'label.setText(url+"\\nOne-time local session • phone approval required");'
if old not in s:
    raise RuntimeError('browser UI newline anchor not found')
p.write_text(s.replace(old, new, 1))
