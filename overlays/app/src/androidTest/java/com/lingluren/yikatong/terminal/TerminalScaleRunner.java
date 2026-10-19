package com.lingluren.yikatong.terminal;

import android.os.Bundle;
import android.test.InstrumentationTestRunner;

/** Only included in the separately installed, signed test APK. */
public final class TerminalScaleRunner extends InstrumentationTestRunner {
    static Bundle arguments;
    @Override public void onCreate(Bundle args) {
        arguments = args == null ? new Bundle() : new Bundle(args);
        super.onCreate(args);
    }
}
