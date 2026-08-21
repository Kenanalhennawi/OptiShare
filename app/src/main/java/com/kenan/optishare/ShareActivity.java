package com.kenan.optishare;

/**
 * v0.9 launcher activity.
 *
 * The previous ShareActivity implementation duplicated the DirectActivity
 * transport/UI stack and referenced a removed startScanLoop() helper, which
 * broke compilation. Keep a single source of truth by inheriting the stable
 * DirectActivity implementation.
 */
public class ShareActivity extends DirectActivity {
}
