from pathlib import Path

p = Path('app/src/main/java/com/kenan/optishare/V2Activity.java')
s = p.read_text(encoding='utf-8').replace('\r\n','\n')
old = '        addHistory(root);\n\n        LinearLayout security = card();'
new = '''        addHistory(root);\n\n        Button receivedFiles = primary("Received files center →");\n        receivedFiles.setOnClickListener(v -> startActivity(new Intent(this, ReceivedFilesActivity.class)));\n        LinearLayout.LayoutParams receivedLp = new LinearLayout.LayoutParams(-1, dp(50));\n        receivedLp.setMargins(0, dp(12), 0, 0);\n        root.addView(receivedFiles, receivedLp);\n\n        LinearLayout security = card();'''
if old not in s:
    raise SystemExit('home history anchor not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')
print('received center linked from home')
