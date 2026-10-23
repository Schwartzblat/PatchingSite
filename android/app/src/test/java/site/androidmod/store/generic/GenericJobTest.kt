package site.androidmod.store.generic

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import site.androidmod.patch.ApkFacts
import site.androidmod.patch.ApkSigner
import site.androidmod.patch.CantPatch
import site.androidmod.patch.Capability
import site.androidmod.patch.GenericPatchEngine
import site.androidmod.patch.GenericPatchInfo
import site.androidmod.patch.GenericPatchRequest
import site.androidmod.patch.GenericPatchResult
import site.androidmod.patch.GenericSource
import site.androidmod.patch.LibTarget
import site.androidmod.patch.PatchBundle
import site.androidmod.patch.PatchException
import site.androidmod.store.data.ApiException
import site.androidmod.store.patch.ArchiveInfo
import site.androidmod.store.patch.PatchFailure
import site.androidmod.store.patch.PatchService
import site.androidmod.store.patch.PatchStage
import site.androidmod.store.patch.PatchState
import site.androidmod.store.patch.SigningKey
import site.androidmod.store.patch.StoreUpdateRequired
import site.androidmod.store.patch.capabilitiesExtra
import site.androidmod.store.patch.capabilitiesFromExtra
import site.androidmod.store.patch.genericTestBundleZip
import site.androidmod.store.patch.newTestKeyPair
import site.androidmod.store.patch.testBundleZip
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GenericJobTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val keys = newTestKeyPair()

    /** Every fake for one run: the server, the disk, the engine and the phone's installed app. */
    private inner class Fixture(val root: File) {
        /** Wraps every record write: [put] writes it. */
        var putHook: (record: GenericRecord, put: () -> Unit) -> Unit = { _, put -> put() }
        val records = object : GenericRecords(File(root, "files/generic")) {
            override fun put(record: GenericRecord) = putHook(record) { super.put(record) }
        }
        val workDir = File(root, "files/patch/work/generic-$PKG")
        val originalDir = records.originalDir(PKG)
        val builtDir = records.builtDir(PKG)
        val builtTmpDir = records.builtTmpDir(PKG)

        // The app as Android installed it.
        val appDir = File(root, "data/app/$PKG")
        val base = File(appDir, BASE).also { it.parentFile!!.mkdirs(); it.writeBytes(BASE_BYTES) }
        val split = File(appDir, SPLIT).also { it.writeBytes(SPLIT_BYTES) }

        val events = mutableListOf<String>()
        val stages = mutableListOf<PatchStage>()
        val key = SigningKey(File(root, "signing-key.p12"), PASSWORD.toCharArray(), "key0", USER_CERT, "b".repeat(40))
        val latest = GenericLatest(
            packageName = "generic",
            patcherVersion = PATCHER_VERSION,
            bundleId = "f".repeat(64),
            sha256 = "e".repeat(64),
            size = 1234,
            minSdk = 33,
            minStoreVersionCode = 0,
            revision = 3,
        )

        var installedApp: InstalledPackage? = original()

        // Server
        var latestAnswer: () -> GenericLatest = { latest }
        var bundleAnswer: () -> PatchBundle = { bundle() }
        var tokenAnswer: () -> GenericToken = { GenericToken(TOKEN, EXPIRES_AT) }
        var keyAnswer: () -> SigningKey = { key }

        // Disk
        val reserveCalls = mutableListOf<Pair<File, Long>>()
        var shortfall = 0L

        // Engine and checks
        val requests = mutableListOf<GenericPatchRequest>()
        val signerKeys = mutableListOf<SigningKey>()
        var engineAction: (GenericPatchRequest, ApkSigner) -> GenericPatchResult = { request, signer -> buildOutputs(request, signer) }
        val inspected = mutableListOf<File>()
        var inspect: (File) -> ArchiveInfo? = { ArchiveInfo(PKG, VERSION_CODE, setOf(USER_CERT)) }
        var storePackages = STORE_PACKAGES
        var onStageHook: (PatchStage) -> Unit = {}

        fun original(
            versionCode: Long = VERSION_CODE,
            apks: List<File> = listOf(base, split),
            signers: Set<String> = setOf(DEV_CERT),
        ) = InstalledPackage(PKG, LABEL, VERSION_NAME, versionCode, apks, signers)

        /** Our patched copy, installed: other bytes, signed with the user's key. */
        fun patchedCopy(signers: Set<String> = setOf(USER_CERT)): InstalledPackage {
            val dir = File(root, "data/app/$PKG-patched").apply { mkdirs() }
            val apks = listOf(
                File(dir, BASE).apply { writeBytes(BASE_BYTES + PATCHED + SIGNED) },
                File(dir, SPLIT).apply { writeBytes(SPLIT_BYTES + SIGNED) },
            )
            return InstalledPackage(PKG, LABEL, VERSION_NAME, VERSION_CODE, apks, signers)
        }

        /**
         * Our patched copy, installed, whose base is a readable APK with a generic patch-info naming
         * [capabilities]; null writes a pre-options info (no capabilities key, which reads as ads).
         */
        fun patchedZipCopy(capabilities: List<String>?): InstalledPackage {
            val dir = File(root, "data/app/$PKG-patched").apply { mkdirs() }
            val info = buildString {
                append(
                    """{"kind":"generic","patcher_version":"$PATCHER_VERSION","bundle_id":"${"f".repeat(64)}",""" +
                        """"catalog_format":1,"abi":"arm64-v8a","lib_dir":"lib/arm64-v8a","sdks_present":"AppLovin",""" +
                        """"added":["${GenericPatchInfo.ENTRY}"]""",
                )
                if (capabilities != null) append(""","capabilities":[${capabilities.joinToString(",") { "\"$it\"" }}]""")
                append("}")
            }
            val baseFile = File(dir, BASE)
            ZipOutputStream(baseFile.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(GenericPatchInfo.ENTRY))
                zip.write(info.toByteArray())
                zip.closeEntry()
            }
            val apks = listOf(baseFile, File(dir, SPLIT).apply { writeBytes(SPLIT_BYTES + SIGNED) })
            return InstalledPackage(PKG, LABEL, VERSION_NAME, VERSION_CODE, apks, setOf(USER_CERT))
        }

        fun record(
            intent: GenericIntent? = GenericIntent.PATCH,
            original: List<SavedApk> = emptyList(),
            built: List<SavedApk> = emptyList(),
            patchedBundleId: String? = null,
            capabilities: List<String>? = null,
        ) = GenericRecord(
            packageName = PKG,
            label = LABEL,
            versionName = VERSION_NAME,
            versionCode = VERSION_CODE,
            originalSigners = setOf(DEV_CERT),
            apkNames = listOf(BASE, SPLIT),
            original = original,
            built = built,
            intent = intent,
            uninstallAskedAt = null,
            patchedBundleId = patchedBundleId,
            capabilities = capabilities,
        )

        /** Saved, verified copies of the installed APKs, as an earlier run leaves them. */
        fun savedByEarlierRun(): List<SavedApk> {
            originalDir.mkdirs()
            return listOf(BASE to BASE_BYTES, SPLIT to SPLIT_BYTES).map { (name, bytes) ->
                File(originalDir, name).writeBytes(bytes)
                SavedApk(name, bytes.size.toLong(), sha256(bytes))
            }
        }

        fun savedFiles() = listOf(File(originalDir, BASE), File(originalDir, SPLIT))

        fun bundle(minSdk: Int = 33, minStoreVersionCode: Long = 0): PatchBundle = PatchBundle.open(
            genericTestBundleZip(
                File.createTempFile("bundle", ".zip", root),
                patcherVersion = PATCHER_VERSION,
                minSdk = minSdk,
                minStoreVersionCode = minStoreVersionCode,
                keyPair = keys,
            ),
            keys.public,
        )

        /** What the engine does, in short: each output named as its input, through the signer. */
        fun buildOutputs(request: GenericPatchRequest, signer: ApkSigner): GenericPatchResult {
            request.workDir.mkdirs()
            request.outputDir.mkdirs()
            val outputs = request.apks.map { apk ->
                val unsigned = File(request.workDir, "${apk.name}.unsigned")
                unsigned.writeBytes(apk.readBytes() + if (apk.name == BASE) PATCHED else byteArrayOf())
                val output = File(request.outputDir, apk.name)
                try {
                    signer.sign(unsigned, output)
                } finally {
                    unsigned.delete()
                }
                output
            }
            return GenericPatchResult(outputs, FACTS, INFO, emptyMap())
        }

        val server = object : GenericServer {
            override suspend fun latest(): GenericLatest {
                events += "latest"
                return latestAnswer()
            }

            override suspend fun bundle(patcherVersion: String): PatchBundle {
                assertEquals(PATCHER_VERSION, patcherVersion)
                events += "bundle"
                return bundleAnswer()
            }

            override suspend fun appToken(): GenericToken {
                events += "token"
                return tokenAnswer()
            }

            override suspend fun signingKey(): SigningKey {
                events += "key"
                return keyAnswer()
            }
        }

        fun job(
            sdkInt: Int = 33,
            storeVersionCode: Long = 100,
            signedIn: Boolean = true,
            requested: Set<Capability>? = null,
        ) = GenericJob(
            server = server,
            records = records,
            reserve = { dir, bytes ->
                events += "reserve"
                reserveCalls += dir to bytes
                shortfall
            },
            engine = { request, signer ->
                events += "engine"
                requests += request
                engineAction(request, signer)
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
                inspected += file
                inspect(file)
            },
            installed = { pkg ->
                assertEquals(PKG, pkg)
                installedApp
            },
            storePackages = { storePackages },
            signedIn = { signedIn },
            sdkInt = sdkInt,
            deviceAbis = ABIS,
            storeVersionCode = storeVersionCode,
            workDir = workDir,
            requested = requested,
        )

        suspend fun run(job: GenericJob = job()): GenericOutcome = job.run(PKG) { stage ->
            stages += stage
            if (stage is PatchStage.SavingOriginal && "save" !in events) events += "save"
            onStageHook(stage)
        }

        fun files(dir: File): List<String> =
            if (!dir.exists()) emptyList() else dir.walk().filter { it.isFile }.map { it.relativeTo(dir).path }.sorted().toList()

        fun assertNoTemporaryFiles() {
            assertEquals(emptyList<String>(), files(workDir))
            assertFalse(builtTmpDir.exists())
        }
    }

    private fun fixture() = Fixture(folder.newFolder())

    private fun failed(reason: GenericFailure, minStoreVersionCode: Long? = null) =
        GenericOutcome.Failed(reason, reason.message(LABEL), minStoreVersionCode = minStoreVersionCode)

    private fun builtEntries() = listOf(
        SavedApk(BASE, BUILT_BASE.size.toLong(), sha256(BUILT_BASE)),
        SavedApk(SPLIT, BUILT_SPLIT.size.toLong(), sha256(BUILT_SPLIT)),
    )

    private fun savedEntries() = listOf(
        SavedApk(BASE, BASE_BYTES.size.toLong(), sha256(BASE_BYTES)),
        SavedApk(SPLIT, SPLIT_BYTES.size.toLong(), sha256(SPLIT_BYTES)),
    )

    @Test
    fun patch_saves_verifies_then_builds_in_order() = runTest {
        val f = fixture()
        f.records.put(f.record())
        val bundle = f.bundle()
        f.bundleAnswer = { bundle }

        val outcome = f.run()

        assertEquals(GenericOutcome.Built, outcome)
        assertEquals(
            listOf("latest", "bundle", "reserve", "save", "token", "key", "engine", "sign", "sign", "inspect", "inspect"),
            f.events,
        )
        val installedBytes = (BASE_BYTES.size + SPLIT_BYTES.size).toLong()
        assertEquals(
            listOf(f.originalDir to installedBytes + GenericPatchEngine.bytesNeeded(listOf(f.base, f.split), bundle)),
            f.reserveCalls,
        )
        val saving = f.stages.filterIsInstance<PatchStage.SavingOriginal>()
        assertEquals(PatchStage.SavingOriginal(0, installedBytes), saving.first())
        assertEquals(PatchStage.SavingOriginal(installedBytes, installedBytes), saving.last())
        assertEquals(
            listOf(PatchStage.CheckingVersion, PatchStage.Patching, PatchStage.Signing, PatchStage.Signing),
            f.stages.filterNot { it is PatchStage.SavingOriginal },
        )
        assertEquals(PatchStage.CheckingVersion, f.stages.first())

        // The record ends with the verified original and the verified build.
        assertEquals(
            f.record().copy(
                original = savedEntries(),
                built = builtEntries(),
                builtBundleId = bundle.id,
                builtTokenExpiresAt = EXPIRES_AT,
                builtSigner = USER_CERT,
                capabilities = listOf("ads"),
            ),
            f.records.get(PKG),
        )
        assertArrayEquals(BASE_BYTES, File(f.originalDir, BASE).readBytes())
        assertArrayEquals(SPLIT_BYTES, File(f.originalDir, SPLIT).readBytes())
        assertEquals(listOf(BASE, SPLIT), f.files(f.builtDir))
        assertArrayEquals(BUILT_BASE, File(f.builtDir, BASE).readBytes())
        assertArrayEquals(BUILT_SPLIT, File(f.builtDir, SPLIT).readBytes())

        // The engine read the saved copies, not the installed files.
        val request = f.requests.single()
        assertEquals(f.savedFiles(), request.apks)
        assertEquals(GenericSource.ORIGINAL, request.source)
        assertSame(bundle, request.bundle)
        assertEquals(TOKEN, request.paywallToken)
        assertEquals(USER_CERT, request.signerCertSha256)
        assertEquals(33, request.deviceSdk)
        assertEquals(ABIS, request.deviceAbis)
        assertEquals(f.workDir, request.workDir)
        assertEquals(f.builtTmpDir, request.outputDir)
        assertEquals(STORE_PACKAGES, request.refusedPackages)
        assertEquals(listOf(f.key), f.signerKeys)
        assertEquals(listOf(File(f.builtTmpDir, BASE), File(f.builtTmpDir, SPLIT)), f.inspected)

        f.assertNoTemporaryFiles()
        // Nothing on the phone was touched.
        assertArrayEquals(BASE_BYTES, f.base.readBytes())
        assertArrayEquals(SPLIT_BYTES, f.split.readBytes())
    }

    @Test
    fun job_refuses_app_changed_since_picked() = runTest {
        val cases = listOf<Pair<String, (Fixture) -> InstalledPackage?>>(
            "new version" to { it.original(versionCode = VERSION_CODE + 1) },
            "extra split" to {
                val extra = File(it.appDir, "split_config.xxhdpi.apk").apply { writeBytes(byteArrayOf(5)) }
                it.original(apks = listOf(it.base, it.split, extra))
            },
            "split gone" to { it.original(apks = listOf(it.base)) },
            "other signer" to { it.original(signers = setOf("9".repeat(64))) },
            "our copy already" to { it.original(signers = setOf(USER_CERT)) },
            "removed before saving" to { null },
        )
        for ((name, app) in cases) {
            val f = fixture()
            f.records.put(f.record())
            f.installedApp = app(f)

            val outcome = f.run()

            assertEquals(name, failed(GenericFailure.CHANGED), outcome)
            assertEquals(name, "Example changed since you picked it. Go back and pick it again.", (outcome as GenericOutcome.Failed).message)
            assertEquals(name, emptyList<String>(), f.events)
            assertTrue(name, f.stages.isEmpty())
            assertEquals(name, emptyList<String>(), f.files(f.originalDir))
            assertEquals(name, f.record(), f.records.get(PKG))
            f.assertNoTemporaryFiles()
        }
    }

    @Test
    fun not_enough_space_saves_nothing() = runTest {
        val f = fixture()
        f.records.put(f.record())
        f.shortfall = 40_000_000

        val outcome = f.run()

        assertEquals(
            GenericOutcome.Failed(GenericFailure.NOT_ENOUGH_SPACE, "Free up 38 MB and try again.", bytesNeeded = 40_000_000),
            outcome,
        )
        assertEquals(40_000_000L, (outcome as GenericOutcome.Failed).bytesNeeded)
        assertEquals(listOf("latest", "bundle", "reserve"), f.events)
        assertFalse(f.stages.any { it is PatchStage.SavingOriginal })
        assertEquals(emptyList<String>(), f.files(f.originalDir))
        assertEquals(f.record(), f.records.get(PKG))
        assertTrue(f.requests.isEmpty())
        f.assertNoTemporaryFiles()
    }

    @Test
    fun saved_copy_mismatch_deletes_copies() = runTest {
        val f = fixture()
        f.records.put(f.record())
        var changed = false
        // The installed base changes under the copy, once it has been read: the copy no longer matches it.
        f.onStageHook = { stage ->
            if (!changed && stage is PatchStage.SavingOriginal && stage.bytesDone == BASE_BYTES.size.toLong()) {
                RandomAccessFile(f.base, "rw").use { it.write('X'.code) }
                changed = true
            }
        }

        val outcome = f.run()

        assertTrue(changed)
        assertEquals(failed(GenericFailure.FAILED), outcome)
        assertEquals(
            "Patching failed. Nothing was removed. Try again, or send us a report.",
            (outcome as GenericOutcome.Failed).message,
        )
        assertEquals(listOf("latest", "bundle", "reserve", "save"), f.events)
        assertEquals(emptyList<String>(), f.files(f.originalDir))
        assertEquals(f.record(), f.records.get(PKG))
        assertTrue(f.requests.isEmpty())
        f.assertNoTemporaryFiles()
    }

    @Test
    fun resumed_patch_skips_saving_when_original_present() = runTest {
        val f = fixture()
        f.records.put(f.record())
        var minted = 0
        f.tokenAnswer = {
            minted++
            GenericToken("$TOKEN-$minted", EXPIRES_AT + minted)
        }
        // The first run saves the original, then the engine fails.
        f.engineAction = { _, _ -> throw PatchException.SignFailed("fastsigner exited 1") }
        assertEquals(failed(GenericFailure.FAILED), f.run())
        assertEquals(savedEntries(), f.records.get(PKG)!!.original)

        f.events.clear()
        f.stages.clear()
        f.reserveCalls.clear()
        f.engineAction = { request, signer -> f.buildOutputs(request, signer) }
        val bundle = f.bundle()
        f.bundleAnswer = { bundle }

        assertEquals(GenericOutcome.Built, f.run())

        assertFalse(f.stages.any { it is PatchStage.SavingOriginal })
        assertEquals(listOf("latest", "bundle", "reserve", "token", "key", "engine", "sign", "sign", "inspect", "inspect"), f.events)
        assertEquals(listOf(f.originalDir to GenericPatchEngine.bytesNeeded(f.savedFiles(), bundle)), f.reserveCalls)
        // A fresh token for every run.
        assertEquals(listOf("$TOKEN-1", "$TOKEN-2"), f.requests.map { it.paywallToken })
        assertEquals(f.savedFiles(), f.requests.last().apks)
        val record = f.records.get(PKG)!!
        assertEquals(savedEntries(), record.original)
        assertEquals(builtEntries(), record.built)
        assertEquals(EXPIRES_AT + 2, record.builtTokenExpiresAt)
        f.assertNoTemporaryFiles()
    }

    @Test
    fun resumed_patch_builds_from_saved_original_when_app_is_gone() = runTest {
        val f = fixture()
        f.records.put(f.record(original = f.savedByEarlierRun()))
        f.installedApp = null

        assertEquals(GenericOutcome.Built, f.run())

        val request = f.requests.single()
        assertEquals(f.savedFiles(), request.apks)
        assertEquals(GenericSource.ORIGINAL, request.source)
        assertFalse(f.stages.any { it is PatchStage.SavingOriginal })
        assertEquals(builtEntries(), f.records.get(PKG)!!.built)
    }

    @Test
    fun resumed_patch_refuses_a_damaged_saved_original() = runTest {
        val cases = listOf<Pair<String, (Fixture) -> Unit>>(
            "changed byte" to { RandomAccessFile(File(it.originalDir, SPLIT), "rw").use { file -> file.write('X'.code) } },
            "missing file" to { File(it.originalDir, SPLIT).delete() },
        )
        for ((name, damage) in cases) {
            val f = fixture()
            val saved = f.savedByEarlierRun()
            f.records.put(f.record(original = saved))
            damage(f)

            val outcome = f.run()
            assertEquals(name, failed(GenericFailure.DAMAGED_ORIGINAL), outcome)
            assertEquals(
                name,
                "The saved original of Example is damaged. Stop, then pick Example again.",
                (outcome as GenericOutcome.Failed).message,
            )
            assertTrue(name, f.requests.isEmpty())
            assertFalse(name, "token" in f.events)
            // Kept as it is: the run screen's Stop cleans it up.
            assertEquals(name, f.record(original = saved), f.records.get(PKG))
            f.assertNoTemporaryFiles()
        }

        // Removed since: Stop needs the original installed again, so the way on is Play.
        for ((name, damage) in cases) {
            val f = fixture()
            val saved = f.savedByEarlierRun()
            f.records.put(f.record(original = saved))
            f.installedApp = null
            damage(f)

            val outcome = f.run()
            assertEquals(name, failed(GenericFailure.DAMAGED_ORIGINAL_APP_REMOVED), outcome)
            assertEquals(
                name,
                "The saved original of Example is damaged. Reinstall Example from Google Play, then Stop.",
                (outcome as GenericOutcome.Failed).message,
            )
            assertTrue(name, f.requests.isEmpty())
            assertFalse(name, "token" in f.events)
            assertEquals(name, f.record(original = saved), f.records.get(PKG))
            f.assertNoTemporaryFiles()
        }
    }

    @Test
    fun refresh_refuses_a_damaged_saved_original() = runTest {
        val f = fixture()
        val saved = f.savedByEarlierRun()
        val before = f.record(intent = GenericIntent.REFRESH, original = saved, patchedBundleId = "old-bundle")
        f.records.put(before)
        f.installedApp = f.patchedCopy()
        RandomAccessFile(File(f.originalDir, SPLIT), "rw").use { file -> file.write('X'.code) }

        val outcome = f.run()
        assertEquals(GenericFailure.DAMAGED_ORIGINAL, (outcome as GenericOutcome.Failed).reason)
        assertEquals("The saved original of Example is damaged. Stop, then delete it in the Library and Refresh again.", outcome.message)
        assertTrue(f.requests.isEmpty())
        assertFalse("token" in f.events)
        assertEquals(before, f.records.get(PKG))
        f.assertNoTemporaryFiles()
    }

    @Test
    fun engine_unpatchable_after_save_is_reported_with_reasons() = runTest {
        val f = fixture()
        f.records.put(f.record())
        f.engineAction = { request, _ ->
            request.outputDir.mkdirs()
            File(request.outputDir, BASE).writeBytes(byteArrayOf(1))
            throw PatchException.Unpatchable(listOf(CantPatch.PAIRIP, CantPatch.SHARED_USER_ID))
        }

        val outcome = f.run()

        assertEquals(GenericOutcome.Unpatchable(listOf(CantPatch.PAIRIP, CantPatch.SHARED_USER_ID)), outcome)
        // The saved original stays; the run screen's Stop cleans it up.
        val record = f.records.get(PKG)!!
        assertEquals(savedEntries(), record.original)
        assertEquals(listOf(BASE, SPLIT), f.files(f.originalDir))
        assertEquals(emptyList<SavedApk>(), record.built)
        assertFalse(f.builtDir.exists())
        f.assertNoTemporaryFiles()
    }

    @Test
    fun refuses_store_packages_before_copying_or_any_network_call() = runTest {
        // The catalogue gained the app after it was picked: refused before the CHANGED check, the copy or the server.
        for (intent in listOf(GenericIntent.PATCH, GenericIntent.REFRESH)) {
            for (app in listOf<(Fixture) -> InstalledPackage?>({ it.original() }, { it.patchedCopy() }, { null })) {
                val f = fixture()
                val record = f.record(intent)
                f.records.put(record)
                f.installedApp = app(f)
                f.storePackages = setOf("com.whatsapp", PKG)

                val outcome = f.run()

                assertEquals("$intent", GenericOutcome.Unpatchable(listOf(CantPatch.IN_OUR_STORE)), outcome)
                assertEquals("$intent", emptyList<String>(), f.events)
                assertTrue("$intent", f.requests.isEmpty())
                assertEquals("$intent", record, f.records.get(PKG))
                assertFalse("$intent", f.originalDir.exists())
                assertFalse("$intent", f.builtDir.exists())
                f.assertNoTemporaryFiles()
            }
        }
    }

    @Test
    fun bundle_min_sdk_and_min_store_checked() = runTest {
        val f = fixture()
        f.records.put(f.record())
        f.bundleAnswer = { f.bundle(minSdk = 34) }
        assertEquals(failed(GenericFailure.ANDROID_TOO_OLD), f.run(f.job(sdkInt = 33)))
        assertEquals(listOf("latest", "bundle"), f.events)
        assertEquals(emptyList<String>(), f.files(f.originalDir))

        val g = fixture()
        g.records.put(g.record())
        g.bundleAnswer = { g.bundle(minStoreVersionCode = 500) }
        assertEquals(failed(GenericFailure.STORE_UPDATE_REQUIRED, minStoreVersionCode = 500), g.run(g.job(storeVersionCode = 100)))
        assertEquals(listOf("latest", "bundle"), g.events)

        val h = fixture()
        h.records.put(h.record())
        h.bundleAnswer = { h.bundle(minSdk = 34, minStoreVersionCode = 100) }
        assertEquals(GenericOutcome.Built, h.run(h.job(sdkInt = 34, storeVersionCode = 100)))
        assertEquals(34, h.requests.single().deviceSdk)

        // An app's bundle is not the generic one.
        val w = fixture()
        w.records.put(w.record())
        w.bundleAnswer = {
            PatchBundle.open(testBundleZip(File.createTempFile("bundle", ".zip", w.root), patcherVersion = PATCHER_VERSION, keyPair = keys), keys.public)
        }
        assertEquals(failed(GenericFailure.FAILED), w.run())
        assertEquals(listOf("latest", "bundle"), w.events)
    }

    @Test
    fun latest_426_is_store_update_required() = runTest {
        val f = fixture()
        f.records.put(f.record())
        f.latestAnswer = { throw StoreUpdateRequired(4242) }

        val outcome = f.run()

        assertEquals(failed(GenericFailure.STORE_UPDATE_REQUIRED, minStoreVersionCode = 4242), outcome)
        assertEquals("Update the store to patch apps.", (outcome as GenericOutcome.Failed).message)
        assertEquals(4242L, outcome.minStoreVersionCode)
        assertEquals(listOf("latest"), f.events)
        assertEquals(emptyList<String>(), f.files(f.originalDir))
    }

    @Test
    fun latest_503_is_not_available() = runTest {
        for (message in listOf("Generic bundle unavailable", "Generic patching is off")) {
            val f = fixture()
            f.records.put(f.record())
            f.latestAnswer = { throw ApiException(503, message) }

            val outcome = f.run()

            assertEquals(message, failed(GenericFailure.NOT_AVAILABLE), outcome)
            assertEquals(
                "Patching other apps isn't available right now. Try again later.",
                (outcome as GenericOutcome.Failed).message,
            )
            assertEquals(listOf("latest"), f.events)
            assertEquals(emptyList<String>(), f.files(f.originalDir))
        }
    }

    @Test
    fun latest_402_needs_subscription() = runTest {
        val f = fixture()
        f.records.put(f.record())
        f.latestAnswer = { throw ApiException(402, "Subscription required") }

        val outcome = f.run()

        assertEquals(failed(GenericFailure.NEEDS_SUBSCRIPTION), outcome)
        assertEquals("You need an active plan to patch apps.", (outcome as GenericOutcome.Failed).message)
        assertEquals(listOf("latest"), f.events)
        assertEquals(emptyList<String>(), f.files(f.originalDir))
    }

    @Test
    fun server_errors_map_to_failures() = runTest {
        val cases = listOf<Triple<String, (Fixture) -> Unit, GenericFailure>>(
            Triple("latest 401", { it.latestAnswer = { throw ApiException(401, "Unauthorized") } }, GenericFailure.SIGNED_OUT),
            Triple("latest 404 none yet", { it.latestAnswer = { throw ApiException(404, "No generic bundle yet") } }, GenericFailure.NOT_AVAILABLE),
            Triple("latest 404 off", { it.latestAnswer = { throw ApiException(404, "Generic patching is off") } }, GenericFailure.NOT_AVAILABLE),
            Triple("latest 503 other", { it.latestAnswer = { throw ApiException(503, "Service Unavailable") } }, GenericFailure.NETWORK),
            Triple("latest 502", { it.latestAnswer = { throw ApiException(502, "Bad gateway") } }, GenericFailure.NETWORK),
            Triple("latest offline", { it.latestAnswer = { throw ApiException(0, "Network error") } }, GenericFailure.NETWORK),
            Triple("latest 429", { it.latestAnswer = { throw ApiException(429, "Too many requests") } }, GenericFailure.FAILED),
            Triple("bundle 402", { it.bundleAnswer = { throw ApiException(402, "Subscription required") } }, GenericFailure.NEEDS_SUBSCRIPTION),
            Triple("bundle 404 off", { it.bundleAnswer = { throw ApiException(404, "Generic patching is off") } }, GenericFailure.NOT_AVAILABLE),
            Triple("bundle 404 other", { it.bundleAnswer = { throw ApiException(404, "Unknown bundle") } }, GenericFailure.FAILED),
            Triple("bundle bad", { it.bundleAnswer = { throw PatchException.BadBundle("bad bundle signature") } }, GenericFailure.FAILED),
            Triple("token 401", { it.tokenAnswer = { throw ApiException(401, "Unauthorized") } }, GenericFailure.SIGNED_OUT),
            Triple("token 402", { it.tokenAnswer = { throw ApiException(402, "Subscription required") } }, GenericFailure.NEEDS_SUBSCRIPTION),
            Triple("token 404 off", { it.tokenAnswer = { throw ApiException(404, "Generic patching is off") } }, GenericFailure.NOT_AVAILABLE),
            Triple("token unreadable", { it.tokenAnswer = { throw ApiException(0, "The server sent a response this app could not read.") } }, GenericFailure.NETWORK),
            Triple("key 401", { it.keyAnswer = { throw ApiException(401, "Unauthorized") } }, GenericFailure.SIGNED_OUT),
            Triple("key 503", { it.keyAnswer = { throw ApiException(503, "Not configured") } }, GenericFailure.NETWORK),
            Triple("key bad", { it.keyAnswer = { throw IOException("The server sent an empty signing key") } }, GenericFailure.FAILED),
        )
        for ((name, setUp, reason) in cases) {
            val f = fixture()
            f.records.put(f.record())
            setUp(f)

            val outcome = f.run()

            assertEquals(name, failed(reason), outcome)
            assertTrue(name, (outcome as GenericOutcome.Failed).cause != null)
            assertTrue(name, f.requests.isEmpty())
            assertEquals(name, emptyList<SavedApk>(), f.records.get(PKG)!!.built)
            f.assertNoTemporaryFiles()
        }
    }

    @Test
    fun engine_errors_map_to_failures() = runTest {
        val cases = listOf(
            PatchException.Storage("cannot write base.apk: ENOSPC") to GenericFailure.NOT_ENOUGH_SPACE,
            IOException("write failed: ENOSPC (No space left on device)") to GenericFailure.NOT_ENOUGH_SPACE,
            PatchException.SignFailed("fastsigner exited 1") to GenericFailure.FAILED,
            PatchException.BadApk("Patching failed: broken zip") to GenericFailure.FAILED,
            PatchException.BadBundle("the generic bundle has no catalog") to GenericFailure.FAILED,
            IllegalStateException("bug") to GenericFailure.FAILED,
        )
        for ((error, reason) in cases) {
            val f = fixture()
            f.records.put(f.record())
            f.engineAction = { request, _ ->
                request.outputDir.mkdirs()
                File(request.outputDir, BASE).writeBytes(byteArrayOf(1))
                throw error
            }

            val outcome = f.run()

            assertEquals("$error", failed(reason), outcome)
            assertEquals("$error", emptyList<SavedApk>(), f.records.get(PKG)!!.built)
            assertFalse("$error", f.builtDir.exists())
            f.assertNoTemporaryFiles()
        }
        val f = fixture()
        f.records.put(f.record())
        f.engineAction = { _, _ -> throw PatchException.Storage("cannot write base.apk") }
        assertEquals("Free up some space and try again.", (f.run() as GenericOutcome.Failed).message)
    }

    @Test
    fun wrong_output_signer_fails_and_keeps_built_empty() = runTest {
        val cases = listOf<Pair<String, (File) -> ArchiveInfo?>>(
            "another signer" to { ArchiveInfo(PKG, VERSION_CODE, setOf("9".repeat(64))) },
            "a second signer" to { ArchiveInfo(PKG, VERSION_CODE, setOf(USER_CERT, DEV_CERT)) },
            "no signer" to { ArchiveInfo(PKG, VERSION_CODE, emptySet()) },
            "unreadable" to { null },
            "another package" to { ArchiveInfo("com.example.other", VERSION_CODE, setOf(USER_CERT)) },
            "another version" to { ArchiveInfo(PKG, VERSION_CODE + 1, setOf(USER_CERT)) },
            "only the split is wrong" to { file -> ArchiveInfo(PKG, VERSION_CODE, setOf(if (file.name == SPLIT) DEV_CERT else USER_CERT)) },
        )
        for ((name, inspect) in cases) {
            val f = fixture()
            f.records.put(f.record())
            f.inspect = inspect

            val outcome = f.run()

            assertEquals(name, failed(GenericFailure.FAILED), outcome)
            val record = f.records.get(PKG)!!
            assertEquals(name, emptyList<SavedApk>(), record.built)
            assertNull(name, record.builtBundleId)
            assertNull(name, record.builtTokenExpiresAt)
            assertNull(name, record.builtSigner)
            assertFalse(name, f.builtDir.exists())
            assertEquals(name, savedEntries(), record.original)
            f.assertNoTemporaryFiles()
        }

        // Outputs that are not one per input fail too.
        val f = fixture()
        f.records.put(f.record())
        f.engineAction = { request, signer -> f.buildOutputs(request, signer).let { it.copy(apks = it.apks.take(1)) } }
        assertEquals(failed(GenericFailure.FAILED), f.run())
        assertFalse(f.builtDir.exists())

        // The signer is compared without regard to case.
        val g = fixture()
        g.records.put(g.record())
        g.inspect = { ArchiveInfo(PKG, VERSION_CODE, setOf(USER_CERT.uppercase())) }
        assertEquals(GenericOutcome.Built, g.run())
    }

    @Test
    fun rebuild_replaces_the_previous_build() = runTest {
        val f = fixture()
        val old = SavedApk(BASE, 3, sha256(byteArrayOf(1, 2, 3)))
        f.records.put(f.record(original = f.savedByEarlierRun(), built = listOf(old)))
        f.builtDir.mkdirs()
        File(f.builtDir, BASE).writeBytes(byteArrayOf(1, 2, 3))
        File(f.builtDir, "stale.apk").writeBytes(byteArrayOf(4))
        // A tmp dir and work files from a run that was killed.
        f.builtTmpDir.mkdirs()
        File(f.builtTmpDir, "half.apk").writeBytes(byteArrayOf(5))
        f.workDir.mkdirs()
        File(f.workDir, "generic-base.unsigned.apk").writeBytes(byteArrayOf(6))

        assertEquals(GenericOutcome.Built, f.run())

        assertEquals(listOf(BASE, SPLIT), f.files(f.builtDir))
        assertArrayEquals(BUILT_BASE, File(f.builtDir, BASE).readBytes())
        assertEquals(builtEntries(), f.records.get(PKG)!!.built)
        f.assertNoTemporaryFiles()
    }

    @Test
    fun the_build_records_the_key_it_was_signed_with() = runTest {
        // Built with another account's key before: that signer goes with the old build.
        val f = fixture()
        val old = SavedApk(BASE, 3, sha256(byteArrayOf(1, 2, 3)))
        f.records.put(f.record(original = f.savedByEarlierRun(), built = listOf(old)).copy(builtSigner = OTHER_CERT))
        f.builtDir.mkdirs()
        File(f.builtDir, BASE).writeBytes(byteArrayOf(1, 2, 3))
        val writes = mutableListOf<GenericRecord>()
        f.putHook = { record, put ->
            writes += record
            put()
        }

        assertEquals(GenericOutcome.Built, f.run())

        // The record never pairs a build with another build's signer.
        for (write in writes) {
            assertEquals("$write", write.built.isEmpty(), write.builtSigner == null)
            if (write.built.isNotEmpty()) assertEquals(USER_CERT, write.builtSigner)
        }
        assertEquals(USER_CERT, f.records.get(PKG)!!.builtSigner)
        assertEquals(f.key.certSha256, f.signerKeys.single().certSha256)
    }

    @Test
    fun refresh_from_saved_original_uses_original_source() = runTest {
        val f = fixture()
        val saved = f.savedByEarlierRun()
        f.records.put(f.record(intent = GenericIntent.REFRESH, original = saved, patchedBundleId = "old-bundle"))
        f.installedApp = f.patchedCopy()
        val bundle = f.bundle()
        f.bundleAnswer = { bundle }

        assertEquals(GenericOutcome.Built, f.run())

        assertEquals(listOf("key", "latest", "bundle", "reserve", "token", "engine", "sign", "sign", "inspect", "inspect"), f.events)
        val request = f.requests.single()
        assertEquals(f.savedFiles(), request.apks)
        assertEquals(GenericSource.ORIGINAL, request.source)
        assertEquals(TOKEN, request.paywallToken)
        assertEquals(listOf(f.originalDir to GenericPatchEngine.bytesNeeded(f.savedFiles(), bundle)), f.reserveCalls)
        assertFalse(f.stages.any { it is PatchStage.SavingOriginal })
        assertEquals(
            f.record(intent = GenericIntent.REFRESH, original = saved, patchedBundleId = "old-bundle").copy(
                built = builtEntries(),
                builtBundleId = bundle.id,
                builtTokenExpiresAt = EXPIRES_AT,
                builtSigner = USER_CERT,
                capabilities = listOf("ads"),
            ),
            f.records.get(PKG),
        )
        f.assertNoTemporaryFiles()
    }

    @Test
    fun refresh_without_original_uses_installed_patched_copy() = runTest {
        val f = fixture()
        f.records.put(f.record(intent = GenericIntent.REFRESH, patchedBundleId = "old-bundle"))
        val patched = f.patchedCopy()
        f.installedApp = patched
        val bundle = f.bundle()
        f.bundleAnswer = { bundle }

        assertEquals(GenericOutcome.Built, f.run())

        val request = f.requests.single()
        assertEquals(patched.apks, request.apks)
        assertEquals(GenericSource.PATCHED, request.source)
        assertEquals(listOf(f.originalDir to GenericPatchEngine.bytesNeeded(patched.apks, bundle)), f.reserveCalls)
        assertFalse(f.stages.any { it is PatchStage.SavingOriginal })
        assertEquals(emptyList<String>(), f.files(f.originalDir))
        val record = f.records.get(PKG)!!
        assertEquals(emptyList<SavedApk>(), record.original)
        assertEquals(listOf(BASE, SPLIT), record.built.map { it.name })
        assertEquals(bundle.id, record.builtBundleId)
        // The installed patched copy is the engine's input, never changed.
        assertArrayEquals(BASE_BYTES + PATCHED + SIGNED, patched.apks[0].readBytes())
        f.assertNoTemporaryFiles()
    }

    @Test
    fun refresh_from_copy_not_ours_is_refused() = runTest {
        val cases = listOf<Pair<String, (Fixture) -> Unit>>(
            "the original" to { it.installedApp = it.patchedCopy(signers = setOf(DEV_CERT)) },
            "a second signer" to { it.installedApp = it.patchedCopy(signers = setOf(USER_CERT, DEV_CERT)) },
            "no signer" to { it.installedApp = it.patchedCopy(signers = emptySet()) },
            "with a saved original" to {
                it.records.put(it.record(intent = GenericIntent.REFRESH, original = it.savedByEarlierRun()))
                it.installedApp = it.patchedCopy(signers = setOf(DEV_CERT))
            },
        )
        for ((name, setUp) in cases) {
            val f = fixture()
            f.records.put(f.record(intent = GenericIntent.REFRESH))
            setUp(f)
            val before = f.records.get(PKG)

            val outcome = f.run()

            assertEquals(name, failed(GenericFailure.NOT_OUR_COPY), outcome)
            assertEquals(
                name,
                "The installed Example isn't our patched copy, so it can't be refreshed.",
                (outcome as GenericOutcome.Failed).message,
            )
            assertEquals(name, listOf("key"), f.events)
            assertTrue(name, f.requests.isEmpty())
            assertEquals(name, before, f.records.get(PKG))
        }

        // Refreshing needs the patched copy installed, at the recorded version.
        for ((name, app) in listOf<Pair<String, (Fixture) -> InstalledPackage?>>(
            "not installed" to { null },
            "another version" to { it.patchedCopy().copy(versionCode = VERSION_CODE + 1) },
        )) {
            val f = fixture()
            f.records.put(f.record(intent = GenericIntent.REFRESH))
            f.installedApp = app(f)

            val outcome = f.run()
            assertEquals(name, GenericFailure.CHANGED, (outcome as GenericOutcome.Failed).reason)
            assertEquals(
                name,
                "Example changed since it was patched. Put the original back, update it in Play, and patch it again.",
                outcome.message,
            )
            assertEquals(name, emptyList<String>(), f.events)
        }
    }

    @Test
    fun cancellation_leaves_no_tmp_or_work_files() = runTest {
        // Cancelled while the engine runs.
        val f = fixture()
        f.records.put(f.record())
        val patching = CompletableDeferred<Unit>()
        f.engineAction = { request, _ ->
            request.workDir.mkdirs()
            request.outputDir.mkdirs()
            File(request.workDir, "generic-base.unsigned.apk").writeBytes(byteArrayOf(1, 2, 3))
            File(request.outputDir, BASE).writeBytes(byteArrayOf(4, 5, 6))
            patching.complete(Unit)
            try {
                Thread.sleep(60_000)
            } catch (error: InterruptedException) {
                // What FastSigner does when its thread is interrupted.
                throw PatchException.SignFailed("interrupted while signing")
            }
            error("not interrupted")
        }
        var outcome: GenericOutcome? = null
        val job = launch(Dispatchers.Default) { outcome = f.run() }
        patching.await()
        assertEquals(listOf("generic-base.unsigned.apk"), f.files(f.workDir))
        assertEquals(listOf(BASE), f.files(f.builtTmpDir))

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertNull(outcome)
        f.assertNoTemporaryFiles()
        assertFalse(f.builtDir.exists())
        assertEquals(emptyList<SavedApk>(), f.records.get(PKG)!!.built)

        // Cancelled halfway through saving the original: the copies so far are not kept.
        val g = fixture()
        g.records.put(g.record())
        val saving = CompletableDeferred<Unit>()
        g.onStageHook = { stage ->
            if (stage is PatchStage.SavingOriginal && stage.bytesDone > 0) {
                saving.complete(Unit)
                Thread.sleep(60_000)
            }
        }
        var outcome2: GenericOutcome? = null
        val job2 = launch(Dispatchers.Default) { outcome2 = g.run() }
        saving.await()
        assertEquals(listOf(BASE), g.files(g.originalDir))

        job2.cancelAndJoin()

        assertNull(outcome2)
        assertEquals(emptyList<String>(), g.files(g.originalDir))
        assertEquals(g.record(), g.records.get(PKG))
        g.assertNoTemporaryFiles()
    }

    @Test
    fun token_never_in_request_or_outcome_strings() = runTest {
        val f = fixture()
        f.records.put(f.record())

        assertEquals(GenericOutcome.Built, f.run())

        val request = f.requests.single()
        assertEquals(TOKEN, request.paywallToken)
        assertFalse(TOKEN in request.toString())
        assertFalse(f.stages.any { TOKEN in it.toString() })
        assertFalse(TOKEN in f.records.get(PKG).toString())
        assertFalse(TOKEN in GenericToken(TOKEN, EXPIRES_AT).toString())

        // A failure whose cause names the token keeps it out of the outcome's message and string.
        val g = fixture()
        g.records.put(g.record())
        g.engineAction = { _, _ -> throw PatchException.BadApk("Patching failed: $TOKEN") }
        val outcome = g.run() as GenericOutcome.Failed
        assertFalse(TOKEN in outcome.toString())
        assertFalse(TOKEN in outcome.message)
    }

    /** Runs [f] in a job that [Fixture.putHook] can cancel at an exact write. */
    private suspend fun kotlinx.coroutines.CoroutineScope.runCancellable(f: Fixture, cancelAfter: (GenericRecord) -> Boolean): Job {
        lateinit var job: Job
        f.putHook = { record, put ->
            put()
            if (cancelAfter(record)) job.cancel()
        }
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            f.run()
            error("the run was not cancelled")
        }
        job.start()
        job.join()
        return job
    }

    @Test
    fun cancel_right_after_saving_keeps_the_recorded_original() = runTest {
        val f = fixture()
        f.records.put(f.record())

        val job = runCancellable(f) { it.original.isNotEmpty() && it.built.isEmpty() }

        assertTrue(job.isCancelled)
        assertEquals(savedEntries(), f.records.get(PKG)!!.original)
        assertEquals(listOf(BASE, SPLIT), f.files(f.originalDir))
        assertArrayEquals(BASE_BYTES, File(f.originalDir, BASE).readBytes())
        assertTrue(f.requests.isEmpty())
        f.assertNoTemporaryFiles()
    }

    @Test
    fun cancel_right_after_recording_the_build_keeps_it() = runTest {
        val f = fixture()
        f.records.put(f.record())

        val job = runCancellable(f) { it.built.isNotEmpty() }

        assertTrue(job.isCancelled)
        val record = f.records.get(PKG)!!
        assertEquals(savedEntries(), record.original)
        assertEquals(builtEntries(), record.built)
        assertEquals(listOf(BASE, SPLIT), f.files(f.originalDir))
        assertEquals(listOf(BASE, SPLIT), f.files(f.builtDir))
        f.assertNoTemporaryFiles()
    }

    @Test
    fun a_build_the_record_does_not_list_is_not_kept() = runTest {
        val f = fixture()
        f.records.put(f.record())
        // built/ is in place, but the record can't be written.
        f.putHook = { record, put -> if (record.built.isNotEmpty()) throw IOException("I/O error") else put() }

        assertEquals(failed(GenericFailure.FAILED), f.run())

        val record = f.records.get(PKG)!!
        assertEquals(emptyList<SavedApk>(), record.built)
        assertFalse(f.builtDir.exists())
        assertEquals(savedEntries(), record.original)
        assertEquals(listOf(BASE, SPLIT), f.files(f.originalDir))
        f.assertNoTemporaryFiles()
    }

    @Test
    fun copies_the_record_does_not_list_are_not_kept() = runTest {
        val f = fixture()
        f.records.put(f.record())
        // The copies verified, but the record can't be written.
        f.putHook = { record, put -> if (record.original.isNotEmpty()) throw IOException("I/O error") else put() }

        assertEquals(failed(GenericFailure.FAILED), f.run())

        assertEquals(f.record(), f.records.get(PKG))
        assertEquals(emptyList<String>(), f.files(f.originalDir))
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun no_catalogue_yet_is_not_available() = runTest {
        for (intent in listOf(GenericIntent.PATCH, GenericIntent.REFRESH)) {
            val f = fixture()
            f.records.put(f.record(intent = intent))
            if (intent == GenericIntent.REFRESH) f.installedApp = f.patchedCopy()
            // Without a catalogue the store can't tell which apps are its own.
            f.storePackages = emptySet()

            assertEquals("$intent", failed(GenericFailure.NOT_AVAILABLE), f.run())
            assertEquals("$intent", emptyList<String>(), f.events)
            assertTrue("$intent", f.stages.isEmpty())
            assertEquals("$intent", emptyList<String>(), f.files(f.originalDir))
            assertEquals("$intent", f.record(intent = intent), f.records.get(PKG))
        }
    }

    @Test
    fun store_packages_are_the_saved_and_loaded_catalogues_and_whatsapp() {
        assertEquals(emptySet<String>(), refusedPackagesOf(saved = emptySet(), loaded = emptySet()))
        assertEquals(setOf("il.co.mako", "com.whatsapp"), refusedPackagesOf(saved = setOf("il.co.mako"), loaded = emptySet()))
        assertEquals(
            setOf("il.co.mako", "com.tranzmate", "com.whatsapp"),
            refusedPackagesOf(saved = setOf("il.co.mako"), loaded = setOf("com.tranzmate", "com.whatsapp")),
        )
    }

    @Test
    fun signed_out_or_old_android_stops_before_anything() = runTest {
        val f = fixture()
        f.records.put(f.record())
        assertEquals(failed(GenericFailure.SIGNED_OUT), f.run(f.job(signedIn = false)))
        assertEquals(emptyList<String>(), f.events)
        assertTrue(f.stages.isEmpty())

        val g = fixture()
        g.records.put(g.record())
        val outcome = g.run(g.job(sdkInt = 32))
        assertEquals(failed(GenericFailure.ANDROID_TOO_OLD), outcome)
        assertEquals("Patching apps needs Android 13 or newer.", (outcome as GenericOutcome.Failed).message)
        assertEquals(emptyList<String>(), g.events)
        assertTrue(g.stages.isEmpty())
    }

    @Test
    fun needs_a_record_that_patches_or_refreshes() = runTest {
        val noRecord = fixture()
        assertEquals(failed(GenericFailure.FAILED), noRecord.run())
        assertEquals(emptyList<String>(), noRecord.events)

        for (intent in listOf(null, GenericIntent.RESTORE)) {
            val f = fixture()
            f.records.put(f.record(intent = intent, original = f.savedByEarlierRun()))

            assertEquals("$intent", failed(GenericFailure.FAILED), f.run())
            assertEquals("$intent", emptyList<String>(), f.events)
            assertEquals("$intent", listOf(BASE, SPLIT), f.files(f.originalDir))
        }
    }

    @Test
    fun failure_texts() {
        assertEquals("Sign in again to continue.", GenericFailure.SIGNED_OUT.message(LABEL))
        assertEquals("Patching apps needs Android 13 or newer.", GenericFailure.ANDROID_TOO_OLD.message(LABEL))
        assertEquals("You need an active plan to patch apps.", GenericFailure.NEEDS_SUBSCRIPTION.message(LABEL))
        assertEquals("Patching other apps isn't available right now. Try again later.", GenericFailure.NOT_AVAILABLE.message(LABEL))
        assertEquals("Update the store to patch apps.", GenericFailure.STORE_UPDATE_REQUIRED.message(LABEL))
        assertEquals("Free up 5.0 MB and try again.", GenericFailure.NOT_ENOUGH_SPACE.message(LABEL, 5L shl 20))
        assertEquals("Free up some space and try again.", GenericFailure.NOT_ENOUGH_SPACE.message(LABEL))
        assertEquals("Network error. Check your connection and try again.", GenericFailure.NETWORK.message(LABEL))
        assertEquals("Example changed since you picked it. Go back and pick it again.", GenericFailure.CHANGED.message(LABEL))
        assertEquals(
            "Example changed since you picked it. Go back and pick it again.",
            GenericFailure.CHANGED.message(LABEL, intent = GenericIntent.PATCH),
        )
        assertEquals(
            "Example changed since it was patched. Put the original back, update it in Play, and patch it again.",
            GenericFailure.CHANGED.message(LABEL, intent = GenericIntent.REFRESH),
        )
        assertEquals(
            "The saved original of Example is damaged. Stop, then pick Example again.",
            GenericFailure.DAMAGED_ORIGINAL.message(LABEL),
        )
        assertEquals(
            "The saved original of Example is damaged. Stop, then delete it in the Library and Refresh again.",
            GenericFailure.DAMAGED_ORIGINAL.message(LABEL, intent = GenericIntent.REFRESH),
        )
        assertEquals(
            "The saved original of Example is damaged. Reinstall Example from Google Play, then Stop.",
            GenericFailure.DAMAGED_ORIGINAL_APP_REMOVED.message(LABEL),
        )
        assertEquals(
            "The installed Example isn't our patched copy, so it can't be refreshed.",
            GenericFailure.NOT_OUR_COPY.message(LABEL),
        )
        assertEquals("Patching failed. Nothing was removed. Try again, or send us a report.", GenericFailure.FAILED.message(LABEL))
    }

    @Test
    fun startExtraRidesIntoTheJob() = runTest {
        // What PatchService.start puts on the intent for the confirm flow's {ads, iap}, and what
        // onStartCommand hands the job.
        val extra = capabilitiesExtra(setOf(Capability.ADS, Capability.IAP))
        assertEquals("ads,iap", extra)
        val f = fixture()
        f.records.put(f.record())

        assertEquals(GenericOutcome.Built, f.run(f.job(requested = capabilitiesFromExtra(extra))))

        assertEquals(setOf(Capability.ADS, Capability.IAP), f.requests.single().capabilities)
        assertEquals(listOf("ads", "iap"), f.records.get(PKG)!!.capabilities)
    }

    @Test
    fun defaultIsAdsOnly() = runTest {
        // A start that names no mix carries ads; the job patches ads-only.
        assertEquals("ads", capabilitiesExtra(PatchService.DEFAULT_CAPABILITIES))
        assertEquals(setOf(Capability.ADS), capabilitiesFromExtra("ads"))

        val f = fixture()
        f.records.put(f.record())

        assertEquals(
            GenericOutcome.Built,
            f.run(f.job(requested = capabilitiesFromExtra(capabilitiesExtra(PatchService.DEFAULT_CAPABILITIES)))),
        )

        assertEquals(setOf(Capability.ADS), f.requests.single().capabilities)
        assertEquals(listOf("ads"), f.records.get(PKG)!!.capabilities)
    }

    @Test
    fun unknownOnlyExtraRefuses() = runTest {
        val f = fixture()
        f.records.put(f.record())
        // The engine's own first statement (GenericPatchEngine.run): an empty set is refused here,
        // never duplicated in the job.
        f.engineAction = { request, signer ->
            if (request.capabilities.isEmpty()) throw PatchException.Unsupported("nothing to patch")
            f.buildOutputs(request, signer)
        }

        val outcome = f.run(f.job(requested = capabilitiesFromExtra("surprise")))

        assertEquals(failed(GenericFailure.FAILED), outcome)
        assertEquals(
            "Patching failed. Nothing was removed. Try again, or send us a report.",
            (outcome as GenericOutcome.Failed).message,
        )
        // The empty request reached the engine; nothing was built.
        assertEquals(emptySet<Capability>(), f.requests.single().capabilities)
        assertTrue("engine" in f.events)
        assertEquals(emptyList<SavedApk>(), f.records.get(PKG)!!.built)
        assertFalse(f.builtDir.exists())
        f.assertNoTemporaryFiles()
    }

    @Test
    fun recordCarriesAndResolves() = runTest {
        // The confirmed mix is the record's from the begin of a PATCH.
        val f = fixture()
        f.records.put(f.record())
        assertEquals(GenericOutcome.Built, f.run(f.job(requested = setOf(Capability.ADS, Capability.IAP))))
        assertEquals(listOf("ads", "iap"), f.records.get(PKG)!!.capabilities)
        assertEquals(setOf(Capability.ADS, Capability.IAP), f.requests.single().capabilities)

        // A record from before choices reads the installed patched copy's patch-info.
        val g = fixture()
        g.records.put(g.record(intent = GenericIntent.REFRESH, patchedBundleId = "old-bundle"))
        g.installedApp = g.patchedZipCopy(capabilities = listOf("iap"))
        assertEquals(GenericOutcome.Built, g.run())
        assertEquals(setOf(Capability.IAP), g.requests.single().capabilities)
        assertEquals(listOf("iap"), g.records.get(PKG)!!.capabilities)

        // A pre-options patch-info (no capabilities key) reads ads, the engine's read-default.
        val h = fixture()
        h.records.put(h.record(intent = GenericIntent.REFRESH, patchedBundleId = "old-bundle"))
        h.installedApp = h.patchedZipCopy(capabilities = null)
        assertEquals(GenericOutcome.Built, h.run())
        assertEquals(setOf(Capability.ADS), h.requests.single().capabilities)
        assertEquals(listOf("ads"), h.records.get(PKG)!!.capabilities)

        // Neither a record field nor an install: ads-only, here a resumed PATCH whose app is gone.
        val i = fixture()
        i.records.put(i.record(original = i.savedByEarlierRun()))
        i.installedApp = null
        assertEquals(GenericOutcome.Built, i.run())
        assertEquals(setOf(Capability.ADS), i.requests.single().capabilities)
        assertEquals(listOf("ads"), i.records.get(PKG)!!.capabilities)
    }

    @Test
    fun refreshResolvesFromTheRecord() = runTest {
        val f = fixture()
        f.records.put(
            f.record(intent = GenericIntent.REFRESH, patchedBundleId = "old-bundle").copy(capabilities = listOf("iap")),
        )
        f.installedApp = f.patchedCopy()

        // The start extra says ads (the default), but a Refresh keeps what the record says.
        assertEquals(GenericOutcome.Built, f.run(f.job(requested = setOf(Capability.ADS))))

        assertEquals(setOf(Capability.IAP), f.requests.single().capabilities)
        assertEquals(listOf("iap"), f.records.get(PKG)!!.capabilities)
    }

    @Test
    fun outcomes_map_onto_patch_states() {
        val built = File("/files/generic/$PKG/built")
        assertEquals(PatchState.Ready(built, VERSION_NAME), genericPatchState(GenericOutcome.Built, LABEL, built, VERSION_NAME))

        // The failure, and whether running the job again can help before the customer changes something.
        val expected = mapOf(
            GenericFailure.SIGNED_OUT to (PatchFailure.SIGNED_OUT to true),
            GenericFailure.ANDROID_TOO_OLD to (PatchFailure.ANDROID_TOO_OLD to true),
            GenericFailure.NEEDS_SUBSCRIPTION to (PatchFailure.NEEDS_SUBSCRIPTION to true),
            GenericFailure.NOT_AVAILABLE to (PatchFailure.NOT_SUPPORTED_YET to true),
            GenericFailure.STORE_UPDATE_REQUIRED to (PatchFailure.STORE_UPDATE_REQUIRED to true),
            GenericFailure.NOT_ENOUGH_SPACE to (PatchFailure.NOT_ENOUGH_SPACE to true),
            GenericFailure.NETWORK to (PatchFailure.NETWORK to true),
            GenericFailure.CHANGED to (PatchFailure.FAILED to false),
            GenericFailure.NOT_OUR_COPY to (PatchFailure.FAILED to false),
            GenericFailure.DAMAGED_ORIGINAL to (PatchFailure.FAILED to false),
            GenericFailure.DAMAGED_ORIGINAL_APP_REMOVED to (PatchFailure.FAILED to false),
            GenericFailure.FAILED to (PatchFailure.FAILED to true),
        )
        assertEquals(GenericFailure.entries.toSet(), expected.keys)
        for ((failure, mapped) in expected) {
            val (patchFailure, retryHelps) = mapped
            val outcome = GenericOutcome.Failed(failure, failure.message(LABEL, 1L shl 20), bytesNeeded = 1L shl 20)
            assertEquals(
                "$failure",
                PatchState.Failed(patchFailure, outcome.message, retryHelps),
                genericPatchState(outcome, LABEL, built, VERSION_NAME),
            )
        }

        // The engine's refusal is the app's, every time.
        assertEquals(
            PatchState.Failed(
                PatchFailure.FAILED,
                "Example can't be patched: It uses Google's anti-tamper check (PairIP), so a patched copy won't start. " +
                    "It's in our store. Nothing was removed.",
                retryHelps = false,
            ),
            genericPatchState(GenericOutcome.Unpatchable(listOf(CantPatch.PAIRIP, CantPatch.IN_OUR_STORE)), LABEL, built, VERSION_NAME),
        )
    }

    private companion object {
        const val PKG = "com.example.app"
        const val LABEL = "Example"
        const val VERSION_NAME = "1.0"
        const val VERSION_CODE = 7L
        const val PATCHER_VERSION = "1.0.0"
        const val TOKEN = "generic-paywall-token-secret"
        const val PASSWORD = "keystore-password-secret"
        const val EXPIRES_AT = 1_800_000_000_000L
        const val BASE = "base.apk"
        const val SPLIT = "split_config.arm64_v8a.apk"
        val USER_CERT = "a".repeat(64)
        val DEV_CERT = "d".repeat(64)
        val OTHER_CERT = "9".repeat(64)
        val ABIS = listOf("arm64-v8a", "armeabi-v7a")
        val STORE_PACKAGES = setOf("com.whatsapp", "il.co.mako")
        val BASE_BYTES = "the installed base apk".toByteArray()
        val SPLIT_BYTES = "the installed arm64 split".toByteArray()
        val PATCHED = "+patched".toByteArray()
        val SIGNED = "+signed".toByteArray()
        val BUILT_BASE = BASE_BYTES + PATCHED + SIGNED
        val BUILT_SPLIT = SPLIT_BYTES + SIGNED
        val FACTS = ApkFacts(PKG, VERSION_CODE, VERSION_NAME, 34, emptySet(), LibTarget("arm64-v8a", "lib/arm64-v8a"), emptyList())
        val INFO = GenericPatchInfo(
            GenericPatchInfo.KIND, PATCHER_VERSION, "f".repeat(64), 1, "arm64-v8a", "lib/arm64-v8a", "AppLovin",
            listOf(GenericPatchInfo.ENTRY),
        )

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
