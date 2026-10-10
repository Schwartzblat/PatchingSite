package com.patcher.migrate;

import android.os.Environment;

import java.io.File;

/**
 * Every path this library touches, spelled once.
 *
 * Android/media rather than Android/data: all files access reaches the first and
 * is refused the second, which is the whole reason this design works. Measured
 * 2026-10-01.
 */
public final class Paths {

    public static final String WHATSAPP_PACKAGE = "com.whatsapp";
    private static final String PARK_DIR = "WAMigrate";

    private Paths() {
    }

    public static File waFolder() {
        return new File(Environment.getExternalStorageDirectory(),
                "Android/media/" + WHATSAPP_PACKAGE + "/WhatsApp");
    }

    public static File parkRoot() {
        return new File(Environment.getExternalStorageDirectory(), PARK_DIR);
    }
}
