package site.androidmod.patch

import java.io.File

/** Signs an unsigned (or differently signed) APK into [signed]. */
fun interface ApkSigner {
    /** @throws PatchException.SignFailed when signing fails; [signed] is then absent. */
    fun sign(unsigned: File, signed: File)
}
