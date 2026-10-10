import {useCallback, useRef, useState} from 'react';
import {api} from './lib/api.js';
import {AuthField} from './AuthForm.jsx';

/*
 * Busy and result state for one action. `run` resolves to the answer, or to
 * undefined when the action failed (the error is then shown). A second click
 * while one is in flight is ignored; the server's own checks are the backstop.
 */
function useAction() {
    const [busy, setBusy] = useState(false);
    const [result, setResult] = useState(null);
    const busyRef = useRef(false);

    const run = useCallback(async (work, success) => {
        if (busyRef.current) return undefined;
        busyRef.current = true;
        setBusy(true);
        setResult(null);
        try {
            const answer = await work();
            if (success) setResult({tone: 'success', text: success});
            return answer ?? true;
        } catch (err) {
            setResult({tone: 'error', text: err?.message || 'Something went wrong. Please try again.'});
            return undefined;
        } finally {
            busyRef.current = false;
            setBusy(false);
        }
    }, []);

    return {busy, result, run};
}

export function ActionResult({result}) {
    if (!result) return null;
    const isError = result.tone === 'error';
    return (
        <p className={`alert ${isError ? 'alert-error' : 'alert-success'} admin-result`} role={isError ? 'alert' : 'status'}>
            {result.text}
        </p>
    );
}

/**
 * Change the address, and mark it verified. One component, so the result of
 * either stays on screen after the account reloads (the verify button goes
 * away once the address is verified).
 */
export function EmailAction({user, onChanged}) {
    const [open, setOpen] = useState(false);
    const [email, setEmail] = useState(user.email);
    const [verified, setVerified] = useState(false);
    const {busy, result, run} = useAction();

    async function onSubmit(e) {
        e.preventDefault();
        const done = await run(() => api.admin.changeEmail(user.username, email.trim(), verified), 'Email changed.');
        if (done) {
            setOpen(false);
            onChanged();
        }
    }

    async function onVerify() {
        if (await run(() => api.admin.verifyEmail(user.username), 'Email marked verified.')) onChanged();
    }

    return (
        <div className="admin-action">
            <h3 className="admin-action__title">Email</h3>
            {open ? (
                <form className="authForm" onSubmit={onSubmit} noValidate>
                    <AuthField
                        id="admin-email"
                        label="New email address"
                        type="email"
                        value={email}
                        onChange={(e) => setEmail(e.target.value)}
                        autoComplete="off"
                        spellCheck={false}
                        required
                    />
                    <label className="admin-check">
                        <input type="checkbox" checked={verified} onChange={(e) => setVerified(e.target.checked)}/>
                        The new address is verified
                    </label>
                    <p className="subscription-hint">The Stripe customer's email is not changed.</p>
                    <div className="subscription-actions">
                        <button type="submit" className="main-button" disabled={busy} aria-busy={busy || undefined}>
                            {busy ? 'Saving…' : 'Save email'}
                        </button>
                        <button type="button" className="subscription-btn" onClick={() => setOpen(false)} disabled={busy}>
                            Cancel
                        </button>
                    </div>
                </form>
            ) : (
                <div className="subscription-actions">
                    <button
                        type="button"
                        className="subscription-btn"
                        onClick={() => {
                            setEmail(user.email);
                            setVerified(false);
                            setOpen(true);
                        }}
                    >
                        Change email
                    </button>
                    {!user.email_verified && (
                        <button type="button" className="subscription-btn" onClick={onVerify} disabled={busy}>
                            Mark email verified
                        </button>
                    )}
                </div>
            )}
            <ActionResult result={result}/>
        </div>
    );
}

/**
 * Free the device slots. Behind a confirm step like the user's own reset
 * (DevicesCard), but unlike it this spends nothing: the user can still reset
 * by themselves.
 */
export function DevicesAction({user, onChanged}) {
    const [confirming, setConfirming] = useState(false);
    const {busy, result, run} = useAction();

    async function onClear() {
        const done = await run(() => api.admin.clearDevices(user.username), 'All device slots are free.');
        setConfirming(false);
        if (done) onChanged();
    }

    return (
        <div className="admin-action">
            <h3 className="admin-action__title">Devices</h3>
            {confirming ? (
                <div className="alert alert-info subscription-confirm" role="group" aria-labelledby="admin-devices-confirm">
                    <p id="admin-devices-confirm" className="subscription-confirm__text">
                        Free all {user.devices_used} slots? This does not use up the user's own reset (once every 30 days).
                    </p>
                    <div className="subscription-confirm__actions">
                        <button
                            type="button"
                            className="subscription-btn subscription-btn--danger"
                            onClick={onClear}
                            disabled={busy}
                            aria-busy={busy || undefined}
                        >
                            {busy ? 'Clearing…' : 'Yes, clear the slots'}
                        </button>
                        <button type="button" className="subscription-btn" onClick={() => setConfirming(false)} disabled={busy}>
                            Keep them
                        </button>
                    </div>
                </div>
            ) : (
                <>
                    <button
                        type="button"
                        className="subscription-btn"
                        onClick={() => setConfirming(true)}
                        disabled={user.devices_used === 0}
                        aria-describedby="admin-devices-note"
                    >
                        Clear device slots
                    </button>
                    <p id="admin-devices-note" className="subscription-hint">
                        {user.devices_used === 0 ? 'No device has registered.' : `${user.devices_used} of ${user.devices_limit} slots in use.`}
                    </p>
                </>
            )}
            <ActionResult result={result}/>
        </div>
    );
}

/**
 * Delete the account, as `manage.py delete-user` does. The username has to be
 * typed out first. Not offered on the admin's own account (the server refuses
 * that too).
 */
export function DeleteAction({user, onDeleted}) {
    const [typed, setTyped] = useState('');
    const {busy, result, run} = useAction();
    const matches = typed.trim() === user.username;

    async function onSubmit(e) {
        e.preventDefault();
        if (!matches) return;
        const answer = await run(() => api.admin.deleteUser(user.username));
        if (answer) onDeleted(answer);
    }

    return (
        <section className="subscription-panel admin-danger" aria-labelledby="admin-delete-title">
            <h2 id="admin-delete-title" className="subscription-panel__title">Delete account</h2>
            <p className="subscription-hint">
                Removes the account for good. Its bug reports are kept without its name, email or IP.
                Stripe is left alone: you will get a link to clean up the customer.
            </p>
            <form className="authForm" onSubmit={onSubmit} noValidate>
                <AuthField
                    id="admin-delete-confirm"
                    label={`Type ${user.username} to confirm`}
                    value={typed}
                    onChange={(e) => setTyped(e.target.value)}
                    autoComplete="off"
                    spellCheck={false}
                />
                <div className="subscription-actions">
                    <button
                        type="submit"
                        className="subscription-btn subscription-btn--danger"
                        disabled={!matches || busy}
                        aria-busy={busy || undefined}
                    >
                        {busy ? 'Deleting…' : 'Delete account'}
                    </button>
                </div>
            </form>
            <ActionResult result={result}/>
        </section>
    );
}
