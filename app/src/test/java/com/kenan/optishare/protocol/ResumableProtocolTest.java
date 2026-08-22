package com.kenan.optishare.protocol;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class ResumableProtocolTest {
    @Test
    public void negotiateOffsetUsesSafestConfirmedByte() {
        assertEquals(4_194_304L, ResumableProtocol.negotiateOffset(6_000_000L, 4_194_304L, 10_000_000L));
    }

    @Test
    public void chunkAlignmentNeverMovesForward() {
        assertEquals(3_145_728L, ResumableProtocol.alignToChunkBoundary(3_900_000L, 1_048_576));
    }

    @Test
    public void resumeRequestRoundTrips() throws Exception {
        Map<String, Long> offsets = new LinkedHashMap<>();
        offsets.put("photo-1", 1_048_576L);
        offsets.put("video-2", 8_388_608L);
        ResumeState original = new ResumeState("SESSION-ABC", 123L, offsets);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ResumableProtocol.writeResumeRequest(new DataOutputStream(bytes), original);
        ResumeState decoded = ResumableProtocol.readResumeRequest(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

        assertEquals("SESSION-ABC", decoded.getSessionId());
        assertEquals(1_048_576L, decoded.getConfirmedOffset("photo-1"));
        assertEquals(8_388_608L, decoded.getConfirmedOffset("video-2"));
    }
}
