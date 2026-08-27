from pathlib import Path


def replace(path, old, new, label):
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise RuntimeError(f"missing {label} in {path}")
    p.write_text(s.replace(old, new, 1))


# Generalize the rich selection queue so folder items and generated text can coexist with normal URIs.
p = Path('app/src/main/java/com/kenan/optishare/storage/FolderTransferQueue.java')
p.write_text(r'''package com.kenan.optishare.storage;

import com.kenan.optishare.model.TransferItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Holds rich metadata for selections whose URI alone is not enough (folders/text). */
public final class FolderTransferQueue {
    private static final List<TransferItem> pending = new ArrayList<>();
    private FolderTransferQueue() {}

    public static synchronized void set(List<TransferItem> items) {
        pending.clear();
        if (items != null) pending.addAll(items);
    }

    public static synchronized void addAll(List<TransferItem> items) {
        if (items == null) return;
        for (TransferItem item : items) add(item);
    }

    public static synchronized void add(TransferItem item) {
        if (item == null || item.getUri() == null) return;
        String uri = item.getUri().toString();
        for (int i = pending.size() - 1; i >= 0; i--) {
            TransferItem existing = pending.get(i);
            if (existing.getUri() != null && uri.equals(existing.getUri().toString())) pending.remove(i);
        }
        pending.add(item);
    }

    public static synchronized List<TransferItem> takeAll() {
        if (pending.isEmpty()) return Collections.emptyList();
        List<TransferItem> result = new ArrayList<>(pending);
        pending.clear();
        return result;
    }

    public static synchronized void clear() { pending.clear(); }
}
''')

# Generated text is a normal encrypted TransferItem backed by app-private storage.
Path('app/src/main/java/com/kenan/optishare/storage/TextTransferStore.java').write_text(r'''package com.kenan.optishare.storage;

import android.content.Context;
import android.net.Uri;

import com.kenan.optishare.model.TransferItem;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public final class TextTransferStore {
    public static final int MAX_TEXT_BYTES = 64 * 1024;
    private TextTransferStore() {}

    public static TransferItem create(Context context, CharSequence value) throws Exception {
        String text = value == null ? "" : value.toString();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) throw new IllegalArgumentException("Text is empty");
        if (bytes.length > MAX_TEXT_BYTES) throw new IllegalArgumentException("Text is larger than 64 KB");
        File dir = new File(context.getFilesDir(), "text_outbox");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create text outbox");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File file = new File(dir, "OptiShare Text " + stamp + "-" + UUID.randomUUID().toString().substring(0, 8) + ".txt");
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(bytes);
            out.getFD().sync();
        }
        return new TransferItem(Uri.fromFile(file), file.getName(), "text/plain", bytes.length,
                TransferItem.Category.DOCUMENT);
    }
}
''')

# V2Activity: append folder selections, add Text/Clipboard entry points, and preserve queue additions.
p = Path('app/src/main/java/com/kenan/optishare/V2Activity.java')
s = p.read_text()
s = s.replace(
    'import com.kenan.optishare.storage.FolderTransferQueue;\n',
    'import com.kenan.optishare.storage.FolderTransferQueue;\nimport com.kenan.optishare.storage.TextTransferStore;\n',
    1,
)
s = s.replace(
    '''                    selected.clear();
                    for (com.kenan.optishare.model.TransferItem item : files) selected.add(item.getUri());
                    FolderTransferQueue.set(files);''',
    '''                    for (com.kenan.optishare.model.TransferItem item : files) {
                        if (!selected.contains(item.getUri())) selected.add(item.getUri());
                    }
                    FolderTransferQueue.addAll(files);''',
    1,
)
# Home third row restores Other and adds text/clipboard.
anchor = '        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(-1,-2); r2.setMargins(0,dp(10),0,0); root.addView(row2,r2);\n'
row3 = '''        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(-1,-2); r2.setMargins(0,dp(10),0,0); root.addView(row2,r2);
        LinearLayout row3 = categoryRow(
                category("T","Text",Color.rgb(89,190,255),v -> showTextComposer(null)),
                category("▣","Clipboard",Color.rgb(81,210,157),v -> addClipboardToQueue()),
                category("…","Other",Color.rgb(122,140,166),v -> openExternal("*/*")));
        LinearLayout.LayoutParams r3 = new LinearLayout.LayoutParams(-1,-2); r3.setMargins(0,dp(10),0,0); root.addView(row3,r3);
'''
if anchor not in s: raise RuntimeError('home row anchor missing')
s = s.replace(anchor, row3, 1)
# Send screen: two rows; keep existing file/photo/video row and add folder/text/clipboard.
anchor = '        tabs.addView(photos,new LinearLayout.LayoutParams(0,dp(46),1));\n'
# no replacement needed for first row; add second row after root.addView(tabs)
old = '        LinearLayout.LayoutParams p2=new LinearLayout.LayoutParams(0,dp(46),1);p2.setMargins(dp(8),0,0,0);tabs.addView(files,p2);root.addView(tabs);\n\n'
new = '''        LinearLayout.LayoutParams p2=new LinearLayout.LayoutParams(0,dp(46),1);p2.setMargins(dp(8),0,0,0);tabs.addView(files,p2);root.addView(tabs);
        LinearLayout addRow=new LinearLayout(this);addRow.setOrientation(LinearLayout.HORIZONTAL);
        Button folder=smallButton("Folder");folder.setOnClickListener(v->openFolder());
        Button textBtn=smallButton("Text");textBtn.setOnClickListener(v->showTextComposer(null));
        Button clipBtn=smallButton("Clipboard");clipBtn.setOnClickListener(v->addClipboardToQueue());
        addRow.addView(folder,new LinearLayout.LayoutParams(0,dp(46),1));
        LinearLayout.LayoutParams ar1=new LinearLayout.LayoutParams(0,dp(46),1);ar1.setMargins(dp(8),0,0,0);addRow.addView(textBtn,ar1);
        LinearLayout.LayoutParams ar2=new LinearLayout.LayoutParams(0,dp(46),1);ar2.setMargins(dp(8),0,0,0);addRow.addView(clipBtn,ar2);
        LinearLayout.LayoutParams arp=new LinearLayout.LayoutParams(-1,-2);arp.setMargins(0,dp(8),0,0);root.addView(addRow,arp);

'''
if old not in s: raise RuntimeError('send add row anchor missing')
s = s.replace(old, new, 1)
# Clear rich metadata whenever user clears all selection.
s = s.replace('clear.setOnClickListener(v->{selected.clear();showSendSelection();});', 'clear.setOnClickListener(v->{selected.clear();FolderTransferQueue.clear();showSendSelection();});', 1)
# Gallery becomes additive: queue survives when adding photos/videos later.
s = s.replace('''        GalleryAdapter adapter=new GalleryAdapter(initial,set->{
            selected.clear();selected.addAll(set);selectedCount.setText(selected.size()+" selected");
        });''','''        GalleryAdapter adapter=new GalleryAdapter(initial,set->{
            selectedCount.setText((selected.size()+Math.max(0,set.size()-initial.size()))+" queued");
        });''',1)
s = s.replace('''        Button done=primary("Done • "+selected.size()+" selected");done.setOnClickListener(v->{selected.clear();selected.addAll(adapter.selection());showSendSelection();});''','''        Button done=primary("Add selected to queue");done.setOnClickListener(v->{for(Uri uri:adapter.selection())if(!selected.contains(uri))selected.add(uri);showSendSelection();});''',1)
# Receiver mode clears rich sender queue as well.
s = s.replace('currentScreen=SCREEN_RECEIVE;receiverMode=true;selected.clear();', 'currentScreen=SCREEN_RECEIVE;receiverMode=true;selected.clear();FolderTransferQueue.clear();',1)
# Handle received text by copying it to clipboard after user accepted the secure batch.
s = s.replace('''            } else if ("file_done".equals(event)) {
                setTransferUi("File verified ✓", message, -1);''','''            } else if ("text_received".equals(event)) {
                android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                if(clipboard!=null&&message!=null)clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OptiShare text",message));
                setTransferUi("Text received & copied ✓","The received text is now in your clipboard.",-1);
            } else if ("file_done".equals(event)) {
                setTransferUi("File verified ✓", message, -1);''',1)
# Add text/clipboard helpers before openFolder.
anchor = '    private void openFolder(){'
helpers = '''    private void showTextComposer(String initial){
        final android.widget.EditText input=new android.widget.EditText(this);
        input.setMinLines(5);input.setMaxLines(12);input.setGravity(Gravity.TOP|Gravity.START);
        input.setText(initial==null?"":initial);input.setHint("Type or paste text to send securely");
        new AlertDialog.Builder(this).setTitle("Send text").setView(input)
                .setPositiveButton("Add to queue",(d,w)->{
                    try{com.kenan.optishare.model.TransferItem item=TextTransferStore.create(this,input.getText());
                        if(!selected.contains(item.getUri()))selected.add(item.getUri());
                        FolderTransferQueue.add(item);showSendSelection();}
                    catch(Exception e){showMessage("Text not added",e.getMessage());}
                }).setNegativeButton("Cancel",null).show();
    }

    private void addClipboardToQueue(){
        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(clipboard==null||!clipboard.hasPrimaryClip()||clipboard.getPrimaryClip()==null||clipboard.getPrimaryClip().getItemCount()==0){showMessage("Clipboard is empty","Copy some text first, then try again.");return;}
        CharSequence value=clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        if(value==null||value.length()==0){showMessage("Clipboard has no text","The current clipboard item cannot be sent as text.");return;}
        try{com.kenan.optishare.model.TransferItem item=TextTransferStore.create(this,value);if(!selected.contains(item.getUri()))selected.add(item.getUri());FolderTransferQueue.add(item);showSendSelection();}
        catch(Exception e){showMessage("Clipboard not added",e.getMessage());}
    }

'''
if anchor not in s: raise RuntimeError('text helpers anchor missing')
s = s.replace(anchor, helpers + anchor, 1)
p.write_text(s)

# TransferService merges rich items with ordinary selected URIs and emits received text.
p = Path('app/src/main/java/com/kenan/optishare/transfer/TransferService.java')
s = p.read_text()
s = s.replace('import java.util.List;\n', 'import java.util.List;\nimport java.util.LinkedHashMap;\nimport java.util.Map;\nimport java.io.ByteArrayOutputStream;\nimport java.io.InputStream;\nimport java.nio.charset.StandardCharsets;\n', 1)
s = s.replace(
    '''                List<TransferItem> folderItems = FolderTransferQueue.takeIfMatches(rawUris.size());
                activeItems = folderItems.isEmpty() ? resolveItems(rawUris) : folderItems;''',
    '''                List<TransferItem> richItems = FolderTransferQueue.takeAll();
                activeItems = mergeRichItems(rawUris, richItems);''',
    1,
)
s = s.replace(
    '''            @Override public void onFileCompleted(String sessionId, String fileId,
                                                  Uri publishedUri) {
                broadcast("file_done", "Verified ✓ • saved to Download/OptiShare",
                        0, 0, sessionId);
            }''',
    '''            @Override public void onFileCompleted(String sessionId, String fileId,
                                                  Uri publishedUri) {
                BatchManifest.Entry entry=findEntry(fileId);
                if(entry!=null&&"text/plain".equalsIgnoreCase(entry.mime)&&entry.name.startsWith("OptiShare Text")){
                    String text=readSmallText(publishedUri);
                    if(text!=null)broadcast("text_received",text,0,0,sessionId);
                }
                broadcast("file_done", "Verified ✓ • saved to Download/OptiShare",
                        0, 0, sessionId);
            }''',
    1,
)
# Add helpers before resolveItems.
anchor = '    private List<TransferItem> resolveItems(List<String> rawUris) throws Exception {'
helpers = '''    private List<TransferItem> mergeRichItems(List<String> rawUris,List<TransferItem> rich) throws Exception {
        Map<String,TransferItem> richByUri=new LinkedHashMap<>();
        if(rich!=null)for(TransferItem item:rich)if(item!=null&&item.getUri()!=null)richByUri.put(item.getUri().toString(),item);
        List<TransferItem> result=new ArrayList<>();
        for(String raw:rawUris){TransferItem item=richByUri.get(raw);if(item!=null){result.add(item);continue;}result.addAll(resolveItems(java.util.Collections.singletonList(raw)));}
        return result;
    }

    private BatchManifest.Entry findEntry(String fileId){
        if(activeManifest==null||fileId==null)return null;
        for(BatchManifest.Entry entry:activeManifest.getEntries())if(fileId.equals(entry.id))return entry;
        return null;
    }

    private String readSmallText(Uri uri){
        if(uri==null)return null;
        try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)return null;byte[] buffer=new byte[8192];int total=0,n;
            while((n=in.read(buffer))!=-1){total+=n;if(total>65536)return null;out.write(buffer,0,n);}return out.toString(StandardCharsets.UTF_8.name());
        }catch(Exception ignored){return null;}
    }

'''
if anchor not in s: raise RuntimeError('service helper anchor missing')
s = s.replace(anchor, helpers + anchor, 1)
p.write_text(s)
