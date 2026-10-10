plugins {
    alias(libs.plugins.android.library)
}

// The WhatsApp chat-move engine, lifted from migrate_app (see "Moving WhatsApp
// chats" in ../README.md). Plain Java with no dependencies, on the app's toolchain.
// Its JVM tests came along on 2026-10-10, before the saved copy changed the engine,
// so that change could not move anything they pin unnoticed.
android {
    namespace = "com.patcher.migrate"
    compileSdk { version = release(37) }

    defaultConfig {
        // API 30 is the floor for MANAGE_EXTERNAL_STORAGE and
        // Environment.isExternalStorageManager(), which this library cannot work without.
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}
