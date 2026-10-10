import {useRef, useState} from 'react';
import {api, ApiError} from './lib/api.js';
import {useAuth} from './lib/auth.jsx';
import SupportText from './SupportText.jsx';
import './VerifyEmail.css';

const SENT_FALLBACK = 'We sent a new verification link. It expires in 48 hours.';
const VERIFIED_FALLBACK = 'Your email address is already verified.';
// The server allows about 3 links an hour per account, and has not said when the window reopens.
const RATE_LIMITED = 'You have asked for several links in a short time. Check your inbox and spam folder, or try again in an hour.';
// Only reachable on /verify-email: everywhere else the 401 handler in auth.jsx has already sent the user to /login.
const SESSION_EXPIRED = 'Your session expired. Log in again to send a new link.';

/**
 * The "Resend link" button and its answer, shown right under it. The site
 * banner, the Subscription page and /verify-email each explain around it why
 * the address needs verifying; this only asks for the link.
 *
 * Returns a fragment (button, then the result line) so each caller lays the
 * two out in its own flow.
 *
 * A 409 means the address was verified after this page loaded the user (the
 * link was opened in another tab or on the phone), so the user is refreshed
 * and whatever shows this button stops showing it. `onVerified` gets the
 * server's sentence before that happens, for a caller that wants to keep
 * saying it after this component is gone.
 */
export default function ResendVerification({buttonClassName = 'verify-resend-button', onVerified}) {
    const {refresh, markVerificationSent} = useAuth();
    const [status, setStatus] = useState({type: 'idle', message: ''});
    const sendingRef = useRef(false);
    const busy = status.type === 'sending';

    async function onClick() {
        // Ref guard as well as `disabled`: a double tap fires twice before state settles.
        if (sendingRef.current) return;
        sendingRef.current = true;
        setStatus({type: 'sending', message: ''});
        try {
            const result = await api.resendVerification();
            markVerificationSent();
            setStatus({type: 'success', message: result?.message || SENT_FALLBACK});
        } catch (err) {
            if (err instanceof ApiError && err.status === 409) {
                const message = err.message || VERIFIED_FALLBACK;
                setStatus({type: 'success', message});
                onVerified?.(message);
                await refresh();
                return;
            }
            let message = err?.message || 'We could not send a new link. Please try again.';
            if (err instanceof ApiError && err.status === 429) message = RATE_LIMITED;
            if (err instanceof ApiError && err.status === 401) message = SESSION_EXPIRED;
            // A 503's detail names the support address, which SupportText links.
            setStatus({type: 'error', message});
        } finally {
            sendingRef.current = false;
        }
    }

    const isError = status.type === 'error';
    return (
        <>
            <button
                type="button"
                className={buttonClassName}
                onClick={onClick}
                disabled={busy}
                aria-busy={busy || undefined}
            >
                {busy ? 'Sending…' : 'Resend link'}
            </button>
            {status.message && (
                // Keyed so a flip between alert and status is a fresh node, which assistive tech announces.
                <p
                    key={isError ? 'alert' : 'status'}
                    role={isError ? 'alert' : 'status'}
                    className={`verify-resend-result verify-resend-result--${isError ? 'error' : 'success'}`}
                >
                    <SupportText text={status.message}/>
                </p>
            )}
        </>
    );
}
