// client/src/AuthForm.jsx
// Shared shell and controls for the full-page auth screens (login, register,
// forgot and reset password). These routes live outside <Layout>, so the card
// owns its own "back to home" affordance and never renders a <Header/>.
import {useState} from "react";
import {Link} from "react-router";
import PageTitle from "./PageTitle.jsx";
import SupportText from "./SupportText.jsx";
import "./AuthForm.css";

// `documentTitle` overrides the tab title when the heading alone would be
// ambiguous; `noindex` keeps token-bearing pages out of search results.
export function AuthCard({className = "", titleId, title, documentTitle, noindex, subtitle, footer, children}) {
    return (
        <main className="authPage">
            <PageTitle title={documentTitle ?? title} noindex={noindex}/>
            <section className={`authCard ${className}`.trim()} aria-labelledby={titleId}>
                <div className="authCardTop">
                    <Link to="/" className="authBackHome">← Back to Home</Link>
                </div>

                <header className="authHeader">
                    <h1 id={titleId} className="authTitle">{title}</h1>
                    {subtitle ? <p className="authSubtitle">{subtitle}</p> : null}
                </header>

                {children}

                {footer ? <p className="authFooter">{footer}</p> : null}
            </section>
        </main>
    );
}

export function AuthField({id, label, error, hint, inputRef, ...inputProps}) {
    const errorId = `${id}-error`;
    const hintId = `${id}-hint`;
    const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ");

    return (
        <div className="authField">
            <label className="authLabel" htmlFor={id}>{label}</label>
            <input
                {...inputProps}
                id={id}
                ref={inputRef}
                className="authInput"
                aria-invalid={error ? true : undefined}
                aria-describedby={describedBy || undefined}
            />
            {hint ? <p id={hintId} className="authFieldHint">{hint}</p> : null}
            {error ? <p id={errorId} className="authFieldError">{error}</p> : null}
        </div>
    );
}

export function PasswordField({id, label, error, hint, inputRef, ...inputProps}) {
    const [visible, setVisible] = useState(false);
    const errorId = `${id}-error`;
    const hintId = `${id}-hint`;
    const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ");
    const noun = String(label).toLowerCase();

    return (
        <div className="authField">
            <label className="authLabel" htmlFor={id}>{label}</label>
            <div className="authPasswordRow">
                <input
                    {...inputProps}
                    id={id}
                    ref={inputRef}
                    className="authInput"
                    type={visible ? "text" : "password"}
                    aria-invalid={error ? true : undefined}
                    aria-describedby={describedBy || undefined}
                />
                <button
                    type="button"
                    className="authToggle"
                    onClick={() => setVisible((v) => !v)}
                    aria-pressed={visible}
                    aria-controls={id}
                    aria-label={visible ? `Hide ${noun}` : `Show ${noun}`}
                >
                    {visible ? "Hide" : "Show"}
                </button>
            </div>
            {hint ? <p id={hintId} className="authFieldHint">{hint}</p> : null}
            {error ? <p id={errorId} className="authFieldError">{error}</p> : null}
        </div>
    );
}

const STATUS_TONE = {
    error: "alert-error",
    success: "alert-success",
    loading: "alert-info",
    info: "alert-info",
};

// Errors must be assertive; everything else stays polite. The key forces a
// fresh node when the role flips so assistive tech re-announces it.
export function AuthStatus({status, statusRef}) {
    if (!status || !status.message) return null;

    const isError = status.type === "error";
    return (
        <div
            key={isError ? "alert" : "status"}
            ref={statusRef}
            tabIndex={-1}
            role={isError ? "alert" : "status"}
            className={`alert ${STATUS_TONE[status.type] ?? "alert-info"} authStatus`}
        >
            {/* Some server messages name the support address; make it tappable. */}
            <SupportText text={status.message}/>
        </div>
    );
}

export function AuthSubmit({busy, busyLabel, disabled, children}) {
    return (
        <button
            type="submit"
            className="main-button authSubmit"
            disabled={busy || disabled}
            aria-busy={busy || undefined}
        >
            {busy ? busyLabel : children}
        </button>
    );
}
