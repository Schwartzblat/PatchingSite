package com.patcher.migrate;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

/**
 * Cancel at any step but the last (2026-10-24): the backup goes back where WhatsApp
 * looks for it, and then the move ends. Until then it could only be cancelled before
 * step 4's park, and a user who backed out of the uninstall dialog was stuck with the
 * folder parked while the original kept running.
 *
 * - At the start: Saving.cancel, as before.
 * - Parked, the original still installed: Parking.unpark, which merges and never
 *   overwrites, then the same as at the start.
 * - Once the original is gone: the put-back the install gate would make, and then
 *   every file of the move is set aside (Leftovers.setAsideMove), never deleted. The
 *   user installs any WhatsApp afterwards, and it finds the backup.
 *
 * Which of these applies, and whether any does, is MigrationState's canCancel. Folders
 * arrive as arguments, as for Parking, so this runs on a JVM against temp folders.
 */
final class Cancelling {

    private final File parkRoot;
    private final Parking parking;
    private final Saving saving;

    Cancelling(File parkRoot, Parking parking, Saving saving) {
        this.parkRoot = parkRoot;
        this.parking = parking;
        this.saving = saving;
    }

    Result cancel(Facts f, long nowMs) {
        Status s = MigrationState.of(f);
        if (!s.canCancel) {
            return Result.failed("The move can't be cancelled right now.", s.blockers);
        }
        switch (s.step) {
            case BACK_UP_AND_PARK:
                return saving.cancel(f.record, nowMs);
            case UNINSTALL_WHATSAPP: {
                Result back = parking.unpark(nowMs);
                if (!back.ok || f.record == null) {
                    // Succeeds only when nothing else is left in it.
                    parkRoot.delete();
                    return back;
                }
                Result ended = saving.cancel(f.record, nowMs);
                return ended.ok ? new Result(true, back.summary + " " + ended.summary, back.details) : ended;
            }
            case PUT_BACK: {
                Result back = Migration.putBackFrom(f, parkRoot, parking, saving, nowMs);
                return back.ok ? setAside(back.summary + " ", nowMs) : back;
            }
            case INSTALL_PATCHED:
            case VERIFY_AND_RESTORE:
                return setAside("", nowMs);
            default:
                return Result.failed("The move can't be cancelled right now.", s.blockers);
        }
    }

    private Result setAside(String done, long nowMs) {
        try {
            Leftovers.setAsideMove(parkRoot, nowMs);
        } catch (IOException e) {
            return Result.failed(done + "The move's copies could not be set aside: " + e.getMessage(),
                    Collections.singletonList(e.getMessage()));
        }
        return Result.ok(done + "Ended the move and set its copies aside.");
    }
}
