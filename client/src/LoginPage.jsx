// client/src/LoginPage.jsx
import {useEffect, useRef, useState} from "react";
import {Link, useLocation, useNavigate, useSearchParams} from "react-router";
import {useAuth} from "./lib/auth.jsx";
import {registerPath, safeRedirect} from "./lib/redirect.js";
import {AuthCard, AuthField, AuthStatus, AuthSubmit, PasswordField} from "./AuthForm.jsx";
import "./LoginPage.css";

export default function LoginPage() {
    const navigate = useNavigate();
    const location = useLocation();
    const [searchParams] = useSearchParams();
    const {login} = useAuth();

    const [username, setUsername] = useState(() => location.state?.username ?? "");
    const [password, setPassword] = useState("");
    const [errors, setErrors] = useState({});
    // Callers hand over a notice in router state: "Account created" from
    // /register (success), "Your session expired" from the 401 handler in
    // auth.jsx (info).
    const [status, setStatus] = useState(() =>
        location.state?.notice
            ? {type: location.state.noticeType ?? "success", message: location.state.notice}
            : {type: "idle", message: ""},
    );

    const formRef = useRef(null);
    const statusRef = useRef(null);
    const submittingRef = useRef(false);

    const redirectTo = safeRedirect(searchParams.get("redirect"));
    const registerHref = registerPath(redirectTo);
    const busy = status.type === "loading";

    // A failed submit must land the caret somewhere useful, otherwise a screen
    // reader user gets no feedback at all.
    useEffect(() => {
        if (status.type !== "error") return;
        const invalid = formRef.current?.querySelector('[aria-invalid="true"]');
        if (invalid) invalid.focus();
        else statusRef.current?.focus();
    }, [status]);

    function change(setter, key) {
        return (e) => {
            setter(e.target.value);
            setErrors((prev) => (prev[key] ? {...prev, [key]: undefined} : prev));
        };
    }

    async function onSubmit(e) {
        e.preventDefault();
        // Ref guard as well as `disabled`: Enter can fire twice before state settles.
        if (submittingRef.current) return;

        const nextErrors = {};
        if (!username.trim()) nextErrors.username = "Enter your username or email address.";
        if (!password) nextErrors.password = "Enter your password.";

        setErrors(nextErrors);
        if (Object.keys(nextErrors).length > 0) {
            setStatus({type: "error", message: "Check the highlighted fields and try again."});
            return;
        }

        submittingRef.current = true;
        setStatus({type: "loading", message: "Signing in…"});

        try {
            await login(username.trim(), password);
            navigate(redirectTo, {replace: true});
        } catch (err) {
            setStatus({type: "error", message: err?.message || "Login failed. Please try again."});
        } finally {
            submittingRef.current = false;
        }
    }

    return (
        <AuthCard
            className="loginCard"
            titleId="loginTitle"
            title="Login"
            subtitle="Use your account to continue."
            footer={
                <>
                    No account?{" "}
                    <Link className="authFooterLink" to={registerHref}>Register</Link>
                </>
            }
        >
            <form className="authForm" ref={formRef} onSubmit={onSubmit} noValidate>
                <AuthField
                    id="login-username"
                    name="username"
                    label="Username or email address"
                    type="text"
                    autoComplete="username"
                    autoCapitalize="none"
                    autoCorrect="off"
                    spellCheck={false}
                    value={username}
                    onChange={change(setUsername, "username")}
                    placeholder="Your username or email address"
                    required
                    error={errors.username}
                />

                <PasswordField
                    id="login-password"
                    name="password"
                    label="Password"
                    autoComplete="current-password"
                    value={password}
                    onChange={change(setPassword, "password")}
                    placeholder="Your password"
                    required
                    error={errors.password}
                />

                <p className="authInlineLinks">
                    <Link className="authFooterLink" to="/forgot-password">Forgot password?</Link>
                </p>

                <AuthStatus status={status} statusRef={statusRef}/>

                <AuthSubmit busy={busy} busyLabel="Signing in…">Login</AuthSubmit>
            </form>
        </AuthCard>
    );
}
