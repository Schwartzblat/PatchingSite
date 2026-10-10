pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PatchedStore"
include(":app")
// The WhatsApp chat-move engine, lifted from migrate_app; see "Moving WhatsApp chats"
// in README.md.
include(":migrate")
