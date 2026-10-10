// client/src/RegisterPage.jsx
import {useEffect, useRef, useState} from "react";
import {Link, useNavigate, useSearchParams} from "react-router";
import {useAuth} from "./lib/auth.jsx";
import {loginPath, safeRedirect} from "./lib/redirect.js";
import {
    confirmPasswordError,
    emailError,
    PASSWORD_HINT,
    PASSWORD_MAX,
    PASSWORD_MIN,
    passwordError,
    USERNAME_MAX,
    USERNAME_MIN,
    USERNAME_PATTERN,
} from "./lib/validation.js";
import {AuthCard, AuthField, AuthStatus, AuthSubmit, PasswordField} from "./AuthForm.jsx";
import LegalConsent from "./LegalConsent.jsx";
import "./RegisterPage.css";

function validate({username, email, password, confirmPassword}) {
    const errors = {};

    if (!username) {
        errors.username = "Choose a username.";
    } else if (username.length < USERNAME_MIN || username.length > USERNAME_MAX) {
        errors.username = `Username must be ${USERNAME_MIN}-${USERNAME_MAX} characters.`;
    } else if (!USERNAME_PATTERN.test(username)) {
        errors.username = "Use letters, digits, and . _ - only.";
    }

    const emailProblem = emailError(email);
    if (emailProblem) errors.email = emailProblem;
    const passwordProblem = passwordError(password);
    if (passwordProblem) errors.password = passwordProblem;
    const confirmProblem = confirmPasswordError(password, confirmPassword);
    if (confirmProblem) errors.confirmPassword = confirmProblem;

    return errors;
}

export default function RegisterPage() {
    const navigate = useNavigate();
    const [searchParams] = useSearchParams();
    const {register, login} = useAuth();

    const [username, setUsername] = useState("");
    const [email, setEmail] = useState("");
    const [password, setPassword] = useState("");
    const [confirmPassword, setConfirmPassword] = useState("");
    const [errors, setErrors] = useState({});
    const [status, setStatus] = useState({type: "idle", message: ""});
    // Set once the account exists: {email, verificationSent, signedIn}.
    const [created, setCreated] = useState(null);

    const formRef = useRef(null);
    const statusRef = useRef(null);
    const submittingRef = useRef(false);

    const redirectTo = safeRedirect(searchParams.get("redirect"));
    const loginHref = loginPath(redirectTo);
    const busy = status.type === "loading";

    // Only warn once the confirmation has something in it, otherwise the
    // message flickers in and out while the user is still typing.
    const mismatch = confirmPassword.length > 0 && password !== confirmPassword;

    useEffect(() => {
        if (status.type !== "error") return;
        const invalid = formRef.current?.querySelector('[aria-invalid="true"]');
        if (invalid) invalid.focus();
        else statusRef.current?.focus();
    }, [status]);

    useEffect(() => {
        if (created) statusRef.current?.focus();
    }, [created]);

    function change(setter, key) {
        return (e) => {
            setter(e.target.value);
            setErrors((prev) => (prev[key] ? {...prev, [key]: undefined} : prev));
        };
    }

    async function onSubmit(e) {
        e.preventDefault();
        if (submittingRef.current) return;

        const trimmedUsername = username.trim();
        const trimmedEmail = email.trim();
        const nextErrors = validate({
            username: trimmedUsername,
            email: trimmedEmail,
            password,
            confirmPassword,
        });

        setErrors(nextErrors);
        if (Object.keys(nextErrors).length > 0) {
            setStatus({type: "error", message: "Check the highlighted fields and try again."});
            return;
        }

        submittingRef.current = true;
        setStatus({type: "loading", message: "Creating your account…"});

        let result;
        try {
            result = await register(trimmedUsername, trimmedEmail, password);
        } catch (err) {
            setStatus({type: "error", message: err?.message || "Registration failed. Please try again."});
            submittingRef.current = false;
            return;
        }

        // Registration succeeded, so never dead-end on this page: sign the user
        // straight in. If that second call fails the account still exists, so
        // the way on is /login, prefilled.
        let signedIn = true;
        try {
            await login(trimmedUsername, password);
        } catch {
            signedIn = false;
        }

        /*
         * A server that verifies addresses says whether it could mail the
         * link, and that is worth a screen of its own: the next thing this
         * account will want is to subscribe, which needs the link. A server
         * without verification says nothing, and the user goes straight on as
         * before.
         */
        const verificationSent = result?.verification_sent;
        if (typeof verificationSent === "boolean") {
            setStatus({type: "idle", message: ""});
            setCreated({email: trimmedEmail, verificationSent, signedIn});
            submittingRef.current = false;
            return;
        }
        if (signedIn) {
            navigate(redirectTo, {replace: true});
        } else {
            navigate(loginHref, {
                replace: true,
                state: {username: trimmedUsername, notice: "Account created. Please sign in."},
            });
        }
        submittingRef.current = false;
    }

    if (created) {
        return (
            <AuthCard
                className="registerCard"
                titleId="registerTitle"
                title={created.verificationSent ? "Check your email" : "Account created"}
                documentTitle="Register"
            >
                {created.verificationSent ? (
                    <>
                        <AuthStatus
                            status={{
                                type: "success",
                                message: `Account created. We sent a verification link to ${created.email}.`,
                            }}
                            statusRef={statusRef}
                        />
                        <p className="authNote">
                            Open it to verify your address. You need to before you can subscribe, and the link
                            expires in 48 hours. Nothing there after a few minutes? Check your spam folder, or send
                            a new one from the Subscription page.
                        </p>
                    </>
                ) : (
                    <>
                        <AuthStatus
                            status={{
                                type: "info",
                                message: "Email isn't available right now, so we could not send your verification link.",
                            }}
                            statusRef={statusRef}
                        />
                        <p className="authNote">
                            You can browse in the meantime. Resend the link later from the Subscription page; you
                            need a verified address before you can subscribe.
                        </p>
                    </>
                )}
                {created.signedIn ? (
                    <Link className="main-button authSubmit" to={redirectTo} replace>
                        Continue
                    </Link>
                ) : (
                    <Link
                        className="main-button authSubmit"
                        to={loginHref}
                        replace
                        state={{username: username.trim(), notice: "Account created. Please sign in."}}
                    >
                        Continue to login
                    </Link>
                )}
            </AuthCard>
        );
    }

    return (
        <AuthCard
            className="registerCard"
            titleId="registerTitle"
            title="Register"
            subtitle="Create an account to get started."
            footer={
                <>
                    Already have an account?{" "}
                    <Link className="authFooterLink" to={loginHref}>Login</Link>
                </>
            }
        >
            <form className="authForm" ref={formRef} onSubmit={onSubmit} noValidate>
                <AuthField
                    id="register-username"
                    name="username"
                    label="Username"
                    type="text"
                    autoComplete="username"
                    autoCapitalize="none"
                    autoCorrect="off"
                    spellCheck={false}
                    value={username}
                    onChange={change(setUsername, "username")}
                    placeholder="Choose a username"
                    required
                    minLength={USERNAME_MIN}
                    maxLength={USERNAME_MAX}
                    hint={`${USERNAME_MIN}-${USERNAME_MAX} characters: letters, digits, and . _ -`}
                    error={errors.username}
                />

                <AuthField
                    id="register-email"
                    name="email"
                    label="Email"
                    type="email"
                    autoComplete="email"
                    autoCapitalize="none"
                    autoCorrect="off"
                    spellCheck={false}
                    value={email}
                    onChange={change(setEmail, "email")}
                    placeholder="Enter your email address"
                    required
                    error={errors.email}
                />

                <PasswordField
                    id="register-password"
                    name="password"
                    label="Password"
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
                    id="register-confirm-password"
                    name="confirmPassword"
                    label="Confirm password"
                    autoComplete="new-password"
                    value={confirmPassword}
                    onChange={change(setConfirmPassword, "confirmPassword")}
                    placeholder="Repeat your password"
                    required
                    maxLength={PASSWORD_MAX}
                    error={errors.confirmPassword || (mismatch ? "Passwords do not match." : undefined)}
                />

                <AuthStatus status={status} statusRef={statusRef}/>

                <LegalConsent action="creating an account"/>

                <AuthSubmit busy={busy} busyLabel="Creating account…">Register</AuthSubmit>
            </form>
        </AuthCard>
    );
}
