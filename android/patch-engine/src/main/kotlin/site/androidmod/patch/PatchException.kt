package site.androidmod.patch

/** Every failure the patch engine reports to its caller. */
sealed class PatchException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class BadApk(message: String, cause: Throwable? = null) : PatchException(message, cause)
    class BadBundle(message: String, cause: Throwable? = null) : PatchException(message, cause)
    class RecordMismatch(message: String, cause: Throwable? = null) : PatchException(message, cause)
    class Unsupported(message: String, cause: Throwable? = null) : PatchException(message, cause)
    class NotOfficial(message: String, cause: Throwable? = null) : PatchException(message, cause)
    class SignFailed(message: String, cause: Throwable? = null) : PatchException(message, cause)

    /** Writing to the work directory or the output location failed (disk full, unwritable, ...). */
    class Storage(message: String, cause: Throwable? = null) : PatchException(message, cause)
}
