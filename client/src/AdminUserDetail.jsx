import {useCallback, useEffect, useState} from 'react';
import {api} from './lib/api.js';
import {formatDate, formatUtcDate} from './lib/format.js';
import {DeleteAction, DevicesAction, EmailAction} from './AdminActions.jsx';
import AdminTrialForm from './AdminTrialForm.jsx';

/* The pill colour for a Stripe status; anything unlisted is neutral. */
const STATUS_TONE = {
    active: 'ok',
    trialing: 'ok',
    past_due: 'warn',
    unpaid: 'warn',
    incomplete: 'warn',
    paused: 'warn',
    canceled: 'danger',
    incomplete_expired: 'danger',
};

function AccountCard({user}) {
    return (
        <section className="subscription-panel" aria-labelledby="admin-account-title">
            <h2 id="admin-account-title" className="subscription-panel__title">
                {user.username}
                {user.is_admin && <span className="admin-badge">admin</span>}
            </h2>
            <dl className="admin-facts">
                <dt>Email</dt>
                <dd>
                    {user.email}{' '}
                    <span className={`subscription-status__pill subscription-status__pill--${user.email_verified ? 'ok' : 'warn'}`}>
                        {user.email_verified ? 'verified' : 'unverified'}
                    </span>
                </dd>
                <dt>Created</dt>
                <dd>{formatDate(user.created_at) ?? '—'}</dd>
                <dt>Devices</dt>
                <dd>{user.devices_used} of {user.devices_limit} used</dd>
                <dt>Own reset</dt>
                <dd>{formatDate(user.devices_reset_at) ?? 'never'}</dd>
            </dl>
        </section>
    );
}

function SubscriptionCard({subscription, stripe}) {
    let body;
    if (stripe.error) {
        body = <p className="alert alert-error" role="alert">Stripe is unavailable: {stripe.error}</p>;
    } else if (!stripe.customer_id) {
        body = <p className="subscription-hint">No linked Stripe customer.</p>;
    } else if (stripe.customer_missing) {
        body = <p className="subscription-hint subscription-hint--warn">The linked customer no longer exists on Stripe.</p>;
    } else if (!subscription) {
        body = <p className="subscription-hint">No subscription to this site's product.</p>;
    } else {
        const status = String(subscription.status ?? 'unknown');
        body = (
            <dl className="admin-facts">
                <dt>Status</dt>
                <dd>
                    <span className={`subscription-status__pill subscription-status__pill--${STATUS_TONE[status] ?? 'neutral'}`}>
                        {status.replace(/_/g, ' ')}
                    </span>
                </dd>
                {subscription.trial_end && (
                    <>
                        <dt>Trial ends</dt>
                        <dd>{formatUtcDate(subscription.trial_end)}</dd>
                    </>
                )}
                {subscription.current_period_end && (
                    <>
                        <dt>Period ends</dt>
                        <dd>{formatUtcDate(subscription.current_period_end)}</dd>
                    </>
                )}
                {subscription.cancel_at_period_end && (
                    <>
                        <dt>Cancels</dt>
                        <dd>at the end of the period</dd>
                    </>
                )}
                {subscription.granted_by && (
                    <>
                        <dt>Granted</dt>
                        <dd>
                            <span className="admin-badge">free trial</span> by {subscription.granted_by}
                        </dd>
                    </>
                )}
            </dl>
        );
    }

    return (
        <section className="subscription-panel" aria-labelledby="admin-subscription-title">
            <h2 id="admin-subscription-title" className="subscription-panel__title">
                Subscription
                {stripe.test_mode && <span className="admin-badge admin-badge--warn">TEST mode</span>}
            </h2>
            {body}
            {stripe.dashboard_url && (
                <p className="subscription-period">
                    <a href={stripe.dashboard_url} target="_blank" rel="noreferrer">Open in Stripe</a>
                </p>
            )}
        </section>
    );
}

/**
 * One account in the admin panel: what the database knows, what Stripe says
 * about its LINKED customer (the server never links one just because the owner
 * looked), and the support actions. AdminPage keys it on the username, so
 * switching accounts starts from a clean slate.
 */
function AdminUserDetail({username, self, onChanged, onDeleted}) {
    const [detail, setDetail] = useState(undefined);
    const [loadError, setLoadError] = useState(null);

    const load = useCallback(async (signal) => {
        try {
            const data = await api.admin.getUser(username, {signal});
            setDetail(data ?? null);
            setLoadError(null);
        } catch (err) {
            if (err?.name === 'AbortError') return;
            setDetail(null);
            setLoadError(err?.message || 'We could not load this account.');
        }
    }, [username]);

    useEffect(() => {
        const controller = new AbortController();
        // Fetching on mount is the point of this effect; the setState calls are in
        // the async continuation. `load` stays a callback because the action
        // cards reload the account through it after a change.
        // eslint-disable-next-line react-hooks/set-state-in-effect
        load(controller.signal);
        return () => controller.abort();
    }, [load]);

    // After any action: this account again from the server, and its row in the list.
    const changed = useCallback(() => {
        load();
        onChanged();
    }, [load, onChanged]);

    if (detail === undefined) {
        return (
            <p className="subscription-loading" role="status">
                <span className="subscription-spinner" aria-hidden="true"/>
                Loading {username}…
            </p>
        );
    }
    if (!detail) return <p className="alert alert-error" role="alert">{loadError}</p>;

    const {user, subscription, stripe} = detail;
    const isSelf = user.username.toLowerCase() === String(self).toLowerCase();
    return (
        <>
            <AccountCard user={user}/>
            <SubscriptionCard subscription={subscription} stripe={stripe}/>
            <section className="subscription-panel" aria-labelledby="admin-actions-title">
                <h2 id="admin-actions-title" className="subscription-panel__title">Actions</h2>
                <EmailAction user={user} onChanged={changed}/>
                <DevicesAction user={user} onChanged={changed}/>
                <AdminTrialForm user={user} onGranted={changed}/>
            </section>
            {!isSelf && <DeleteAction user={user} onDeleted={onDeleted}/>}
        </>
    );
}

export default AdminUserDetail;
