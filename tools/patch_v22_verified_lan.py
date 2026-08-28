from pathlib import Path
p=Path('app/src/main/java/com/kenan/optishare/V2Activity.java')
s=p.read_text()

def rep(old,new,label):
    global s
    c=s.count(old)
    if c!=1:
        raise SystemExit(f'{label}: anchor count={c}')
    s=s.replace(old,new,1)

rep('''    private final Runnable lanFallbackConnect = () -> {
        if (currentScreen == SCREEN_DISCOVERY && !transferStarted && peers.isEmpty()
                && pendingLanHost != null) {
            connectViaLan(pendingLanName, pendingLanHost);
        }
    };''','''    private final Runnable lanFallbackConnect = () -> {
        if (currentScreen == SCREEN_DISCOVERY && !transferStarted
                && pendingLanHost != null) {
            connectViaLan(pendingLanName, pendingLanHost);
        }
    };''','lan fallback runnable')

rep('''                    pendingLanName=name;pendingLanHost=host;
                    if(peers.isEmpty()){
                        setDiscoveryText("Found "+name+" on the same Wi-Fi • giving Wi-Fi Direct a moment…");
                        discoveryHandler.removeCallbacks(lanFallbackConnect);
                        discoveryHandler.postDelayed(lanFallbackConnect,routeStore.lanFallbackDelayMillis());
                    }''','''                    pendingLanName=name;pendingLanHost=host;
                    renderPeers();
                    setDiscoveryText("Verified OptiShare receiver found on the same Wi-Fi • connecting securely…");
                    discoveryHandler.removeCallbacks(lanFallbackConnect);
                    discoveryHandler.postDelayed(lanFallbackConnect,450L);''','lan discovery callback')

anchor='''            for(PcDiscovery.Peer pc:pcPeers){'''
insert='''            if(pendingLanHost!=null&&!pendingLanHost.trim().isEmpty()){
                LinearLayout row=card();LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);
                TextView avatar=text("OS",13,Color.WHITE,true);avatar.setGravity(Gravity.CENTER);avatar.setBackground(gradient(Color.rgb(43,196,126),Color.rgb(31,137,213),18));
                line.addView(avatar,new LinearLayout.LayoutParams(dp(48),dp(48)));
                LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);names.setPadding(dp(12),0,0,0);
                String lanName=(pendingLanName==null||pendingLanName.trim().isEmpty())?"OptiShare device":pendingLanName;
                names.addView(text(lanName,15,Color.WHITE,true));
                names.addView(text("Verified OptiShare • encrypted same-Wi-Fi route",12,Color.rgb(151,205,184),false));
                line.addView(names,new LinearLayout.LayoutParams(0,-2,1));row.addView(line);
                LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);actions.setPadding(0,dp(10),0,0);
                Button test=secondaryButton("Speed test");test.setOnClickListener(v->benchmarkViaLan(lanName,pendingLanHost));
                Button send=secondaryButton("Send here");send.setOnClickListener(v->connectViaLan(lanName,pendingLanHost));
                actions.addView(test,new LinearLayout.LayoutParams(0,dp(46),1));
                LinearLayout.LayoutParams sendLp=new LinearLayout.LayoutParams(0,dp(46),1);sendLp.setMargins(dp(8),0,0,0);actions.addView(send,sendLp);
                row.addView(actions);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(8));peerList.addView(row,lp);
            }
            for(PcDiscovery.Peer pc:pcPeers){'''
rep(anchor,insert,'verified LAN card')

old='''                LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);actions.setPadding(0,dp(10),0,0);
                Button test=secondaryButton("Speed test");test.setOnClickListener(v->benchmarkDevice(device));
                Button connect=secondaryButton("Send here");connect.setOnClickListener(v->connectTo(device));
                actions.addView(test,new LinearLayout.LayoutParams(0,dp(46),1));
                LinearLayout.LayoutParams sendLp=new LinearLayout.LayoutParams(0,dp(46),1);sendLp.setMargins(dp(8),0,0,0);actions.addView(connect,sendLp);
                row.addView(actions);'''
new='''                TextView note=text("Not verified as OptiShare • use the verified OptiShare card above or scan the receiver QR",11,Color.rgb(142,166,187),false);
                note.setPadding(0,dp(10),0,0);row.addView(note);'''
rep(old,new,'generic P2P actions')

anchor='''    private void connectViaLan(String name,String host){'''
helper='''    private void benchmarkViaLan(String name,String host){
        if(host==null||host.trim().isEmpty())return;
        benchmarkMode=true;pcTransferMode=true;transferStarted=true;receiverMode=false;
        discoveryHandler.removeCallbacks(p2pConnectTimeout);pendingP2pDevice=null;
        connectedPeerName=(name==null||name.trim().isEmpty())?"OptiShare device":name;
        activeRoute=RoutePerformanceStore.ROUTE_LAN;activeTransferStartedAt=System.currentTimeMillis();
        stopPcDiscovery();stopLanDiscovery();
        showTransferScreen("Android speed test");setConnectionUi("SAME WI-FI SPEED TEST",Color.rgb(89,205,255));
        setTransferUi("Preparing 8 MB encrypted speed test","Using the verified OptiShare same-Wi-Fi route. No test file is saved.",0);
        startBenchmarkService(host);
    }

    private void connectViaLan(String name,String host){'''
rep(anchor,helper,'LAN benchmark helper')

old='''@Override public void onFailure(int reason){pendingP2pDevice=null;benchmarkMode=false;pcTransferMode=false;setConnectionUi("CONNECTION FAILED",Color.rgb(255,91,101));setDiscoveryText(p2pError("Speed-test connection failed",reason));}'''
new='''@Override public void onFailure(int reason){pendingP2pDevice=null;if(pendingLanHost!=null&&!pendingLanHost.trim().isEmpty()){benchmarkViaLan(pendingLanName,pendingLanHost);return;}benchmarkMode=false;pcTransferMode=false;setConnectionUi("CONNECTION FAILED",Color.rgb(255,91,101));setDiscoveryText(p2pError("Speed-test connection failed",reason));}'''
rep(old,new,'benchmark P2P failure fallback')

old='''@Override public void onFailure(int reason){pendingP2pDevice=null;setConnectionUi("CONNECTION FAILED",Color.rgb(255,91,101));setDiscoveryText(p2pError("Connection failed",reason));}'''
new='''@Override public void onFailure(int reason){pendingP2pDevice=null;if(pendingLanHost!=null&&!pendingLanHost.trim().isEmpty()){connectViaLan(pendingLanName,pendingLanHost);return;}setConnectionUi("CONNECTION FAILED",Color.rgb(255,91,101));setDiscoveryText(p2pError("Connection failed",reason));}'''
rep(old,new,'send P2P failure fallback')

p.write_text(s)
print('verified LAN routing patch applied')
