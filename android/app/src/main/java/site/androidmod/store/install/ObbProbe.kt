package site.androidmod.store.install

import android.content.Context
import android.os.RemoteException
import java.io.File

/** Longer than any package name Android installs, and a file name can't be longer. */
private const val MAX_PACKAGE_NAME = 255

private val PACKAGE_NAME = Regex("""[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*""")

/** A package name: letters, digits and `_` in parts joined by single dots. Nothing that leaves Android/obb. */
internal fun isPackageName(value: String): Boolean = value.length <= MAX_PACKAGE_NAME && PACKAGE_NAME.matches(value)

/**
 * Whether [packageName] keeps files in `<storageRoot>/Android/obb/<packageName>`: true or false,
 * or null (unknown, which doesn't block) when that can't be told: the folder is missing or can't
 * be listed from this process, or [packageName] isn't a package name.
 */
fun obbFolderHasFiles(storageRoot: File, packageName: String): Boolean? {
    if (!isPackageName(packageName)) return null
    return try {
        File(storageRoot, "Android/obb/$packageName").listFiles()?.isNotEmpty()
    } catch (error: Exception) {
        null
    }
}

/** How [ObbProbeClient] and [ObbProbeProvider] talk: one call, its package as the argument, a code back. */
internal object ObbProbeProtocol {
    const val METHOD = "hasObbFiles"
    const val KEY = "obb"
    private const val HAS_FILES = 1
    private const val NO_FILES = 0
    private const val UNKNOWN = -1

    fun codeOf(answer: Boolean?): Int = when (answer) {
        true -> HAS_FILES
        false -> NO_FILES
        null -> UNKNOWN
    }

    fun answerOf(code: Int): Boolean? = when (code) {
        HAS_FILES -> true
        NO_FILES -> false
        else -> null
    }

    /** The probe's authority, `${applicationId}.obbprobe` in the manifest. */
    fun authority(packageName: String): String = "$packageName.obbprobe"
}

/** The probe's answer code to [method] for the package [arg]; null for a method it doesn't have. */
internal fun probeAnswer(method: String, arg: String?, storageRoot: File): Int? {
    if (method != ObbProbeProtocol.METHOD) return null
    return ObbProbeProtocol.codeOf(arg?.let { obbFolderHasFiles(storageRoot, it) })
}

/**
 * The answer [call] gets from the probe, asking twice at most: no answer (the provider couldn't be
 * reached), or a failure [retryable] says is its process dying, is asked once more, which starts a
 * new process. Any other failure, or a second miss, is unknown (null).
 */
internal fun askProbe(retryable: (Exception) -> Boolean, call: () -> Int?): Boolean? {
    repeat(2) {
        val code = try {
            call()
        } catch (error: Exception) {
            if (!retryable(error)) return null
            null
        }
        if (code != null) return ObbProbeProtocol.answerOf(code)
    }
    return null
}

/**
 * Asks [ObbProbeProvider], in a process of its own, whether an app keeps files in Android/obb, which
 * Android deletes with the app. Blocks while Android starts that process, so call it off the main
 * thread. Null when it can't be told.
 *
 * A process sees other apps' Android/obb only when "Install unknown apps" was allowed before Android
 * started it: allowing it later doesn't change the storage view of a running process (Android 12+
 * no longer restarts it), and customers usually allow it at "Remove <App>". So the picker reads in
 * the store's own process ([InstalledApps.hasObbFiles]), and the check right before an original is
 * removed asks the probe, whose process starts for the question and ends after answering.
 */
class ObbProbeClient(private val context: Context) {

    fun hasObbFiles(packageName: String): Boolean? = askProbe(retryable = { it is RemoteException }) {
        // Unstable: a stable reference to a provider whose process dies gets this process killed too,
        // and the probe ends its process after every answer. A DeadObjectException is a RemoteException.
        context.contentResolver.acquireUnstableContentProviderClient(ObbProbeProtocol.authority(context.packageName))
            ?.use { client ->
                client.call(ObbProbeProtocol.METHOD, packageName, null)
                    ?.getInt(ObbProbeProtocol.KEY, ObbProbeProtocol.codeOf(null))
            }
    }
}
