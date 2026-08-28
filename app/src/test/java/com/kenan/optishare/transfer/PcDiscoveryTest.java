package com.kenan.optishare.transfer;

import org.junit.Test;

import java.net.InetAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PcDiscoveryTest {
    @Test public void parsesValidWindowsCompanionResponse() throws Exception {
        PcDiscovery.Peer peer = PcDiscovery.parse(
                "OPTISHARE_PC_V1|DESKTOP-TEST|49890|0123456789abcdef0123456789abcdef|1",
                InetAddress.getByName("192.168.1.25"));
        assertEquals("DESKTOP-TEST", peer.name);
        assertEquals("192.168.1.25", peer.host);
        assertEquals(49890, peer.port);
        assertEquals(1, peer.protocolVersion);
    }

    @Test public void acceptsSecureV2AndRejectsUnknownVersionOrWeakToken() throws Exception {
        assertNull(PcDiscovery.parse(
                "OPTISHARE_PC_V1|PC|49890|short|1",
                InetAddress.getByName("192.168.1.25")));
        PcDiscovery.Peer secure = PcDiscovery.parse(
                "OPTISHARE_PC_V1|PC|49890|0123456789abcdef0123456789abcdef|2",
                InetAddress.getByName("192.168.1.25"));
        assertEquals(2, secure.protocolVersion);
        assertNull(PcDiscovery.parse(
                "OPTISHARE_PC_V1|PC|49890|0123456789abcdef0123456789abcdef|3",
                InetAddress.getByName("192.168.1.25")));
    }

    @Test public void rejectsInvalidPort() throws Exception {
        assertNull(PcDiscovery.parse(
                "OPTISHARE_PC_V1|PC|70000|0123456789abcdef0123456789abcdef|1",
                InetAddress.getByName("192.168.1.25")));
    }
}
