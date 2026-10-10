import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {Link, useNavigate, useSearchParams} from 'react-router';
import {api, ApiError, isEmailUnverified, isSubscriptionActive} from './lib/api.js';
import {useAuth} from './lib/auth.jsx';
import {formatDate} from './lib/format.js';
import {formatPrice, usePrice} from './lib/price.js';
import {loginPath} from './lib/redirect.js';
import {MAX_DEVICES} from './lib/site.js';
import ChangePasswordCard from './ChangePasswordCard.jsx';
import DevicesCard from './DevicesCard.jsx';
import LegalConsent from './LegalConsent.jsx';
import PageTitle from './PageTitle.jsx';
import ResendVerification from './ResendVerification.jsx';
import './Subscription.css';

/* The backend maps this price back to the "pro" plan in payment.py. */
const PRO_PRICE_ID = 'price_1Sw7fiIVyiVj53XrmnQKBjUb';

/*
 * Every claim here has to be true of the product. The Free card used to
 * promise "Free patched releases as they ship", but every download requires a
 * subscription; Pro promised "Automatic updates", but only the store app
 * updates itself - patched apps are re-downloaded by hand. The price is not
 * here at all: it comes from Stripe (usePrice), never from a literal.
 */
const pricingPlans = [
    {
        id: 'free',
        name: 'Free',
        tag: 'Starter',
        priceId: null,
        features: [
            {text: 'Browse every app and build'},
            {text: 'The Patched Store app for Android'},
            {text: 'Downloading patched builds', excluded: true},
        ],
    },
    {
        id: 'pro',
        name: 'Pro',
        tag: 'Full access',
        priceId: PRO_PRICE_ID,
        features: [
            {text: 'Download every patched app'},
            {text: 'New patched versions as they ship'},
            {text: 'The store app keeps itself up to date'},
            {text: `Up to ${MAX_DEVICES} devices`},
            {text: 'Cancel anytime'},
        ],
        highlighted: true,
    },
];

/* Stripe statuses we may be handed. Anything unknown falls back to a neutral
   pill instead of silently rendering an unstyled class. */
const STATUS_META = {
    active: {label: 'Active', tone: 'ok'},
    trialing: {label: 'Trialing', tone: 'ok'},
    past_due: {label: 'Payment overdue', tone: 'warn'},
    incomplete: {label: 'Incomplete', tone: 'warn'},
    paused: {label: 'Paused', tone: 'warn'},
    unpaid: {label: 'Unpaid', tone: 'danger'},
    canceled: {label: 'Cancelled', tone: 'danger'},
    incomplete_expired: {label: 'Expired', tone: 'danger'},
};

/* Statuses where the subscription no longer grants anything, so the account is
   back on the Free plan. */
const ENDED_STATUSES = ['canceled', 'incomplete_expired'];

function statusMeta(status) {
    if (STATUS_META[status]) return STATUS_META[status];
    const label = typeof status === 'string' && status ? status.replace(/_/g, ' ') : 'Unknown';
    return {label, tone: 'neutral'};
}

function Subscription() {
    const {user, loading: authLoading, refresh, verificationSent} = useAuth();
    const navigate = useNavigate();
    const [searchParams, setSearchParams] = useSearchParams();

    const [subscription, setSubscription] = useState(null);
    const [loadedOnce, setLoadedOnce] = useState(false);
    const [pendingAction, setPendingAction] = useState(null);
    const [error, setError] = useState(null);
    const [success, setSuccess] = useState(null);
    const [notice, setNotice] = useState(null);
    const [confirmingCancel, setConfirmingCancel] = useState(false);
    const price = usePrice();

    const isAuthed = Boolean(user);
    const actionLoading = pendingAction !== null;
    // The server refuses checkout (403) until the address is verified; see the Pro card.
    const unverified = isEmailUnverified(user);

    /* Both latch once per visit; see the silent restore and the checkout
       confirmation below. */
    const restoreTriedRef = useRef(false);
    const confirmFiredRef = useRef(false);

    /*
     * Silent restore. If Stripe's success redirect never reached us (the tab
     * was closed, the network dropped on the way back), the payment went
     * through but confirm_success never ran, so the subscription is not
     * linked to this account and the page offers to sell it again. Calling
     * confirm_success once, quietly, links it. For everyone who simply has
     * not subscribed it 404s, which is expected, so every failure here is
     * swallowed and the page just shows "no subscription" as before.
     */
    const quietRestore = useCallback(async () => {
        try {
            await api.confirmSuccess();
        } catch {
            return null;
        }
        // The confirm refreshes the session cookie to carry the customer id.
        await refresh();
        const again = await api.getSubscription();
        return again?.subscription ?? null;
    }, [refresh]);

    const loadSubscription = useCallback(async () => {
        if (!isAuthed) {
            setSubscription(null);
            setLoadedOnce(true);
            return;
        }
        try {
            const data = await api.getSubscription();
            let current = data?.subscription ?? null;
            // Not while ?success=true is being confirmed: that flow runs the
            // same call itself and reports its own result.
            if (current === null && !restoreTriedRef.current && !confirmFiredRef.current) {
                restoreTriedRef.current = true;
                current = await quietRestore();
                if (current) setSuccess('We found a subscription paid from this account and linked it.');
            }
            setSubscription(current);
            setError(null);
        } catch (err) {
            setSubscription(null);
            setError(err.message || 'Could not load your subscription.');
        } finally {
            setLoadedOnce(true);
        }
    }, [isAuthed, quietRestore]);

    useEffect(() => {
        if (authLoading) return;
        loadSubscription();
    }, [authLoading, loadSubscription]);

    const stripParam = useCallback(
        (name) => {
            setSearchParams(
                (prev) => {
                    const next = new URLSearchParams(prev);
                    next.delete(name);
                    return next;
                },
                {replace: true},
            );
        },
        [setSearchParams],
    );

    const confirmCheckout = useCallback(async () => {
        setPendingAction('confirm');
        setError(null);
        try {
            await api.confirmSuccess();
            setSuccess('Subscription confirmed. Thanks for subscribing!');
            await refresh();
            await loadSubscription();
        } catch (err) {
            setError(err.message || 'We could not confirm your subscription.');
        } finally {
            setPendingAction(null);
        }
    }, [refresh, loadSubscription]);

    /*
     * Stripe's success_url is `{FRONTEND_URL}/subscription?success=true`. This
     * used to live in the component body, so every response re-rendered the
     * page and POSTed again in an unbounded loop. The ref latches
     * synchronously before any await, and the flag is dropped from the URL
     * immediately, so the POST happens at most once per landing.
     * (confirmFiredRef is declared above, beside the silent restore it gates.)
     */
    const checkoutSucceeded = searchParams.get('success') === 'true';
    const checkoutCanceled = searchParams.get('canceled') === 'true';

    useEffect(() => {
        if (!checkoutSucceeded || authLoading || confirmFiredRef.current) return;
        confirmFiredRef.current = true;
        stripParam('success');
        if (!isAuthed) {
            setError('Your payment went through, but your session has expired. Log in again to activate it.');
            return;
        }
        confirmCheckout();
    }, [checkoutSucceeded, authLoading, isAuthed, confirmCheckout, stripParam]);

    useEffect(() => {
        if (!checkoutCanceled) return;
        setNotice('Checkout cancelled. You have not been charged.');
        stripParam('canceled');
    }, [checkoutCanceled, stripParam]);

    async function handleSubscribe(plan) {
        if (!isAuthed) {
            navigate(loginPath('/subscription'));
            return;
        }
        if (!plan.priceId) return;

        setPendingAction('checkout');
        setError(null);
        setNotice(null);
        try {
            const data = await api.createCheckout(plan.priceId);
            if (!data?.url) throw new Error('Checkout is unavailable right now. Please try again.');
            /* Leaving the SPA for Stripe, so the button stays busy on purpose. */
            window.location.href = data.url;
        } catch (err) {
            if (err instanceof ApiError && err.status === 409) {
                /*
                 * The server refuses a second checkout when this account's
                 * Stripe customer already has a live subscription, linking it
                 * first if it was not. confirm_success covers a server that
                 * did not; its 404 (or any failure) is ignored because the
                 * reload below shows the real state either way.
                 */
                try {
                    await api.confirmSuccess();
                } catch {
                    // already linked, or nothing to link
                }
                await refresh();
                await loadSubscription();
                setNotice('You already have an active subscription.');
                setPendingAction(null);
                return;
            }
            if (err instanceof ApiError && err.status === 403) {
                /*
                 * Unverified, by the server's reckoning, which reads the
                 * database rather than this page's copy of the user (a tab
                 * left open from before, say). Its detail says what to do;
                 * reloading the user brings up the verify prompt on the card.
                 */
                setError(err.message || 'Verify your email address before subscribing.');
                setPendingAction(null);
                await refresh();
                return;
            }
            setError(err.message || 'Could not start checkout.');
            setPendingAction(null);
        }
    }

    async function handleManageBilling() {
        setPendingAction('portal');
        setError(null);
        try {
            const data = await api.billingPortal();
            if (!data?.url) throw new Error('The billing portal is unavailable right now. Please try again.');
            window.location.href = data.url;
        } catch (err) {
            setError(err.message || 'Could not open the billing portal.');
            setPendingAction(null);
        }
    }

    async function handleCancelSubscription() {
        setPendingAction('cancel');
        setError(null);
        try {
            await api.cancelSubscription();
            setConfirmingCancel(false);
            setSuccess('Subscription cancelled. You keep access until the end of the billing period.');
            await loadSubscription();
        } catch (err) {
            setError(err.message || 'Could not cancel your subscription.');
        } finally {
            setPendingAction(null);
        }
    }

    async function handleReactivate() {
        setPendingAction('reactivate');
        setError(null);
        try {
            await api.reactivateSubscription();
            setSuccess('Subscription reactivated. Billing continues as normal.');
            await loadSubscription();
        } catch (err) {
            setError(err.message || 'Could not reactivate your subscription.');
        } finally {
            setPendingAction(null);
        }
    }

    /*
     * `null` means "nobody is signed in", which is not the same as being on the
     * Free plan - a logged out visitor must not see a Current Plan badge.
     */
    const currentPlanId = useMemo(() => {
        if (!isAuthed) return null;
        if (!subscription || ENDED_STATUSES.includes(subscription.status)) return 'free';
        return subscription.planId ?? 'pro';
    }, [isAuthed, subscription]);

    const pageLoading = authLoading || !loadedOnce;
    const periodEnd = formatDate(subscription?.currentPeriodEnd);
    const meta = subscription ? statusMeta(subscription.status) : null;
    const showPanel = isAuthed && subscription !== null;
    const needsAttention = subscription?.status === 'past_due' || subscription?.status === 'unpaid';
    // Slots matter while the subscription still grants downloads (past_due included).
    const showDevices = showPanel && !ENDED_STATUSES.includes(subscription.status);

    // `undefined` while loading keeps the row's height; `null` hides the amount.
    const proPrice = formatPrice(price);
    // Free is free in any currency; the Pro price's currency keeps them consistent.
    const freePrice = formatPrice({
        amount: 0,
        currency: price?.currency ?? 'usd',
        interval: price?.interval ?? 'month',
        interval_count: price?.interval_count ?? 1,
    });

    if (pageLoading) {
        return (
            <section className="subscription-page" aria-busy="true">
                <PageTitle title="Subscription" description={`Subscribe to download patched Android builds on up to ${MAX_DEVICES} devices, manage billing in Stripe, and cancel at any time.`}/>
                {/* The prerendered page is this state (the session is unknown at
                    build time), so it carries the same h1 as the loaded page. */}
                <h1 className="subscription-title">Subscription</h1>
                <p className="subscription-loading" role="status">
                    <span className="subscription-spinner" aria-hidden="true" />
                    Loading your subscription...
                </p>
            </section>
        );
    }

    return (
        <section className="subscription-page">
            <PageTitle title="Subscription" description={`Subscribe to download patched Android builds on up to ${MAX_DEVICES} devices, manage billing in Stripe, and cancel at any time.`}/>
            <h1 className="subscription-title">Subscription</h1>
            <p className="subscription-subtitle">
                {isAuthed ? 'Manage your subscription and billing' : 'Choose the plan that fits you'}
            </p>

            <div className="subscription-alerts">
                {pendingAction === 'confirm' && (
                    <p className="alert alert-info" role="status">
                        <span className="subscription-spinner" aria-hidden="true" />
                        Confirming your subscription...
                    </p>
                )}
                {error && (
                    <p className="alert alert-error" role="alert">
                        {error}
                    </p>
                )}
                {success && (
                    <p className="alert alert-success" role="status">
                        {success}
                    </p>
                )}
                {notice && (
                    <p className="alert alert-info" role="status">
                        {notice}
                    </p>
                )}
            </div>

            {showPanel && (
                <div className="subscription-panel">
                    <h2 className="subscription-panel__title">Current plan</h2>
                    <div className="subscription-panel__body">
                        <div className="subscription-summary">
                            <span className="subscription-plan-badge">{subscription.planName || 'Pro'}</span>
                            <p className="subscription-status">
                                Status:{' '}
                                <span className={`subscription-status__pill subscription-status__pill--${meta.tone}`}>
                                    {meta.label}
                                </span>
                            </p>
                            {periodEnd && (
                                <p className="subscription-period">
                                    {subscription.cancelAtPeriodEnd
                                        ? `Access until ${periodEnd}`
                                        : `Next billing ${periodEnd}`}
                                </p>
                            )}
                            {subscription.cancelAtPeriodEnd && (
                                <p className="subscription-hint">
                                    This subscription is set to end. Reactivate to keep it running.
                                </p>
                            )}
                            {needsAttention && (
                                <p className="subscription-hint subscription-hint--warn">
                                    Your last payment did not go through. Update your card in the billing portal to
                                    keep access.
                                </p>
                            )}
                        </div>

                        {confirmingCancel ? (
                            <div className="alert alert-info subscription-confirm" role="status">
                                <p className="subscription-confirm__text">
                                    Cancel your subscription?{' '}
                                    {periodEnd
                                        ? `You keep access until ${periodEnd}.`
                                        : 'You keep access until the end of the current billing period.'}
                                </p>
                                <div className="subscription-confirm__actions">
                                    <button
                                        type="button"
                                        className="subscription-btn subscription-btn--danger"
                                        onClick={handleCancelSubscription}
                                        disabled={actionLoading}
                                        aria-busy={pendingAction === 'cancel'}
                                    >
                                        {pendingAction === 'cancel' && (
                                            <span className="subscription-spinner" aria-hidden="true" />
                                        )}
                                        {pendingAction === 'cancel' ? 'Cancelling...' : 'Yes, cancel it'}
                                    </button>
                                    <button
                                        type="button"
                                        className="subscription-btn"
                                        onClick={() => setConfirmingCancel(false)}
                                        disabled={actionLoading}
                                    >
                                        Keep subscription
                                    </button>
                                </div>
                            </div>
                        ) : (
                            <div className="subscription-actions">
                                <button
                                    type="button"
                                    className="subscription-btn"
                                    onClick={handleManageBilling}
                                    disabled={actionLoading}
                                    aria-busy={pendingAction === 'portal'}
                                >
                                    {pendingAction === 'portal' && (
                                        <span className="subscription-spinner" aria-hidden="true" />
                                    )}
                                    {pendingAction === 'portal' ? 'Opening...' : 'Manage billing'}
                                </button>
                                {subscription.cancelAtPeriodEnd ? (
                                    <button
                                        type="button"
                                        className="main-button"
                                        onClick={handleReactivate}
                                        disabled={actionLoading}
                                        aria-busy={pendingAction === 'reactivate'}
                                    >
                                        {pendingAction === 'reactivate' && (
                                            <span className="subscription-spinner" aria-hidden="true" />
                                        )}
                                        {pendingAction === 'reactivate' ? 'Reactivating...' : 'Reactivate'}
                                    </button>
                                ) : (
                                    isSubscriptionActive(subscription) && (
                                        <button
                                            type="button"
                                            className="subscription-btn subscription-btn--danger"
                                            onClick={() => setConfirmingCancel(true)}
                                            disabled={actionLoading}
                                        >
                                            Cancel subscription
                                        </button>
                                    )
                                )}
                            </div>
                        )}
                    </div>
                </div>
            )}

            {showDevices && <DevicesCard/>}

            {isAuthed && !showPanel && (
                <p className="subscription-empty">
                    {/* On a phone the Pro card and its verify prompt are below the fold. */}
                    {unverified
                        ? 'You do not have a subscription yet. Verify your email address, then pick a plan below.'
                        : 'You do not have a subscription yet. Pick a plan below.'}
                </p>
            )}

            {/* After the "pick a plan below" line, so the plans really are below it. */}
            {isAuthed && <ChangePasswordCard/>}

            <div className="subscription-plans">
                {pricingPlans.map((plan) => {
                    const isCurrentPlan = currentPlanId === plan.id;
                    const isCheckingOut = pendingAction === 'checkout' && plan.priceId !== null;
                    const needsVerifying = unverified && plan.priceId !== null && !isCurrentPlan;

                    let label = 'Subscribe';
                    let onClick = () => handleSubscribe(plan);
                    let disabled = actionLoading;

                    if (isCurrentPlan) {
                        label = 'Current plan';
                        onClick = undefined;
                        disabled = true;
                    } else if (!plan.priceId) {
                        if (isAuthed) {
                            label = 'Included';
                            onClick = undefined;
                            disabled = true;
                        } else {
                            label = 'Create a free account';
                            onClick = () => navigate('/register');
                        }
                    } else if (needsVerifying) {
                        onClick = undefined;
                        disabled = true;
                    } else if (isCheckingOut) {
                        label = 'Redirecting...';
                    }

                    const classes = [
                        'subscription-card',
                        plan.highlighted ? 'subscription-card--highlighted' : '',
                        isCurrentPlan ? 'subscription-card--current' : '',
                    ]
                        .filter(Boolean)
                        .join(' ');

                    return (
                        <article key={plan.id} className={classes}>
                            {isCurrentPlan && <span className="subscription-card__badge">Current plan</span>}
                            <span className="subscription-card__tag">{plan.tag}</span>
                            <h2 className="subscription-card__name">{plan.name}</h2>
                            <PlanPrice
                                value={plan.priceId ? proPrice : freePrice}
                                loading={plan.priceId !== null && price === undefined}
                            />
                            <ul className="subscription-card__features">
                                {plan.features.map((feature) => (
                                    <li
                                        key={feature.text}
                                        className={feature.excluded ? 'subscription-card__feature--excluded' : undefined}
                                    >
                                        {feature.excluded && <span className="visually-hidden">Not included: </span>}
                                        {feature.text}
                                    </li>
                                ))}
                            </ul>
                            <button
                                type="button"
                                className="main-button subscription-card__button"
                                onClick={onClick}
                                disabled={disabled}
                                aria-busy={isCheckingOut}
                                aria-describedby={needsVerifying ? 'subscription-verify-note' : undefined}
                            >
                                {isCheckingOut && <span className="subscription-spinner" aria-hidden="true" />}
                                {label}
                            </button>
                            {needsVerifying && (
                                <div className="subscription-card__verify">
                                    <p id="subscription-verify-note" className="subscription-card__verify-text">
                                        {verificationSent === false ? (
                                            <>Verify your email address to subscribe: send yourself a link
                                                at <strong>{user.email}</strong>.</>
                                        ) : (
                                            <>Verify your email address to subscribe: open the link we sent
                                                to <strong>{user.email}</strong>, or send a new one.</>
                                        )}
                                    </p>
                                    <ResendVerification onVerified={setSuccess}/>
                                </div>
                            )}
                            {plan.priceId && !isCurrentPlan && (
                                <LegalConsent action="subscribing" className="subscription-card__consent"/>
                            )}
                        </article>
                    );
                })}
            </div>

            {!isAuthed && (
                <p className="subscription-login-prompt">
                    {/* A router Link: a plain href reloaded the whole bundle. */}
                    <Link to={loginPath('/subscription')}>Log in</Link> to manage your subscription
                </p>
            )}
        </section>
    );
}

/*
 * The amount row of a plan card. While the price loads, an invisible
 * placeholder holds the row's height so the cards do not jump. When Stripe is
 * unavailable the amount is omitted rather than guessed; checkout always
 * shows the real price before anyone pays.
 */
function PlanPrice({value, loading}) {
    if (loading) {
        return (
            <p className="subscription-card__price" aria-hidden="true">
                <span className="subscription-card__amount subscription-card__amount--pending">&nbsp;</span>
            </p>
        );
    }
    if (!value) {
        return (
            <p className="subscription-card__price">
                <span className="subscription-card__period">Price shown at checkout</span>
            </p>
        );
    }
    return (
        <p className="subscription-card__price">
            <span className="subscription-card__amount">{value.amount}</span>
            <span className="subscription-card__period">{value.period}</span>
        </p>
    );
}

export default Subscription;
