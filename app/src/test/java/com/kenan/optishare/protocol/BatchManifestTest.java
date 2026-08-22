package com.kenan.optishare.protocol;

import com.kenan.optishare.model.TransferItem;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class BatchManifestTest {
    private static BatchManifest.Entry entry(String id, long size) {
        return new BatchManifest.Entry(
                id,
                id + ".bin",
                "application/octet-stream",
                size,
                TransferItem.Category.OTHER,
                new byte[32]);
    }

    @Test public void totalsMultipleFilesWithoutOverflow() {
        BatchManifest manifest = new BatchManifest(Arrays.asList(
                entry("a", 4_194_304L),
                entry("b", 8_388_608L)));
        assertEquals(12_582_912L, manifest.totalBytes());
    }

    @Test public void rejectsDuplicateFileIds() {
        try {
            new BatchManifest(Arrays.asList(entry("same", 1), entry("same", 2)));
            fail("Duplicate IDs must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test public void rejectsNonSha256Digest() {
        try {
            new BatchManifest.Entry(
                    "a", "a.bin", "application/octet-stream", 1,
                    TransferItem.Category.OTHER, new byte[31]);
            fail("Invalid digest length must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test public void rejectsEmptyBatch() {
        try {
            new BatchManifest(java.util.Collections.emptyList());
            fail("Empty batch must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test public void saturatesTotalAtLongMax() {
        BatchManifest manifest = new BatchManifest(Arrays.asList(
                entry("a", Long.MAX_VALUE - 5),
                entry("b", 10)));
        assertEquals(Long.MAX_VALUE, manifest.totalBytes());
    }
}
