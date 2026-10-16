package site.androidmod.patch

import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest
import org.apache.commons.compress.archivers.zip.ZipFile

/** The dex hash shared with the Python patcher (Stitch); must match it byte for byte. */
object DexHash {
    private val DEX_NAME = Regex("classes([0-9]*)\\.dex")

    /**
     * SHA-256 over `utf8(name + "\n" + hex(sha256(bytes)) + "\n")` for each `classesN.dex`
     * entry, ordered by N (`classes.dex` = 1), ties by name. Duplicate entry names or no
     * dex at all is a [PatchException.BadApk].
     */
    fun dexSha256(apk: File): String {
        try {
            ZipFile.builder().setFile(apk).get().use { zip ->
                val seen = HashSet<String>()
                val dexes = ArrayList<Triple<BigInteger, String, org.apache.commons.compress.archivers.zip.ZipArchiveEntry>>()
                for (entry in zip.entries) {
                    if (!seen.add(entry.name)) throw PatchException.BadApk("duplicate entry ${entry.name}")
                    val m = DEX_NAME.matchEntire(entry.name) ?: continue
                    val n = m.groupValues[1].let { if (it.isEmpty()) BigInteger.ONE else BigInteger(it) }
                    dexes.add(Triple(n, entry.name, entry))
                }
                if (dexes.isEmpty()) throw PatchException.BadApk("no classes.dex in ${apk.name}")
                dexes.sortWith(compareBy<Triple<BigInteger, String, *>> { it.first }.thenBy { it.second })
                val outer = MessageDigest.getInstance("SHA-256")
                for ((_, name, entry) in dexes) {
                    val inner = zip.getInputStream(entry).use { sha256Hex(it) }
                    outer.update("$name\n$inner\n".toByteArray(Charsets.UTF_8))
                }
                return hex(outer.digest())
            }
        } catch (e: PatchException) {
            throw e
        } catch (e: java.io.IOException) {
            throw PatchException.BadApk("cannot read ${apk.name}: ${e.message}")
        }
    }

    /** Lowercase hex SHA-256 of the whole file, streamed. */
    fun apkSha256(file: File): String = file.inputStream().use { sha256Hex(it) }

    private fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
