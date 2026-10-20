package site.androidmod.store.install

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process

/**
 * Answers [ObbProbeClient] from a process of its own (`:obbprobe`, not exported): whether an app
 * keeps files in Android/obb, by [obbFolderHasFiles]. After each answer it ends its process, so the
 * next question starts a new one with the storage view the permissions give now.
 *
 * Touches neither StoreApp.instance nor the Container: StoreApp sets nothing up in this process.
 */
class ObbProbeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val code = probeAnswer(method, arg, Environment.getExternalStorageDirectory())
        endSoon()
        return code?.let { Bundle().apply { putInt(ObbProbeProtocol.KEY, it) } }
    }

    /** After the answer has gone back: the client holds only an unstable reference, so it lives on. */
    private fun endSoon() {
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, END_DELAY_MILLIS)
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? =
        null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    private companion object {
        const val END_DELAY_MILLIS = 300L
    }
}
