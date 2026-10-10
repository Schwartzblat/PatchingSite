package site.androidmod.store.update

import site.androidmod.store.data.StoreInfo

/** This app as installed, read from PackageManager rather than BuildConfig. */
data class InstalledBuild(val versionCode: Long, val versionName: String)

/**
 * Whether the published store build should be offered as an update.
 *
 * Ordered by versionCode alone. Release builds stamp it from the build time (see
 * app/build.gradle.kts), so a later build always has a higher code, while
 * versionName is hand-edited and can stay "1.0" across many releases.
 */
sealed interface UpdateCheck {
    data class Available(val versionCode: Long, val versionName: String?, val size: Long) : UpdateCheck
    data object UpToDate : UpdateCheck

    /**
     * Nothing published, or a build whose version the server could not read.
     * Deliberately not an update: an APK that cannot be ordered might be older,
     * and offering it is a button that ends in INSTALL_FAILED_VERSION_DOWNGRADE.
     */
    data object Unknown : UpdateCheck

    companion object {
        fun of(published: StoreInfo, installed: InstalledBuild): UpdateCheck {
            val code = published.versionCode
            return when {
                !published.available || code == null -> Unknown
                code > installed.versionCode -> Available(code, published.versionName, published.size)
                else -> UpToDate
            }
        }
    }
}

/**
 * "1.0 (build 389123)". The build number is always shown because versionName is
 * hand-edited and repeats across releases - two different builds both reading
 * "1.0" would make "update available" look like a lie.
 */
fun versionLabel(versionName: String?, versionCode: Long): String =
    if (versionName.isNullOrBlank()) "Build $versionCode" else "$versionName (build $versionCode)"
