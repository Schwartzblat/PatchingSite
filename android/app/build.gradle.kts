import java.time.Duration
import java.time.Instant

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// --- signing -----------------------------------------------------------------
//
// The store app and every build the server hands out must carry the SAME signing
// certificate. SignatureVerifier refuses to install an APK whose signer differs
// from this app's own, so a mismatch here does not merely weaken the store - it
// breaks every download.
//
// The server reads its keystore from KEYSTORE_PATH / KEY_ALIAS /
// KEYSTORE_PASSWORD / KEY_PASSWORD (server/utils/apps_utils.py), so the build
// reads those same four names, with the precedence server/config.py documents:
//
//     process environment  >  ../.dev_env  >  ../.env
//
// Set them once and both halves sign with one key. Leave them unset and the debug
// build falls back to the local Android debug key - fine for UI work, but its own
// downloads will be refused - while assembleRelease fails outright rather than
// quietly emitting an unsigned, untrusted APK.

/**
 * `KEY=value` pairs from the repo-root env files, later file winning.
 *
 * Parsed the way python-dotenv parses them - surrounding quotes dropped, an
 * unquoted ` #` starting a comment - so the build and the server can never
 * disagree about the same password.
 */
val envFiles: Map<String, String> = listOf(".env", ".dev_env")
    .map { rootDir.parentFile.resolve(it) }
    .filter { it.isFile }
    .flatMap { file ->
        file.readLines().mapNotNull { line ->
            val entry = line.trim().removePrefix("export ").trim()
            if (entry.startsWith("#") || "=" !in entry) return@mapNotNull null
            val raw = entry.substringAfter("=").substringBefore(" #").trim()
            entry.substringBefore("=").trim() to
                raw.removeSurrounding("\"").removeSurrounding("'")
        }
    }
    .toMap()

fun signingSecret(name: String): String? =
    (System.getenv(name) ?: envFiles[name])?.takeIf { it.isNotBlank() }

val sharedSigning = "shared"
val keystore = signingSecret("KEYSTORE_PATH")?.let { rootDir.parentFile.resolve(it) }
val keystoreAlias = signingSecret("KEY_ALIAS")
val keystorePassword = signingSecret("KEYSTORE_PASSWORD")
// fastsigner leaves --key-pass optional and reuses the keystore password when
// it is omitted; mirror that, so one variable can cover both.
val keystoreKeyPassword = signingSecret("KEY_PASSWORD") ?: keystorePassword

/** Null once signing is fully configured, otherwise why it is not. */
val signingGap: String? = when {
    keystore == null -> "KEYSTORE_PATH is not set"
    !keystore.isFile -> "KEYSTORE_PATH does not point at a file: $keystore"
    keystoreAlias == null -> "KEY_ALIAS is not set"
    keystorePassword == null -> "KEYSTORE_PASSWORD is not set"
    else -> null
}

android {
    namespace = "site.androidmod.store"

    // 37, not 36: androidx 2026.09 and okhttp-android 5.5.0 both refuse to be
    // compiled against 36. targetSdk stays at 36 - the runtime behaviour we want
    // is Android 16's, and compileSdk moves independently of it.
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "site.androidmod.store"
        minSdk = 30
        targetSdk = 36
        // Debug only; release overrides the code below. versionName is the
        // hand-edited, human-facing number.
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        // Created only when the environment supplies a keystore. Without one, debug
        // keeps the default Android key and release refuses to build at all - see
        // the assembleRelease guard at the foot of this file.
        if (signingGap == null) {
            create(sharedSigning) {
                storeFile = keystore
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // The shared key is applied to debug too, when configured: a locally
            // built store app has to match the artifacts the dev server signs, or
            // SignatureVerifier rejects the app's own downloads. Switching this key
            // means uninstalling any previously installed debug build once, since
            // Android will not replace an app with a differently signed one.
            signingConfigs.findByName(sharedSigning)?.let { signingConfig = it }
            buildConfigField("String", "DEFAULT_BASE_URL", "\"http://10.0.0.20:8000\"")
        }
        release {
            signingConfig = signingConfigs.findByName(sharedSigning)
            // R8 shrinks, optimises and obfuscates release builds; see "Release
            // builds and R8" in android/README.md. Obfuscated stack traces need
            // the build's own app/build/outputs/mapping/release/mapping.txt to be
            // read, and nothing else can reproduce it, so keep it per release.
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("String", "DEFAULT_BASE_URL", "\"https://androidmod.site\"")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release as shipped (minified, shared key, same versionCode rule) but pointed
        // at a dev server; src/staging allows cleartext like debug does.
        create("staging") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            signingConfig = signingConfigs.findByName(sharedSigning)
            val stagingBaseUrl = providers.gradleProperty("androidmod.stagingBaseUrl")
                .getOrElse("http://127.0.0.1:8000")
            buildConfigField("String", "DEFAULT_BASE_URL", "\"$stagingBaseUrl\"")
        }
    }

    packaging {
        // libfastsigner.so is an executable, not a library: it must exist as a real
        // file in nativeLibraryDir to be exec'd, so it cannot stay inside the APK.
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

// --- versioning --------------------------------------------------------------
//
// The store updates itself by comparing its installed versionCode with the one
// the server reads out of the published store.apk, so every release must carry a
// higher code than the last. Stamping it from the clock - minutes since
// 2026-01-01 UTC - makes that automatic: a release built after another is newer,
// with no number to remember to bump. A forgotten manual bump fails silently -
// the server reports the same code and nobody is offered the update.
//
// Release only: a code that changes every minute would invalidate debug's build
// cache on each run. Minutes rather than seconds keep an Int good until the year
// 6000. Read at configuration time, so with Gradle's configuration cache enabled a
// cache hit would reuse the stamp - keep it off, or move this to a ValueSource.
// Imported rather than spelled `java.time.*`: in the Kotlin DSL a bare `java`
// resolves to the project's `java` extension, not the package.
val releaseVersionCode = Duration.between(Instant.parse("2026-01-01T00:00:00Z"), Instant.now())
    .toMinutes().toInt()

androidComponents {
    // Staging gets the same code so it installs over a release store.
    for (buildType in listOf("release", "staging")) {
        onVariants(selector().withBuildType(buildType)) { variant ->
            variant.outputs.forEach { it.versionCode.set(releaseVersionCode) }
        }
    }
}

dependencies {
    implementation(project(":migrate"))
    implementation(project(":patch-engine"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
    implementation(libs.browser)
    implementation(libs.security.crypto)
    implementation(libs.work.runtime)

    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)
}

// AGP will happily emit app-release-unsigned.apk when no signingConfig is set.
// That build installs nowhere and no SignatureVerifier trusts it, so fail the task
// instead of shipping one. Values are read here, at configuration time, so nothing
// reaches back into the project while the task runs.
val releaseSigningGap = signingGap
val envFilePath = rootDir.parentFile.resolve(".env").path
tasks.matching { it.name in setOf("assembleRelease", "bundleRelease", "assembleStaging") }.configureEach {
    doFirst {
        check(releaseSigningGap == null) {
            "A release build must be signed with the same keystore the server signs " +
                "downloads with, or SignatureVerifier refuses every install. " +
                "$releaseSigningGap. Set KEYSTORE_PATH, KEY_ALIAS, KEYSTORE_PASSWORD and " +
                "KEY_PASSWORD in $envFilePath - the same four names " +
                "server/utils/apps_utils.py reads - or in the environment."
        }
    }
}
