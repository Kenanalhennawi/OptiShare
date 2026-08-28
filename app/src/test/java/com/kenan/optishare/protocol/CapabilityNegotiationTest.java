package com.kenan.optishare.protocol;

import org.junit.Test;

import java.io.IOException;
import java.security.MessageDigest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CapabilityNegotiationTest {
    @Test public void offerRoundTripIsCanonical() throws Exception {
        CapabilityNegotiation.Offer source=CapabilityNegotiation.localOffer();
        byte[] encoded=CapabilityNegotiation.encodeOffer(source);
        assertEquals(48,encoded.length);
        CapabilityNegotiation.Offer decoded=CapabilityNegotiation.decodeOffer(encoded);
        assertEquals(source.major,decoded.major);assertEquals(source.transports,decoded.transports);
        assertEquals(source.maxChunkBytes,decoded.maxChunkBytes);assertEquals(source.flags,decoded.flags);
        assertTrue(MessageDigest.isEqual(encoded,CapabilityNegotiation.encodeOffer(decoded)));
    }

    @Test public void selectionUsesSafeIntersectionAndLowerLimits() throws Exception {
        int required=CapabilityNegotiation.FLAG_RESUME|CapabilityNegotiation.FLAG_ATOMIC_PUBLISH;
        CapabilityNegotiation.Offer client=new CapabilityNegotiation.Offer(2,2,0,3,3,1,1024*1024,4,1000,65536,32,required|CapabilityNegotiation.FLAG_CLIPBOARD);
        CapabilityNegotiation.Offer server=new CapabilityNegotiation.Offer(2,1,1,1,1,1,512*1024,2,100,32768,16,required);
        CapabilityNegotiation.Selection selected=CapabilityNegotiation.negotiate(client,server);
        assertEquals(1,selected.minor);assertEquals(1,selected.transports);assertEquals(CapabilityNegotiation.AEAD_AES_256_GCM,selected.aead);
        assertEquals(512*1024,selected.chunkBytes);assertEquals(2,selected.streams);assertEquals(100,selected.maxFiles);
        assertEquals(required,selected.flags);
    }

    @Test public void noCommonAeadOrRequiredResumeFailsClosed() throws Exception {
        CapabilityNegotiation.Offer local=CapabilityNegotiation.localOffer();
        expectFailure(local,new CapabilityNegotiation.Offer(2,0,0,1,2,1,65536,1,1,1024,1,CapabilityNegotiation.FLAG_RESUME|CapabilityNegotiation.FLAG_ATOMIC_PUBLISH),"No common AEAD");
        expectFailure(local,new CapabilityNegotiation.Offer(2,0,0,1,1,1,65536,1,1,1024,1,CapabilityNegotiation.FLAG_ATOMIC_PUBLISH),"Required transfer capability missing");
    }

    @Test public void transcriptCommitmentDetectsOfferReorderingAndDowngrade() throws Exception {
        CapabilityNegotiation.Offer local=CapabilityNegotiation.localOffer();byte[] first=CapabilityNegotiation.encodeOffer(local);
        CapabilityNegotiation.Offer reduced=new CapabilityNegotiation.Offer(2,0,0,1,1,1,65536,1,10,4096,2,CapabilityNegotiation.FLAG_RESUME|CapabilityNegotiation.FLAG_ATOMIC_PUBLISH);
        byte[] second=CapabilityNegotiation.encodeOffer(reduced);CapabilityNegotiation.Selection selected=CapabilityNegotiation.negotiate(local,reduced);
        byte[] expected=CapabilityNegotiation.encodeSelection(selected,first,second);byte[] reordered=CapabilityNegotiation.encodeSelection(selected,second,first);
        assertFalse(MessageDigest.isEqual(expected,reordered));
        second[second.length-1]^=1;byte[] changed=CapabilityNegotiation.encodeSelection(selected,first,second);
        assertFalse(MessageDigest.isEqual(expected,changed));
    }

    @Test public void malformedAndUnknownCapabilityBitsAreRejected() throws Exception {
        try{CapabilityNegotiation.decodeOffer(new byte[47]);fail("Expected length rejection");}catch(IOException expected){assertEquals("Invalid capability offer length",expected.getMessage());}
        CapabilityNegotiation.Offer bad=new CapabilityNegotiation.Offer(2,0,0,8,1,1,65536,1,1,1024,1,CapabilityNegotiation.FLAG_RESUME|CapabilityNegotiation.FLAG_ATOMIC_PUBLISH);
        try{CapabilityNegotiation.encodeOffer(bad);fail("Expected bitset rejection");}catch(IOException expected){assertEquals("Invalid capability bitset",expected.getMessage());}
    }

    private static void expectFailure(CapabilityNegotiation.Offer a,CapabilityNegotiation.Offer b,String message)throws Exception{
        try{CapabilityNegotiation.negotiate(a,b);fail("Expected negotiation failure");}catch(IOException expected){assertEquals(message,expected.getMessage());}
    }
}
