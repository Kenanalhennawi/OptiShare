from pathlib import Path

p = Path('app/src/main/java/com/kenan/optishare/transfer/TransferService.java')
s = p.read_text()
old = '''                WifiDirectRecovery.Peer peer = wifiRecovery.capture(2500);
                String host = peer != null && peer.host != null ? peer.host : initialHost;
                String peerAddress = peer == null ? null : peer.deviceAddress;
                senderStore.save(host, peerAddress, currentRoute, activeItems, activeManifest);
                runSenderLoop(host, peerAddress, engine);'''
new = '''                WifiDirectRecovery.Peer peer = RoutePerformanceStore.ROUTE_DIRECT.equals(currentRoute)
                        ? wifiRecovery.capture(2500) : null;
                String host = peer != null && peer.host != null ? peer.host : initialHost;
                String peerAddress = peer == null ? null : peer.deviceAddress;
                senderStore.save(host, peerAddress, currentRoute, activeItems, activeManifest);
                runSenderLoop(host, peerAddress, engine);'''
if s.count(old) != 1:
    raise SystemExit('startSender recovery anchor not found exactly once')
s = s.replace(old, new, 1)

old2 = '''        WifiDirectRecovery.Peer refreshed = wifiRecovery.recover(peerAddress, 3500);
        if (refreshed != null && refreshed.host != null) host = refreshed.host;'''
new2 = '''        WifiDirectRecovery.Peer refreshed = RoutePerformanceStore.ROUTE_DIRECT.equals(currentRoute)
                ? wifiRecovery.recover(peerAddress, 3500) : null;
        if (refreshed != null && refreshed.host != null) host = refreshed.host;'''
if old2 in s:
    s = s.replace(old2, new2)

p.write_text(s)
print('LAN host preservation patch applied')
