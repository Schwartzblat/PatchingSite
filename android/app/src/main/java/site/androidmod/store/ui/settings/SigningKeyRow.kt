package site.androidmod.store.ui.settings

import site.androidmod.store.move.WHATSAPP_PACKAGE

/*
 * Settings' "Signing key SHA-1" row, decided apart from the screen so the rules are
 * testable on the JVM.
 */

/**
 * Whether Settings shows (and fetches) the account's signing key: only once WhatsApp is
 * patched on this phone. Before that every WhatsApp from the store is signed with the shared
 * key, and a per-account SHA-1 would set Google Drive backup up for a key nothing uses.
 */
internal fun showsSigningKey(onDevicePackages: Set<String>): Boolean = WHATSAPP_PACKAGE in onDevicePackages

/**
 * The line under the SHA-1. [installedSigner] is the installed WhatsApp's certificate
 * SHA-256, null when none is installed (the next one the store builds is signed with the
 * key). A WhatsApp on any other key - the shared one, or the regular app - only uses this
 * key once it has moved.
 */
internal fun signingKeyHint(installedSigner: String?, keySha256: String): String =
    if (installedSigner == null || installedSigner.equals(keySha256, ignoreCase = true)) {
        "Needed for Google Drive backup setup."
    } else {
        "Your WhatsApp will use this key after its one-time move to the new update system."
    }

/** What the row shows: the certificate's SHA-1 as Google's consoles print it, and [hint]. */
internal data class SigningKeyRow(val sha1: String, val hint: String)
