package site.androidmod.store.move

import com.patcher.migrate.Facts
import com.patcher.migrate.ParkManifest.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WhatsApp patched on the phone (the 2026-10-16 spec, §7 "Which key counts where"):
 * ours is signed with the user's own key, and a WhatsApp signed with the store's old
 * shared key is a previous build. In the guide tests here OURS_SIG is the user's key
 * and PREVIOUS_SIG the shared one.
 */
class PreviousBuildTest {

    private fun atStep(step: GuideStep, variant: StepVariant = StepVariant.NORMAL) = MovePhase.AtStep(step, variant)

    /** The store's own certificate and the user's key, as MoveRepository is given them. */
    private val shared = PREVIOUS_SIG
    private val user = OURS_SIG

    // --- which build is which ---

    @Test
    fun `without on-device the shared key is still ours`() {
        assertEquals(BuildKind.OURS, buildKind(shared, ownSignature = shared, userSignature = user, onDevice = false))
        // The user's key means nothing until the server moves WhatsApp onto the phone.
        assertEquals(BuildKind.OTHER, buildKind(user, ownSignature = shared, userSignature = user, onDevice = false))
        assertEquals(BuildKind.OTHER, buildKind(OFFICIAL_SIG, shared, user, onDevice = false))
    }

    @Test
    fun `on the phone the user's key is ours and the shared key a previous build`() {
        assertEquals(BuildKind.OURS, buildKind(user, shared, user, onDevice = true))
        assertEquals(BuildKind.PREVIOUS, buildKind(shared, shared, user, onDevice = true))
        assertEquals(BuildKind.OTHER, buildKind(OFFICIAL_SIG, shared, user, onDevice = true))
    }

    @Test
    fun `without the user's key nothing is ours`() {
        assertEquals(BuildKind.OTHER, buildKind(user, shared, userSignature = null, onDevice = true))
        assertEquals(BuildKind.PREVIOUS, buildKind(shared, shared, userSignature = null, onDevice = true))
    }

    @Test
    fun `certificates compare as the engine compares them`() {
        assertEquals(BuildKind.OURS, buildKind(user.uppercase(), shared, user, onDevice = true))
        assertEquals(BuildKind.PREVIOUS, buildKind(shared.uppercase(), shared, user, onDevice = true))
        assertEquals(BuildKind.OURS, buildKind(shared.uppercase(), shared, user, onDevice = false))
        // An unreadable certificate is neither ours nor a previous build: the guide stays cautious.
        assertEquals(BuildKind.OTHER, buildKind(null, shared, user, onDevice = true))
        assertEquals(BuildKind.OTHER, buildKind("", shared, user, onDevice = true))
        assertEquals(BuildKind.OTHER, buildKind(null, null, null, onDevice = false))
        assertEquals(BuildKind.OTHER, buildKind("", "", "", onDevice = true))
    }

    // --- moving into ours ---

    /** The move into ours, state by state, for whichever build is being left. */
    private val movesIn: List<(String) -> Facts> = listOf(
        { sig -> facts(signature = sig) },
        { sig -> facts(signature = sig, newestBackupMs = 0, newestBackupCrypt = "none") },
        { sig -> facts(signature = sig, newestBackupMs = STALE_BACKUP) },
        { sig -> facts(signature = sig, newestBackupCrypt = "crypt15") },
        { sig -> facts(signature = sig, versionName = "2.26.40.1") },
        { sig -> facts(access = false, signature = sig, waFolder = false, newestBackupMs = 0) },
        { sig ->
            facts(access = false, signature = sig, versionName = "2.26.40.1", waFolder = false, newestBackupMs = 0)
        },
        { sig -> facts(signature = sig, record = record(from = sig)) },
        { sig -> facts(signature = sig, waFolder = false, parkFolder = true, manifest = manifest(from = sig)) },
        { sig ->
            facts(signature = sig, dataKept = true, waFolder = false, parkFolder = true,
                manifest = manifest(from = sig))
        },
        { sig -> facts(installed = false, waFolder = false, parkFolder = true, manifest = manifest(from = sig)) },
        { sig ->
            facts(installed = false, waFolder = true, kept = true, manifest = manifest(State.RESTORED, from = sig))
        },
    )

    @Test
    fun `a previous build moves to ours with the official phases`() {
        movesIn.forEachIndexed { i, state ->
            // As the store has it: once something moved, the hint is set.
            val moved = state(OFFICIAL_SIG).let { it.manifest != null || it.record != null }
            for (downloaded in listOf(false, true)) {
                val official = guide(state(OFFICIAL_SIG), moveActive = moved, downloaded = downloaded)
                val previous = guide(state(PREVIOUS_SIG), moveActive = moved, downloaded = downloaded)
                assertEquals("state $i", MoveDirection.TO_OURS, previous.direction)
                // The same guide, marked, and offering the way back before anything moved.
                assertEquals(
                    "state $i, downloaded $downloaded",
                    official.copy(fromPreviousBuild = true, oursAtRisk = !official.inProgress),
                    previous,
                )
            }
        }
        assertEquals(atStep(GuideStep.DOWNLOAD), guide(facts(signature = PREVIOUS_SIG)).phase)
        // WhatsApp can't restore a backup made by a newer version of itself, whoever built it.
        assertEquals(
            MovePhase.NotPossibleYet("2.26.40.1", NEWEST),
            guide(facts(signature = PREVIOUS_SIG, versionName = "2.26.40.1")).phase,
        )
    }

    @Test
    fun `the way back is offered from a previous build before anything moved`() {
        val g = guide(facts(signature = PREVIOUS_SIG))
        assertEquals(MoveDirection.TO_OURS, g.direction)
        assertTrue(g.oursAtRisk)
        assertTrue(g.offersReturn)
        // Not once the move into ours has begun, as with ours.
        val begun = facts(signature = PREVIOUS_SIG, record = record(from = PREVIOUS_SIG))
        assertFalse(guide(begun, moveActive = true).offersReturn)
        // Nor without on-device, where the repository never reports a previous build.
        assertFalse(guide(facts(signature = PREVIOUS_SIG), previous = false).offersReturn)
    }

    // --- going back ---

    /** What park() writes when leaving [sig], a build of NEWEST. */
    private fun from(sig: String, state: State = State.PARKED) =
        manifest(state, from = sig, versionName = NEWEST, versionCode = 263974L)

    /** The return, state by state, for whichever build the backup is leaving. */
    private val movesBack: List<(String) -> Facts> = listOf(
        { sig -> facts(signature = sig) },
        { sig -> facts(signature = sig, newestBackupMs = 0, newestBackupCrypt = "none") },
        { sig -> facts(signature = sig, newestBackupMs = STALE_BACKUP) },
        { sig -> facts(access = false, signature = sig, waFolder = false, newestBackupMs = 0) },
        { sig ->
            facts(signature = sig, versionName = NEWEST, versionCode = 263974L,
                record = record(from = sig, versionName = NEWEST, versionCode = 263974L))
        },
        { sig -> facts(signature = sig, waFolder = false, parkFolder = true, manifest = from(sig)) },
        { sig -> facts(signature = sig, dataKept = true, waFolder = false, parkFolder = true, manifest = from(sig)) },
        { sig -> facts(installed = false, waFolder = false, parkFolder = true, manifest = from(sig)) },
        { sig ->
            facts(versionName = "2.26.40.1", versionCode = 264001L, waFolder = false, parkFolder = true,
                manifest = from(sig))
        },
        { sig ->
            facts(versionName = "2.26.38.80", versionCode = 263880L, waFolder = false, parkFolder = true,
                manifest = from(sig))
        },
        { sig -> facts(installed = false, waFolder = true, kept = true, manifest = from(sig, State.RESTORED)) },
        { sig ->
            facts(versionName = "2.26.40.1", versionCode = 264001L, kept = true, manifest = from(sig, State.RESTORED))
        },
    )

    @Test
    fun `a previous build can go back to the regular WhatsApp`() {
        movesBack.forEachIndexed { i, state ->
            // As the store has it: once something moved, the hint carries the direction.
            val moved = state(OURS_SIG).let { it.manifest != null || it.record != null }
            val ours = guide(state(OURS_SIG), moveActive = moved, toOfficialHint = moved)
            val leaving = state(PREVIOUS_SIG)
            val previous = guide(leaving, moveActive = moved, toOfficialHint = moved, previousGoesBack = true)
            assertEquals("state $i", MoveDirection.TO_OFFICIAL, previous.direction)
            // The same return, marked. The store's builds can't install over a previous
            // build, so before the backup leaves it is also guarded like the official app.
            assertEquals(
                "state $i",
                ours.copy(fromPreviousBuild = true, officialAtRisk = previousIn(leaving) && !ours.pastBackup),
                previous,
            )
        }
        val g = guide(facts(signature = PREVIOUS_SIG), previousGoesBack = true)
        assertEquals(atStep(GuideStep.REMOVE_OLD), g.phase)
        assertFalse(GuideStep.DOWNLOAD in g.direction.steps)
        assertTrue(g.oursAtRisk)
        assertTrue(g.offersReturn)
    }

    @Test
    fun `a previous build going back stays going back when the store dies before storing the direction`() {
        // The copy is saved first and the hint written after it; in between only the choice says which way.
        val f = facts(signature = PREVIOUS_SIG, record = record(from = PREVIOUS_SIG))
        assertEquals(MoveDirection.TO_OFFICIAL, guide(f, previousGoesBack = true).direction)
        assertEquals(MoveDirection.TO_OURS, guide(f).direction)
    }

    @Test
    fun `park stores the direction the guide shows`() {
        mapOf("official" to OFFICIAL_SIG, "ours" to OURS_SIG, "previous" to PREVIOUS_SIG).forEach { (name, sig) ->
            for (goesBack in listOf(false, true)) {
                val f = facts(signature = sig)
                val shown = guide(f, previousGoesBack = goesBack).direction
                assertEquals(
                    "$name, going back $goesBack",
                    shown == MoveDirection.TO_OFFICIAL,
                    startsToOfficial(oursIn(f), previousIn(f), goesBack),
                )
            }
        }
    }

    // --- the guide's words ---

    @Test
    fun `the guide marks a previous build`() {
        assertTrue(guide(facts(signature = PREVIOUS_SIG)).fromPreviousBuild)
        assertTrue(guide(facts(signature = PREVIOUS_SIG), previousGoesBack = true).fromPreviousBuild)
        val noAccess = facts(access = false, signature = PREVIOUS_SIG, waFolder = false, newestBackupMs = 0)
        assertTrue(guide(noAccess).fromPreviousBuild)
        // Once the backup has left it, the move's own certificate says so, removed or not.
        val parked = facts(installed = false, waFolder = false, parkFolder = true,
            manifest = manifest(from = PREVIOUS_SIG))
        assertTrue(guide(parked, moveActive = true).fromPreviousBuild)
        assertTrue(guide(parked, moveActive = true, toOfficialHint = true).fromPreviousBuild)
        val keptData = facts(signature = PREVIOUS_SIG, dataKept = true, waFolder = false, parkFolder = true,
            manifest = manifest(from = PREVIOUS_SIG))
        assertTrue(guide(keptData, moveActive = true).fromPreviousBuild)
        // Leaving the official app or ours is not, nor a move from the official app with a
        // previous build installed over its put-back (a build downloaded before the switch).
        assertFalse(guide(facts()).fromPreviousBuild)
        assertFalse(guide(facts(signature = OURS_SIG)).fromPreviousBuild)
        val putBack = facts(signature = PREVIOUS_SIG, versionName = NEWEST, versionCode = 263974L, kept = true,
            manifest = manifest(State.RESTORED))
        assertFalse(guide(putBack, moveActive = true).fromPreviousBuild)
    }

    // --- the store's own safety nets ---

    @Test
    fun `a previous build is guarded like the official app whichever way it goes`() {
        // The store's builds are signed with the user's key and can't install over it, so
        // the Library and the app page ask about the chats before anything removes it.
        assertTrue(guide(facts(signature = PREVIOUS_SIG)).officialAtRisk)
        assertTrue(guide(facts(signature = PREVIOUS_SIG), previousGoesBack = true).officialAtRisk)
        // Once the backup has left, the guide owns removing it, as with every WhatsApp.
        val parked = facts(signature = PREVIOUS_SIG, waFolder = false, parkFolder = true,
            manifest = manifest(from = PREVIOUS_SIG))
        assertFalse(guide(parked, moveActive = true).officialAtRisk)
        // And the install gate will not put a backup in front of an install that would
        // fail on the certificate: MoveRepository passes the strict isOurs.
        val putBack = facts(signature = PREVIOUS_SIG, versionName = NEWEST, versionCode = 263974L, kept = true,
            manifest = manifest(State.RESTORED))
        assertTrue(storeInstallWouldStrandBackup(status(putBack), ours = oursIn(putBack)))
    }
}
