package site.androidmod.store.data

import java.io.IOException

/**
 * The device cannot hold the download. [bytesNeeded] is how much more free space
 * the transfer needs, or -1 when that is not known (the server sent no length).
 *
 * Its own type rather than an [ApiException] or a plain IOException because it
 * is the one download failure the user can fix on the device, so it gets its own
 * message instead of "the download could not be completed" - and because retrying
 * it is pointless, so ApiClient never does.
 */
class NotEnoughSpaceException(val bytesNeeded: Long) :
    IOException("Not enough free space for the download (needs $bytesNeeded more bytes)")

/** A parsed `Content-Range: bytes <start>-<end>/<total>`. [total] is null for `*`. */
internal data class ContentRange(val start: Long, val end: Long, val total: Long?)

private val CONTENT_RANGE = Regex("""^bytes (\d+)-(\d+)/(\d+|\*)$""")

/**
 * Null for anything that is not a satisfied byte range, including the
 * `bytes * /<total>` form a 416 carries. A range this client cannot read is never
 * guessed at: the caller starts the file over instead.
 */
internal fun parseContentRange(header: String?): ContentRange? {
    val match = CONTENT_RANGE.matchEntire(header?.trim() ?: return null) ?: return null
    val (start, end, total) = match.destructured
    val range = ContentRange(
        start = start.toLongOrNull() ?: return null,
        end = end.toLongOrNull() ?: return null,
        total = if (total == "*") null else total.toLongOrNull() ?: return null,
    )
    return range.takeIf { it.end >= it.start && (it.total == null || it.end < it.total) }
}

/**
 * Whether a failed write means the disk is full. Android reports ENOSPC as an
 * IOException whose message carries the errno name ("write failed: ENOSPC (No
 * space left on device)"), sometimes wrapped, and a JVM says only the strerror
 * text - so the whole cause chain is checked for either.
 */
internal fun isOutOfSpace(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.take(8).any { cause ->
        val message = cause.message.orEmpty()
        "ENOSPC" in message || "No space left on device" in message
    }
