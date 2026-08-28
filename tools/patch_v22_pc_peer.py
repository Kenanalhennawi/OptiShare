from pathlib import Path

p = Path('app/src/main/java/com/kenan/optishare/V2Activity.java')
s = p.read_text()

def once(old, new):
    global s
    if old not in s:
        raise SystemExit('anchor not found: ' + old[:100])
    s = s.replace(old, new, 1)

once('import com.kenan.optishare.transfer.LanDiscovery;\n',
     'import com.kenan.optishare.transfer.LanDiscovery;\nimport com.kenan.optishare.transfer.PcDiscovery;\nimport com.kenan.optishare.transfer.PcTransferService;\n')

once('    private final List<WifiP2pDevice> peers = new ArrayList<>();\n',
     '    private final List<WifiP2pDevice> peers = new ArrayList<>();\n    private final List<PcDiscovery.Peer> pcPeers = new ArrayList<>();\n')

once('    private boolean browserMode;\n',
     '    private boolean browserMode;\n    private boolean pcTransferMode;\n')

once('    private LanDiscovery lanDiscovery;\n',
     '    private LanDiscovery lanDiscovery;\n    private PcDiscovery pcDiscovery;\n')

once('        lanDiscovery = new LanDiscovery(this);\n',
     '        lanDiscovery = new LanDiscovery(this);\n        pcDiscovery = new PcDiscovery(this);\n')

once('    private void showHome() {\n        currentScreen = SCREEN_HOME;\n',
     '    private void showHome() {\n        stopPcDiscovery();\n        currentScreen = SCREEN_HOME;\n')

once('        TextView hint=text("SmartRoute chooses Wi-Fi Direct or same-Wi-Fi automatically. "+routeStore.summary()+" • QR remains a fallback.",12,Color.rgb(150,179,202),false);',
     '        TextView hint=text("SmartRoute finds Android receivers and OptiShare Windows Companion on the local network. "+routeStore.summary()+" • QR remains a fallback.",12,Color.rgb(150,179,202),false);')

once('        startLanDiscovery();\n        if(!ensureNearbyReady())return;\n',
     '        startLanDiscovery();\n        startPcDiscovery();\n        if(!ensureNearbyReady())return;\n')

once('    private void stopLanDiscovery(){\n        discoveryHandler.removeCallbacks(lanFallbackConnect);\n        if(lanDiscovery!=null)lanDiscovery.stopDiscovery();\n    }\n',
'''    private void stopLanDiscovery(){
        discoveryHandler.removeCallbacks(lanFallbackConnect);
        if(lanDiscovery!=null)lanDiscovery.stopDiscovery();
    }

    private void startPcDiscovery(){
        if(pcDiscovery==null||!pcDiscovery.available())return;
        pcPeers.clear();
        pcDiscovery.discover(new PcDiscovery.Listener(){
            @Override public void onPc(PcDiscovery.Peer peer){
                runOnUiThread(()->{
                    if(currentScreen!=SCREEN_DISCOVERY||transferStarted)return;
                    for(PcDiscovery.Peer existing:pcPeers)if(existing.id().equals(peer.id()))return;
                    pcPeers.add(peer);
                    renderPeers();
                    setDiscoveryText("Found "+peer.name+" • Windows Companion ready");
                });
            }
            @Override public void onStatus(String message){
                if(currentScreen==SCREEN_DISCOVERY&&peers.isEmpty()&&pcPeers.isEmpty()&&pendingLanHost==null)setDiscoveryText(message);
            }
        });
    }

    private void stopPcDiscovery(){if(pcDiscovery!=null)pcDiscovery.stop();}

    private void connectToPc(PcDiscovery.Peer peer){
        if(peer==null||transferStarted)return;
        transferStarted=true;pcTransferMode=true;receiverMode=false;
        activeRoute="pc-local";activeTransferStartedAt=System.currentTimeMillis();
        connectedPeerName=peer.name+" • Windows PC";
        discoveryHandler.removeCallbacks(discoveryRetry);stopLanDiscovery();stopPcDiscovery();
        showTransferScreen("Sending to Windows");
        setConnectionUi("PC LOCAL ROUTE ✓",Color.rgb(89,205,255));
        setTransferUi("Connecting to "+peer.name,"Local PC route • session token + SHA-256 verification",0);
        ArrayList<String> uris=new ArrayList<>();for(Uri uri:selected)uris.add(uri.toString());
        Intent i=new Intent(this,PcTransferService.class).setAction(PcTransferService.ACTION_SEND_PC);
        i.putExtra(PcTransferService.EXTRA_HOST,peer.host);i.putExtra(PcTransferService.EXTRA_PORT,peer.port);
        i.putExtra(PcTransferService.EXTRA_TOKEN,peer.token);i.putStringArrayListExtra(PcTransferService.EXTRA_URIS,uris);
        ContextCompat.startForegroundService(this,i);
    }
''')

start = s.index('    private void renderPeers() {')
end = s.index('    private void connectTo(WifiP2pDevice device)', start)
new_render = '''    private void renderPeers() {
        runOnUiThread(()->{
            if(peerList==null)return;
            peerList.removeAllViews();
            if(peers.isEmpty()&&pcPeers.isEmpty()){
                LinearLayout empty=card();empty.addView(text("Searching…",14,Color.WHITE,true));
                empty.addView(text("Looking for Android receivers and OptiShare Windows Companion on this network.",12,Color.rgb(147,173,196),false));
                peerList.addView(empty);return;
            }
            for(PcDiscovery.Peer pc:pcPeers){
                LinearLayout row=card();LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);
                TextView avatar=text("PC",14,Color.WHITE,true);avatar.setGravity(Gravity.CENTER);avatar.setBackground(gradient(Color.rgb(39,178,255),Color.rgb(84,82,222),18));
                line.addView(avatar,new LinearLayout.LayoutParams(dp(48),dp(48)));
                LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);names.setPadding(dp(12),0,0,0);
                names.addView(text(pc.name,15,Color.WHITE,true));names.addView(text("Windows Companion • same network",12,Color.rgb(151,182,205),false));
                line.addView(names,new LinearLayout.LayoutParams(0,-2,1));Button connect=secondaryButton("Send here");connect.setOnClickListener(v->connectToPc(pc));
                line.addView(connect,new LinearLayout.LayoutParams(dp(112),dp(46)));row.addView(line);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(8));peerList.addView(row,lp);
            }
            for(WifiP2pDevice device:peers){
                LinearLayout row=card();LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);TextView avatar=text(firstLetter(deviceName(device)),18,Color.WHITE,true);avatar.setGravity(Gravity.CENTER);avatar.setBackground(gradient(Color.rgb(38,151,232),Color.rgb(62,91,220),18));line.addView(avatar,new LinearLayout.LayoutParams(dp(48),dp(48)));LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);names.setPadding(dp(12),0,0,0);names.addView(text(deviceName(device),15,Color.WHITE,true));names.addView(text(deviceStatus(device.status),12,Color.rgb(151,182,205),false));line.addView(names,new LinearLayout.LayoutParams(0,-2,1));Button connect=secondaryButton("Send here");connect.setOnClickListener(v->connectTo(device));line.addView(connect,new LinearLayout.LayoutParams(dp(112),dp(46)));row.addView(line);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(8));peerList.addView(row,lp);
            }
        });
    }

'''
s = s[:start] + new_render + s[end:]

once('    private void connectTo(WifiP2pDevice device) {\n        if(!ensureNearbyReady())return;',
     '    private void connectTo(WifiP2pDevice device) {\n        pcTransferMode=false;stopPcDiscovery();\n        if(!ensureNearbyReady())return;')

once('        if(!receiverMode){\n            transferPauseButton=secondaryButton("Pause transfer");',
     '        if(!receiverMode&&!pcTransferMode){\n            transferPauseButton=secondaryButton("Pause transfer");')

once('    private void stopTransferService(){startService(new Intent(this,TransferService.class).setAction(TransferService.ACTION_STOP));}\n',
     '    private void stopTransferService(){startService(new Intent(this,TransferService.class).setAction(TransferService.ACTION_STOP));try{startService(new Intent(this,PcTransferService.class).setAction(PcTransferService.ACTION_STOP_PC));}catch(Exception ignored){}}\n')

once('        if(RoutePerformanceStore.ROUTE_LAN.equals(route))return "same Wi-Fi";\n',
     '        if(RoutePerformanceStore.ROUTE_LAN.equals(route))return "same Wi-Fi";\n        if("pc-local".equals(route))return "Windows PC";\n')

once('                transferStarted = false;\n                transferPaused=false;\n',
     '                transferStarted = false;\n                pcTransferMode=false;\n                transferPaused=false;\n')

p.write_text(s)
