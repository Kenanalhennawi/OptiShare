package com.kenan.optishare.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LanDiscoveryTest {
    @Test public void safeServiceNameHasStablePrefix() {
        assertEquals("OptiShare-Android", LanDiscovery.safeServiceName(null));
        assertEquals("OptiShare-Pixel 9", LanDiscovery.safeServiceName("Pixel 9"));
    }

    @Test public void safeServiceNameRemovesUnsafeCharactersAndBoundsLength() {
        String value = LanDiscovery.safeServiceName("Kenan's / Phone: very very very very very long name");
        assertTrue(value.startsWith("OptiShare-"));
        assertTrue(value.length() <= "OptiShare-".length() + 36);
        assertTrue(!value.contains("/") && !value.contains(":"));
    }
}
