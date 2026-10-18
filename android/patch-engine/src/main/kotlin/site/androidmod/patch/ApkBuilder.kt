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
 * How [ApkBuilder.build] treats the source APK beyond the WhatsApp defaults.
 *
 * - [drop]: source entries left out, and not counted when numbering the new dex files.
 * - [libs]: when set, only each module's `lib/<hookAbi>/<name>.so` is added, as `lib/<libDir>/<name>`
 *   (stored). When null, a module lib is added for every ABI the source has (all of them if it has none).
 * - [refuseCollisions]: an addition other than `AndroidManifest.xml` that a kept source entry already has
 *   is [PatchException.Unsupported] instead of replacing it.
 * - [describe]: called once, after every other addition is known, with their sorted names (the manifest
 *   aside); it returns its own entry, which is written last.
 */
data class BuildOptions(
    val drop: Set<String> = emptySet(),
    val libs: LibTarget? = null,
    val refuseCollisions: Boolean = false,
    val describe: ((added: List<String>) -> Pair<String, ByteArray>)? = null,
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

    /** Returns the sorted names of the entries it added (`AndroidManifest.xml`, always replaced, aside). */
    fun build(official: File, inputs: BuildInputs, out: File, options: BuildOptions = BuildOptions()): List<String> =
        writeAtomically(out, "build") { tmp -> write(official, inputs, tmp, options) }

    /** Copies [apk] to [out] raw, entry for entry, without its v1 signature files (or its signing block). */
    fun stripV1(apk: File, out: File) {
        writeAtomically(out, "copy") { tmp ->
            open(apk).use { src ->
                ZipArchiveOutputStream(tmp).use { zos ->
                    for (ze in src.entries) {
                        if (!V1_SIGNATURE.matches(ze.name)) zos.addRawArchiveEntry(ZipArchiveEntry(ze), src.getRawInputStream(ze))
                    }
                }
            }
        }
    }

    /** Writes through a temp file next to [out], so [out] is either complete or untouched. */
    private fun <T> writeAtomically(out: File, verb: String, write: (File) -> T): T {
        val tmp = try {
            File.createTempFile(out.name, ".tmp", out.absoluteFile.parentFile)
        } catch (e: IOException) {
            throw PatchException.Storage("cannot write ${out.name}: ${e.message}", e)
        }
        try {
            val result = write(tmp)
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return result
        } catch (e: ZipException) {
            throw PatchException.BadApk("cannot $verb ${out.name}: ${e.message}", e)
        } catch (e: IOException) {
            throw PatchException.Storage("cannot write ${out.name}: ${e.message}", e)
        } finally {
            tmp.delete()
        }
    }

    private fun open(apk: File): ZipFile = try {
        ZipFile.builder().setFile(apk).get()
    } catch (e: IOException) {
        throw PatchException.BadApk("Cannot read the APK: ${e.message}", e)
    }

    private fun write(official: File, inputs: BuildInputs, tmp: File, options: BuildOptions): List<String> {
        open(official).use { app ->
            val appEntries = app.entries.asSequence().filter { it.name !in options.drop }.toList()
            val names = appEntries.map { it.name }
            val appAbis = names.filter { it.startsWith("lib/") }
                .mapNotNull { it.split('/').getOrNull(1)?.takeIf { abi -> abi.isNotEmpty() && it.split('/').size > 2 } }
                .toSet()
            var nextDex = (names.mapNotNull { DexHash.dexIndex(it) }.maxOrNull() ?: BigInteger.ZERO).toInt() + 1
            val target = options.libs

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
                    val parts = n.split('/')
                    val isLib = n.startsWith("lib/") && n.endsWith(".so") && parts.size == 3
                    if (isLib && target != null) {
                        if (parts[1] == target.hookAbi) additions["lib/${target.libDir}/${parts[2]}"] = Added(f.data, stored = true)
                    } else if (isLib && (appAbis.isEmpty() || parts[1] in appAbis)) {
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

            val kept = appEntries.filter { it.name != MANIFEST && !V1_SIGNATURE.matches(it.name) }
            val keptNames = kept.mapTo(HashSet()) { it.name }
            fun refuseCollision(name: String) {
                if (options.refuseCollisions && name != MANIFEST && name in keptNames) {
                    throw PatchException.Unsupported("${ManifestPatcher.packageName(inputs.manifest)} already has $name")
                }
            }
            additions.keys.forEach(::refuseCollision)
            val described = options.describe?.let { describe ->
                val (name, data) = describe(additions.keys.filter { it != MANIFEST }.sorted())
                require(name !in additions) { "described entry $name is already an addition" }
                refuseCollision(name)
                name to Added(data, stored = false)
            }

            ZipArchiveOutputStream(tmp).use { zos ->
                for (ze in kept) {
                    if (ze.name in additions || ze.name == described?.first) continue
                    zos.addRawArchiveEntry(ZipArchiveEntry(ze), app.getRawInputStream(ze))
                }
                for ((n, a) in additions) put(zos, n, a)
                described?.let { (n, a) -> put(zos, n, a) }
            }
            return (additions.keys.filter { it != MANIFEST } + listOfNotNull(described?.first)).sorted()
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
