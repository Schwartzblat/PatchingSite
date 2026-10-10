import {useState} from 'react';
import {isEmailUnverified} from './lib/api.js';
import {useAuth} from './lib/auth.jsx';
import ResendVerification from './ResendVerification.jsx';
import './VerifyEmail.css';

/**
 * Tells a signed-in user whose address is not verified yet that it needs to
 * be, on every page with the site chrome. Guests and verified users (which
 * includes every account from before verification existed) never see it.
 *
 * Layout keys it on the path, so a resend's answer belongs to the page it was
 * asked on and does not follow the user around. When a resend finds the
 * address already verified, the user is refreshed and the prompt would simply
 * vanish; the banner says why instead, until the next navigation.
 */
export default function VerifyEmailBanner() {
    const {user, verificationSent} = useAuth();
    const [verifiedNotice, setVerifiedNotice] = useState(null);

    // `user` too: logging out from "/" navigates to "/", which does not remount this.
    if (verifiedNotice && user) {
        return (
            <section className="verify-banner verify-banner--done" aria-label="Email verification">
                <p className="verify-banner-text" role="status">{verifiedNotice}</p>
            </section>
        );
    }

    if (!isEmailUnverified(user)) return null;

    return (
        <section className="verify-banner" aria-label="Email verification">
            <p className="verify-banner-text">
                {verificationSent === false ? (
                    <>
                        <strong>Verify your email:</strong> email isn&apos;t available right now, so we could not
                        send the link to <strong>{user.email}</strong>. Try resending it later.
                    </>
                ) : (
                    <>
                        <strong>Verify your email:</strong> we sent a link to <strong>{user.email}</strong>.
                    </>
                )}
                {' '}<span className="verify-banner-why">You need a verified address to subscribe.</span>
            </p>
            <ResendVerification onVerified={setVerifiedNotice}/>
        </section>
    );
}
