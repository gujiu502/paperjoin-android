package com.paperjoin.app;

/** Provides package metadata for BlueStacks; instrumentation itself is started through ADB. */
public final class TestActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        finish();
    }
}
