package com.patcher.migrate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The store app calls this before it downloads anything, which is the only
 * moment the answer is still free: after the uninstall, an incompatible patched
 * build means re-finding the old version with no WhatsApp on the phone.
 */
public class CompatibilityTest {

    @Test
    public void the_same_version_is_compatible() {
        assertTrue(Migration.checkCompatible(263807433L, 263807433L));
    }

    @Test
    public void a_newer_patched_build_is_compatible() {
        assertTrue(Migration.checkCompatible(263807433L, 263907433L));
    }

    @Test
    public void an_older_patched_build_is_not() {
        // WhatsApp refuses a backup made by a newer version of itself, and it
        // refuses it after the number has been verified, with nothing installed
        // to go back to.
        assertFalse(Migration.checkCompatible(263807433L, 263707433L));
    }

    @Test
    public void an_unknown_parked_version_is_allowed_through() {
        // A manifest from before this was recorded should not block a migration
        // the user can still judge for themselves.
        assertTrue(Migration.checkCompatible(0L, 263707433L));
    }
}
