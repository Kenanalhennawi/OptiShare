package com.kenan.optishare.storage;

import com.kenan.optishare.model.TransferItem;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class FileClassifier {
    private static final Set<String> PHOTO = set("jpg","jpeg","png","gif","webp","heic","heif","bmp","avif");
    private static final Set<String> VIDEO = set("mp4","mkv","mov","avi","webm","3gp","m4v","ts");
    private static final Set<String> MUSIC = set("mp3","aac","m4a","flac","wav","ogg","opus","wma");
    private static final Set<String> APP = set("apk","apks","xapk","apkm");
    private static final Set<String> ARCHIVE = set("zip","rar","7z","tar","gz","bz2","xz");
    private static final Set<String> DOC = set("pdf","doc","docx","xls","xlsx","ppt","pptx","txt","rtf","csv","json","xml","md","epub","odt","ods","odp");

    private FileClassifier() {}

    public static TransferItem.Category classify(String mime, String name) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.US);
        String ext = TransferItem.normalizedExtension(name);
        if (m.startsWith("image/") || PHOTO.contains(ext)) return TransferItem.Category.PHOTO;
        if (m.startsWith("video/") || VIDEO.contains(ext)) return TransferItem.Category.VIDEO;
        if (m.startsWith("audio/") || MUSIC.contains(ext)) return TransferItem.Category.MUSIC;
        if ("application/vnd.android.package-archive".equals(m) || APP.contains(ext)) return TransferItem.Category.APP;
        if (m.contains("zip") || m.contains("rar") || m.contains("compressed") || ARCHIVE.contains(ext)) return TransferItem.Category.ARCHIVE;
        if (m.startsWith("text/") || m.contains("pdf") || m.contains("document") || m.contains("sheet") || m.contains("presentation") || DOC.contains(ext)) return TransferItem.Category.DOCUMENT;
        return TransferItem.Category.OTHER;
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
