package com.kenan.optishare.storage;

import com.kenan.optishare.model.TransferItem;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FileClassifierTest {
    @Test public void classifiesCommonTypes() {
        assertEquals(TransferItem.Category.PHOTO, FileClassifier.classify("image/jpeg", "a.jpg"));
        assertEquals(TransferItem.Category.VIDEO, FileClassifier.classify("video/mp4", "a.mp4"));
        assertEquals(TransferItem.Category.MUSIC, FileClassifier.classify("audio/flac", "a.flac"));
        assertEquals(TransferItem.Category.APP, FileClassifier.classify("application/octet-stream", "app.apk"));
        assertEquals(TransferItem.Category.ARCHIVE, FileClassifier.classify("application/octet-stream", "archive.7z"));
        assertEquals(TransferItem.Category.DOCUMENT, FileClassifier.classify("application/pdf", "doc.pdf"));
        assertEquals(TransferItem.Category.OTHER, FileClassifier.classify("application/octet-stream", "data.bin"));
    }
}
