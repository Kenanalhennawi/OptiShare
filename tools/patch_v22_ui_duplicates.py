from pathlib import Path

# V2Activity: keep a tagged transfer-screen title, update it on completion,
# and hide unverified Wi-Fi Direct candidates whenever a verified LAN peer exists.
p = Path('app/src/main/java/com/kenan/optishare/V2Activity.java')
s = p.read_text()

old = '''    private void showTransferScreen(String title) {\n        currentScreen=SCREEN_TRANSFER;\n        ScrollView scroll=new ScrollView(this);LinearLayout root=shell(scroll);addBackHeader(root,title,"You can leave this screen; the foreground transfer service keeps running");'''
new = '''    private void showTransferScreen(String title) {\n        currentScreen=SCREEN_TRANSFER;\n        ScrollView scroll=new ScrollView(this);LinearLayout root=shell(scroll);addBackHeader(root,title,"You can leave this screen; the foreground transfer service keeps running");'''
if old not in s:
    raise SystemExit('transfer screen anchor missing')
# no structural replacement needed; title is tagged in addBackHeader below

old = '''            } else if ("completed".equals(event)) {\n                setConnectionUi("COMPLETED ✓", Color.rgb(65, 225, 151));\n                setTransferUi("Transfer complete ✓", message, 100);'''
new = '''            } else if ("completed".equals(event)) {\n                setConnectionUi("COMPLETED ✓", Color.rgb(65, 225, 151));\n                TextView screenTitle=findViewByTag("transfer_screen_title");\n                if(screenTitle!=null)screenTitle.setText("Transfer complete");\n                setTransferUi("Transfer complete ✓", message, 100);'''
if old not in s:
    raise SystemExit('completed UI anchor missing')
s = s.replace(old, new, 1)

old = '''            for(WifiP2pDevice device:peers){\n                LinearLayout row=card();'''
new = '''            boolean showUnverifiedP2p=pendingLanHost==null||pendingLanHost.trim().isEmpty();\n            if(!showUnverifiedP2p&&!peers.isEmpty()){\n                TextView hidden=text(peers.size()+" unverified Wi-Fi Direct device"+(peers.size()==1?"":"s")+" hidden while verified OptiShare is available",11,Color.rgb(126,157,181),false);\n                hidden.setPadding(0,dp(4),0,dp(10));peerList.addView(hidden);\n            }\n            if(showUnverifiedP2p)for(WifiP2pDevice device:peers){\n                LinearLayout row=card();'''
if old not in s:
    raise SystemExit('P2P render anchor missing')
s = s.replace(old, new, 1)

old = '''    private void addBackHeader(LinearLayout root,String title,String subtitle){Button back=smallButton("← Back");back.setOnClickListener(v->{if(currentScreen==SCREEN_GALLERY||currentScreen==SCREEN_DISCOVERY)showSendSelection();else showHome();});root.addView(back,new LinearLayout.LayoutParams(dp(96),dp(44)));TextView t=text(title,27,Color.WHITE,true);t.setPadding(0,dp(18),0,dp(3));root.addView(t);TextView s=text(subtitle,13,Color.rgb(162,194,219),false);s.setPadding(0,0,0,dp(14));root.addView(s);}'''
new = '''    private void addBackHeader(LinearLayout root,String title,String subtitle){Button back=smallButton("← Back");back.setOnClickListener(v->{if(currentScreen==SCREEN_GALLERY||currentScreen==SCREEN_DISCOVERY)showSendSelection();else showHome();});root.addView(back,new LinearLayout.LayoutParams(dp(96),dp(44)));TextView t=text(title,27,Color.WHITE,true);if(currentScreen==SCREEN_TRANSFER)t.setTag("transfer_screen_title");t.setPadding(0,dp(18),0,dp(3));root.addView(t);TextView s=text(subtitle,13,Color.rgb(162,194,219),false);s.setPadding(0,0,0,dp(14));root.addView(s);}'''
if old not in s:
    raise SystemExit('back header anchor missing')
s = s.replace(old, new, 1)
p.write_text(s)

# DownloadStore: deterministically keep both on Android 10+ as legacy path already does.
p = Path('app/src/main/java/com/kenan/optishare/storage/DownloadStore.java')
s = p.read_text()
if 'import android.database.Cursor;' not in s:
    s = s.replace('import android.content.Context;\n', 'import android.content.Context;\nimport android.database.Cursor;\n', 1)

old = '''        ContentResolver resolver = context.getContentResolver();\n        ContentValues values = new ContentValues();\n        values.put(MediaStore.Downloads.DISPLAY_NAME, name);\n        values.put(MediaStore.Downloads.MIME_TYPE,\n                mime == null ? "application/octet-stream" : mime);\n        values.put(MediaStore.Downloads.RELATIVE_PATH,\n                Environment.DIRECTORY_DOWNLOADS + "/OptiShare/" + folder);'''
new = '''        ContentResolver resolver = context.getContentResolver();\n        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/OptiShare/" + folder;\n        String uniqueName = uniqueMediaStoreName(resolver, name, relativePath);\n        ContentValues values = new ContentValues();\n        values.put(MediaStore.Downloads.DISPLAY_NAME, uniqueName);\n        values.put(MediaStore.Downloads.MIME_TYPE,\n                mime == null ? "application/octet-stream" : mime);\n        values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);'''
if old not in s:
    raise SystemExit('MediaStore publish anchor missing')
s = s.replace(old, new, 1)

anchor = '''    @SuppressWarnings("deprecation")\n    private Uri publishLegacy(File source, String name, String folder) throws IOException {'''
helper = '''    @RequiresApi(Build.VERSION_CODES.Q)\n    private String uniqueMediaStoreName(ContentResolver resolver, String name, String relativePath) {\n        if (!mediaStoreNameExists(resolver, name, relativePath)) return name;\n        int dot = name.lastIndexOf('.');\n        String base = dot > 0 ? name.substring(0, dot) : name;\n        String ext = dot > 0 ? name.substring(dot) : "";\n        for (int i = 1; i < 10000; i++) {\n            String candidate = base + " (" + i + ")" + ext;\n            if (!mediaStoreNameExists(resolver, candidate, relativePath)) return candidate;\n        }\n        return System.currentTimeMillis() + "-" + name;\n    }\n\n    @RequiresApi(Build.VERSION_CODES.Q)\n    private boolean mediaStoreNameExists(ContentResolver resolver, String name, String relativePath) {\n        Cursor cursor = null;\n        try {\n            cursor = resolver.query(\n                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,\n                    new String[]{MediaStore.Downloads._ID},\n                    MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + "=?",\n                    new String[]{name, relativePath}, null);\n            return cursor != null && cursor.moveToFirst();\n        } catch (RuntimeException ignored) {\n            // If an OEM restricts the query, MediaStore still protects existing rows; publishing continues.\n            return false;\n        } finally {\n            if (cursor != null) cursor.close();\n        }\n    }\n\n'''
if anchor not in s:
    raise SystemExit('legacy publish anchor missing')
s = s.replace(anchor, helper + anchor, 1)
p.write_text(s)
print('UX and duplicate-file patch applied')
