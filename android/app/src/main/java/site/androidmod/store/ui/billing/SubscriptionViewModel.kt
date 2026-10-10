package site.androidmod.store.ui.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import site.androidmod.store.Container
import site.androidmod.store.StoreApp
import site.androidmod.store.data.ApiException
import site.androidmod.store.data.SessionRestore
import site.androidmod.store.data.Subscription
import site.androidmod.store.data.isAuth

data class SubscriptionState(
    val subscription: Subscription? = null,
    /**
     * False until the first answer lands, cached or fetched.
     *
     * Without it the card cannot tell "no subscription" from "not known yet", and
     * it drew the two the same way - as "Free". CatalogState.subscribed solves the
     * same problem for the paywall banner by being nullable.
     */
    val resolved: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val openUrl: String? = null,
    val awaitingCheckout: Boolean = false,
    /** Drives the Retry action; confirm_success is idempotent, so retrying is safe. */
    val confirmationFailed: Boolean = false,
)

class SubscriptionViewModel(
    private val container: Container = StoreApp.instance.container,
) : ViewModel() {

    // Seeded from disk, not from a default, for two separate reasons. The cached
    // subscription is what this screen paints while the network answers, so a
    // subscriber is not told "Free" on every visit to the tab - the view model is
    // nav-scoped and rebuilt each time. awaitingCheckout is the other: a checkout
    // the user started before the process was killed still has to be confirmed.
    private val _state = MutableStateFlow(
        container.billing.cached().let { cached ->
            SubscriptionState(
                subscription = cached?.subscription,
                resolved = cached != null,
                awaitingCheckout = container.settings.awaitingCheckout,
            )
        }
    )
    val state: StateFlow<SubscriptionState> = _state.asStateFlow()

    /** The in-flight confirmation, so ON_RESUME cannot start a second one. */
    private var confirmJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            _state.value = try {
                _state.value.copy(
                    subscription = container.billing.status(),
                    resolved = true,
                    loading = false,
                )
            } catch (error: ApiException) {
                failed(error)
            }
        }
    }

    /**
     * Every failure on this screen lands here, so a 401 is handled in one place.
     * An expired session is not something the subscription screen can fix - it has
     * to go back to the login form, which the nav shell does as soon as the auth
     * state clears.
     */
    private fun failed(error: ApiException): SubscriptionState {
        if (error.isAuth) container.auth.expireSession()
        return _state.value.copy(loading = false, error = error.message)
    }

    fun subscribe() = openExternally(awaitingCheckout = true) { container.billing.checkoutUrl() }

    fun openBillingPortal() = openExternally(awaitingCheckout = false) { container.billing.billingPortalUrl() }

    fun cancel() = act { container.billing.cancel() }

    fun reactivate() = act { container.billing.reactivate() }

    /**
     * Called when the user comes back from the Custom Tab.
     *
     * Stripe redirects to a web page, not into the app, so returning is the only
     * signal there is. `confirm_success` attaches the customer and `restoreSession`
     * re-mints the token, without which every later request still claims no customer.
     */
    fun onReturnedFromCheckout() {
        if (!_state.value.awaitingCheckout) return
        confirm()
    }

    /** The Retry action. Safe to call repeatedly: confirm_success is idempotent. */
    fun retryConfirmation() = confirm()

    /**
     * "I did not actually subscribe." Stops the pending confirmation from being
     * retried on every return to this screen; the user can start checkout again.
     */
    fun abandonCheckout() {
        container.settings.awaitingCheckout = false
        _state.value = _state.value.copy(awaitingCheckout = false, confirmationFailed = false, error = null)
    }

    /**
     * The whole post-checkout completion, in one place.
     *
     * Both halves used to be `runCatching` with no else branch, which discarded
     * everything. confirm_success legitimately answers 404 while Stripe has not
     * settled yet, and there is no webhook in this backend - it is the ONLY writer
     * of customer_id - so a silently swallowed failure leaves a paying user with a
     * free account and no way to fix it from inside the app. The failure is now
     * surfaced with a Retry, and `awaitingCheckout` stays set (on disk) so the next
     * return to this screen tries again by itself.
     */
    private fun confirm() {
        if (confirmJob?.isActive == true) return
        _state.value = _state.value.copy(loading = true, error = null, confirmationFailed = false)
        confirmJob = viewModelScope.launch {
            try {
                container.billing.confirmSuccess()
            } catch (error: ApiException) {
                if (error.isAuth) container.auth.expireSession()
                _state.value = _state.value.copy(
                    loading = false, error = error.message, confirmationFailed = true,
                )
                return@launch
            }
            // Only now is the payment definitely attached to this account.
            container.settings.awaitingCheckout = false
            _state.value = _state.value.copy(awaitingCheckout = false)
            // customer_id is a JWT claim, so the token has to be re-minted for the
            // rest of the app to see it. A failure here is not fatal and not worth
            // a banner: restoreSession runs again at every launch. A refused session
            // is the exception - restoreSession has already dropped the token, so
            // send the app to the login form now rather than on the next request.
            val restored = runCatching { container.auth.restoreSession() }.getOrNull()
            if (restored == SessionRestore.Expired) container.auth.expireSession()
            refresh()
        }
    }

    fun urlOpened() {
        _state.value = _state.value.copy(openUrl = null)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun openExternally(awaitingCheckout: Boolean, url: suspend () -> String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val target = url()
                // Persisted BEFORE the Custom Tab opens: from here on the Activity
                // may be destroyed at any moment and the flag has to outlive it.
                if (awaitingCheckout) container.settings.awaitingCheckout = true
                _state.value = _state.value.copy(
                    loading = false,
                    openUrl = target,
                    awaitingCheckout = awaitingCheckout || _state.value.awaitingCheckout,
                )
            } catch (error: ApiException) {
                _state.value = failed(error)
            }
        }
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                block()
                refresh()
            } catch (error: ApiException) {
                _state.value = failed(error)
            }
        }
    }
}
