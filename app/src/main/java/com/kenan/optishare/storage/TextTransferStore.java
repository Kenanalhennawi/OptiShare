package com.kenan.optishare.storage;

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
