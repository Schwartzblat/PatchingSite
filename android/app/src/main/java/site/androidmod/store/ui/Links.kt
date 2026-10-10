package site.androidmod.store.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/**
 * Pages on the website, and the support address.
 *
 * Always the public site, never Settings' debug base URL: that points at a dev
 * API server, and uvicorn does not serve the website - nginx does, in production
 * only. The legal pages a debug build links to are the real ones either way.
 */
object SiteLinks {
    const val SITE = "https://androidmod.site"
    const val SUPPORT_EMAIL = "sudorm.ext@gmail.com"

    const val TERMS = "$SITE/terms"
    const val PRIVACY = "$SITE/privacy"
    const val REFUNDS = "$SITE/refunds"
    const val INSTALL_HELP = "$SITE/install"
    const val FORGOT_PASSWORD = "$SITE/forgot-password"

    /** Where a user sees and resets the devices their apps are registered on. */
    const val MANAGE_DEVICES = "$SITE/subscription"
}

/**
 * Opens [url] in a Custom Tab: in-app, but the site's own page with the browser's
 * cookies, so pages that need a web sign-in (Manage devices) get one. With no
 * browser at all there is nothing to open it in, which is said rather than thrown.
 */
fun openLink(context: Context, url: String) {
    try {
        CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
    } catch (error: ActivityNotFoundException) {
        Toast.makeText(context, "No browser is installed to open $url", Toast.LENGTH_LONG).show()
    }
}

/**
 * Starts an email to support. A phone with no mail app gets the address on the
 * clipboard and is told so, rather than a button that silently does nothing.
 * [body] is prefilled - the app version and device, which support needs anyway.
 * [subject] lets a specific flow, such as the WhatsApp chat move, be told apart
 * in the inbox at a glance.
 */
fun emailSupport(context: Context, body: String, subject: String = "Patched Store support") {
    val intent = Intent(Intent.ACTION_SENDTO, "mailto:${SiteLinks.SUPPORT_EMAIL}".toUri())
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, body)
    try {
        context.startActivity(intent)
    } catch (error: ActivityNotFoundException) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Support email", SiteLinks.SUPPORT_EMAIL))
        Toast.makeText(
            context,
            "No email app found. ${SiteLinks.SUPPORT_EMAIL} was copied to the clipboard.",
            Toast.LENGTH_LONG,
        ).show()
    }
}
