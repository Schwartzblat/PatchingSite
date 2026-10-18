package site.androidmod.patch.cli

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.GeneralSecurityException
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

internal class UsageError(message: String) : Exception(message)

private fun defaultSigner(c: SignerConfig): ApkSigner =
    FastSigner(c.fastsigner, c.keystore, c.password, c.alias, c.keyPassword)

/** Exit 0 on success, 2 on usage errors, 1 on a [PatchException]. */
fun run(
    args: Array<String>,
    env: Map<String, String> = System.getenv(),
    signerFactory: (SignerConfig) -> ApkSigner = ::defaultSigner,
): Int {
    val workDir = Files.createTempDirectory("patch-cli").toFile()
    var usage = USAGE
    try {
        when (args.firstOrNull()) {
            "generic" -> { usage = GENERIC_USAGE; return generic(args.drop(1), env, signerFactory, workDir) }
            "scan" -> { usage = SCAN_USAGE; return scan(args.drop(1)) }
        }
        val flags = parse(args.toList(), REQUIRED, OPTIONAL).mapValues { it.value.single() }
        fun envValue(flag: String) = envPassword(env, flags.getValue(flag), flag)
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
        val record = VersionRecord.parse(File(flags.getValue("record")).readText())
        val bundle = PatchBundle.open(
            File(flags.getValue("bundle")),
            publicKeyFromPem(File(flags.getValue("bundle-key")).readText()),
        )
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
        System.err.println(usage)
        return 2
    } catch (e: PatchException) {
        System.err.println("patch-cli: ${e.message}")
        return 1
    } catch (e: IOException) {
        return inputError("cannot read input: ${e.message}")
    } catch (e: GeneralSecurityException) {
        return inputError("invalid bundle key: ${e.message}")
    } catch (e: IllegalArgumentException) { // includes kotlinx SerializationException
        return inputError("invalid input: ${e.message?.lineSequence()?.first()}")
    } finally {
        workDir.deleteRecursively()
    }
}

internal fun inputError(message: String): Int {
    System.err.println("patch-cli: $message")
    return 1
}

/** The password in environment variable [name] (given by flag [flag]); never taken from a flag value. */
internal fun envPassword(env: Map<String, String>, name: String, flag: String): CharArray {
    val value = env[name]
    if (value.isNullOrEmpty()) throw UsageError("environment variable $name (--$flag) is not set or empty")
    return value.toCharArray()
}

/**
 * Parses `--flag value` pairs. Flags in [repeatable] may repeat (values kept in order), [switches] take no value
 * (stored as an empty value), every other flag is given at most once.
 */
internal fun parse(
    args: List<String>,
    required: List<String>,
    optional: List<String>,
    repeatable: Set<String> = emptySet(),
    switches: Set<String> = emptySet(),
): Map<String, List<String>> {
    val known = required + optional
    val flags = mutableMapOf<String, MutableList<String>>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        val name = arg.removePrefix("--")
        if (!arg.startsWith("--") || name !in known) throw UsageError("unknown argument: $arg")
        val isSwitch = name in switches
        if (!isSwitch && i + 1 >= args.size) throw UsageError("$arg needs a value")
        if (name in flags && name !in repeatable) throw UsageError("$arg given twice")
        flags.getOrPut(name) { mutableListOf() }.add(if (isSwitch) "" else args[i + 1])
        i += if (isSwitch) 1 else 2
    }
    val missing = required.filter { it !in flags }
    if (missing.isNotEmpty()) throw UsageError("missing: ${missing.joinToString { "--$it" }}")
    return flags
}

fun main(args: Array<String>) {
    exitProcess(run(args))
}
