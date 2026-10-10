package site.androidmod.store.ui.billing

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import site.androidmod.store.R
import site.androidmod.store.data.Subscription
import site.androidmod.store.ui.ErrorBanner
import site.androidmod.store.ui.auth.AuthViewModel
import site.androidmod.store.ui.auth.VerificationState
import site.androidmod.store.ui.theme.PapSuccess
import site.androidmod.store.ui.theme.PapWarning

/**
 * [authViewModel] is the activity's, not this tab's: it holds the signed-in user,
 * whose email verification decides whether the verify-your-email card shows.
 */
@Composable
fun SubscriptionScreen(viewModel: SubscriptionViewModel, authViewModel: AuthViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    val needsVerification = authState.user?.needsVerification == true
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(state.openUrl) {
        state.openUrl?.let { url ->
            CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
            viewModel.urlOpened()
        }
    }

    // Returning from the Custom Tab is the only signal that checkout finished.
    // Resuming is also the only sign that the email may have been verified: the
    // link opens in a browser or a mail app, never here. ON_RESUME fires on each
    // visit to this tab as well, so opening Plan re-reads an unverified user.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onReturnedFromCheckout()
                authViewModel.recheckVerification()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Scrolls because the verification card can push the buttons past the bottom
    // bar on a short screen.
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ErrorBanner(state.error)

        // First, above the plan: the server refuses a checkout until the address
        // is verified, so this is the step that comes before Subscribe. Absent for
        // a verified user, and for a server that predates verification.
        if (needsVerification) {
            VerifyEmailCard(
                email = authState.user?.email,
                verification = authState.verification,
                onResend = authViewModel::resendVerification,
            )
        }

        // confirm_success is the only thing in this system that attaches the Stripe
        // customer to the account - there is no webhook - and it legitimately answers
        // 404 while Stripe has not settled yet. Retrying is safe: it is idempotent.
        if (state.confirmationFailed) {
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    onClick = { viewModel.retryConfirmation() },
                    enabled = !state.loading,
                ) { Text("Retry confirmation") }
                OutlinedButton(
                    onClick = { viewModel.abandonCheckout() },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Dismiss") }
            }
        }

        PlanCard(state.subscription, resolved = state.resolved)

        val subscription = state.subscription
        when {
            // Neither "Subscribe" nor "Cancel subscription" is an honest label before
            // the answer is known. Reachable only on a cold start with nothing cached:
            // a fresh install, or the first visit after signing in.
            !state.resolved -> Unit

            // Kept, and kept enabled, while unverified - the app's copy of the user
            // can be behind a link just opened elsewhere, and the server has the
            // final say (a 403 whose message lands in the banner above). Outlined
            // then, so the card's Resend is the one filled button on the screen.
            subscription == null || !subscription.isActive ->
                if (needsVerification) {
                    OutlinedButton(
                        onClick = { viewModel.subscribe() },
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Subscribe") }
                } else {
                    Button(
                        onClick = { viewModel.subscribe() },
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Subscribe") }
                }

            subscription.cancelAtPeriodEnd ->
                Button(
                    onClick = { viewModel.reactivate() },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Reactivate") }

            else ->
                OutlinedButton(
                    onClick = { viewModel.cancel() },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel subscription") }
        }

        OutlinedButton(
            onClick = { viewModel.openBillingPortal() },
            enabled = !state.loading && state.subscription != null,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text("Manage billing") }
    }
}

/**
 * "Verify your email to subscribe", for a signed-in user whose address is not
 * verified. The wording does not claim a link was sent: registration may have
 * found email unavailable, and "send a new link" covers that case too. Resend's
 * answers - sent, too many, email unavailable - appear inside the card, next to
 * the button that asked.
 */
@Composable
private fun VerifyEmailCard(email: String?, verification: VerificationState, onResend: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_mail),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    "Verify your email to subscribe",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(
                buildAnnotatedString {
                    append("Open the link we email to ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) {
                        append(email ?: "your address")
                    }
                    append(" to verify it. Can't find one? Check your spam folder, or send a new link.")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
            verification.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (verification.failed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Button(
                onClick = onResend,
                enabled = !verification.sending,
                modifier = Modifier.padding(top = 16.dp),
            ) { Text(if (verification.sending) "Sending…" else "Resend link") }
        }
    }
}

@Composable
private fun PlanCard(subscription: Subscription?, resolved: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_plan),
                    contentDescription = null,
                    tint = if (subscription?.isActive == true) PapSuccess
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    // "Free" is a claim about the account, so it waits for an answer.
                    // Drawing it while the status was still unknown is what made the
                    // card flash Free at subscribers on the way to Pro.
                    text = when {
                        !resolved -> "Your plan"
                        subscription == null -> "Free"
                        else -> subscription.planName ?: "Pro"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            if (!resolved) {
                Text(
                    "Checking your subscription…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (subscription == null) {
                Text(
                    "Downloads need an active subscription.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                DetailRow("Status", subscription.status.replaceFirstChar { it.uppercase() })
                subscription.currentPeriodEnd?.let {
                    DetailRow(
                        label = if (subscription.cancelAtPeriodEnd) "Ends" else "Renews",
                        value = it,
                        tint = if (subscription.cancelAtPeriodEnd) PapWarning else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, tint: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = tint ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}
