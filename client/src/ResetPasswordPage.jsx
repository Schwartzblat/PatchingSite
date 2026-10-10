// client/src/ResetPasswordPage.jsx
import {useEffect, useRef, useState} from "react";
import {Link, useSearchParams} from "react-router";
import {api, ApiError} from "./lib/api.js";
import {confirmPasswordError, PASSWORD_HINT, PASSWORD_MAX, PASSWORD_MIN, passwordError} from "./lib/validation.js";
import {AuthCard, AuthStatus, AuthSubmit, PasswordField} from "./AuthForm.jsx";

/*
 * One email address can own several accounts, and the reset email then holds
 * one link per account, so the page says which account this link resets. The
 * name is read from the token's payload for display only: nothing here trusts
 * it, and the server verifies the signature when the form is submitted.
 */
function usernameFromToken(token) {
    try {
        const segment = token.split(".")[1];
        const base64 = segment.replace(/-/g, "+").replace(/_/g, "/");
        const payload = JSON.parse(atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, "=")));
        return typeof payload?.username === "string" && payload.username ? payload.username : null;
    } catch {
        return null;
    }
}

function RequestNewLink({title, message}) {
    return (
        <AuthCard
            titleId="resetTitle"
            title={title}
            documentTitle="Reset password"
            noindex
            footer={<Link className="authFooterLink" to="/login">Back to login</Link>}
        >
            <p className="alert alert-error authStatus" role="alert">{message}</p>
            <Link className="main-button authSubmit" to="/forgot-password">Request a new link</Link>
        </AuthCard>
    );
}

export default function ResetPasswordPage() {
    const [searchParams] = useSearchParams();
    const token = searchParams.get("token") ?? "";
    const username = token ? usernameFromToken(token) : null;

    const [password, setPassword] = useState("");
    const [confirmPassword, setConfirmPassword] = useState("");
    const [errors, setErrors] = useState({});
    const [status, setStatus] = useState({type: "idle", message: ""});
    const [done, setDone] = useState(null);
    const [expired, setExpired] = useState(false);

    const formRef = useRef(null);
    const statusRef = useRef(null);
    const submittingRef = useRef(false);

    const mismatch = confirmPassword.length > 0 && password !== confirmPassword;

    useEffect(() => {
        if (status.type !== "error") return;
        const invalid = formRef.current?.querySelector('[aria-invalid="true"]');
        if (invalid) invalid.focus();
        else statusRef.current?.focus();
    }, [status]);

    useEffect(() => {
        if (done) statusRef.current?.focus();
    }, [done]);

    function change(setter, key) {
        return (e) => {
            setter(e.target.value);
            setErrors((prev) => (prev[key] ? {...prev, [key]: undefined} : prev));
        };
    }

    async function onSubmit(e) {
        e.preventDefault();
        if (submittingRef.current) return;

        const nextErrors = {};
        const passwordProblem = passwordError(password);
        if (passwordProblem) nextErrors.password = passwordProblem;
        const confirmProblem = confirmPasswordError(password, confirmPassword);
        if (confirmProblem) nextErrors.confirmPassword = confirmProblem;
        setErrors(nextErrors);
        if (Object.keys(nextErrors).length > 0) {
            setStatus({type: "error", message: "Check the highlighted fields and try again."});
            return;
        }

        submittingRef.current = true;
        setStatus({type: "loading", message: "Saving your new password…"});
        try {
            const result = await api.resetPassword(token, password);
            setStatus({type: "idle", message: ""});
            setDone(result?.message || "Your password has been updated. You can log in now.");
        } catch (err) {
            if (err instanceof ApiError && err.status === 400) {
                // Expired, already used, or the password changed since it was sent.
                setExpired(true);
                return;
            }
            let message = err?.message || "Something went wrong. Please try again.";
            if (err instanceof ApiError && err.status === 429) {
                message = "Too many attempts from your network. Please try again in an hour.";
            }
            setStatus({type: "error", message});
        } finally {
            submittingRef.current = false;
        }
    }

    if (!token) {
        return (
            <RequestNewLink
                title="Reset link incomplete"
                message="This link is missing its reset code. Open the link from the email again, or request a new one."
            />
        );
    }

    if (expired) {
        return <RequestNewLink title="Link expired" message="This reset link is invalid or has expired."/>;
    }

    if (done) {
        return (
            <AuthCard titleId="resetTitle" title="Password updated" documentTitle="Reset password" noindex>
                <AuthStatus status={{type: "success", message: done}} statusRef={statusRef}/>
                {/* Prefilled, so the next screen asks for the new password only. */}
                <Link
                    className="main-button authSubmit"
                    to="/login"
                    state={{
                        username: username ?? undefined,
                        notice: "Password updated. Sign in with your new password.",
                    }}
                >
                    Go to login
                </Link>
            </AuthCard>
        );
    }

    return (
        <AuthCard
            titleId="resetTitle"
            title="Choose a new password"
            documentTitle="Reset password"
            noindex
            subtitle={username
                ? <>For the account <strong className="authStrong">{username}</strong>.</>
                : "Pick a new password for your account."}
            footer={<Link className="authFooterLink" to="/login">Back to login</Link>}
        >
            <form className="authForm" ref={formRef} onSubmit={onSubmit} noValidate>
                {/* Lets a password manager file the new password under the right account. */}
                {username && (
                    <input
                        type="text"
                        name="username"
                        autoComplete="username"
                        value={username}
                        readOnly
                        hidden
                    />
                )}

                <PasswordField
                    id="reset-password"
                    name="password"
                    label="New password"
                    autoComplete="new-password"
                    value={password}
                    onChange={change(setPassword, "password")}
                    placeholder="Create a password"
                    required
                    minLength={PASSWORD_MIN}
                    maxLength={PASSWORD_MAX}
                    hint={PASSWORD_HINT}
                    error={errors.password}
                />

                <PasswordField
                    id="reset-confirm-password"
                    name="confirmPassword"
                    label="Confirm new password"
                    autoComplete="new-password"
                    value={confirmPassword}
                    onChange={change(setConfirmPassword, "confirmPassword")}
                    placeholder="Repeat your password"
                    required
                    maxLength={PASSWORD_MAX}
                    error={errors.confirmPassword || (mismatch ? "Passwords do not match." : undefined)}
                />

                <AuthStatus status={status} statusRef={statusRef}/>

                <AuthSubmit busy={status.type === "loading"} busyLabel="Saving…">Save new password</AuthSubmit>
            </form>
        </AuthCard>
    );
}
