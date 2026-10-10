// client/src/ForgotPasswordPage.jsx
import {useEffect, useRef, useState} from "react";
import {Link} from "react-router";
import {api, ApiError} from "./lib/api.js";
import {emailError} from "./lib/validation.js";
import {AuthCard, AuthField, AuthStatus, AuthSubmit} from "./AuthForm.jsx";

/*
 * The server answers 200 with the same message whether or not an account uses
 * the address, so this page never learns (or reveals) which emails are
 * registered. The fallback copy below matches that promise for a server that
 * sends no message.
 */
const GENERIC_SENT = "If an account uses that email, we've sent a reset link. It expires in 1 hour.";

export default function ForgotPasswordPage() {
    const [email, setEmail] = useState("");
    const [error, setError] = useState(undefined);
    const [status, setStatus] = useState({type: "idle", message: ""});
    const [sent, setSent] = useState(null);

    const formRef = useRef(null);
    const statusRef = useRef(null);
    const submittingRef = useRef(false);

    useEffect(() => {
        if (status.type !== "error") return;
        const invalid = formRef.current?.querySelector('[aria-invalid="true"]');
        if (invalid) invalid.focus();
        else statusRef.current?.focus();
    }, [status]);

    useEffect(() => {
        if (sent) statusRef.current?.focus();
    }, [sent]);

    async function onSubmit(e) {
        e.preventDefault();
        if (submittingRef.current) return;

        const address = email.trim();
        const problem = emailError(address);
        setError(problem);
        if (problem) {
            setStatus({type: "error", message: "Check the highlighted field and try again."});
            return;
        }

        submittingRef.current = true;
        setStatus({type: "loading", message: "Sending…"});
        try {
            const result = await api.forgotPassword(address);
            setStatus({type: "idle", message: ""});
            setSent(result?.message || GENERIC_SENT);
        } catch (err) {
            // A 503 means email is not set up on the server; its detail names
            // the support address, which AuthStatus turns into a link.
            let message = err?.message || "Something went wrong. Please try again.";
            if (err instanceof ApiError && err.status === 429) {
                message = "Too many reset requests from your network. Please try again in an hour.";
            }
            setStatus({type: "error", message});
        } finally {
            submittingRef.current = false;
        }
    }

    if (sent) {
        return (
            <AuthCard
                titleId="forgotTitle"
                title="Check your email"
                documentTitle="Forgot password"
                noindex
                footer={<Link className="authFooterLink" to="/login">Back to login</Link>}
            >
                <AuthStatus status={{type: "success", message: sent}} statusRef={statusRef}/>
                <p className="authNote">
                    If several accounts use this address, the email has a separate link for each. Nothing there
                    after a few minutes? Check your spam folder, or{" "}
                    <button type="button" className="authLinkButton" onClick={() => setSent(null)}>
                        send it again
                    </button>.
                </p>
            </AuthCard>
        );
    }

    return (
        <AuthCard
            titleId="forgotTitle"
            title="Forgot password"
            subtitle="Enter the email address on your account and we'll send you a link to choose a new password."
            noindex
            footer={
                <>
                    Remembered it?{" "}
                    <Link className="authFooterLink" to="/login">Back to login</Link>
                </>
            }
        >
            <form className="authForm" ref={formRef} onSubmit={onSubmit} noValidate>
                <AuthField
                    id="forgot-email"
                    name="email"
                    label="Email"
                    type="email"
                    autoComplete="email"
                    autoCapitalize="none"
                    autoCorrect="off"
                    spellCheck={false}
                    value={email}
                    onChange={(e) => {
                        setEmail(e.target.value);
                        if (error) setError(undefined);
                    }}
                    placeholder="Enter your email address"
                    required
                    error={error}
                />

                <AuthStatus status={status} statusRef={statusRef}/>

                <AuthSubmit busy={status.type === "loading"} busyLabel="Sending…">Send reset link</AuthSubmit>
            </form>
        </AuthCard>
    );
}
