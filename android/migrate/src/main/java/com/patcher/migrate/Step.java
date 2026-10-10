package com.patcher.migrate;

/**
 * Where the user is in the seven-step procedure. Derived from what is on the
 * device, never stored: a step that was written down would disagree with reality
 * the first time somebody moved a folder themselves.
 */
public enum Step {
    BACK_UP_AND_PARK,
    UNINSTALL_WHATSAPP,
    PUT_BACK,
    INSTALL_PATCHED,
    VERIFY_AND_RESTORE,
    DONE,
    NOTHING_TO_MIGRATE,
    NEEDS_ATTENTION
}
