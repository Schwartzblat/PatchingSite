package com.patcher.migrate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

/**
 * Every refusal in this class is protecting somebody's only copy of their chat
 * history, so each one is stated as its own case rather than left to fall out of
 * the ordering.
 */
public class MigrationStateTest {

    private static final String STOCK_SIG = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String PATCHED_SIG = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private static ParkManifest parked(ParkManifest.State state) {
        return new ParkManifest(state, 1759000000000L, "2.26.38.74", 263807433L, STOCK_SIG,
                "crypt14", 10, 1000L, Collections.<ParkManifest.Db>emptyList());
    }

    private static Facts facts(boolean grant, boolean installed, String sig,
                               boolean waFolder, boolean parkFolder, boolean kept,
                               ParkManifest m) {
        return new Facts(grant, installed, installed ? "2.26.38.74" : null,
                installed ? 263807433L : 0L, sig, waFolder, parkFolder, kept, m);
    }

    private static boolean mentions(List<String> blockers, String fragment) {
        for (String b : blockers) {
            if (b.toLowerCase().contains(fragment.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static final long INSTALLED_AT = 1_700_000_000_000L;
    private static final long SAVED_BACKUP_MS = 1_758_999_000_000L;
    private static final String HASH = "c".repeat(64);

    /** The record of a move away from [sig], its backup saved as 1000 bytes at SAVED_BACKUP_MS. */
    private static MoveRecord record(String sig) {
        return new MoveRecord("2.26.38.74", 263807433L, sig, INSTALLED_AT, "crypt14", 1_759_000_000_000L,
                Collections.singletonList(new ParkManifest.Db("Databases/msgstore.db.crypt14", 1000L,
                        SAVED_BACKUP_MS, HASH)),
                0L, MoveRecord.Source.NONE, 0L, 0L, 0L, "");
    }

    /** A manifest from STOCK_SIG whose one database is [size] bytes at [mtimeMs]. */
    private static ParkManifest parkedWith(ParkManifest.State state, long mtimeMs, long size) {
        return new ParkManifest(state, 1759000000000L, "2.26.38.74", 263807433L, STOCK_SIG,
                "crypt14", 10, 1000L,
                Collections.singletonList(new ParkManifest.Db("msgstore.db.crypt14", size, mtimeMs, HASH)));
    }

    /**
     * Facts with the move's own: the record [r] and its Saved/ copy ([saved] present,
     * [savedOk] matching it), the installed WhatsApp first installed at
     * [firstInstallMs], and the newest backup in WhatsApp's folder ([newestMs],
     * [newestSize]; 0 for none).
     */
    private static Facts moved(boolean installed, String sig, boolean dataKept, boolean waFolder,
                               boolean parkFolder, boolean kept, ParkManifest m, MoveRecord r,
                               boolean saved, boolean savedOk, long firstInstallMs,
                               long newestMs, long newestSize) {
        return new Facts(true, installed, installed ? "2.26.38.74" : null,
                installed ? 263807433L : 0L, installed ? sig : null, waFolder, parkFolder, kept, m,
                null, dataKept, newestMs, newestMs > 0 ? "crypt14" : "none")
                .withMove(r, false, saved, savedOk, false, kept, installed ? firstInstallMs : 0L,
                        newestSize, newestMs > 0 ? "msgstore.db.crypt14" : null);
    }

    /** Row 5's usual shape: a move away from STOCK_SIG with a good saved copy and nothing parked. */
    private static Facts recorded(boolean installed, String sig, boolean waFolder) {
        return moved(installed, sig, false, waFolder, false, false, null, record(STOCK_SIG), true, true,
                INSTALLED_AT, waFolder ? SAVED_BACKUP_MS : 0L, waFolder ? 1000L : 0L);
    }

    @Test
    public void stock_installed_with_a_folder_and_nothing_parked_is_ready_to_park() {
        Status s = MigrationState.of(facts(true, true, STOCK_SIG, true, false, false, null));
        assertEquals(Step.BACK_UP_AND_PARK, s.step);
        assertTrue(s.canPark);
        assertFalse(s.canPutBack);
        assertTrue(s.blockers.isEmpty());
    }

    @Test
    public void without_the_grant_nothing_can_run_and_the_step_is_unchanged() {
        Status s = MigrationState.of(facts(false, true, STOCK_SIG, true, false, false, null));
        assertEquals(Step.BACK_UP_AND_PARK, s.step);
        assertTrue(s.needsGrant);
        assertFalse(s.canPark);
        assertTrue(mentions(s.blockers, "all files access"));
    }

    @Test
    public void an_empty_whatsapp_folder_is_nothing_to_park() {
        Status s = MigrationState.of(facts(true, true, STOCK_SIG, false, false, false, null));
        assertFalse(s.canPark);
        assertTrue(mentions(s.blockers, "nothing to park"));
    }

    @Test
    public void parking_with_an_unreadable_signature_is_refused() {
        // WhatsApp is installed, folder exists, but signature is null: cannot park.
        Status s = MigrationState.of(facts(true, true, null, true, false, false, null));
        assertEquals(Step.BACK_UP_AND_PARK, s.step);
        assertFalse(s.canPark);
        assertTrue(mentions(s.blockers, "certificate could not be read"));
    }

    @Test
    public void parked_while_whatsapp_is_still_installed_says_uninstall_next() {
        Status s = MigrationState.of(facts(true, true, STOCK_SIG, false, true, false,
                parked(ParkManifest.State.PARKED)));
        assertEquals(Step.UNINSTALL_WHATSAPP, s.step);
    }

    @Test
    public void put_back_is_refused_while_the_original_whatsapp_is_still_installed() {
        // Review Focus 1: putting it back now means the uninstall that follows
        // deletes Android/media/com.whatsapp again, and that folder is the only
        // copy left.
        Status s = MigrationState.of(facts(true, true, STOCK_SIG, false, true, false,
                parked(ParkManifest.State.PARKED)));
        assertFalse(s.canPutBack);
        assertTrue(mentions(s.blockers, "still installed"));
    }

    @Test
    public void put_back_is_allowed_once_whatsapp_is_gone() {
        Status s = MigrationState.of(facts(true, false, null, false, true, false,
                parked(ParkManifest.State.PARKED)));
        assertEquals(Step.PUT_BACK, s.step);
        assertTrue(s.canPutBack);
        assertTrue(s.blockers.isEmpty());
    }

    @Test
    public void put_back_is_allowed_when_the_patched_build_is_already_installed() {
        // Its signature differs from the recorded one, so uninstalling the thing
        // that would delete the folder is no longer ahead of us.
        Status s = MigrationState.of(facts(true, true, PATCHED_SIG, false, true, false,
                parked(ParkManifest.State.PARKED)));
        assertTrue(s.canPutBack);
    }

    @Test
    public void put_back_is_refused_while_a_non_empty_destination_is_in_the_way() {
        Status s = MigrationState.of(facts(true, false, null, true, true, false,
                parked(ParkManifest.State.PARKED)));
        assertFalse(s.canPutBack);
        assertTrue(mentions(s.blockers, "in the way"));
    }

    @Test
    public void parking_again_is_refused_while_something_is_already_parked() {
        Status s = MigrationState.of(facts(true, true, STOCK_SIG, true, true, false,
                parked(ParkManifest.State.PARKED)));
        assertFalse(s.canPark);
        assertTrue(mentions(s.blockers, "already"));
    }

    @Test
    public void a_parked_folder_with_an_unreadable_manifest_disables_everything() {
        // Review Focus 3: the manifest is how we know which way the data should
        // move. Without it, guessing is worse than stopping.
        Status s = MigrationState.of(facts(true, false, null, false, true, false, null));
        assertEquals(Step.NEEDS_ATTENTION, s.step);
        assertFalse(s.canPark);
        assertFalse(s.canPutBack);
        assertFalse(s.canFinish);
        assertTrue(mentions(s.blockers, "manifest"));
    }

    @Test
    public void restored_with_no_whatsapp_says_install_the_patched_apk() {
        Status s = MigrationState.of(facts(true, false, null, true, false, true,
                parked(ParkManifest.State.RESTORED)));
        assertEquals(Step.INSTALL_PATCHED, s.step);
    }

    @Test
    public void restored_with_the_patched_build_installed_says_verify_and_restore() {
        Status s = MigrationState.of(facts(true, true, PATCHED_SIG, true, false, true,
                parked(ParkManifest.State.RESTORED)));
        assertEquals(Step.VERIFY_AND_RESTORE, s.step);
        // Nothing is deleted before DONE, and DONE needs the user's word and proof.
        assertFalse(s.canFinish);
    }

    @Test
    public void restored_with_the_original_whatsapp_back_says_install_the_patched_apk() {
        // They reinstalled the one they started with; the migration is not done.
        Status s = MigrationState.of(facts(true, true, STOCK_SIG, true, false, true,
                parked(ParkManifest.State.RESTORED)));
        assertEquals(Step.INSTALL_PATCHED, s.step);
        assertTrue(mentions(s.blockers, "started with"));
    }

    @Test
    public void restored_with_nothing_kept_still_waits_for_the_users_word() {
        Status s = MigrationState.of(facts(true, true, PATCHED_SIG, true, false, false,
                parked(ParkManifest.State.RESTORED)));
        // Before 2026-10-10 this was DONE: a missing Databases-kept was taken to mean the
        // move had finished. Finishing now needs the user's word and the new WhatsApp's
        // own backup.
        assertEquals(Step.VERIFY_AND_RESTORE, s.step);
        assertFalse(s.canFinish);
    }

    @Test
    public void no_whatsapp_and_nothing_parked_has_nothing_to_migrate() {
        Status s = MigrationState.of(facts(true, false, null, false, false, false, null));
        assertEquals(Step.NOTHING_TO_MIGRATE, s.step);
        assertFalse(s.canPark);
    }

    @Test
    public void an_interrupted_park_is_treated_as_parked() {
        // The manifest is written before the rename, so PARKING plus a folder
        // that is present means the rename finished and the flip did not.
        Status s = MigrationState.of(facts(true, false, null, false, true, false,
                parked(ParkManifest.State.PARKING)));
        assertEquals(Step.PUT_BACK, s.step);
        assertTrue(s.canPutBack);
    }

    @Test
    public void grant_revoked_does_not_change_step_in_restored() {
        // Step should be based on keptDatabasesPresent alone, not on hasAllFilesAccess.
        // Restored, patched app, kept databases, but no grant.
        Status s = MigrationState.of(facts(false, true, PATCHED_SIG, true, false, true,
                parked(ParkManifest.State.RESTORED)));
        assertEquals(Step.VERIFY_AND_RESTORE, s.step);
        assertTrue(s.needsGrant);
        assertFalse(s.canFinish);
    }

    @Test
    public void null_signature_is_treated_as_possibly_the_original() {
        // Unreadable signature refuses put-back rather than risk deleting the only copy.
        Status s = MigrationState.of(facts(true, true, null, false, true, false,
                parked(ParkManifest.State.PARKED)));
        assertFalse(s.canPutBack);
        assertTrue(mentions(s.blockers, "certificate could not be read"));
    }

    @Test
    public void signature_comparison_is_case_insensitive() {
        // Same signature in different letter case is still recognized as the original.
        Status s = MigrationState.of(facts(true, true, STOCK_SIG.toUpperCase(), false, true, false,
                parked(ParkManifest.State.PARKED)));
        assertFalse(s.canPutBack);
        assertTrue(mentions(s.blockers, "still installed"));
    }

    @Test
    public void interrupted_put_back_with_whatsapp_folder_is_treated_as_restored() {
        // PARKED manifest with no park folder but WhatsApp folder present: rename completed,
        // manifest flip didn't. Treat as restored.
        Status s = MigrationState.of(facts(true, false, null, true, false, true,
                parked(ParkManifest.State.PARKED)));
        assertEquals(Step.INSTALL_PATCHED, s.step);
    }

    @Test
    public void interrupted_put_back_with_no_folders_is_broken() {
        // Manifest says PARKED, but both park folder and WhatsApp folder are gone.
        Status s = MigrationState.of(facts(true, false, null, false, false, false,
                parked(ParkManifest.State.PARKED)));
        assertEquals(Step.NEEDS_ATTENTION, s.step);
        assertFalse(s.canPark);
        assertFalse(s.canPutBack);
        assertFalse(s.canFinish);
        assertTrue(mentions(s.blockers, "neither"));
    }

    @Test
    public void null_manifest_with_original_whatsapp_installed() {
        // No parked folder, no manifest, but original WhatsApp is installed.
        // Should still be ready to park.
        Status s = MigrationState.of(facts(true, true, STOCK_SIG, true, false, false, null));
        assertEquals(Step.BACK_UP_AND_PARK, s.step);
        assertTrue(s.canPark);
    }

    @Test
    public void delete_is_refused_while_parked_with_no_whatsapp_folder() {
        // Parked with no WhatsApp folder means the only copy is in the park folder.
        Status s = MigrationState.of(facts(true, false, null, false, true, false,
                parked(ParkManifest.State.PARKED)));
        assertFalse(s.canFinish);
    }

    @Test
    public void delete_is_refused_at_install_patched() {
        // At INSTALL_PATCHED (no app or wrong app), deletion is offered only at VERIFY_AND_RESTORE.
        Status s = MigrationState.of(facts(true, false, null, true, false, true,
                parked(ParkManifest.State.RESTORED)));
        assertEquals(Step.INSTALL_PATCHED, s.step);
        assertFalse(s.canFinish);
    }

    @Test
    public void parking_with_no_park_folder_is_a_fresh_start() {
        // PARKING manifest with no park folder means the rename never ran.
        // This is not an interrupted put-back; it is a fresh start.
        Status s = MigrationState.of(facts(true, true, STOCK_SIG, true, false, false,
                parked(ParkManifest.State.PARKING)));
        assertEquals(Step.BACK_UP_AND_PARK, s.step);
        assertTrue(s.canPark);
    }

    @Test
    public void restored_with_unreadable_signature_cannot_identify_app() {
        // Restored branch cannot proceed if WhatsApp is installed but signature is unreadable.
        Status s = MigrationState.of(facts(true, true, null, true, false, true,
                parked(ParkManifest.State.RESTORED)));
        assertEquals(Step.NEEDS_ATTENTION, s.step);
        assertTrue(mentions(s.blockers, "certificate could not be read"));
    }

    @Test
    public void nothing_can_be_deleted_while_parked_with_both_locations() {
        // Allowed before 2026-10-10. Never used by the store, and never safe to offer.
        Status s = MigrationState.of(facts(true, false, null, true, true, false,
                parked(ParkManifest.State.PARKED)));
        assertEquals(Step.PUT_BACK, s.step);
        assertFalse(s.canFinish);
    }

    @Test
    public void crypt15_manifest_emits_a_warning_in_parked_state() {
        // crypt15 means the user needs their own 64-digit key. The warning must appear
        // at UNINSTALL_WHATSAPP and PUT_BACK so they see it before uninstalling.
        ParkManifest crypt15 = new ParkManifest(ParkManifest.State.PARKED, 1759000000000L,
                "2.26.38.74", 263807433L, STOCK_SIG, "crypt15", 10, 1000L,
                Collections.<ParkManifest.Db>emptyList());
        Status s = MigrationState.of(facts(true, false, null, false, true, false, crypt15));
        assertEquals(Step.PUT_BACK, s.step);
        assertTrue(s.warnings.size() > 0);
        assertTrue(mentions(s.warnings, "crypt15"));
    }

    @Test
    public void crypt15_manifest_emits_a_warning_in_restored_state() {
        // crypt15 warning also appears at VERIFY_AND_RESTORE for completeness.
        ParkManifest crypt15 = new ParkManifest(ParkManifest.State.RESTORED, 1759000000000L,
                "2.26.38.74", 263807433L, STOCK_SIG, "crypt15", 10, 1000L,
                Collections.<ParkManifest.Db>emptyList());
        Status s = MigrationState.of(facts(true, true, PATCHED_SIG, true, false, true, crypt15));
        assertTrue(s.warnings.size() > 0);
        assertTrue(mentions(s.warnings, "crypt15"));
    }

    @Test
    public void crypt14_manifest_does_not_emit_the_crypt15_warning() {
        // Only crypt15 needs the warning about the 64-digit key.
        Status s = MigrationState.of(facts(true, true, PATCHED_SIG, true, false, true,
                parked(ParkManifest.State.RESTORED)));
        for (String w : s.warnings) {
            if (w.toLowerCase().contains("crypt15")) {
                throw new AssertionError("crypt14 should not emit the crypt15 warning");
            }
        }
    }

    @Test
    public void a_stale_parking_manifest_with_wa_folder_present_is_workable() {
        Status s = MigrationState.of(facts(true, true, STOCK_SIG, true, false, false,
                parked(ParkManifest.State.PARKING)));
        assertEquals(Step.BACK_UP_AND_PARK, s.step);
        assertTrue(s.canPark);
        assertTrue(s.blockers.isEmpty());
    }

    @Test
    public void a_parking_manifest_with_no_folder_anywhere_has_nothing_to_park() {
        Status s = MigrationState.of(facts(true, true, STOCK_SIG, false, false, false,
                parked(ParkManifest.State.PARKING)));
        assertEquals(Step.BACK_UP_AND_PARK, s.step);
        assertFalse(s.canPark);
        assertTrue(mentions(s.blockers, "nothing to park"));
    }

    @Test
    public void a_broken_parked_state_names_the_kept_copy_it_may_leave_behind() {
        Facts f = new Facts(true, true, "2.26.38.74", 263807433L, STOCK_SIG, false, false, true,
                parked(ParkManifest.State.PARKED), "/sdcard/WAMigrate/Databases-kept");
        Status s = MigrationState.of(f);
        assertEquals(Step.NEEDS_ATTENTION, s.step);
        assertTrue(mentions(s.blockers, "/sdcard/WAMigrate/Databases-kept"));
    }

    // --- the move's record and its saved copy (2026-10-10 spec, states table) ---

    @Test
    public void an_unreadable_record_stops_everything() {
        Facts f = facts(true, true, STOCK_SIG, true, false, false, null)
                .withMove(null, true, true, false, false, false, INSTALLED_AT, 1000L, "msgstore.db.crypt14");
        Status s = MigrationState.of(f);
        assertEquals(Step.NEEDS_ATTENTION, s.step);
        assertFalse(s.canPark);
        assertFalse(s.canCancel);
        assertFalse(s.canFinish);
        assertTrue(mentions(s.blockers, "move.txt"));
    }

    @Test
    public void with_a_saved_copy_and_the_original_installed_the_move_is_at_its_start() {
        Status s = MigrationState.of(recorded(true, STOCK_SIG, true));
        assertEquals(Step.BACK_UP_AND_PARK, s.step);
        assertTrue(s.canPark);
        assertTrue(s.canCancel);
        assertFalse(s.canFinish);
    }

    @Test
    public void with_no_manifest_the_records_certificate_names_the_original() {
        assertTrue(recorded(true, STOCK_SIG, true).installedIsTheOriginal());
        assertFalse(recorded(true, PATCHED_SIG, false).installedIsTheOriginal());
    }

    @Test
    public void cancel_is_refused_once_WhatsApp_was_installed_again() {
        Status s = MigrationState.of(moved(true, STOCK_SIG, false, true, false, false, null,
                record(STOCK_SIG), true, true, INSTALLED_AT + 1, SAVED_BACKUP_MS, 1000L));
        assertEquals(Step.BACK_UP_AND_PARK, s.step);
        assertFalse(s.canCancel);
    }

    @Test
    public void cancel_is_refused_when_WhatsApp_no_longer_holds_the_saved_backup() {
        // Its newest backup is older than the saved one, so the saved one is gone from it.
        Status s = MigrationState.of(moved(true, STOCK_SIG, false, true, false, false, null,
                record(STOCK_SIG), true, true, INSTALLED_AT, SAVED_BACKUP_MS - 60_000L, 1000L));
        assertFalse(s.canCancel);
    }

    @Test
    public void cancel_is_allowed_after_WhatsApp_rotated_its_backup_into_a_newer_one() {
        // A new backup renames the old msgstore.db.crypt14 to a dated name and writes a
        // new one: same name, newer, about the same size.
        Status s = MigrationState.of(moved(true, STOCK_SIG, false, true, false, false, null,
                record(STOCK_SIG), true, true, INSTALLED_AT, SAVED_BACKUP_MS + 3_600_000L, 1100L));
        assertTrue(s.canCancel);
    }

    @Test
    public void the_original_removed_with_its_data_kept_must_be_removed_properly() {
        Status s = MigrationState.of(moved(true, STOCK_SIG, true, false, false, false, null,
                record(STOCK_SIG), true, true, INSTALLED_AT, 0L, 0L));
        assertEquals(Step.UNINSTALL_WHATSAPP, s.step);
        assertFalse(s.canPutBack);
        assertFalse(s.canCancel);
    }

    @Test
    public void the_original_removed_by_hand_is_put_back_from_the_saved_copy() {
        // The 2026-10-10 loss: removed from Settings before step 4.
        Status s = MigrationState.of(recorded(false, null, false));
        assertEquals(Step.PUT_BACK, s.step);
        assertTrue(s.canPutBack);
        assertFalse(s.canFinish);
        assertFalse(s.canCancel);
    }

    @Test
    public void another_WhatsApp_over_an_empty_folder_takes_the_saved_copy() {
        Status s = MigrationState.of(recorded(true, PATCHED_SIG, false));
        assertEquals(Step.PUT_BACK, s.step);
        assertTrue(s.canPutBack);
    }

    @Test
    public void something_in_WhatsApps_folder_blocks_the_put_back_from_the_saved_copy() {
        Status s = MigrationState.of(recorded(true, PATCHED_SIG, true));
        assertEquals(Step.PUT_BACK, s.step);
        assertFalse(s.canPutBack);
        assertTrue(mentions(s.blockers, "in the way"));
    }

    @Test
    public void the_original_removed_and_the_saved_copy_damaged_or_missing_needs_attention() {
        Status damaged = MigrationState.of(moved(false, null, false, false, false, false, null,
                record(STOCK_SIG), true, false, 0L, 0L, 0L));
        assertEquals(Step.NEEDS_ATTENTION, damaged.step);
        Status missing = MigrationState.of(moved(false, null, false, false, false, false, null,
                record(STOCK_SIG), false, false, 0L, 0L, 0L));
        assertEquals(Step.NEEDS_ATTENTION, missing.step);
        assertFalse(missing.canPutBack);
    }

    @Test
    public void a_copy_without_its_record_and_no_backup_left_needs_attention() {
        // Killed between Saved.tmp -> Saved and writing move.txt, then WhatsApp removed.
        Status s = MigrationState.of(moved(false, null, false, false, false, false, null, null,
                true, false, 0L, 0L, 0L));
        assertEquals(Step.NEEDS_ATTENTION, s.step);
        assertFalse(s.canFinish);
    }

    @Test
    public void a_copy_without_its_record_beside_a_live_backup_is_a_fresh_start() {
        Status s = MigrationState.of(moved(true, STOCK_SIG, false, true, false, false, null, null,
                true, false, INSTALLED_AT, SAVED_BACKUP_MS, 1000L));
        assertEquals(Step.BACK_UP_AND_PARK, s.step);
        assertTrue(s.canPark);
    }

    @Test
    public void a_parked_backup_older_than_the_saved_one_needs_attention() {
        Status s = MigrationState.of(moved(false, null, false, false, true, false,
                parkedWith(ParkManifest.State.PARKED, SAVED_BACKUP_MS - 60_000L, 1000L),
                record(STOCK_SIG), true, true, 0L, 0L, 0L));
        assertEquals(Step.NEEDS_ATTENTION, s.step);
        assertFalse(s.canPutBack);
    }

    @Test
    public void a_parked_backup_much_smaller_than_the_saved_one_needs_attention() {
        Status s = MigrationState.of(moved(false, null, false, false, true, false,
                parkedWith(ParkManifest.State.PARKED, SAVED_BACKUP_MS, 100L),
                record(STOCK_SIG), true, true, 0L, 0L, 0L));
        assertEquals(Step.NEEDS_ATTENTION, s.step);
    }

    @Test
    public void a_parked_backup_that_is_the_saved_one_goes_on_as_today() {
        Status s = MigrationState.of(moved(false, null, false, false, true, false,
                parkedWith(ParkManifest.State.PARKED, SAVED_BACKUP_MS, 1000L),
                record(STOCK_SIG), true, true, 0L, 0L, 0L));
        assertEquals(Step.PUT_BACK, s.step);
        assertTrue(s.canPutBack);
    }

    @Test
    public void a_put_back_backup_deleted_again_goes_back_from_the_kept_copy() {
        // Put back, then the WhatsApp it went into was removed ("Uninstall and retry").
        Status s = MigrationState.of(facts(true, false, null, false, false, true,
                parked(ParkManifest.State.RESTORED)));
        assertEquals(Step.PUT_BACK, s.step);
        assertTrue(s.canPutBack);
    }

    @Test
    public void a_put_back_backup_deleted_again_with_no_copy_left_needs_attention() {
        Status s = MigrationState.of(facts(true, false, null, false, false, false,
                parked(ParkManifest.State.RESTORED)));
        assertEquals(Step.NEEDS_ATTENTION, s.step);
        assertFalse(s.canPutBack);
    }

    @Test
    public void the_users_word_alone_does_not_finish_a_move() {
        MoveRecord r = record(STOCK_SIG).withRestored(1_759_100_000_000L, MoveRecord.Source.SAVED)
                .withChatsConfirmed(1_759_200_000_000L);
        Status s = MigrationState.of(moved(true, PATCHED_SIG, false, true, false, false, null, r,
                true, true, INSTALLED_AT, 1_759_000_000_000L, 1000L));
        assertEquals(Step.VERIFY_AND_RESTORE, s.step);
        assertFalse(s.canFinish);
    }

    @Test
    public void a_put_back_from_the_park_without_its_manifest_needs_attention() {
        MoveRecord r = record(STOCK_SIG).withRestored(1L, MoveRecord.Source.PARKED).withChatsConfirmed(2L);
        Status s = MigrationState.of(moved(true, PATCHED_SIG, false, true, false, false, null, r,
                false, false, INSTALLED_AT, 2L, 1000L));
        assertEquals(Step.NEEDS_ATTENTION, s.step);
        assertFalse(s.canFinish);
    }

    @Test
    public void proof_alone_does_not_finish_a_move() {
        MoveRecord r = record(STOCK_SIG).withRestored(1L, MoveRecord.Source.SAVED)
                .withEvidence(2L, 1000L, "e".repeat(64));
        Status s = MigrationState.of(moved(true, PATCHED_SIG, false, true, false, false, null, r,
                true, true, INSTALLED_AT, 2L, 1000L));
        assertEquals(Step.VERIFY_AND_RESTORE, s.step);
        assertFalse(s.canFinish);
    }

    @Test
    public void the_users_word_and_proof_finish_a_move() {
        MoveRecord r = record(STOCK_SIG).withRestored(1L, MoveRecord.Source.SAVED)
                .withEvidence(2L, 1000L, "e".repeat(64)).withChatsConfirmed(3L);
        Status s = MigrationState.of(moved(true, PATCHED_SIG, false, true, false, false, null, r,
                true, true, INSTALLED_AT, 2L, 1000L));
        assertEquals(Step.DONE, s.step);
        assertTrue(s.canFinish);
    }

    @Test
    public void a_confirmation_and_proof_from_before_the_latest_put_back_do_not_finish_a_move() {
        // The stale case: put back again at 10 with the earlier round's 2 and 3 left in place.
        MoveRecord base = record(STOCK_SIG);
        MoveRecord stale = new MoveRecord(base.waVersionName, base.waVersionCode, base.waSignatureSha256,
                base.waFirstInstallMs, base.crypt, base.savedAtMs, base.saved,
                10L, MoveRecord.Source.SAVED, 3L, 2L, 1000L, "e".repeat(64));
        Status s = MigrationState.of(moved(true, PATCHED_SIG, false, true, false, false, null, stale,
                true, true, INSTALLED_AT, 2L, 1000L));
        assertEquals(Step.VERIFY_AND_RESTORE, s.step);
        assertFalse(s.canFinish);
    }

    @Test
    public void a_put_back_with_no_database_needs_only_the_users_word() {
        // parked(RESTORED) lists no databases: nothing was put back that a backup could prove.
        ParkManifest m = parked(ParkManifest.State.RESTORED);
        MoveRecord r = MoveRecord.fromManifest(m, 0L).withRestored(1L, MoveRecord.Source.PARKED)
                .withChatsConfirmed(2L);
        Status s = MigrationState.of(facts(true, true, PATCHED_SIG, true, false, false, m)
                .withMove(r, false, false, false, false, false, 0L, 0L, null));
        assertEquals(Step.DONE, s.step);
        assertTrue(s.canFinish);
    }

    @Test
    public void a_move_restored_before_records_existed_waits_for_the_users_word() {
        // Review Focus 1: mid-way when this update lands. It must not jump to DONE.
        Status s = MigrationState.of(facts(true, true, PATCHED_SIG, true, false, true,
                parked(ParkManifest.State.RESTORED)));
        assertEquals(Step.VERIFY_AND_RESTORE, s.step);
        assertFalse(s.canFinish);
    }
}
