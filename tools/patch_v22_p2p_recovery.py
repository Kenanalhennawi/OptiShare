from pathlib import Path
p=Path('app/src/main/java/com/kenan/optishare/V2Activity.java')
s=p.read_text()

def rep(old,new,label):
    global s
    c=s.count(old)
    if c!=1: raise SystemExit(f'{label}: anchor count={c}')
    s=s.replace(old,new,1)

# Add connection timeout runnable near existing discovery handler fields.
rep('''    private final Handler discoveryHandler=new Handler(Looper.getMainLooper());''','''    private final Handler discoveryHandler=new Handler(Looper.getMainLooper());
    private WifiP2pDevice pendingP2pDevice;
    private final Runnable p2pConnectTimeout=()->{
        if(currentScreen!=SCREEN_DISCOVERY||transferStarted||pendingP2pDevice==null)return;
        String name=deviceName(pendingP2pDevice);
        try{manager.cancelConnect(channel,new WifiP2pManager.ActionListener(){@Override public void onSuccess(){}@Override public void onFailure(int reason){}});}catch(Exception ignored){}
        pendingP2pDevice=null;
        if(pendingLanHost!=null&&!pendingLanHost.trim().isEmpty()){
            if(benchmarkMode){
                activeRoute=RoutePerformanceStore.ROUTE_LAN;transferStarted=true;pcTransferMode=true;
                showTransferScreen("Android speed test");setConnectionUi("SAME WI-FI SPEED TEST",Color.rgb(89,205,255));
                setTransferUi("Direct link timed out — testing same Wi-Fi","Still uses the encrypted OptiShare ECDH/AES-GCM transport.",0);
                startBenchmarkService(pendingLanHost);
            }else{
                setDiscoveryText("Direct link timed out. Switching to same Wi-Fi…");
                connectViaLan(pendingLanName==null?name:pendingLanName,pendingLanHost);
            }
        }else{
            benchmarkMode=false;pcTransferMode=false;
            setConnectionUi("DIRECT LINK TIMEOUT",Color.rgb(255,91,101));
            setDiscoveryText("Could not establish Wi-Fi Direct with "+name+". Keep RECEIVE open on the other phone, then tap Search again. Same-Wi-Fi fallback will be used automatically when available.");
            scheduleDiscoveryRetry();
        }
    };''','handler field')

# Receiver group creation must wait for removal completion.
old='''    private void startReceiverMode() {
        if(!ensureNearbyReady())return;safeRemoveGroup();
        try{manager.createGroup(channel,new WifiP2pManager.ActionListener(){@Override public void onSuccess(){setDiscoveryText("READY TO RECEIVE ✓\\nWaiting for sender…");setConnectionUi("VISIBLE TO SENDERS",Color.rgb(65,222,151));try{if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){manager.requestDeviceInfo(channel,device->{thisDevice=device;refreshReceiverIdentity();});}else{refreshReceiverIdentity();}manager.requestConnectionInfo(channel,V2Activity.this);}catch(SecurityException ignored){showNearbyPermissionHelp();}}@Override public void onFailure(int reason){setDiscoveryText(p2pError("Receiver could not start",reason));setConnectionUi("RECEIVER ERROR",Color.rgb(255,91,101));}});}catch(SecurityException e){showNearbyPermissionHelp();}
    }'''
new='''    private void startReceiverMode() {
        if(!ensureNearbyReady())return;
        Runnable create=()->{
            try{manager.createGroup(channel,new WifiP2pManager.ActionListener(){
                @Override public void onSuccess(){setDiscoveryText("READY TO RECEIVE ✓\\nWaiting for sender…");setConnectionUi("VISIBLE TO SENDERS",Color.rgb(65,222,151));try{if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){manager.requestDeviceInfo(channel,device->{thisDevice=device;refreshReceiverIdentity();});}else{refreshReceiverIdentity();}manager.requestConnectionInfo(channel,V2Activity.this);}catch(SecurityException ignored){showNearbyPermissionHelp();}}
                @Override public void onFailure(int reason){
                    if(reason==WifiP2pManager.BUSY){discoveryHandler.postDelayed(this::retryCreate,700);}else{setDiscoveryText(p2pError("Receiver could not start",reason));setConnectionUi("RECEIVER ERROR",Color.rgb(255,91,101));}
                }
                private void retryCreate(){ if(currentScreen==SCREEN_RECEIVE&&receiverMode) startReceiverMode(); }
            });}catch(SecurityException e){showNearbyPermissionHelp();}
        };
        try{
            manager.removeGroup(channel,new WifiP2pManager.ActionListener(){
                @Override public void onSuccess(){discoveryHandler.postDelayed(create,250);}
                @Override public void onFailure(int reason){discoveryHandler.postDelayed(create,250);}
            });
        }catch(Exception ignored){discoveryHandler.postDelayed(create,250);}
    }'''
rep(old,new,'receiver race')

# Do not falsely identify every P2P device as encrypted OptiShare.
rep('''                names.addView(text(deviceStatus(device.status)+" • encrypted Android peer",12,Color.rgb(151,182,205),false));''','''                names.addView(text(deviceStatus(device.status)+" • Wi-Fi Direct candidate",12,Color.rgb(151,182,205),false));''','peer label')

# Benchmark connect: preserve LAN discovery and schedule timeout.
old='''    private void benchmarkDevice(WifiP2pDevice device) {
        benchmarkMode=true;pcTransferMode=true;stopPcDiscovery();
        if(!ensureNearbyReady())return;
        discoveryHandler.removeCallbacks(discoveryRetry);stopLanDiscovery();
        connectedPeerName=deviceName(device);setConnectionUi("CONNECTING FOR TEST…",Color.rgb(89,205,255));
        setDiscoveryText("Connecting to "+connectedPeerName+" for encrypted speed test…");
        WifiP2pConfig config=new WifiP2pConfig();config.deviceAddress=device.deviceAddress;config.wps.setup=WpsInfo.PBC;config.groupOwnerIntent=0;
        try{manager.connect(channel,config,new WifiP2pManager.ActionListener(){
            @Override public void onSuccess(){setDiscoveryText("Speed-test connection request sent…");}
            @Override public void onFailure(int reason){benchmarkMode=false;pcTransferMode=false;setConnectionUi("CONNECTION FAILED",Color.rgb(255,91,101));setDiscoveryText(p2pError("Speed-test connection failed",reason));}
        });}catch(SecurityException e){benchmarkMode=false;pcTransferMode=false;showNearbyPermissionHelp();}
    }'''
new='''    private void benchmarkDevice(WifiP2pDevice device) {
        benchmarkMode=true;pcTransferMode=true;stopPcDiscovery();
        if(!ensureNearbyReady())return;
        discoveryHandler.removeCallbacks(discoveryRetry);
        pendingP2pDevice=device;connectedPeerName=deviceName(device);setConnectionUi("CONNECTING FOR TEST…",Color.rgb(89,205,255));
        setDiscoveryText("Connecting to "+connectedPeerName+" for encrypted speed test…");
        WifiP2pConfig config=new WifiP2pConfig();config.deviceAddress=device.deviceAddress;config.wps.setup=WpsInfo.PBC;config.groupOwnerIntent=0;
        try{manager.cancelConnect(channel,new WifiP2pManager.ActionListener(){
            private void go(){try{manager.connect(channel,config,new WifiP2pManager.ActionListener(){@Override public void onSuccess(){setDiscoveryText("Speed-test request sent. Waiting up to 12 seconds for the direct link…");discoveryHandler.removeCallbacks(p2pConnectTimeout);discoveryHandler.postDelayed(p2pConnectTimeout,12000);}@Override public void onFailure(int reason){pendingP2pDevice=null;benchmarkMode=false;pcTransferMode=false;setConnectionUi("CONNECTION FAILED",Color.rgb(255,91,101));setDiscoveryText(p2pError("Speed-test connection failed",reason));}});}catch(SecurityException e){benchmarkMode=false;pcTransferMode=false;showNearbyPermissionHelp();}}
            @Override public void onSuccess(){go();}@Override public void onFailure(int reason){go();}
        });}catch(SecurityException e){benchmarkMode=false;pcTransferMode=false;showNearbyPermissionHelp();}
    }'''
rep(old,new,'benchmark connect')

# Normal connect: same stale-cancel + timeout, keep LAN discovery alive for fallback.
old='''    private void connectTo(WifiP2pDevice device) {
        benchmarkMode=false;pcTransferMode=false;stopPcDiscovery();
        if(!ensureNearbyReady())return;discoveryHandler.removeCallbacks(discoveryRetry);stopLanDiscovery();connectedPeerName=deviceName(device);setConnectionUi("CONNECTING…",Color.rgb(255,194,73));setDiscoveryText("Connecting to "+connectedPeerName+"…");WifiP2pConfig config=new WifiP2pConfig();config.deviceAddress=device.deviceAddress;config.wps.setup=WpsInfo.PBC;config.groupOwnerIntent=0;
        try{manager.connect(channel,config,new WifiP2pManager.ActionListener(){@Override public void onSuccess(){setDiscoveryText("Connection request sent. Waiting for the direct link…");}@Override public void onFailure(int reason){setConnectionUi("CONNECTION FAILED",Color.rgb(255,91,101));setDiscoveryText(p2pError("Connection failed",reason));}});}catch(SecurityException e){showNearbyPermissionHelp();}
    }'''
new='''    private void connectTo(WifiP2pDevice device) {
        benchmarkMode=false;pcTransferMode=false;stopPcDiscovery();
        if(!ensureNearbyReady())return;discoveryHandler.removeCallbacks(discoveryRetry);pendingP2pDevice=device;connectedPeerName=deviceName(device);setConnectionUi("CONNECTING…",Color.rgb(255,194,73));setDiscoveryText("Connecting to "+connectedPeerName+"…");WifiP2pConfig config=new WifiP2pConfig();config.deviceAddress=device.deviceAddress;config.wps.setup=WpsInfo.PBC;config.groupOwnerIntent=0;
        try{manager.cancelConnect(channel,new WifiP2pManager.ActionListener(){
            private void go(){try{manager.connect(channel,config,new WifiP2pManager.ActionListener(){@Override public void onSuccess(){setDiscoveryText("Connection request sent. Waiting up to 12 seconds for the direct link…");discoveryHandler.removeCallbacks(p2pConnectTimeout);discoveryHandler.postDelayed(p2pConnectTimeout,12000);}@Override public void onFailure(int reason){pendingP2pDevice=null;setConnectionUi("CONNECTION FAILED",Color.rgb(255,91,101));setDiscoveryText(p2pError("Connection failed",reason));}});}catch(SecurityException e){showNearbyPermissionHelp();}}
            @Override public void onSuccess(){go();}@Override public void onFailure(int reason){go();}
        });}catch(SecurityException e){showNearbyPermissionHelp();}
    }'''
rep(old,new,'normal connect')

# Clear timeout when connection succeeds.
rep('''    @Override public void onConnectionInfoAvailable(WifiP2pInfo info) {
        if(info==null||!info.groupFormed||info.groupOwnerAddress==null)return;setConnectionUi("CONNECTED ✓",Color.rgb(65,225,151));''','''    @Override public void onConnectionInfoAvailable(WifiP2pInfo info) {
        if(info==null||!info.groupFormed||info.groupOwnerAddress==null)return;
        discoveryHandler.removeCallbacks(p2pConnectTimeout);pendingP2pDevice=null;setConnectionUi("CONNECTED ✓",Color.rgb(65,225,151));''','connection success')

# Stop timeout on pause/destroy navigation.
rep('''    @Override protected void onPause(){super.onPause();discoveryHandler.removeCallbacks(discoveryRetry);stopLanDiscovery();''','''    @Override protected void onPause(){super.onPause();discoveryHandler.removeCallbacks(discoveryRetry);discoveryHandler.removeCallbacks(p2pConnectTimeout);pendingP2pDevice=null;stopLanDiscovery();''','pause cleanup')

p.write_text(s)
print('p2p recovery patch applied')