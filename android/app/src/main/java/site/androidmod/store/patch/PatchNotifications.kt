package site.androidmod.store.patch

import site.androidmod.store.download.NotificationText

/** The words on PatchService's notifications, apart from the service so they are testable on the JVM. */
internal object PatchNotifications {

    fun progressText(name: String, stage: PatchStage): String = when (stage) {
        PatchStage.CheckingVersion -> "Checking version…"
        is PatchStage.Downloading ->
            if (stage.total > 0) "Downloading $name ${(stage.bytesRead * 100 / stage.total).coerceIn(0, 100)}%"
            else "Downloading $name…"
        is PatchStage.SavingOriginal -> "Saving the original…"
        PatchStage.Patching -> "Patching…"
        PatchStage.Signing -> "Signing…"
    }

    /** Shown when Android wants the user's OK for an update; its content intent is the system's confirm dialog. */
    fun installText(name: String, versionName: String): NotificationText =
        NotificationText("Tap to install $name $versionName", "Android needs your OK to install the update.")

    /** Shown when a build waits for the user's go-ahead; its content intent opens the app. */
    @Suppress("UNUSED_PARAMETER")
    fun confirmText(name: String): NotificationText =
        NotificationText("Some features are off in this version", "Open the app to continue or cancel.")
}
