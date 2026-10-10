// client/src/VerifyEmailPage.jsx
import {useCallback, useEffect, useRef, useState} from "react";
import {Link, useSearchParams} from "react-router";
import {api, ApiError, isEmailUnverified} from "./lib/api.js";
import {useAuth} from "./lib/auth.jsx";
import {loginPath} from "./lib/redirect.js";
import {AuthCard, AuthStatus} from "./AuthForm.jsx";
import ResendVerification from "./ResendVerification.jsx";
import "./VerifyEmail.css";

const VERIFIED_FALLBACK = "Your email address is verified.";
const INVALID_FALLBACK = "This verification link is invalid or has expired.";

/*
 * Where the link in the verification email lands:
 * {FRONTEND_URL}/verify-email?token=<jwt>. The token goes to exactly one place,
 * the POST below. It is never put in a link: the login link names
 * /subscription rather than this URL, auth.jsx lists this page so an expired
 * session does not copy the URL into ?redirect=, and a bug report drops
 * `token` from the page address it sends.
 *
 * The link works signed out, since it is often opened on another device than
 * the one that registered. Verifying is idempotent on the server, so opening
 * the link twice, or reloading this page, just says "verified" again.
 */
export default function VerifyEmailPage() {
    const [searchParams] = useSearchParams();
    const token = searchParams.get("token") ?? "";
    const {user, loading: authLoading, refresh} = useAuth();

    // verifying -> verified | failed, or "missing" when the URL has no token at all.
    const [result, setResult] = useState({state: token ? "verifying" : "missing"});
    const statusRef = useRef(null);
    // The token the POST was last sent for, so a re-render never sends it twice.
    const sentForRef = useRef(null);
    const refreshedRef = useRef(false);

    // Starts in "verifying" already; only the retry button has to set it again.
    // A promise chain rather than async/await: the effect below calls this, and
    // the hooks linter only sees that the setState calls are deferred this way.
    const verify = useCallback(() => api.verifyEmail(token).then(
        (data) => setResult({
            state: "verified",
            message: data?.message || VERIFIED_FALLBACK,
            username: typeof data?.username === "string" && data.username ? data.username : null,
        }),
        (err) => setResult({state: "failed", ...describeFailure(err)}),
    ), [token]);

    useEffect(() => {
        if (!token || sentForRef.current === token) return;
        sentForRef.current = token;
        verify();
    }, [token, verify]);

    /*
     * Signed in, and the account on screen still reads as unverified: reload it
     * so the banner is gone on the next page. This waits for the first
     * /users/me to settle, because that probe runs alongside the POST and
     * could answer with the state from before it; refreshing earlier would let
     * that stale answer land last. It runs once: if the link was for another
     * account than the one signed in, the refreshed user is still unverified,
     * which is the truth.
     */
    useEffect(() => {
        if (result.state !== "verified" || authLoading || refreshedRef.current) return;
        if (!isEmailUnverified(user)) return;
        refreshedRef.current = true;
        refresh().catch(() => {});
    }, [result.state, authLoading, user, refresh]);

    useEffect(() => {
        if (result.state === "verified" || result.state === "failed") statusRef.current?.focus();
    }, [result.state]);

    if (result.state === "missing") {
        return (
            <VerifyCard title="Verification link incomplete">
                <p className="alert alert-error authStatus" role="alert">
                    This link is missing its verification code. Open the link from the email again.
                </p>
                <NextStep user={user} loading={authLoading}/>
            </VerifyCard>
        );
    }

    if (result.state === "verifying") {
        return (
            <VerifyCard title="Verifying your email">
                <AuthStatus status={{type: "loading", message: "Checking your link…"}}/>
            </VerifyCard>
        );
    }

    if (result.state === "verified") {
        return (
            <VerifyCard
                title="Email verified"
                subtitle={result.username
                    ? <>For the account <strong className="authStrong">{result.username}</strong>.</>
                    : null}
            >
                <AuthStatus status={{type: "success", message: result.message}} statusRef={statusRef}/>
                <p className="authNote">You can subscribe now to download every patched app.</p>
                <Link className="main-button authSubmit" to="/subscription">Go to Subscription</Link>
            </VerifyCard>
        );
    }

    return (
        <VerifyCard title={result.title}>
            <AuthStatus status={{type: "error", message: result.message}} statusRef={statusRef}/>
            {result.retry ? (
                <button
                    type="button"
                    className="main-button authSubmit"
                    onClick={() => {
                        setResult({state: "verifying"});
                        verify();
                    }}
                >
                    Try again
                </button>
            ) : (
                <NextStep user={user} loading={authLoading}/>
            )}
        </VerifyCard>
    );
}

function describeFailure(err) {
    if (err instanceof ApiError && err.status === 400) {
        // Bad signature, expired, or the account's address changed since the link was sent.
        return {title: "Link expired", message: err.message || INVALID_FALLBACK, retry: false};
    }
    if (err instanceof ApiError && err.status === 429) {
        return {
            title: "Too many attempts",
            message: "Too many attempts from your network. Please try again in an hour.",
            retry: false,
        };
    }
    // Offline, timed out or a 5xx: the link itself may be fine, so let the user send it again.
    return {
        title: "Something went wrong",
        message: err?.message || "We could not check your link. Please try again.",
        retry: true,
    };
}

function VerifyCard({title, subtitle, children}) {
    return (
        <AuthCard titleId="verifyTitle" title={title} subtitle={subtitle} documentTitle="Verify email" noindex>
            {children}
        </AuthCard>
    );
}

/*
 * What to do about a link that did not work. Nothing until the session check
 * settles, so a signed-in visitor never sees "Log in" flash first.
 */
function NextStep({user, loading}) {
    if (loading) return null;

    if (!user) {
        return (
            <>
                <p className="authNote">Log in to send yourself a new link.</p>
                {/* Back to /subscription, never to this page: its URL holds the token. */}
                <Link className="main-button authSubmit" to={loginPath("/subscription")}>Log in</Link>
            </>
        );
    }

    if (!isEmailUnverified(user)) {
        return (
            <>
                <p className="authNote">
                    Your email address is already verified, so there is nothing more to do here.
                </p>
                <Link className="main-button authSubmit" to="/subscription">Go to Subscription</Link>
            </>
        );
    }

    return (
        <>
            <p className="authNote">
                Send a new link to <strong className="authStrong verify-address">{user.email}</strong>. It expires in
                48 hours.
            </p>
            <ResendVerification buttonClassName="main-button authSubmit"/>
        </>
    );
}
