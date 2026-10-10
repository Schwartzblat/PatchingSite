import {useEffect, useRef, useState} from 'react';
import {api, ApiError} from './lib/api.js';
import {useAuth} from './lib/auth.jsx';
import {confirmPasswordError, PASSWORD_HINT, PASSWORD_MAX, PASSWORD_MIN, passwordError} from './lib/validation.js';
import {AuthStatus, PasswordField} from './AuthForm.jsx';
import './Subscription.css';

const SAME_PASSWORD = 'Choose a new password that is different from your current one.';

/**
 * Changing the password while signed in (POST /api/users/me/password), on the
 * subscription page because that is the account page. Shown to every signed-in
 * user, subscriber or not, and closed behind one button so it does not push
 * the plans further down the page.
 *
 * Other sessions stay signed in (session tokens are not bound to the password),
 * and the form says so: someone changing it because it leaked should not
 * believe that also signed the other person out.
 */
function ChangePasswordCard() {
    const {user} = useAuth();
    const [open, setOpen] = useState(false);
    const [currentPassword, setCurrentPassword] = useState('');
    const [newPassword, setNewPassword] = useState('');
    const [confirmPassword, setConfirmPassword] = useState('');
    const [errors, setErrors] = useState({});
    const [status, setStatus] = useState({type: 'idle', message: ''});
    const [done, setDone] = useState(null);

    const openButtonRef = useRef(null);
    const currentRef = useRef(null);
    const formRef = useRef(null);
    const statusRef = useRef(null);
    const doneRef = useRef(null);
    const submittingRef = useRef(false);

    const saving = status.type === 'loading';
    const mismatch = confirmPassword.length > 0 && newPassword !== confirmPassword;

    // The button is swapped for the form and back, so focus is placed on
    // purpose each way or it falls back to <body>.
    useEffect(() => {
        if (open) currentRef.current?.focus();
    }, [open]);

    useEffect(() => {
        if (done) doneRef.current?.focus();
    }, [done]);

    useEffect(() => {
        if (status.type !== 'error') return;
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

    function reset() {
        setCurrentPassword('');
        setNewPassword('');
        setConfirmPassword('');
        setErrors({});
        setStatus({type: 'idle', message: ''});
    }

    function close() {
        reset();
        setOpen(false);
        // Next frame: the button only exists again after this render commits.
        requestAnimationFrame(() => openButtonRef.current?.focus());
    }

    async function onSubmit(e) {
        e.preventDefault();
        if (submittingRef.current) return;

        const nextErrors = {};
        if (!currentPassword) nextErrors.currentPassword = 'Enter your current password.';
        const newProblem = passwordError(newPassword);
        if (newProblem) nextErrors.newPassword = newProblem;
        else if (newPassword === currentPassword) nextErrors.newPassword = SAME_PASSWORD;
        const confirmProblem = confirmPasswordError(newPassword, confirmPassword);
        if (confirmProblem) nextErrors.confirmPassword = confirmProblem;
        setErrors(nextErrors);
        if (Object.keys(nextErrors).length > 0) {
            setStatus({type: 'error', message: 'Check the highlighted fields and try again.'});
            return;
        }

        submittingRef.current = true;
        setStatus({type: 'loading', message: 'Saving your new password…'});
        try {
            const result = await api.changePassword(currentPassword, newPassword);
            reset();
            setOpen(false);
            setDone(result?.message || 'Your password has been changed.');
        } catch (err) {
            let message = err?.message || 'Something went wrong. Please try again.';
            if (err instanceof ApiError && err.status === 400) {
                // The same-password 400 is caught above, so this is the wrong
                // current password: put it on that field.
                setErrors({currentPassword: message});
                message = 'Check the highlighted fields and try again.';
            } else if (err instanceof ApiError && err.status === 429) {
                message = 'Too many attempts from your network. Please try again in an hour.';
            }
            setStatus({type: 'error', message});
        } finally {
            submittingRef.current = false;
        }
    }

    return (
        <section className="subscription-panel" aria-labelledby="password-title">
            <h2 id="password-title" className="subscription-panel__title">Password</h2>

            {open ? (
                <form className="authForm subscription-password__form" ref={formRef} onSubmit={onSubmit} noValidate>
                    {/* Lets a password manager file the new password under the right account. */}
                    {user?.username && (
                        <input
                            type="text"
                            name="username"
                            autoComplete="username"
                            value={user.username}
                            readOnly
                            hidden
                        />
                    )}

                    <PasswordField
                        id="change-current-password"
                        name="currentPassword"
                        label="Current password"
                        autoComplete="current-password"
                        value={currentPassword}
                        onChange={change(setCurrentPassword, 'currentPassword')}
                        required
                        inputRef={currentRef}
                        error={errors.currentPassword}
                    />

                    <PasswordField
                        id="change-new-password"
                        name="newPassword"
                        label="New password"
                        autoComplete="new-password"
                        value={newPassword}
                        onChange={change(setNewPassword, 'newPassword')}
                        required
                        minLength={PASSWORD_MIN}
                        maxLength={PASSWORD_MAX}
                        hint={PASSWORD_HINT}
                        error={errors.newPassword}
                    />

                    <PasswordField
                        id="change-confirm-password"
                        name="confirmPassword"
                        label="Confirm new password"
                        autoComplete="new-password"
                        value={confirmPassword}
                        onChange={change(setConfirmPassword, 'confirmPassword')}
                        required
                        maxLength={PASSWORD_MAX}
                        error={errors.confirmPassword || (mismatch ? 'Passwords do not match.' : undefined)}
                    />

                    <p className="subscription-hint">This does not sign out your other devices and browsers.</p>

                    <AuthStatus status={status} statusRef={statusRef}/>

                    <div className="subscription-actions">
                        <button type="submit" className="main-button" disabled={saving} aria-busy={saving || undefined}>
                            {saving && <span className="subscription-spinner" aria-hidden="true"/>}
                            {saving ? 'Saving…' : 'Save new password'}
                        </button>
                        <button type="button" className="subscription-btn" onClick={close} disabled={saving}>
                            Cancel
                        </button>
                    </div>
                </form>
            ) : (
                <button
                    ref={openButtonRef}
                    type="button"
                    className="subscription-btn"
                    onClick={() => {
                        setDone(null);
                        setOpen(true);
                    }}
                >
                    Change password
                </button>
            )}

            {done && (
                <p ref={doneRef} tabIndex={-1} className="alert alert-success subscription-password__done" role="status">
                    {done}
                </p>
            )}
        </section>
    );
}

export default ChangePasswordCard;
