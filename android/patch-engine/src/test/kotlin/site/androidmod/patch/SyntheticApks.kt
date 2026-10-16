package site.androidmod.patch

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File

/** Synthetic app and module APKs for builder and signer tests. */
object SyntheticApks {
    fun manifest(pkg: String, versionCode: Int? = null): ByteArray {
        val am = AndroidManifestBlock.empty()
        am.packageName = pkg
        if (versionCode != null) am.versionCode = versionCode
        am.getOrCreateApplicationElement()
        am.refreshFull()
        return am.bytes
    }

    val appManifest = manifest("com.example.app")
    val moduleManifest = manifest("com.smali_generator")
    val dexApp1 = "dex\n035\u0000app1".toByteArray()
    val dexApp2 = "dex\n035\u0000app2".toByteArray()
    val dexA = "dex\n035\u0000modA".toByteArray()
    val dexB = "dex\n035\u0000modB".toByteArray()
    val appSo = ByteArray(64) { 1 }
    val appAsset = "app asset".toByteArray()
    val moduleAsset = "module asset".toByteArray()
    val arsc = ByteArray(32) { 7 }

    fun app(file: File): File = zip(
        file,
        listOf(
            "AndroidManifest.xml" to manifest("com.example.app.orig", versionCode = 1),
            "classes.dex" to dexApp1,
            "classes2.dex" to dexApp2,
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0".toByteArray(),
            "META-INF/CERT.RSA" to ByteArray(8),
            "META-INF/services/a.B" to "svc".toByteArray(),
            "assets/x.txt" to appAsset,
        ),
    ).also { addStored(it, "lib/x86_64/libapp.so" to appSo) }

    private fun addStored(file: File, entry: Pair<String, ByteArray>) {
        val tmp = File(file.path + ".src")
        file.copyTo(tmp, overwrite = true)
        // Rewrite with the stored lib appended, copying existing entries raw.
        org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(tmp).get().use { src ->
            org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(file).use { zos ->
                for (e in src.entries) zos.addRawArchiveEntry(
                    org.apache.commons.compress.archivers.zip.ZipArchiveEntry(e), src.getRawInputStream(e),
                )
                val e = org.apache.commons.compress.archivers.zip.ZipArchiveEntry(entry.first)
                e.method = java.util.zip.ZipEntry.STORED
                e.size = entry.second.size.toLong()
                e.compressedSize = e.size
                e.crc = java.util.zip.CRC32().also { it.update(entry.second) }.value
                zos.putArchiveEntry(e)
                zos.write(entry.second)
                zos.closeArchiveEntry()
            }
        }
        tmp.delete()
    }

    fun moduleA(file: File): File = zip(
        file,
        listOf(
            "AndroidManifest.xml" to moduleManifest,
            "classes.dex" to dexA,
            "lib/x86_64/libarthooks.so" to ByteArray(16) { 2 },
            "lib/arm64-v8a/libarthooks.so" to ByteArray(16) { 3 },
            "assets/patcher/icon.png" to ByteArray(10) { 4 },
            "assets/x.txt" to moduleAsset,
            "resources.arsc" to arsc,
            "res/layout/a.xml" to ByteArray(12) { 5 },
        ),
    )

    fun moduleB(file: File): File = zip(file, listOf("classes.dex" to dexB))

    /** App + modules written under [dir], and inputs ready for [ApkBuilder.build]. */
    class Set(val app: File, val inputs: BuildInputs)

    fun create(dir: File): Set {
        val app = app(File(dir, "app.apk"))
        val a = moduleA(File(dir, "a.apk")).readBytes()
        val b = moduleB(File(dir, "b.apk")).readBytes()
        return Set(
            app,
            BuildInputs(
                manifest = manifest("com.example.app"),
                modules = listOf(a, b),
                jsonAssets = mapOf("assets/patcher/artifactory.json" to """{"k":"v"}""".toByteArray()),
            ),
        )
    }
}
