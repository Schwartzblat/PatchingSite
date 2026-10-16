package site.androidmod.patch

/** Every failure the patch engine reports to its caller. */
sealed class PatchException(message: String) : Exception(message) {
    class BadApk(message: String) : PatchException(message)
    class BadBundle(message: String) : PatchException(message)
    class RecordMismatch(message: String) : PatchException(message)
    class Unsupported(message: String) : PatchException(message)
    class NotOfficial(message: String) : PatchException(message)
    class SignFailed(message: String) : PatchException(message)
}
