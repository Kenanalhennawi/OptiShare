from pathlib import Path

p = Path('app/src/main/java/com/kenan/optishare/V2Activity.java')
s = p.read_text()
old = '''                    pendingLanName=name;pendingLanHost=host;
                    renderPeers();
                    setDiscoveryText("Verified OptiShare receiver found on the same Wi-Fi • connecting securely…");
                    discoveryHandler.removeCallbacks(lanFallbackConnect);
                    discoveryHandler.postDelayed(lanFallbackConnect,450L);'''
new = '''                    pendingLanName=name;pendingLanHost=host;
                    renderPeers();
                    setConnectionUi("OPTISHARE FOUND",Color.rgb(65,225,151));
                    setDiscoveryText("Verified OptiShare receiver found • choose Speed test or Send here below.");
                    discoveryHandler.removeCallbacks(lanFallbackConnect);'''
count = s.count(old)
if count != 1:
    raise SystemExit(f'LAN auto-connect anchor count={count}')
s = s.replace(old, new, 1)
p.write_text(s)
print('manual LAN choice patch applied')
