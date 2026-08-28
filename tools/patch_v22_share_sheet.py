from pathlib import Path
p=Path('app/src/main/java/com/kenan/optishare/V2Activity.java')
s=p.read_text()

def once(old,new):
    global s
    if old not in s: raise SystemExit('anchor not found: '+old[:100])
    s=s.replace(old,new,1)

once('        requestNotificationPermissionIfUseful();\n        showHome();\n    }\n',
'''        requestNotificationPermissionIfUseful();
        if(!handleInboundShare(getIntent())) showHome();
    }

    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        setIntent(intent);
        handleInboundShare(intent);
    }

    private boolean handleInboundShare(Intent intent){
        if(intent==null)return false;
        String action=intent.getAction();
        if(!Intent.ACTION_SEND.equals(action)&&!Intent.ACTION_SEND_MULTIPLE.equals(action))return false;
        selected.clear();FolderTransferQueue.clear();
        try{
            if(Intent.ACTION_SEND_MULTIPLE.equals(action)){
                ArrayList<Uri> streams;
                if(Build.VERSION.SDK_INT>=33)streams=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM,Uri.class);
                else streams=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if(streams!=null)for(Uri uri:streams)if(uri!=null&&!selected.contains(uri))selected.add(uri);
            }else{
                Uri stream;
                if(Build.VERSION.SDK_INT>=33)stream=intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri.class);
                else stream=intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if(stream!=null)selected.add(stream);
                CharSequence sharedText=intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if(stream==null&&sharedText!=null&&sharedText.length()>0){
                    com.kenan.optishare.model.TransferItem item=TextTransferStore.create(this,sharedText);
                    selected.add(item.getUri());FolderTransferQueue.add(item);
                }
            }
        }catch(Exception error){showMessage("Could not import shared content",error.getMessage());return true;}
        if(selected.isEmpty()){showMessage("Nothing to share","OptiShare did not receive a file, link or text from the other app.");showHome();return true;}
        showSendSelection();return true;
    }
''')
p.write_text(s)
