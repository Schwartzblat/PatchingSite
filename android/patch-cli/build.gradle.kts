plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Desktop front end for :patch-engine, for patching an APK from a shell.
kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "site.androidmod.patch.cli.MainKt"
}

dependencies {
    implementation(project(":patch-engine"))
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":patch-engine")))
    testImplementation(libs.apksig)
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
