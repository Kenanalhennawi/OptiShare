package com.kenan.optishare.transfer;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BrowserRequestPolicyTest {
    @Test public void sameOriginBrowserPostIsAllowed() {
        Map<String, String> headers = new HashMap<>();
        headers.put("host", "192.168.1.9:49889");
        headers.put("origin", "http://192.168.1.9:49889");
        assertTrue(BrowserRequestPolicy.allowedOrigin(headers));
    }

    @Test public void crossOriginBrowserPostIsRejected() {
        Map<String, String> headers = new HashMap<>();
        headers.put("host", "192.168.1.9:49889");
        headers.put("origin", "https://attacker.example");
        assertFalse(BrowserRequestPolicy.allowedOrigin(headers));
    }

    @Test public void nativeClientWithoutOriginRemainsCompatible() {
        Map<String, String> headers = new HashMap<>();
        headers.put("host", "192.168.1.9:49889");
        assertTrue(BrowserRequestPolicy.allowedOrigin(headers));
    }

    @Test public void originWithoutHostIsRejected() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Origin", "http://192.168.1.9:49889");
        assertFalse(BrowserRequestPolicy.allowedOrigin(headers));
    }
}
