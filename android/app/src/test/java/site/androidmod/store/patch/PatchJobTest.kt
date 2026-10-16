package site.androidmod.store.patch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import site.androidmod.patch.ApkSigner
import site.androidmod.patch.PatchBundle
import site.androidmod.patch.PatchException
import site.androidmod.patch.PatchRequest
import site.androidmod.patch.PatchResult
import site.androidmod.store.data.ApiException
import site.androidmod.store.data.NotEnoughSpaceException
import site.androidmod.store.ui.notEnoughSpaceMessage
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.ceil

class PatchJobTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val keys = newTestKeyPair()

    /** Every fake for one run, so a table test can start each case from scratch. */
    private inner class Fixture(val root: File) {
        val workDir = File(root, "patch/work")
        val downloadsDir = File(root, "downloads")
        val official = File(workDir, "official.apk")
        val events = mutableListOf<String>()
        val stages = mutableListOf<PatchStage>()
        val confirmations = mutableListOf<List<String>>()
        val finalNames = mutableListOf<String>()

        val key = SigningKey(File(root, "signing-key.p12"), PASSWORD.toCharArray(), "key0", USER_CERT, "b".repeat(40))
        val latest = LatestPatch(
            packageName = PKG,
            versionCode = VERSION_CODE,
            versionName = VERSION_NAME,
            apkUrl = APK_URL,
            apkSha256 = OFFICIAL_SHA,
            dexSha256 = DEX_SHA,
            patcherVersion = PATCHER_VERSION,
            revision = 7,
        )

        // Server
        var latestAnswer: () -> LatestPatch = { latest }
        var bundleAnswer: () -> PatchBundle = { bundle() }
        val recordsByApk = mutableMapOf<String, String>()
        val recordsByDex = mutableMapOf<String, String>()
        var recordAnswer: (() -> String?)? = null
        var tokenAnswer: () -> String = { TOKEN }
        var keyAnswer: () -> SigningKey = { key }
        var onLatest: () -> Unit = {}

        // Downloader, disk and checks
        var size: Long? = OFFICIAL_BYTES.size.toLong()
        var sizeAnswer: (() -> Long?)? = null
        var downloadError: Exception? = null
        val reserveCalls = mutableListOf<Pair<File, Long>>()
        var shortfall = 0L
        val verifyCalls = mutableListOf<Pair<File, List<String>>>()
        var verifyError: Exception? = null
        val dexHashCalls = mutableListOf<File>()

        // Engine
        val requests = mutableListOf<PatchRequest>()
        val signerKeys = mutableListOf<SigningKey>()
        var patcherAction: (PatchRequest, ApkSigner) -> PatchResult = { request, signer ->
            val unsigned = File(request.workDir, "unsigned.apk")
            unsigned.writeBytes(request.official.readBytes() + PATCHED)
            try {
                signer.sign(unsigned, request.output)
            } finally {
                unsigned.delete()
            }
            PatchResult(request.output, emptyList(), emptyList())
        }
        var inspectedVersionCode = VERSION_CODE
        var inspect: (File) -> ArchiveInfo? = { ArchiveInfo(PKG, inspectedVersionCode, setOf(USER_CERT)) }

        fun bundle(minSdk: Int = 33, minStoreVersionCode: Long = 0): PatchBundle = PatchBundle.open(
            testBundleZip(
                File.createTempFile("bundle", ".zip", root),
                pkg = PKG,
                patcherVersion = PATCHER_VERSION,
                features = FEATURES_JSON,
                minSdk = minSdk,
                minStoreVersionCode = minStoreVersionCode,
                keyPair = keys,
            ),
            keys.public,
        )

        val server = object : PatchServer {
            override suspend fun latest(pkg: String): LatestPatch {
                events += "latest"
                onLatest()
                return latestAnswer()
            }

            override suspend fun record(pkg: String, patcherVersion: String, apkSha256: String?, dexSha256: String?): String? {
                assertEquals(PATCHER_VERSION, patcherVersion)
                events += if (apkSha256 != null) "record apk" else "record dex"
                recordAnswer?.let { return it() }
                return if (apkSha256 != null) recordsByApk[apkSha256] else recordsByDex[dexSha256]
            }

            override suspend fun bundle(pkg: String, patcherVersion: String): PatchBundle {
                assertEquals(PATCHER_VERSION, patcherVersion)
                events += "bundle"
                return bundleAnswer()
            }

            override suspend fun appToken(pkg: String): String {
                events += "app token"
                return tokenAnswer()
            }

            override suspend fun signingKey(): SigningKey {
                events += "signing key"
                return keyAnswer()
            }
        }

        val downloader = object : OfficialDownloader {
            override suspend fun size(url: String): Long? {
                assertEquals(APK_URL, url)
                events += "size"
                sizeAnswer?.let { return it() }
                return size
            }

            override suspend fun download(url: String, target: File, digest: MessageDigest?, onProgress: (Long, Long) -> Unit) {
                assertEquals(APK_URL, url)
                assertEquals(official, target)
                events += "download"
                val total = OFFICIAL_BYTES.size.toLong()
                val half = OFFICIAL_BYTES.size / 2
                target.parentFile?.mkdirs()
                target.writeBytes(OFFICIAL_BYTES.copyOfRange(0, half))
                digest?.update(OFFICIAL_BYTES, 0, half)
                onProgress(half.toLong(), total)
                downloadError?.let { throw it }
                target.appendBytes(OFFICIAL_BYTES.copyOfRange(half, OFFICIAL_BYTES.size))
                digest?.update(OFFICIAL_BYTES, half, OFFICIAL_BYTES.size - half)
                onProgress(total, total)
            }
        }

        fun job(sdkInt: Int = 33, storeVersionCode: Long = 100, signedIn: Boolean = true) = PatchJob(
            server = server,
            downloader = downloader,
            reserve = { dir, bytes ->
                events += "reserve"
                reserveCalls += dir to bytes
                shortfall
            },
            verifyOfficial = { apk, certs ->
                events += "verify"
                assertArrayEquals(OFFICIAL_BYTES, apk.readBytes())
                verifyCalls += apk to certs
                verifyError?.let { throw it }
            },
            dexHash = { apk ->
                events += "dex hash"
                dexHashCalls += apk
                DEX_SHA
            },
            patcher = { request, signer ->
                events += "patch"
                requests += request
                patcherAction(request, signer)
            },
            signerFor = { key ->
                signerKeys += key
                ApkSigner { unsigned, signed ->
                    events += "sign"
                    signed.writeBytes(unsigned.readBytes() + SIGNED)
                }
            },
            inspector = { file ->
                events += "inspect"
                inspect(file)
            },
            signedIn = { signedIn },
            sdkInt = sdkInt,
            storeVersionCode = storeVersionCode,
            workDir = workDir,
        )

        suspend fun run(job: PatchJob = job(), proceed: Boolean = true): PatchOutcome = job.run(
            PKG,
            finalFile = { versionName ->
                finalNames += versionName
                File(downloadsDir, "$PKG-$versionName.apk")
            },
            onStage = { stages += it },
            confirm = { titles ->
                confirmations += titles
                proceed
            },
        )

        fun finalFile(versionName: String = VERSION_NAME) = File(downloadsDir, "$PKG-$versionName.apk")

        fun workFiles(): List<String> = workDir.walk().filter { it != workDir }.map { it.relativeTo(workDir).path }.toList()

        fun assertNothingLeft() {
            assertEquals(emptyList<String>(), workFiles())
            assertFalse(downloadsDir.exists() && downloadsDir.list()!!.isNotEmpty())
        }
    }

    private fun fixture() = Fixture(folder.newFolder())

    private fun failed(reason: PatchFailure, minStoreVersionCode: Long? = null) =
        PatchOutcome.Failed(reason, reason.message(), minStoreVersionCode)

    @Test
    fun `happy path reports stages in order and returns Ready at the final file`() = runTest {
        val f = fixture()
        f.recordsByApk[OFFICIAL_SHA] = recordJson()

        val outcome = f.run()

        val final = f.finalFile()
        assertEquals(PatchOutcome.Ready(final, VERSION_NAME, VERSION_CODE, 7), outcome)
        assertArrayEquals(OFFICIAL_BYTES + PATCHED + SIGNED, final.readBytes())
        val total = OFFICIAL_BYTES.size.toLong()
        assertEquals(
            listOf(
                PatchStage.CheckingVersion,
                PatchStage.Downloading(0, total),
                PatchStage.Downloading(total / 2, total),
                PatchStage.Downloading(total, total),
                PatchStage.Patching,
                PatchStage.Signing,
            ),
            f.stages,
        )
        assertEquals(
            listOf("latest", "bundle", "size", "reserve", "download", "verify", "record apk", "app token", "signing key", "patch", "sign", "inspect"),
            f.events,
        )
        assertEquals(listOf(f.workDir to ceil(3.2 * total).toLong()), f.reserveCalls)
        assertEquals(listOf(f.official to listOf(OFFICIAL_CERT)), f.verifyCalls)
        val request = f.requests.single()
        assertEquals(f.official, request.official)
        assertEquals(File(f.workDir, "signed.apk"), request.output)
        assertEquals(f.workDir, request.workDir)
        assertEquals(TOKEN, request.paywallToken)
        assertEquals(7, request.revision)
        assertEquals(VERSION_NAME, request.record.versionName)
        assertEquals(listOf(f.key), f.signerKeys)
        assertEquals(emptyList<List<String>>(), f.confirmations)
        assertEquals(listOf(VERSION_NAME), f.finalNames)
        assertEquals(emptyList<String>(), f.workFiles())
    }

    @Test
    fun `whatsapp com moved on uses the dex hash record`() = runTest {
        val f = fixture()
        f.recordsByDex[DEX_SHA] = recordJson(versionName = "2.26.2.9", versionCode = 262029, apkSha256 = "e".repeat(64), revision = 9)
        f.inspectedVersionCode = 262029

        val outcome = f.run()

        assertEquals(PatchOutcome.Ready(f.finalFile("2.26.2.9"), "2.26.2.9", 262029, 9), outcome)
        assertEquals(listOf(f.official), f.dexHashCalls)
        assertEquals(listOf("record apk", "dex hash", "record dex"), f.events.filter { it.startsWith("record") || it == "dex hash" })
        val request = f.requests.single()
        assertEquals("2.26.2.9", request.record.versionName)
        assertEquals(262029L, request.record.versionCode)
        assertEquals(9, request.revision)
        assertEquals(listOf("2.26.2.9"), f.finalNames)
        assertEquals(emptyList<String>(), f.workFiles())
    }

    @Test
    fun `a record without a revision takes the latest one`() = runTest {
        val f = fixture()
        f.recordsByApk[OFFICIAL_SHA] = recordJson(revision = null)

        val outcome = f.run()

        assertEquals(7, (outcome as PatchOutcome.Ready).revision)
        assertEquals(7, f.requests.single().revision)
    }

    @Test
    fun `unknown build is not supported yet`() = runTest {
        val f = fixture()

        val outcome = f.run()

        assertEquals(failed(PatchFailure.NOT_SUPPORTED_YET), outcome)
        assertEquals("Not supported yet. This version of WhatsApp is new, so try again shortly.", (outcome as PatchOutcome.Failed).message)
        assertEquals(listOf("record apk", "dex hash", "record dex"), f.events.filter { it.startsWith("record") || it == "dex hash" })
        assertFalse("app token" in f.events)
        assertTrue(f.requests.isEmpty())
        f.assertNothingLeft()
    }

    @Test
    fun `an unsupported record is not supported yet and asks nothing`() = runTest {
        val f = fixture()
        f.recordsByApk[OFFICIAL_SHA] = recordJson(artifacts = mapOf("core" to "a.b"), supported = false)

        val outcome = f.run()

        assertEquals(failed(PatchFailure.NOT_SUPPORTED_YET), outcome)
        assertTrue(f.confirmations.isEmpty())
        assertFalse("app token" in f.events)
        f.assertNothingLeft()
    }

    @Test
    fun `an unreadable record fails`() = runTest {
        val f = fixture()
        f.recordsByApk[OFFICIAL_SHA] = """{"package": "com.whatsapp"}"""

        assertEquals(failed(PatchFailure.FAILED), f.run())
        assertTrue(f.requests.isEmpty())
        f.assertNothingLeft()
    }

    @Test
    fun `402 needs a subscription and downloads nothing`() = runTest {
        val f = fixture()
        f.latestAnswer = { throw ApiException(402, "Subscription required") }

        val outcome = f.run()

        assertEquals(failed(PatchFailure.NEEDS_SUBSCRIPTION), outcome)
        assertEquals("You need a subscription to get it.", (outcome as PatchOutcome.Failed).message)
        assertEquals(listOf("latest"), f.events)
        f.assertNothingLeft()
    }

    @Test
    fun `426 asks for a store update`() = runTest {
        val f = fixture()
        f.latestAnswer = { throw StoreUpdateRequired(4242) }

        val outcome = f.run()

        assertEquals(failed(PatchFailure.STORE_UPDATE_REQUIRED, minStoreVersionCode = 4242), outcome)
        assertEquals("This app needs an update first. It's updating now.", (outcome as PatchOutcome.Failed).message)
        assertEquals(listOf("latest"), f.events)
    }

    @Test
    fun `bundle needing a newer store asks for a store update`() = runTest {
        val f = fixture()
        f.bundleAnswer = { f.bundle(minStoreVersionCode = 500) }

        val outcome = f.run(f.job(storeVersionCode = 100))

        assertEquals(failed(PatchFailure.STORE_UPDATE_REQUIRED, minStoreVersionCode = 500), outcome)
        assertEquals(listOf("latest", "bundle"), f.events)
    }

    @Test
    fun `bundle for this store builds`() = runTest {
        val f = fixture()
        f.bundleAnswer = { f.bundle(minStoreVersionCode = 100) }
        f.recordsByApk[OFFICIAL_SHA] = recordJson()

        assertTrue(f.run(f.job(storeVersionCode = 100)) is PatchOutcome.Ready)
    }

    @Test
    fun `android 12 is too old`() = runTest {
        val f = fixture()

        val outcome = f.run(f.job(sdkInt = 32))

        assertEquals(failed(PatchFailure.ANDROID_TOO_OLD), outcome)
        assertEquals("Patching on this phone needs Android 13 or newer.", (outcome as PatchOutcome.Failed).message)
        assertEquals(emptyList<String>(), f.events)
    }

    @Test
    fun `bundle needing a newer android is too old`() = runTest {
        val f = fixture()
        f.bundleAnswer = { f.bundle(minSdk = 34) }

        assertEquals(failed(PatchFailure.ANDROID_TOO_OLD), f.run(f.job(sdkInt = 33)))
        assertEquals(listOf("latest", "bundle"), f.events)
    }

    @Test
    fun `signed out stops before asking the server`() = runTest {
        val f = fixture()

        val outcome = f.run(f.job(signedIn = false))

        assertEquals(failed(PatchFailure.SIGNED_OUT), outcome)
        assertEquals("Sign in again to continue.", (outcome as PatchOutcome.Failed).message)
        assertEquals(emptyList<String>(), f.events)
        assertTrue(f.stages.isEmpty())
    }

    @Test
    fun `not enough space stops before downloading`() = runTest {
        val f = fixture()
        f.size = 100_000_000
        f.shortfall = 40_000_000

        val outcome = f.run()

        assertEquals(PatchOutcome.Failed(PatchFailure.NOT_ENOUGH_SPACE, notEnoughSpaceMessage(NotEnoughSpaceException(40_000_000))), outcome)
        assertEquals(
            "There isn't enough free space for this download. Free up 38 MB and try again.",
            (outcome as PatchOutcome.Failed).message,
        )
        assertEquals(listOf(f.workDir to 320_000_000L), f.reserveCalls)
        assertFalse("download" in f.events)
        f.assertNothingLeft()
    }

    @Test
    fun `room is reserved as 3_2 times the size rounded up`() = runTest {
        val f = fixture()
        f.size = 101
        f.shortfall = 1

        f.run()

        assertEquals(listOf(f.workDir to ceil(3.2 * 101).toLong()), f.reserveCalls)
        assertEquals(324L, f.reserveCalls.single().second)
    }

    @Test
    fun `unknown size downloads without reserving`() = runTest {
        val f = fixture()
        f.size = null
        f.recordsByApk[OFFICIAL_SHA] = recordJson()

        val outcome = f.run()

        assertTrue(outcome is PatchOutcome.Ready)
        assertTrue(f.reserveCalls.isEmpty())
        assertEquals(PatchStage.Downloading(0, -1), f.stages[1])
    }

    @Test
    fun `not official stops before patching`() = runTest {
        val f = fixture()
        f.recordsByApk[OFFICIAL_SHA] = recordJson()
        f.verifyError = PatchException.NotOfficial("The APK is not signed by the official publisher")

        val outcome = f.run()

        assertEquals(failed(PatchFailure.NOT_OFFICIAL), outcome)
        assertEquals("The download from whatsapp.com didn't check out. Try again.", (outcome as PatchOutcome.Failed).message)
        assertFalse(f.events.any { it.startsWith("record") })
        assertTrue(f.requests.isEmpty())
        f.assertNothingLeft()
    }

    @Test
    fun `features off asks first and cancel stops`() = runTest {
        val f = fixture()
        f.recordsByApk[OFFICIAL_SHA] = recordJson(artifacts = FULL_ARTIFACTS - "backup_key")

        val outcome = f.run(proceed = false)

        assertEquals(PatchOutcome.Cancelled, outcome)
        assertEquals(listOf(listOf("Backups")), f.confirmations)
        assertFalse("app token" in f.events)
        assertFalse("signing key" in f.events)
        assertTrue(f.requests.isEmpty())
        f.assertNothingLeft()
    }

    @Test
    fun `features off continues once the user agrees`() = runTest {
        val f = fixture()
        f.recordsByApk[OFFICIAL_SHA] = recordJson(artifacts = FULL_ARTIFACTS - "backup_key")

        val outcome = f.run(proceed = true)

        assertTrue(outcome is PatchOutcome.Ready)
        assertEquals(listOf(listOf("Backups")), f.confirmations)
        assertEquals(1, f.requests.size)
    }

    @Test
    fun `no visible features off does not ask`() = runTest {
        val f = fixture()
        f.recordsByApk[OFFICIAL_SHA] = recordJson(artifacts = FULL_ARTIFACTS - "internal_key", featuresOff = listOf("internal"))

        val outcome = f.run(proceed = false)

        assertTrue(outcome is PatchOutcome.Ready)
        assertTrue(f.confirmations.isEmpty())
    }

    @Test
    fun `output signed with another key fails`() = runTest {
        val f = fixture()
        f.recordsByApk[OFFICIAL_SHA] = recordJson()
        f.inspect = { ArchiveInfo(PKG, VERSION_CODE, setOf("f".repeat(64))) }

        val outcome = f.run()

        assertEquals(failed(PatchFailure.FAILED), outcome)
        assertEquals("Patching failed. Nothing on your phone was changed.", (outcome as PatchOutcome.Failed).message)
        assertFalse(f.finalFile().exists())
        f.assertNothingLeft()
    }

    @Test
    fun `output that is not the build asked for fails`() = runTest {
        val cases = listOf<(File) -> ArchiveInfo?>(
            { null },
            { ArchiveInfo("com.whatsapp.w4b", VERSION_CODE, setOf(USER_CERT)) },
            { ArchiveInfo(PKG, VERSION_CODE + 1, setOf(USER_CERT)) },
        )
        for (inspect in cases) {
            val f = fixture()
            f.recordsByApk[OFFICIAL_SHA] = recordJson()
            f.inspect = inspect

            assertEquals(failed(PatchFailure.FAILED), f.run())
            f.assertNothingLeft()
        }
    }

    @Test
    fun `output signer is compared without regard to case`() = runTest {
        val f = fixture()
        f.recordsByApk[OFFICIAL_SHA] = recordJson()
        f.inspect = { ArchiveInfo(PKG, VERSION_CODE, setOf(OFFICIAL_CERT, USER_CERT.uppercase())) }

        assertTrue(f.run() is PatchOutcome.Ready)
    }

    @Test
    fun `cancelled job leaves no files`() = runTest {
        val f = fixture()
        f.recordsByApk[OFFICIAL_SHA] = recordJson()
        val patching = CompletableDeferred<Unit>()
        f.patcherAction = { request, _ ->
            File(request.workDir, "unsigned.apk").writeBytes(byteArrayOf(1, 2, 3))
            File(request.workDir, ".signed.apk.fastsigner-tmp").writeBytes(byteArrayOf(4, 5, 6))
            patching.complete(Unit)
            try {
                Thread.sleep(60_000)
            } catch (error: InterruptedException) {
                // What FastSigner does when its thread is interrupted.
                throw PatchException.SignFailed("interrupted while signing")
            }
            error("not interrupted")
        }
        var outcome: PatchOutcome? = null
        val job = launch(Dispatchers.Default) { outcome = f.run() }
        patching.await()
        assertTrue(f.workFiles().containsAll(listOf("official.apk", "unsigned.apk", ".signed.apk.fastsigner-tmp")))

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertNull(outcome)
        f.assertNothingLeft()
    }

    @Test
    fun `cancelling while waiting for the answer leaves no files`() = runTest {
        val f = fixture()
        f.recordsByApk[OFFICIAL_SHA] = recordJson(artifacts = FULL_ARTIFACTS - "backup_key")
        val asked = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Boolean>()
        var outcome: PatchOutcome? = null
        val job = launch {
            outcome = f.job().run(PKG, { f.finalFile(it) }, {}, { asked.complete(Unit); never.await() })
        }
        asked.await()
        assertEquals(listOf("official.apk"), f.workFiles())

        job.cancelAndJoin()

        assertNull(outcome)
        f.assertNothingLeft()
    }

    @Test
    fun `stale work files are wiped at start`() = runTest {
        val f = fixture()
        f.workDir.mkdirs()
        File(f.workDir, "official.apk").writeBytes(byteArrayOf(9, 9))
        File(f.workDir, ".signed.apk.fastsigner-tmp").writeBytes(byteArrayOf(9))
        File(f.workDir, "old/unsigned.apk").apply { parentFile!!.mkdirs() }.writeBytes(byteArrayOf(9))
        var seenAtStart: List<String>? = null
        f.onLatest = { seenAtStart = f.workFiles() }
        f.recordsByApk[OFFICIAL_SHA] = recordJson()

        val outcome = f.run()

        assertEquals(emptyList<String>(), seenAtStart)
        assertTrue(outcome is PatchOutcome.Ready)
        assertEquals(emptyList<String>(), f.workFiles())
    }

    @Test
    fun `stale work files are wiped even when signed out`() = runTest {
        val f = fixture()
        File(f.workDir, "official.apk").apply { parentFile!!.mkdirs() }.writeBytes(byteArrayOf(9, 9))

        assertEquals(failed(PatchFailure.SIGNED_OUT), f.run(f.job(signedIn = false)))
        assertEquals(emptyList<String>(), f.workFiles())
    }

    @Test
    fun `engine Storage error maps to not enough space`() = runTest {
        val f = fixture()
        f.recordsByApk[OFFICIAL_SHA] = recordJson()
        f.patcherAction = { _, _ -> throw PatchException.Storage("cannot write signed.apk: ENOSPC") }

        val outcome = f.run()

        assertEquals(PatchOutcome.Failed(PatchFailure.NOT_ENOUGH_SPACE, notEnoughSpaceMessage(NotEnoughSpaceException(-1))), outcome)
        assertEquals(
            "There isn't enough free space for this download. Free up some space and try again.",
            (outcome as PatchOutcome.Failed).message,
        )
        f.assertNothingLeft()
    }

    @Test
    fun `engine errors map to failures`() = runTest {
        val cases = listOf(
            PatchException.Unsupported("lacks required keys") to PatchFailure.NOT_SUPPORTED_YET,
            PatchException.RecordMismatch("different build") to PatchFailure.NOT_SUPPORTED_YET,
            PatchException.NotOfficial("not the publisher") to PatchFailure.NOT_OFFICIAL,
            PatchException.SignFailed("fastsigner exited 1") to PatchFailure.FAILED,
            PatchException.BadApk("Patching failed: $TOKEN") to PatchFailure.FAILED,
            PatchException.BadBundle("bundle has no module.apk") to PatchFailure.FAILED,
            IllegalStateException("bug") to PatchFailure.FAILED,
        )
        for ((error, reason) in cases) {
            val f = fixture()
            f.recordsByApk[OFFICIAL_SHA] = recordJson()
            f.patcherAction = { request, _ ->
                request.output.writeBytes(byteArrayOf(1))
                throw error
            }

            assertEquals("$error", failed(reason), f.run())
            f.assertNothingLeft()
        }
    }

    @Test
    fun `server errors map to failures`() = runTest {
        val cases = listOf<Triple<String, (Fixture) -> Unit, PatchFailure>>(
            Triple("latest 401", { it.latestAnswer = { throw ApiException(401, "Unauthorized") } }, PatchFailure.SIGNED_OUT),
            Triple("latest 404", { it.latestAnswer = { throw ApiException(404, "No supported version yet") } }, PatchFailure.NOT_SUPPORTED_YET),
            Triple("latest 503", { it.latestAnswer = { throw ApiException(503, "Unavailable") } }, PatchFailure.NETWORK),
            Triple("latest offline", { it.latestAnswer = { throw ApiException(0, "Network error") } }, PatchFailure.NETWORK),
            Triple("latest 429", { it.latestAnswer = { throw ApiException(429, "Too many requests") } }, PatchFailure.FAILED),
            Triple("bundle 402", { it.bundleAnswer = { throw ApiException(402, "Subscription required") } }, PatchFailure.NEEDS_SUBSCRIPTION),
            Triple("bundle 404", { it.bundleAnswer = { throw ApiException(404, "Unknown bundle") } }, PatchFailure.FAILED),
            Triple("bundle bad", { it.bundleAnswer = { throw PatchException.BadBundle("bad bundle signature") } }, PatchFailure.FAILED),
            Triple("record 402", { it.recordAnswer = { throw ApiException(402, "Subscription required") } }, PatchFailure.NEEDS_SUBSCRIPTION),
            Triple("record offline", { it.recordAnswer = { throw ApiException(0, "Network error") } }, PatchFailure.NETWORK),
            Triple("app token 402", { it.tokenAnswer = { throw ApiException(402, "Subscription required") } }, PatchFailure.NEEDS_SUBSCRIPTION),
            Triple("app token 401", { it.tokenAnswer = { throw ApiException(401, "Unauthorized") } }, PatchFailure.SIGNED_OUT),
            Triple("signing key 401", { it.keyAnswer = { throw ApiException(401, "Unauthorized") } }, PatchFailure.SIGNED_OUT),
            Triple("signing key 503", { it.keyAnswer = { throw ApiException(503, "Not configured") } }, PatchFailure.NETWORK),
            Triple("signing key bad", { it.keyAnswer = { throw IOException("The server sent an empty signing key") } }, PatchFailure.FAILED),
        )
        for ((name, setUp, reason) in cases) {
            val f = fixture()
            f.recordsByApk[OFFICIAL_SHA] = recordJson()
            setUp(f)

            assertEquals(name, failed(reason), f.run())
            assertTrue(name, f.requests.isEmpty())
            f.assertNothingLeft()
        }
    }

    @Test
    fun `download failures map to failures`() = runTest {
        val cases = listOf<Triple<String, (Fixture) -> Unit, PatchOutcome.Failed>>(
            Triple(
                "disk full",
                { it.downloadError = NotEnoughSpaceException(30L shl 20) },
                PatchOutcome.Failed(PatchFailure.NOT_ENOUGH_SPACE, notEnoughSpaceMessage(NotEnoughSpaceException(30L shl 20))),
            ),
            Triple("dropped", { it.downloadError = IOException("Received 10 of 21 bytes") }, failed(PatchFailure.NETWORK)),
            Triple("offline", { it.downloadError = ApiException(0, "Network error") }, failed(PatchFailure.NETWORK)),
            Triple("cdn 502", { it.downloadError = ApiException(502, "Bad gateway") }, failed(PatchFailure.NETWORK)),
            Triple("cdn 404", { it.downloadError = ApiException(404, "Not found") }, failed(PatchFailure.FAILED)),
            Triple("cdn 401", { it.downloadError = ApiException(401, "Unauthorized") }, failed(PatchFailure.FAILED)),
            Triple("cdn 402", { it.downloadError = ApiException(402, "Payment required") }, failed(PatchFailure.FAILED)),
            Triple("head offline", { it.sizeAnswer = { throw ApiException(0, "Network error") } }, failed(PatchFailure.NETWORK)),
            Triple("head 403", { it.sizeAnswer = { throw ApiException(403, "Forbidden") } }, failed(PatchFailure.FAILED)),
        )
        for ((name, setUp, expected) in cases) {
            val f = fixture()
            f.recordsByApk[OFFICIAL_SHA] = recordJson()
            setUp(f)

            assertEquals(name, expected, f.run())
            assertFalse(name, "verify" in f.events)
            f.assertNothingLeft()
        }
    }

    @Test
    fun `failure texts`() {
        assertEquals("Not supported yet. This version of WhatsApp is new, so try again shortly.", PatchFailure.NOT_SUPPORTED_YET.message())
        assertEquals("You need a subscription to get it.", PatchFailure.NEEDS_SUBSCRIPTION.message())
        assertEquals("This app needs an update first. It's updating now.", PatchFailure.STORE_UPDATE_REQUIRED.message())
        assertEquals("Patching on this phone needs Android 13 or newer.", PatchFailure.ANDROID_TOO_OLD.message())
        assertEquals("The download from whatsapp.com didn't check out. Try again.", PatchFailure.NOT_OFFICIAL.message())
        assertEquals("Sign in again to continue.", PatchFailure.SIGNED_OUT.message())
        assertEquals("Network error. Check your connection and try again.", PatchFailure.NETWORK.message())
        assertEquals("Patching failed. Nothing on your phone was changed.", PatchFailure.FAILED.message())
        assertEquals(notEnoughSpaceMessage(NotEnoughSpaceException(5L shl 20)), PatchFailure.NOT_ENOUGH_SPACE.message(5L shl 20))
        assertEquals(notEnoughSpaceMessage(NotEnoughSpaceException(-1)), PatchFailure.NOT_ENOUGH_SPACE.message())
    }

    private fun recordJson(
        versionName: String = VERSION_NAME,
        versionCode: Long = VERSION_CODE,
        apkSha256: String = OFFICIAL_SHA,
        dexSha256: String = DEX_SHA,
        artifacts: Map<String, String> = FULL_ARTIFACTS,
        featuresOff: List<String> = emptyList(),
        supported: Boolean = true,
        revision: Int? = 7,
    ): String = buildJsonObject {
        put("package", PKG)
        put("version_name", versionName)
        put("version_code", versionCode)
        put("apk_sha256", apkSha256)
        put("dex_sha256", dexSha256)
        put("patcher_version", PATCHER_VERSION)
        put("artifacts", buildJsonObject { artifacts.forEach { (k, v) -> put(k, v) } })
        put("missing_keys", buildJsonArray { })
        put("features_off", buildJsonArray { featuresOff.forEach { add(JsonPrimitive(it)) } })
        put("supported", supported)
        put("source", "whatsapp.com")
        if (revision != null) put("revision", revision)
    }.toString()

    private companion object {
        const val PKG = "com.whatsapp"
        const val VERSION_NAME = "2.26.1.1"
        const val VERSION_CODE = 261011L
        const val PATCHER_VERSION = "3.0.0"
        const val APK_URL = "https://www.whatsapp.com/android/current/WhatsApp.apk"
        const val TOKEN = "paywall-token-secret"
        const val PASSWORD = "keystore-password-secret"
        val OFFICIAL_BYTES = "the official whatsapp apk".toByteArray()
        val OFFICIAL_SHA = MessageDigest.getInstance("SHA-256").digest(OFFICIAL_BYTES).joinToString("") { "%02x".format(it) }
        val DEX_SHA = "d".repeat(64)
        val OFFICIAL_CERT = "c".repeat(64)
        val USER_CERT = "a".repeat(64)
        val PATCHED = "+patched".toByteArray()
        val SIGNED = "+signed".toByteArray()
        val FULL_ARTIFACTS = mapOf("core" to "a.b", "backup_key" to "c.d", "internal_key" to "e.f")
        val FEATURES_JSON = """{"required_keys":["core"],"official_cert_sha256":["${"c".repeat(64)}"],"features":[""" +
            """{"id":"backup","title":"Backups","keys":["backup_key"],"visible":true},""" +
            """{"id":"internal","title":"Internal","keys":["internal_key"],"visible":false}]}"""
    }
}
