package com.kenan.optishare.storage;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import com.kenan.optishare.model.TransferItem;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Exports user-selected launchable apps without requesting QUERY_ALL_PACKAGES. */
public final class InstalledAppExporter {
    private InstalledAppExporter() { }

    public static List<TransferItem> export(Context context, List<String> packageNames) throws Exception {
        List<TransferItem> result = new ArrayList<>();
        if (packageNames == null) return result;
        PackageManager pm = context.getPackageManager();
        File outbox = new File(context.getFilesDir(), "app_outbox");
        if (!outbox.exists() && !outbox.mkdirs()) throw new IllegalStateException("Could not create app outbox");
        for (String packageName : packageNames) {
            PackageInfo info = pm.getPackageInfo(packageName, 0);
            ApplicationInfo app = info.applicationInfo;
            if (app == null || app.sourceDir == null) continue;
            String label = safeLabel(pm.getApplicationLabel(app).toString());
            if (app.splitSourceDirs == null || app.splitSourceDirs.length == 0) {
                File target = new File(outbox, label + "-" + safeLabel(info.versionName) + ".apk");
                copy(new File(app.sourceDir), target);
                result.add(new TransferItem(Uri.fromFile(target), target.getName(),
                        "application/vnd.android.package-archive", target.length(), TransferItem.Category.APP));
            } else {
                File target = new File(outbox, label + "-" + safeLabel(info.versionName) + ".apks");
                try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(target))) {
                    addZip(zip, new File(app.sourceDir), "base.apk");
                    for (int i = 0; i < app.splitSourceDirs.length; i++) {
                        addZip(zip, new File(app.splitSourceDirs[i]), "split-" + (i + 1) + ".apk");
                    }
                }
                result.add(new TransferItem(Uri.fromFile(target), target.getName(),
                        "application/x-apks", target.length(), TransferItem.Category.APP));
            }
        }
        return result;
    }

    private static void addZip(ZipOutputStream zip, File source, String name) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        try (FileInputStream in = new FileInputStream(source)) { copy(in, zip); }
        zip.closeEntry();
    }

    private static void copy(File source, File target) throws Exception {
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(target)) {
            copy(in, out);
            out.getFD().sync();
        }
    }

    private static void copy(java.io.InputStream in, java.io.OutputStream out) throws Exception {
        byte[] buffer = new byte[1024 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
    }

    private static String safeLabel(String value) {
        String safe = value == null ? "app" : value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return safe.isEmpty() ? "app" : safe;
    }
}
