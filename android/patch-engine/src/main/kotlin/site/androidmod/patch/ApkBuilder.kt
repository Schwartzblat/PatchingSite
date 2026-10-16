package site.androidmod.patch

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile

/**
 * What goes into the patched APK besides the official one's entries. [modules] are module APKs
 * in `dex_order`; [jsonAssets] are keyed by full entry path.
 */
data class BuildInputs(
    val manifest: ByteArray,
    val modules: List<ByteArray>,
    val jsonAssets: Map<String, ByteArray>,
)

/**
 * Writes the unsigned patched APK, mirroring Stitch's `inject_module_files`, except that the
 * official app's entries are copied raw (original compressed bytes). Entries are not aligned;
 * the signer does that.
 */
object ApkBuilder {
    private const val MANIFEST = "AndroidManifest.xml"
    private const val ARSC = "resources.arsc"
    private val V1_SIGNATURE = Regex("META-INF/(MANIFEST\\.MF|[^/]+\\.(SF|RSA|DSA|EC))")

    private class Added(val data: ByteArray, val stored: Boolean)

    fun build(official: File, inputs: BuildInputs, out: File) {
        val tmp = try {
            File.createTempFile(out.name, ".tmp", out.absoluteFile.parentFile)
        } catch (e: IOException) {
            throw PatchException.Storage("cannot write ${out.name}: ${e.message}", e)
        }
        try {
            write(official, inputs, tmp)
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: ZipException) {
            throw PatchException.BadApk("cannot build ${out.name}: ${e.message}", e)
        } catch (e: IOException) {
            throw PatchException.Storage("cannot write ${out.name}: ${e.message}", e)
        } finally {
            tmp.delete()
        }
    }

    private fun write(official: File, inputs: BuildInputs, tmp: File) {
        val zip = try {
            ZipFile.builder().setFile(official).get()
        } catch (e: IOException) {
            throw PatchException.BadApk("Cannot read the APK: ${e.message}", e)
        }
        zip.use { app ->
            val appEntries = app.entries.asSequence().toList()
            val names = appEntries.map { it.name }
            val appAbis = names.filter { it.startsWith("lib/") }
                .mapNotNull { it.split('/').getOrNull(1)?.takeIf { abi -> abi.isNotEmpty() && it.split('/').size > 2 } }
                .toSet()
            var nextDex = (names.mapNotNull { DexHash.dexIndex(it) }.maxOrNull() ?: BigInteger.ZERO).toInt() + 1

            val additions = LinkedHashMap<String, Added>()
            additions[MANIFEST] = Added(inputs.manifest, stored = false)
            for (module in inputs.modules) {
                val files = readModule(module)
                files.keys.mapNotNull { n -> DexHash.dexIndex(n)?.let { n to it } }
                    .sortedBy { it.second }
                    .forEach { (n, _) ->
                        additions[dexName(nextDex++)] = Added(files.getValue(n).data, stored = false)
                    }
                for ((n, f) in files) {
                    val abi = n.split('/').getOrNull(1)
                    if (n.startsWith("lib/") && n.endsWith(".so") && n.split('/').size == 3 &&
                        (appAbis.isEmpty() || abi in appAbis)
                    ) {
                        additions[n] = Added(f.data, stored = true)
                    } else if (n.startsWith("assets/")) {
                        additions[n] = f
                    }
                }
                if (ARSC in files) {
                    val pkg = ManifestPatcher.packageName(
                        files[MANIFEST]?.data ?: throw PatchException.BadApk("module has resources.arsc but no manifest"),
                    )
                    additions["assets/stitch/$pkg.apk"] = Added(resourceApk(files), stored = true)
                }
            }
            for ((n, b) in inputs.jsonAssets) additions[n] = Added(b, stored = false)

            ZipArchiveOutputStream(tmp).use { zos ->
                for (ze in appEntries) {
                    if (ze.name == MANIFEST || V1_SIGNATURE.matches(ze.name) || ze.name in additions) continue
                    zos.addRawArchiveEntry(ZipArchiveEntry(ze), app.getRawInputStream(ze))
                }
                for ((n, a) in additions) put(zos, n, a)
            }
        }
    }

    private fun dexName(index: Int) = if (index == 1) "classes.dex" else "classes$index.dex"

    private fun readModule(bytes: ByteArray): Map<String, Added> {
        val files = LinkedHashMap<String, Added>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.isDirectory) continue
                files[e.name] = Added(zin.readBytes(), stored = e.method == ZipEntry.STORED)
            }
        }
        return files
    }

    private fun resourceApk(files: Map<String, Added>): ByteArray {
        val buf = ByteArrayOutputStream()
        ZipArchiveOutputStream(buf).use { zos ->
            for ((n, f) in files) {
                if (n == MANIFEST || n == ARSC || n.startsWith("res/")) put(zos, n, Added(f.data, stored = true))
            }
        }
        return buf.toByteArray()
    }

    private fun put(zos: ZipArchiveOutputStream, name: String, a: Added) {
        val e = ZipArchiveEntry(name)
        if (a.stored) {
            e.method = ZipEntry.STORED
            e.size = a.data.size.toLong()
            e.compressedSize = a.data.size.toLong()
            e.crc = CRC32().also { it.update(a.data) }.value
        } else {
            e.method = ZipEntry.DEFLATED
        }
        zos.putArchiveEntry(e)
        zos.write(a.data)
        zos.closeArchiveEntry()
    }
}
