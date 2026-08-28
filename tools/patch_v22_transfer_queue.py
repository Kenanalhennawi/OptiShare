from pathlib import Path

# TransferService: expose active-file progress/name metadata and verified file completion.
p=Path('app/src/main/java/com/kenan/optishare/transfer/TransferService.java')
s=p.read_text()
s=s.replace('    public static final String EXTRA_TOTAL_BYTES = "total_bytes";\n', '''    public static final String EXTRA_TOTAL_BYTES = "total_bytes";\n    public static final String EXTRA_FILE_ID = "file_id";\n    public static final String EXTRA_FILE_NAME = "file_name";\n    public static final String EXTRA_FILE_DONE = "file_done_bytes";\n    public static final String EXTRA_FILE_TOTAL = "file_total_bytes";\n    public static final String EXTRA_FILE_INDEX = "file_index";\n''',1)

old='''                broadcastProgress(message, p, bytesPerSecond, sessionId,\n                        batchDone, batchTotal, eta);'''
new='''                broadcastProgress(message, p, bytesPerSecond, sessionId,\n                        batchDone, batchTotal, eta, fileId, fileName, done, total, entryIndex(fileId));'''
# receiver and sender each have this exact block
if s.count(old) < 2: raise SystemExit('expected two normal progress blocks')
s=s.replace(old,new,2)

# Give sender a file_done event too, and receiver a useful file name.
old='''                broadcast("file_done", "Verified ✓ • saved to Download/OptiShare",\n                        0, 0, sessionId);'''
new='''                String completedName = entry == null ? "Received file" : entry.name;\n                broadcastFileDone(sessionId, fileId, completedName, entryIndex(fileId),\n                        "Verified ✓ • saved to Download/OptiShare");'''
if old not in s: raise SystemExit('receiver file_done block missing')
s=s.replace(old,new,1)
old='''            @Override public void onFileCompleted(String sessionId, String fileId,\n                                                  Uri publishedUri) { }'''
new='''            @Override public void onFileCompleted(String sessionId, String fileId,\n                                                  Uri publishedUri) {\n                BatchManifest.Entry entry = findEntry(fileId);\n                String completedName = entry == null ? "Sent file" : entry.name;\n                broadcastFileDone(sessionId, fileId, completedName, entryIndex(fileId),\n                        "Sent and verified ✓");\n            }'''
if old not in s: raise SystemExit('sender empty file completion missing')
s=s.replace(old,new,1)

# Keep benchmark/striped callers on the original simple progress helper by adding an overload.
needle='''    private void broadcastProgress(String message, int progress, double speed, String session,\n                                   long done, long total, long etaSeconds) {\n        Intent intent = new Intent(ACTION_EVENT);'''
replacement='''    private void broadcastProgress(String message, int progress, double speed, String session,\n                                   long done, long total, long etaSeconds) {\n        broadcastProgress(message, progress, speed, session, done, total, etaSeconds, null, null, 0L, 0L, -1);\n    }\n\n    private void broadcastProgress(String message, int progress, double speed, String session,\n                                   long done, long total, long etaSeconds, String fileId, String fileName,\n                                   long fileDone, long fileTotal, int fileIndex) {\n        Intent intent = new Intent(ACTION_EVENT);'''
if needle not in s: raise SystemExit('broadcastProgress helper missing')
s=s.replace(needle,replacement,1)
needle='''        intent.putExtra(EXTRA_ETA_SECONDS, etaSeconds);\n        sendBroadcast(intent);\n    }\n\n    private void pauseOutgoingTransfer()'''
replacement='''        intent.putExtra(EXTRA_ETA_SECONDS, etaSeconds);\n        if (fileId != null) intent.putExtra(EXTRA_FILE_ID, fileId);\n        if (fileName != null) intent.putExtra(EXTRA_FILE_NAME, fileName);\n        intent.putExtra(EXTRA_FILE_DONE, fileDone);\n        intent.putExtra(EXTRA_FILE_TOTAL, fileTotal);\n        intent.putExtra(EXTRA_FILE_INDEX, fileIndex);\n        sendBroadcast(intent);\n    }\n\n    private void broadcastFileDone(String session, String fileId, String fileName, int fileIndex, String message) {\n        Intent intent = new Intent(ACTION_EVENT);\n        intent.setPackage(getPackageName());\n        intent.putExtra(EXTRA_EVENT, "file_done");\n        intent.putExtra(EXTRA_MESSAGE, message);\n        intent.putExtra(EXTRA_SESSION, session);\n        intent.putExtra(EXTRA_FILE_ID, fileId);\n        intent.putExtra(EXTRA_FILE_NAME, fileName);\n        intent.putExtra(EXTRA_FILE_INDEX, fileIndex);\n        sendBroadcast(intent);\n    }\n\n    private int entryIndex(String fileId) {\n        if (activeManifest == null || fileId == null) return -1;\n        List<BatchManifest.Entry> entries = activeManifest.getEntries();\n        for (int i = 0; i < entries.size(); i++) if (fileId.equals(entries.get(i).id)) return i;\n        return -1;\n    }\n\n    private void pauseOutgoingTransfer()'''
if needle not in s: raise SystemExit('progress helper tail missing')
s=s.replace(needle,replacement,1)
p.write_text(s)

# V2Activity: editable queue before transfer + live per-file state + retry/resume action on error.
p=Path('app/src/main/java/com/kenan/optishare/V2Activity.java')
s=p.read_text()
s=s.replace('''    private ProgressBar transferProgress;\n    private Button transferPauseButton;''','''    private ProgressBar transferProgress;\n    private LinearLayout transferQueueList;\n    private final Set<Integer> completedQueueIndexes = new HashSet<>();\n    private Button transferPauseButton;''',1)

needle='''            int reconnects = intent.getIntExtra(TransferService.EXTRA_RECONNECTS, 0);'''
replacement='''            String activeFileName = intent.getStringExtra(TransferService.EXTRA_FILE_NAME);\n            long activeFileDone = intent.getLongExtra(TransferService.EXTRA_FILE_DONE, 0L);\n            long activeFileTotal = intent.getLongExtra(TransferService.EXTRA_FILE_TOTAL, 0L);\n            int activeFileIndex = intent.getIntExtra(TransferService.EXTRA_FILE_INDEX, -1);\n            int reconnects = intent.getIntExtra(TransferService.EXTRA_RECONNECTS, 0);'''
if needle not in s: raise SystemExit('receiver metrics insertion missing')
s=s.replace(needle,replacement,1)

old='''                setTransferMetrics(progress, done, total, speed, etaSeconds);'''
new='''                setTransferMetrics(progress, done, total, speed, etaSeconds);\n                updateLiveQueue(activeFileIndex, activeFileName, activeFileDone, activeFileTotal, false);'''
if old not in s: raise SystemExit('progress metrics line missing')
s=s.replace(old,new,1)
old='''            } else if ("file_done".equals(event)) {\n                setTransferUi("File verified ✓", message, -1);'''
new='''            } else if ("file_done".equals(event)) {\n                setTransferUi("File verified ✓", message, -1);\n                updateLiveQueue(activeFileIndex, activeFileName, activeFileTotal, activeFileTotal, true);'''
if old not in s: raise SystemExit('file_done activity block missing')
s=s.replace(old,new,1)

old='''            } else if ("error".equals(event)) {\n                setConnectionUi("TRANSFER ERROR", Color.rgb(255, 92, 102));\n                setTransferUi("Transfer could not continue", message, -1);'''
new='''            } else if ("error".equals(event)) {\n                setConnectionUi("TRANSFER ERROR", Color.rgb(255, 92, 102));\n                setTransferUi("Transfer could not continue", message, -1);\n                if (!receiverMode && senderSessionStore.exists() && transferCancelButton != null) {\n                    transferCancelButton.setText("Retry / resume →");\n                    transferCancelButton.setOnClickListener(v -> resumePendingTransfer());\n                }'''
if old not in s: raise SystemExit('error activity block missing')
s=s.replace(old,new,1)

# Replace compact selection text list with queue rows supporting reorder/remove.
old='''        LinearLayout selection=card();\n        if(selected.isEmpty()) selection.addView(text("Nothing selected yet. Photos and Videos open inside OptiShare; Files opens Android's document picker.",13,Color.rgb(156,181,202),false));\n        else {\n            int show=Math.min(selected.size(),10);\n            for(int i=0;i<show;i++) selection.addView(text("✓ "+displayName(selected.get(i)),13,Color.WHITE,false));\n            if(selected.size()>show) selection.addView(text("+ "+(selected.size()-show)+" more",12,Color.rgb(82,196,255),true));\n            Button clear=secondaryButton("Clear selection");clear.setOnClickListener(v->{selected.clear();FolderTransferQueue.clear();showSendSelection();});\n            LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(-1,dp(46));cl.setMargins(0,dp(10),0,0);selection.addView(clear,cl);\n        }'''
new='''        LinearLayout selection=card();\n        if(selected.isEmpty()) selection.addView(text("Nothing selected yet. Photos and Videos open inside OptiShare; Files opens Android's document picker.",13,Color.rgb(156,181,202),false));\n        else {\n            selection.addView(text("Transfer queue • drag-free controls keep ordering predictable",12,Color.rgb(115,196,255),true));\n            int show=Math.min(selected.size(),40);\n            for(int i=0;i<show;i++) selection.addView(queueSelectionRow(i));\n            if(selected.size()>show) selection.addView(text("+ "+(selected.size()-show)+" more queued",12,Color.rgb(82,196,255),true));\n            Button clear=secondaryButton("Clear selection");clear.setOnClickListener(v->{selected.clear();FolderTransferQueue.clear();showSendSelection();});\n            LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(-1,dp(46));cl.setMargins(0,dp(10),0,0);selection.addView(clear,cl);\n        }'''
if old not in s: raise SystemExit('selection block missing')
s=s.replace(old,new,1)

# Transfer screen queue card.
needle='''        card.addView(metrics);root.addView(card);\n        if(!receiverMode&&!pcTransferMode){'''
replacement='''        card.addView(metrics);root.addView(card);\n        if(!benchmarkMode){\n            LinearLayout queueCard=card();\n            TextView queueTitle=text(receiverMode?"Receiving files":"Transfer queue",15,Color.WHITE,true);queueCard.addView(queueTitle);\n            transferQueueList=new LinearLayout(this);transferQueueList.setOrientation(LinearLayout.VERTICAL);transferQueueList.setPadding(0,dp(8),0,0);queueCard.addView(transferQueueList);\n            completedQueueIndexes.clear();renderLiveQueue(-1,null,0L,0L);\n            LinearLayout.LayoutParams qlp=new LinearLayout.LayoutParams(-1,-2);qlp.setMargins(0,dp(12),0,0);root.addView(queueCard,qlp);\n        }else transferQueueList=null;\n        if(!receiverMode&&!pcTransferMode){'''
if needle not in s: raise SystemExit('transfer queue insertion missing')
s=s.replace(needle,replacement,1)

# Helpers inserted before openInternalGallery.
needle='''    private void openInternalGallery(String type) {'''
helpers='''    private View queueSelectionRow(int index){\n        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(5),0,dp(5));\n        TextView label=text((index+1)+". "+displayName(selected.get(index)),12,Color.WHITE,false);label.setMaxLines(2);row.addView(label,new LinearLayout.LayoutParams(0,-2,1));\n        Button up=smallButton("↑");up.setEnabled(index>0);up.setAlpha(index>0?1f:.35f);up.setOnClickListener(v->moveQueueItem(index,index-1));row.addView(up,new LinearLayout.LayoutParams(dp(42),dp(40)));\n        Button down=smallButton("↓");down.setEnabled(index<selected.size()-1);down.setAlpha(index<selected.size()-1?1f:.35f);down.setOnClickListener(v->moveQueueItem(index,index+1));LinearLayout.LayoutParams dl=new LinearLayout.LayoutParams(dp(42),dp(40));dl.setMargins(dp(5),0,0,0);row.addView(down,dl);\n        Button remove=smallButton("×");remove.setOnClickListener(v->removeQueueItem(index));LinearLayout.LayoutParams rl=new LinearLayout.LayoutParams(dp(42),dp(40));rl.setMargins(dp(5),0,0,0);row.addView(remove,rl);\n        return row;\n    }\n\n    private void moveQueueItem(int from,int to){\n        if(from<0||to<0||from>=selected.size()||to>=selected.size()||from==to)return;\n        Uri item=selected.remove(from);selected.add(to,item);showSendSelection();\n    }\n\n    private void removeQueueItem(int index){\n        if(index<0||index>=selected.size())return;selected.remove(index);showSendSelection();\n    }\n\n    private void updateLiveQueue(int index,String name,long done,long total,boolean complete){\n        if(index>=0&&complete)completedQueueIndexes.add(index);\n        renderLiveQueue(index,name,done,total);\n    }\n\n    private void renderLiveQueue(int activeIndex,String activeName,long activeDone,long activeTotal){\n        if(transferQueueList==null)return;transferQueueList.removeAllViews();\n        if(receiverMode&&selected.isEmpty()&&activeIndex<0){transferQueueList.addView(text("Waiting for incoming manifest…",12,Color.rgb(151,181,205),false));return;}\n        int count=receiverMode?Math.max(activeIndex+1,completedQueueIndexes.isEmpty()?0:(Collections.max(completedQueueIndexes)+1)):selected.size();\n        if(count==0&&activeIndex>=0)count=activeIndex+1;\n        int show=Math.min(count,20);\n        for(int i=0;i<show;i++){\n            boolean completed=completedQueueIndexes.contains(i);boolean active=i==activeIndex&&!completed;\n            String name=(!receiverMode&&i<selected.size())?displayName(selected.get(i)):(active&&activeName!=null?activeName:"File "+(i+1));\n            String state=completed?"✓ Verified":active?"↗ "+(activeTotal>0?formatBytes(activeDone)+" / "+formatBytes(activeTotal):"Transferring…"):"• Pending";\n            int color=completed?Color.rgb(65,225,151):active?Color.rgb(89,205,255):Color.rgb(151,181,205);\n            transferQueueList.addView(text((i+1)+". "+name+"   "+state,11,color,active||completed));\n        }\n        if(count>show)transferQueueList.addView(text("+ "+(count-show)+" more files",11,Color.rgb(122,158,185),false));\n    }\n\n    private void openInternalGallery(String type) {'''
if needle not in s: raise SystemExit('helper insertion anchor missing')
s=s.replace(needle,helpers,1)
p.write_text(s)
