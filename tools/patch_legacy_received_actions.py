from pathlib import Path
p=Path('app/src/main/java/com/kenan/optishare/ReceivedFilesActivity.java')
s=p.read_text()
if 'import androidx.core.content.FileProvider;' not in s:
    s=s.replace('import androidx.annotation.RequiresApi;\n','import androidx.annotation.RequiresApi;\nimport androidx.core.content.FileProvider;\n',1)
old='''        boolean actionable = item.uri != null && "content".equals(item.uri.getScheme());\n        open.setEnabled(actionable);\n        share.setEnabled(actionable);\n        open.setAlpha(actionable ? 1f : .45f);\n        share.setAlpha(actionable ? 1f : .45f);'''
new='''        boolean actionable = itemUri(item) != null;\n        open.setEnabled(actionable);\n        share.setEnabled(actionable);\n        open.setAlpha(actionable ? 1f : .45f);\n        share.setAlpha(actionable ? 1f : .45f);'''
if old not in s: raise SystemExit('actionable anchor missing')
s=s.replace(old,new,1)
old='''    private void openItem(Item item) {\n        if (item.uri == null) return;\n        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mime)\n                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);'''
new='''    private void openItem(Item item) {\n        Uri uri = itemUri(item);\n        if (uri == null) return;\n        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime)\n                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);'''
if old not in s: raise SystemExit('open anchor missing')
s=s.replace(old,new,1)
old='''    private void shareItem(Item item) {\n        if (item.uri == null) return;\n        Intent intent = new Intent(Intent.ACTION_SEND).setType(item.mime)\n                .putExtra(Intent.EXTRA_STREAM, item.uri)\n                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);'''
new='''    private void shareItem(Item item) {\n        Uri uri = itemUri(item);\n        if (uri == null) return;\n        Intent intent = new Intent(Intent.ACTION_SEND).setType(item.mime)\n                .putExtra(Intent.EXTRA_STREAM, uri)\n                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);'''
if old not in s: raise SystemExit('share anchor missing')
s=s.replace(old,new,1)
anchor='''    private void showSimple(String message) {'''
helper='''    private Uri itemUri(Item item) {\n        if (item == null) return null;\n        if (item.uri != null && "content".equals(item.uri.getScheme())) return item.uri;\n        if (item.path == null || item.path.trim().isEmpty()) return null;\n        try {\n            File file = new File(item.path);\n            if (!file.exists() || !file.isFile()) return null;\n            File allowedRoot = new File(Environment.getExternalStoragePublicDirectory(\n                    Environment.DIRECTORY_DOWNLOADS), "OptiShare");\n            String rootPath = allowedRoot.getCanonicalPath() + File.separator;\n            String filePath = file.getCanonicalPath();\n            if (!filePath.startsWith(rootPath)) return null;\n            return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);\n        } catch (Exception ignored) {\n            return null;\n        }\n    }\n\n'''
if anchor not in s: raise SystemExit('helper anchor missing')
s=s.replace(anchor,helper+anchor,1)
p.write_text(s)
print('legacy received actions patch applied')
