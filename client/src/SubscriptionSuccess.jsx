import {useCallback, useEffect, useRef, useState} from 'react';
import {useNavigate} from 'react-router';
import {api} from './lib/api.js';
import {useAuth} from './lib/auth.jsx';
import PageTitle from './PageTitle.jsx';
import './Subscription.css';

/*
 * Stripe's success_url is `{FRONTEND_URL}/subscription?success=true`, which is
 * handled by Subscription.jsx. This route is the secondary landing page (old
 * links, bookmarks) and runs the same confirm_success flow rather than the
 * `/api/subscription/verify` endpoint the server never implemented.
 */
function SubscriptionSuccess() {
    const {user, loading: authLoading, refresh} = useAuth();
    const navigate = useNavigate();
    const [status, setStatus] = useState('pending');
    const [error, setError] = useState(null);

    const confirmCheckout = useCallback(
        () =>
            api
                .confirmSuccess()
                .then(() => refresh())
                .then(() => setStatus('confirmed'))
                .catch((err) => {
                    setError(err.message || 'We could not confirm your subscription.');
                    setStatus('failed');
                }),
        [refresh],
    );

    /* Same latch as Subscription.jsx: one POST per landing, whatever re-renders.
       The route sits inside <RequireAuth>, so this only ever mounts with a
       signed-in user; the guards are for the first render's loading state. A
       "log in to finish" branch used to live here and could never render. */
    const firedRef = useRef(false);

    useEffect(() => {
        if (authLoading || !user || firedRef.current) return;
        firedRef.current = true;
        confirmCheckout();
    }, [authLoading, user, confirmCheckout]);

    function retry() {
        setStatus('pending');
        setError(null);
        confirmCheckout();
    }

    const busy = status === 'pending';

    return (
        <section className="subscription-page subscription-page--narrow">
            <PageTitle title="Confirming your subscription" noindex/>
            <div className="subscription-success" aria-busy={busy}>
                {busy && (
                    <p className="subscription-loading" role="status">
                        <span className="subscription-spinner" aria-hidden="true" />
                        Confirming your subscription...
                    </p>
                )}

                {status === 'confirmed' && (
                    <>
                        <p className="subscription-success__mark subscription-success__mark--ok" aria-hidden="true">
                            &#10003;
                        </p>
                        <h1 className="subscription-success__title">Subscription active</h1>
                        <p className="subscription-success__text" role="status">
                            Thanks for subscribing. Your account has been upgraded.
                        </p>
                        <button
                            type="button"
                            className="main-button"
                            onClick={() => navigate('/subscription')}
                        >
                            View subscription
                        </button>
                    </>
                )}

                {status === 'failed' && (
                    <>
                        <p className="subscription-success__mark subscription-success__mark--error" aria-hidden="true">
                            &#10005;
                        </p>
                        <h1 className="subscription-success__title">We could not confirm it</h1>
                        <p className="alert alert-error" role="alert">
                            {error}
                        </p>
                        <div className="subscription-confirm__actions">
                            <button
                                type="button"
                                className="main-button"
                                onClick={retry}
                            >
                                Try again
                            </button>
                            <button
                                type="button"
                                className="subscription-btn"
                                onClick={() => navigate('/subscription')}
                            >
                                Back to subscription
                            </button>
                        </div>
                    </>
                )}
            </div>
        </section>
    );
}

export default SubscriptionSuccess;
