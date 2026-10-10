package site.androidmod.store.install

enum class VersionOrder { NEWER, SAME, OLDER, UNKNOWN }

/**
 * Orders the version strings this catalogue actually uses: "5.199.1.1804",
 * "2.26.33.76", "42.0".
 *
 * Segment-by-segment and numeric, because a text comparison puts "5.199" before
 * "5.98". Two rules keep it total, so it can never throw on real data: a missing
 * trailing segment is zero, and a segment that is not an integer is compared as
 * text once the numeric segments before it have failed to decide.
 */
object Versions {

    fun compare(available: String, installed: String): VersionOrder {
        if (available.isBlank() || installed.isBlank()) return VersionOrder.UNKNOWN

        val left = available.trim().split('.')
        val right = installed.trim().split('.')

        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { "0" }
            val b = right.getOrElse(index) { "0" }
            val numericA = a.toIntOrNull()
            val numericB = b.toIntOrNull()

            val result = if (numericA != null && numericB != null) {
                numericA.compareTo(numericB)
            } else {
                a.compareTo(b)
            }
            if (result != 0) return if (result > 0) VersionOrder.NEWER else VersionOrder.OLDER
        }
        return VersionOrder.SAME
    }
}
