import {useState} from 'react';
import {api} from './lib/api.js';
import {formatUtcDate} from './lib/format.js';
import {AuthField} from './AuthForm.jsx';
import {ActionResult} from './AdminActions.jsx';

/* What `manage.py grant-trial` prints before its prompt, as a page. */
function TrialPreview({preview, busy, onGrant, onChange}) {
    const ends = formatUtcDate(preview.trial_end);
    return (
        <div className="admin-preview" role="group" aria-labelledby="admin-trial-preview-title">
            <h4 id="admin-trial-preview-title" className="admin-action__title">Before you grant</h4>
            <dl className="admin-facts">
                <dt>Customer</dt>
                <dd>{preview.customer_id ? `${preview.customer_id}, ${preview.customer_how}` : `A new customer for ${preview.email}`}</dd>
                {preview.note && (
                    <>
                        <dt>Note</dt>
                        <dd>{preview.note}</dd>
                    </>
                )}
                <dt>Ends</dt>
                <dd>{ends}, 00:00 UTC ({preview.days} {preview.days === 1 ? 'day' : 'days'} from now)</dd>
                <dt>Price</dt>
                <dd>{preview.price ? `${preview.price} (${preview.price_id})` : preview.price_id}</dd>
                <dt>Stripe</dt>
                <dd>{preview.test_mode ? 'TEST mode' : 'LIVE mode'}</dd>
            </dl>
            {preview.saved_card ? (
                <p className="alert alert-error" role="alert">
                    This customer has a saved card. It will be charged {preview.price || 'the plan price'} on {ends} unless
                    the subscription is cancelled first.
                </p>
            ) : (
                <p className="subscription-hint">With no card on file the subscription is cancelled at the end; nothing is charged.</p>
            )}
            <div className="subscription-actions">
                <button type="button" className="main-button" onClick={onGrant} disabled={busy} aria-busy={busy || undefined}>
                    {busy ? 'Granting…' : 'Grant trial'}
                </button>
                <button type="button" className="subscription-btn" onClick={onChange} disabled={busy}>
                    Change
                </button>
            </div>
        </div>
    );
}

/**
 * A free trial, in the two steps `manage.py grant-trial` takes. Preview runs
 * every check and lookup a grant makes and says what will happen, above all
 * whether a saved card will be CHARGED when the trial ends. Only then is Grant
 * offered. The server checks everything again on Grant, so a preview that went
 * stale cannot push a bad grant through. Changing the end drops the preview,
 * so what is granted is always what was previewed: Grant sends the previewed
 * end as a date, because "N days" is recomputed from today on the server and
 * would shift by a day if 00:00 UTC passed between Preview and Grant.
 */
function AdminTrialForm({user, onGranted}) {
    const [mode, setMode] = useState('days');
    const [days, setDays] = useState('30');
    const [until, setUntil] = useState('');
    const [preview, setPreview] = useState(null);
    const [busy, setBusy] = useState(false);
    const [result, setResult] = useState(null);

    const blocked = !user.email_verified;
    const end = mode === 'days' ? {days: Number(days)} : {until};

    function edit(setter) {
        return (e) => {
            setter(e.target.value);
            setPreview(null);
            setResult(null);
        };
    }

    async function ask(work) {
        if (busy) return;
        setBusy(true);
        setResult(null);
        try {
            await work();
        } catch (err) {
            setResult({tone: 'error', text: err?.message || 'Something went wrong. Please try again.'});
        } finally {
            setBusy(false);
        }
    }

    function onPreview(e) {
        e.preventDefault();
        ask(async () => {
            setPreview(null);
            setPreview(await api.admin.previewTrial(user.username, end));
        });
    }

    function onGrant() {
        ask(async () => {
            const granted = await api.admin.grantTrial(user.username, {until: preview.trial_end.slice(0, 10)});
            setPreview(null);
            setResult({tone: 'success', text: `Granted: a free trial until ${formatUtcDate(granted.trial_end)}.`});
            onGranted();
        });
    }

    return (
        <div className="admin-action">
            <h3 className="admin-action__title">Free trial</h3>
            {blocked && (
                <p className="subscription-hint subscription-hint--warn">
                    Verify the email first: use “Mark email verified” above.
                </p>
            )}
            <form className="authForm" onSubmit={onPreview} noValidate>
                <fieldset className="admin-choice" disabled={blocked || busy}>
                    <legend className="authLabel">The trial ends</legend>
                    <label className="admin-check">
                        <input type="radio" name="admin-trial-mode" value="days" checked={mode === 'days'} onChange={edit(setMode)}/>
                        After a number of days
                    </label>
                    <label className="admin-check">
                        <input type="radio" name="admin-trial-mode" value="until" checked={mode === 'until'} onChange={edit(setMode)}/>
                        On a date
                    </label>
                </fieldset>
                {mode === 'days' ? (
                    <AuthField
                        id="admin-trial-days"
                        label="Days"
                        type="number"
                        min="1"
                        max="730"
                        step="1"
                        inputMode="numeric"
                        value={days}
                        onChange={edit(setDays)}
                        disabled={blocked || busy}
                        hint="Ends at 00:00 UTC, this many days from today."
                    />
                ) : (
                    <AuthField
                        id="admin-trial-until"
                        label="Ends on"
                        type="date"
                        value={until}
                        onChange={edit(setUntil)}
                        disabled={blocked || busy}
                        hint="Ends at 00:00 UTC on this day."
                    />
                )}
                {!preview && (
                    <div className="subscription-actions">
                        <button type="submit" className="subscription-btn" disabled={blocked || busy} aria-busy={busy || undefined}>
                            {busy ? 'Checking…' : 'Preview'}
                        </button>
                    </div>
                )}
            </form>
            {preview && <TrialPreview preview={preview} busy={busy} onGrant={onGrant} onChange={() => setPreview(null)}/>}
            <ActionResult result={result}/>
        </div>
    );
}

export default AdminTrialForm;
