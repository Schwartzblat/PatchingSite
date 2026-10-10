package site.androidmod.store.move

/**
 * Runs the move's writes one at a time, in this process. The engine's own guard
 * (Migration's running flag) fails a second caller with "Another operation is
 * already running". This makes it wait instead. The store copies the backup on
 * every open, so step 4's park can easily start while a copy is still running,
 * and it should finish after the copy rather than fail. The install watcher runs
 * in the same process, so it waits here too.
 */
class OneAtATime {

    private val lock = Any()

    fun <T> run(block: () -> T): T = synchronized(lock) { block() }
}
