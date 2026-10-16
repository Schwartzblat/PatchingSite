plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// The on-device patcher engine: pure JVM (no Android APIs) so it runs inside :app and
// on a desktop. Real-data tests read fixtures from the monorepo and skip when absent.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.commons.compress)
    implementation(libs.arsclib)
    implementation(libs.apksig)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    systemProperty("patch.fixtures", rootProject.file("../../patchers/WhatsAppPatcher").path)
    systemProperty(
        "patch.bundleKey",
        "${System.getProperty("user.home")}/.config/androidmod/bundle-signing.pub.pem",
    )
    systemProperty(
        "patch.fastsigner",
        rootProject.file("../../utils/Stitch/src/stitch/bin/fastsigner").path,
    )
}
