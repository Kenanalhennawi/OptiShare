from pathlib import Path

exec(Path('tools/patch_v22_browser_receive_fix.py').read_text(), {'__name__': '__main__'})

p = Path('app/src/main/java/com/kenan/optishare/transfer/BrowserReceiveService.java')
s = p.read_text()
anchor = 'import fi.iki.elonen.NanoHTTPD;\n'
if anchor not in s:
    raise RuntimeError('NanoHTTPD import anchor missing')
s = s.replace(anchor, anchor + 'import fi.iki.elonen.NanoHTTPD.Response;\n', 1)
s = s.replace('Response.Status.PAYLOAD_TOO_LARGE', 'Response.Status.BAD_REQUEST')
p.write_text(s)
