package site.androidmod.patch.cli

import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess
import site.androidmod.patch.ApkSigner
import site.androidmod.patch.FastSigner
import site.androidmod.patch.PatchBundle
import site.androidmod.patch.PatchEngine
import site.androidmod.patch.PatchException
import site.androidmod.patch.PatchRequest
import site.androidmod.patch.VersionRecord
import site.androidmod.patch.publicKeyFromPem

/** What [run] hands the signer factory; passwords come from environment variables, never flags. */
class SignerConfig(
    val fastsigner: File,
    val keystore: File,
    val password: CharArray,
    val alias: String?,
    val keyPassword: CharArray?,
)

private val USAGE = """
    usage: patch-cli --apk FILE --bundle FILE --record FILE --bundle-key FILE
                     --fastsigner FILE --keystore FILE --keystore-pass-env NAME
                     [--keystore-alias NAME] [--key-pass-env NAME]
                     [--token TOKEN] [--revision N] --out FILE
""".trimIndent()

private val REQUIRED = listOf(
    "apk", "bundle", "record", "bundle-key", "fastsigner", "keystore", "keystore-pass-env", "out",
)
private val OPTIONAL = listOf("keystore-alias", "key-pass-env", "token", "revision")

private class UsageError(message: String) : Exception(message)

private fun defaultSigner(c: SignerConfig): ApkSigner =
    FastSigner(c.fastsigner, c.keystore, c.password, c.alias, c.keyPassword)

/** Exit 0 on success, 2 on usage errors, 1 on a [PatchException]. */
fun run(
    args: Array<String>,
    env: Map<String, String> = System.getenv(),
    signerFactory: (SignerConfig) -> ApkSigner = ::defaultSigner,
): Int {
    val workDir = Files.createTempDirectory("patch-cli").toFile()
    try {
        val flags = parse(args)
        fun envValue(flag: String): CharArray {
            val name = flags.getValue(flag)
            return env[name]?.toCharArray() ?: throw UsageError("environment variable $name (--$flag) is not set")
        }
        val revision = flags["revision"]?.let {
            it.toIntOrNull() ?: throw UsageError("--revision must be an integer, got '$it'")
        } ?: 1
        val config = SignerConfig(
            fastsigner = File(flags.getValue("fastsigner")),
            keystore = File(flags.getValue("keystore")),
            password = envValue("keystore-pass-env"),
            alias = flags["keystore-alias"],
            keyPassword = if ("key-pass-env" in flags) envValue("key-pass-env") else null,
        )
        for ((flag, file) in listOf("apk", "bundle", "record", "bundle-key").map { it to File(flags.getValue(it)) }) {
            if (!file.isFile) throw UsageError("--$flag: no such file: $file")
        }

        val started = System.nanoTime()
        val bundle = PatchBundle.open(
            File(flags.getValue("bundle")),
            publicKeyFromPem(File(flags.getValue("bundle-key")).readText()),
        )
        val record = VersionRecord.parse(File(flags.getValue("record")).readText())
        val result = PatchEngine(signerFactory(config)).patch(
            PatchRequest(
                official = File(flags.getValue("apk")),
                bundle = bundle,
                record = record,
                paywallToken = flags["token"] ?: "dev",
                revision = revision,
                workDir = workDir,
                output = File(flags.getValue("out")),
            ),
        )
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        println("featuresOff: ${result.featuresOff}")
        println("visibleFeaturesOff: ${result.visibleFeaturesOff}")
        println("output: ${result.output}")
        println("elapsed: %.1fs".format(seconds))
        return 0
    } catch (e: UsageError) {
        System.err.println("patch-cli: ${e.message}")
        System.err.println(USAGE)
        return 2
    } catch (e: PatchException) {
        System.err.println("patch-cli: ${e.message}")
        return 1
    } finally {
        workDir.deleteRecursively()
    }
}

private fun parse(args: Array<String>): Map<String, String> {
    val known = REQUIRED + OPTIONAL
    val flags = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        val name = arg.removePrefix("--")
        if (!arg.startsWith("--") || name !in known) throw UsageError("unknown argument: $arg")
        if (i + 1 >= args.size) throw UsageError("$arg needs a value")
        if (name in flags) throw UsageError("$arg given twice")
        flags[name] = args[i + 1]
        i += 2
    }
    val missing = REQUIRED.filter { it !in flags }
    if (missing.isNotEmpty()) throw UsageError("missing: ${missing.joinToString { "--$it" }}")
    return flags
}

fun main(args: Array<String>) {
    exitProcess(run(args))
}
