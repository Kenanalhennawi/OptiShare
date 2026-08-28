from pathlib import Path


def patch(path, old, new, count=1):
    p=Path(path)
    text=p.read_text(encoding='utf-8').replace('\r\n','\n')
    if old not in text:
        raise SystemExit(f'anchor not found in {path}: {old[:120]!r}')
    p.write_text(text.replace(old,new,count),encoding='utf-8')

# Surface local Browser/PC clipboard and benchmark results in Android receive UI.
p='app/src/main/java/com/kenan/optishare/V2Activity.java'
patch(p,
'''            }else if("completed".equals(event)){\n                setDiscoveryText(message);\n                setConnectionUi("BROWSER FILE SAVED ✓",Color.rgb(65,225,151));\n            }else if("error".equals(event)){''',
'''            }else if("completed".equals(event)){\n                setDiscoveryText(message);\n                setConnectionUi("BROWSER FILE SAVED ✓",Color.rgb(65,225,151));\n            }else if("clipboard".equals(event)){\n                setDiscoveryText(message);\n                setConnectionUi("CLIPBOARD COPIED ✓",Color.rgb(65,225,151));\n            }else if("benchmark".equals(event)){\n                setDiscoveryText(message);\n                setConnectionUi("SPEED TEST ✓",Color.rgb(89,205,255));\n            }else if("error".equals(event)){''')

# Explicit STA guarantees Windows Forms Clipboard APIs work in the hidden receiver process.
p='windows/OptiShare-Companion.ps1'
patch(p,
'''            '-NoProfile','-ExecutionPolicy','Bypass','-File',('"' + $receiverScript + '"')''',
'''            '-NoProfile','-Sta','-ExecutionPolicy','Bypass','-File',('"' + $receiverScript + '"')''')

print('clipboard UX polish applied')
