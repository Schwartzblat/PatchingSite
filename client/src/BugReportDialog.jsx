import {useEffect, useRef, useState} from 'react';
import {api, ApiError} from './lib/api.js';
import {useAuth} from './lib/auth.jsx';
import {APP_VERSION, SUPPORT_EMAIL} from './lib/site.js';
import {EMAIL_PATTERN} from './lib/validation.js';
import SupportText from './SupportText.jsx';
import './AuthForm.css';
import './BugReportDialog.css';

// The server's limits (POST /api/bug-reports): message 10-5000 characters,
// email at most 254, each context value at most 500.
const MESSAGE_MIN = 10;
const MESSAGE_MAX = 5000;
const EMAIL_MAX = 254;
const CONTEXT_MAX = 500;

const FOCUSABLE = 'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])';

/*
 * The page the report was sent from, minus anything secret in its query:
 * /reset-password?token= carries a live password-reset token and
 * /verify-email?token= an email-verification one, and a bug report is stored
 * in the database and emailed in plain text. `token` is dropped whatever the
 * path, so a future page that takes one is covered too.
 */
function reportUrl() {
    const url = new URL(window.location.href);
    url.searchParams.delete('token');
    url.searchParams.delete('sig');
    return url.toString();
}

function reportContext() {
    const clip = (value) => String(value).slice(0, CONTEXT_MAX);
    return {
        url: clip(reportUrl()),
        user_agent: clip(navigator.userAgent),
        app_version: clip(APP_VERSION),
    };
}

function describeFailure(err) {
    if (err instanceof ApiError && err.status === 429) {
        return `You have sent several reports in a short time. Please try again in an hour, or email ${SUPPORT_EMAIL}.`;
    }
    if (err instanceof ApiError && err.status === 422) {
        return `Check your report and try again (${err.message}).`;
    }
    const reason = err?.message ? ` (${err.message})` : '';
    return `We could not send your report${reason}. You can email ${SUPPORT_EMAIL} instead.`;
}

/**
 * Modal bug report form. A native <dialog> opened with showModal() makes the
 * rest of the page inert and is announced as a modal; on top of that, Tab is
 * wrapped so focus cannot wander out to the browser chrome, Esc closes, and
 * focus goes back to whatever opened it.
 *
 * The parent mounts it only while open, so every opening starts from a clean
 * form. Clicking the backdrop deliberately does nothing: it is too easy to do
 * by accident on a phone, and it would throw away a half-written report.
 *
 * `returnFocusRef` names the control to refocus on close. document.activeElement
 * at open time is only a fallback: Safari does not focus a button when it is
 * clicked, so there it is <body>.
 */
export default function BugReportDialog({onClose, returnFocusRef}) {
    const {user} = useAuth();
    const dialogRef = useRef(null);
    const messageRef = useRef(null);
    const sentRef = useRef(null);
    const statusRef = useRef(null);
    const sendingRef = useRef(false);

    const [message, setMessage] = useState('');
    const [email, setEmail] = useState(() => user?.email ?? '');
    const [errors, setErrors] = useState({});
    const [status, setStatus] = useState({type: 'idle', message: ''});

    useEffect(() => {
        const dialog = dialogRef.current;
        const opener = returnFocusRef?.current ?? document.activeElement;
        if (!dialog.open) dialog.showModal();
        // showModal() focuses the first control, which is the close button;
        // the message box is where the user wants to be.
        messageRef.current?.focus();
        return () => {
            if (dialog.open) dialog.close();
            if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
        };
        // Mount-only: the ref object is stable and read once, at open.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    useEffect(() => {
        if (status.type === 'sent') {
            sentRef.current?.focus();
        } else if (status.type === 'error') {
            const invalid = dialogRef.current?.querySelector('[aria-invalid="true"]');
            (invalid ?? statusRef.current)?.focus();
        }
    }, [status]);

    function onCancel(event) {
        // Esc. Closing through React state keeps the parent's `open` flag honest.
        event.preventDefault();
        onClose();
    }

    function onKeyDown(event) {
        if (event.key !== 'Tab') return;
        const focusable = [...dialogRef.current.querySelectorAll(FOCUSABLE)];
        if (focusable.length === 0) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    }

    async function onSubmit(event) {
        event.preventDefault();
        if (sendingRef.current) return;

        const text = message.trim();
        const address = email.trim();
        const nextErrors = {};
        if (text.length < MESSAGE_MIN) {
            nextErrors.message = `Tell us a little more: at least ${MESSAGE_MIN} characters.`;
        } else if (text.length > MESSAGE_MAX) {
            nextErrors.message = `Keep it under ${MESSAGE_MAX} characters.`;
        }
        if (address && (address.length > EMAIL_MAX || !EMAIL_PATTERN.test(address))) {
            nextErrors.email = 'Enter a valid email address, or leave it empty.';
        }
        setErrors(nextErrors);
        if (Object.keys(nextErrors).length > 0) {
            setStatus({type: 'error', message: 'Check the highlighted fields and try again.'});
            return;
        }

        sendingRef.current = true;
        setStatus({type: 'sending', message: 'Sending your report…'});
        try {
            const result = await api.sendBugReport({
                message: text,
                // Omitted rather than "", which the server would have to treat as an address.
                email: address || undefined,
                source: 'web',
                context: reportContext(),
            });
            setStatus({type: 'sent', message: result?.message || 'Thanks! Your report was sent.'});
        } catch (err) {
            setStatus({type: 'error', message: describeFailure(err)});
        } finally {
            sendingRef.current = false;
        }
    }

    const sending = status.type === 'sending';
    const replyTo = email.trim();

    return (
        <dialog
            ref={dialogRef}
            className="bug-dialog"
            aria-labelledby="bug-dialog-title"
            onCancel={onCancel}
            onKeyDown={onKeyDown}
        >
            <div className="bug-dialog-head">
                <h2 id="bug-dialog-title" className="bug-dialog-title">Report a bug</h2>
                <button type="button" className="bug-dialog-close" onClick={onClose} aria-label="Close">
                    <span aria-hidden="true">×</span>
                </button>
            </div>

            {status.type === 'sent' ? (
                <div className="bug-dialog-body">
                    <p ref={sentRef} tabIndex={-1} className="alert alert-success bug-dialog-status">
                        {status.message}
                    </p>
                    <p className="bug-dialog-text">
                        {replyTo
                            ? `We read every report. If we need more details, we will write to ${replyTo}.`
                            : 'We read every report. Thanks for taking the time.'}
                    </p>
                    <div className="bug-dialog-actions">
                        <button type="button" className="main-button" onClick={onClose}>Close</button>
                    </div>
                </div>
            ) : (
                <form className="bug-dialog-body" onSubmit={onSubmit} noValidate>
                    <p className="bug-dialog-text">
                        What did you do, what did you expect, and what happened instead? The more detail, the
                        faster we can fix it.
                    </p>

                    <div className="authField">
                        <label className="authLabel" htmlFor="bug-message">What went wrong?</label>
                        <textarea
                            ref={messageRef}
                            id="bug-message"
                            name="message"
                            className="authInput bug-dialog-message"
                            value={message}
                            onChange={(e) => {
                                setMessage(e.target.value);
                                if (errors.message) setErrors((prev) => ({...prev, message: undefined}));
                            }}
                            rows={6}
                            maxLength={MESSAGE_MAX}
                            required
                            aria-invalid={errors.message ? true : undefined}
                            aria-describedby={errors.message ? 'bug-message-hint bug-message-error' : 'bug-message-hint'}
                        />
                        <p id="bug-message-hint" className="authFieldHint bug-dialog-hint">
                            <span>At least {MESSAGE_MIN} characters.</span>
                            <span aria-hidden="true">{message.length}/{MESSAGE_MAX}</span>
                        </p>
                        {errors.message && <p id="bug-message-error" className="authFieldError">{errors.message}</p>}
                    </div>

                    <div className="authField">
                        <label className="authLabel" htmlFor="bug-email">Email for a reply (optional)</label>
                        <input
                            id="bug-email"
                            name="email"
                            type="email"
                            className="authInput"
                            autoComplete="email"
                            autoCapitalize="none"
                            spellCheck={false}
                            value={email}
                            onChange={(e) => {
                                setEmail(e.target.value);
                                if (errors.email) setErrors((prev) => ({...prev, email: undefined}));
                            }}
                            maxLength={EMAIL_MAX}
                            aria-invalid={errors.email ? true : undefined}
                            aria-describedby={errors.email ? 'bug-email-hint bug-email-error' : 'bug-email-hint'}
                        />
                        <p id="bug-email-hint" className="authFieldHint">
                            {user?.email
                                ? 'Filled in from your account. We only use it to reply to this report.'
                                : 'Leave it empty if you do not need a reply.'}
                        </p>
                        {errors.email && <p id="bug-email-error" className="authFieldError">{errors.email}</p>}
                    </div>

                    <p className="bug-dialog-context">
                        Sent along with your report: this page&apos;s address, your browser&apos;s user agent and
                        the site version ({APP_VERSION}).
                    </p>

                    {(status.type === 'error' || sending) && (
                        <div
                            key={status.type}
                            ref={statusRef}
                            tabIndex={-1}
                            role={status.type === 'error' ? 'alert' : 'status'}
                            className={`alert ${status.type === 'error' ? 'alert-error' : 'alert-info'} bug-dialog-status`}
                        >
                            <SupportText text={status.message}/>
                        </div>
                    )}

                    <div className="bug-dialog-actions">
                        <button type="button" className="bug-dialog-secondary" onClick={onClose}>Cancel</button>
                        <button type="submit" className="main-button" disabled={sending} aria-busy={sending || undefined}>
                            {sending ? 'Sending…' : 'Send report'}
                        </button>
                    </div>
                </form>
            )}
        </dialog>
    );
}
