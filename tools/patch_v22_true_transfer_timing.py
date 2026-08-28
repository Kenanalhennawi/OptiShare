from pathlib import Path

svc=Path('app/src/main/java/com/kenan/optishare/transfer/TransferService.java')
s=svc.read_text()
s=s.replace('    private volatile long activeTransferStartedNanos;\n', '    private volatile long activeTransferStartedNanos;\n    private volatile long dataTransferStartedNanos;\n',1)
s=s.replace('''        activeTransferStartedNanos = System.nanoTime();\n        reconnectCount = 0;''','''        activeTransferStartedNanos = System.nanoTime();\n        dataTransferStartedNanos = 0L;\n        reconnectCount = 0;''',1)
s=s.replace('''        activeTransferStartedNanos = 0L;\n        reconnectCount = 0;''','''        activeTransferStartedNanos = 0L;\n        dataTransferStartedNanos = 0L;\n        reconnectCount = 0;''',1)
# sender + receiver progress callbacks both contain this pair; stamp the first real data byte.
needle='''                latestBatchDone = batchDone;\n                latestSpeed = bytesPerSecond;'''
replacement='''                if (dataTransferStartedNanos == 0L && batchDone > 0L) dataTransferStartedNanos = System.nanoTime();\n                latestBatchDone = batchDone;\n                latestSpeed = bytesPerSecond;'''
count=s.count(needle)
if count < 2: raise SystemExit(f'expected at least 2 progress anchors, got {count}')
s=s.replace(needle,replacement)
old='''        long durationMs = activeTransferStartedNanos == 0L ? 0L\n                : Math.max(0L, Math.round((System.nanoTime() - activeTransferStartedNanos) / 1_000_000.0));'''
new='''        long timingStart = dataTransferStartedNanos != 0L ? dataTransferStartedNanos : activeTransferStartedNanos;\n        long durationMs = timingStart == 0L ? 0L\n                : Math.max(0L, Math.round((System.nanoTime() - timingStart) / 1_000_000.0));'''
if old not in s: raise SystemExit('duration anchor missing')
s=s.replace(old,new,1)
old='''        double seconds = activeTransferStartedNanos == 0L ? 0d : Math.max(0.001, (System.nanoTime() - activeTransferStartedNanos) / 1_000_000_000.0);'''
new='''        long timingStart = dataTransferStartedNanos != 0L ? dataTransferStartedNanos : activeTransferStartedNanos;\n        double seconds = timingStart == 0L ? 0d : Math.max(0.001, (System.nanoTime() - timingStart) / 1_000_000_000.0);'''
if old not in s: raise SystemExit('average timing anchor missing')
s=s.replace(old,new,1)
old='''        double seconds = activeTransferStartedNanos == 0L ? 0d\n                : Math.max(0.001, (System.nanoTime() - activeTransferStartedNanos) / 1_000_000_000.0);'''
new='''        long timingStart = dataTransferStartedNanos != 0L ? dataTransferStartedNanos : activeTransferStartedNanos;\n        double seconds = timingStart == 0L ? 0d\n                : Math.max(0.001, (System.nanoTime() - timingStart) / 1_000_000_000.0);'''
if old not in s: raise SystemExit('summary timing anchor missing')
s=s.replace(old,new,1)
svc.write_text(s)

act=Path('app/src/main/java/com/kenan/optishare/V2Activity.java')
a=act.read_text()
a=a.replace('    private Button transferPauseButton;\n', '    private Button transferPauseButton;\n    private Button transferCancelButton;\n',1)
old='''            Button cancel=secondaryButton("Cancel transfer");cancel.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Cancel transfer?").setMessage("Confirmed data will remain resumable until the session is cleared.").setPositiveButton("Cancel transfer",(d,w)->{stopTransferService();showHome();}).setNegativeButton("Keep transferring",null).show());LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(50));cp.setMargins(0,dp(12),0,0);root.addView(cancel,cp);'''
new='''            transferCancelButton=secondaryButton("Cancel transfer");transferCancelButton.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Cancel transfer?").setMessage("Confirmed data will remain resumable until the session is cleared.").setPositiveButton("Cancel transfer",(d,w)->{stopTransferService();showHome();}).setNegativeButton("Keep transferring",null).show());LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(50));cp.setMargins(0,dp(12),0,0);root.addView(transferCancelButton,cp);'''
if old not in a: raise SystemExit('cancel button anchor missing')
a=a.replace(old,new,1)
# ensure benchmark screens do not retain stale normal-transfer button reference
old='''        if(benchmarkMode){\n            Button back=secondaryButton("Back to nearby devices");'''
new='''        if(benchmarkMode){\n            transferCancelButton=null;\n            Button back=secondaryButton("Back to nearby devices");'''
if old not in a: raise SystemExit('benchmark button anchor missing')
a=a.replace(old,new,1)
old='''                transferStarted = false;\n                pcTransferMode=false;\n                transferPaused=false;\n                updatePauseButton(false);'''
new='''                transferStarted = false;\n                pcTransferMode=false;\n                transferPaused=false;\n                if(transferPauseButton!=null)transferPauseButton.setVisibility(View.GONE);\n                if(transferCancelButton!=null){transferCancelButton.setText("Done");transferCancelButton.setOnClickListener(v->showHome());}\n                updatePauseButton(false);'''
if old not in a: raise SystemExit('completed UI anchor missing')
a=a.replace(old,new,1)
# updatePauseButton should not resurrect a hidden button after completion.
old='''    private void updatePauseButton(boolean paused){runOnUiThread(()->{if(transferPauseButton==null)return;transferPauseButton.setText(paused?"Resume transfer":"Pause transfer");transferPauseButton.setOnClickListener(v->pauseOrResumeTransfer());});}'''
new='''    private void updatePauseButton(boolean paused){runOnUiThread(()->{if(transferPauseButton==null||transferPauseButton.getVisibility()!=View.VISIBLE)return;transferPauseButton.setText(paused?"Resume transfer":"Pause transfer");transferPauseButton.setOnClickListener(v->pauseOrResumeTransfer());});}'''
if old not in a: raise SystemExit('pause update anchor missing')
a=a.replace(old,new,1)
act.write_text(a)
print('true transfer timing + completed UI patch applied')
