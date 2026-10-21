import {useCallback, useEffect, useRef, useState} from 'react';
import {api, ApiError} from './lib/api.js';
import {formatDate} from './lib/format.js';
import './Subscription.css';

/**
 * Device slots for a subscriber (GET/POST /api/users/me/devices). A patched
 * app registers "<device>_<app>" the first time it starts, so each patched app
 * on each phone takes a slot, and a phone that was wiped or sold keeps its
 * slots until they are reset. Before this card the only way out of "already
 * active on 3 devices" was emailing support.
 *
 * A reset clears every slot and starts a 30-day cooldown, so it sits behind a
 * confirm step, and it is refused outright while nothing is registered (it
 * would burn the cooldown for nothing).
 */
function DevicesCard() {
    const [devices, setDevices] = useState(undefined);
    const [loadError, setLoadError] = useState(null);
    const [confirming, setConfirming] = useState(false);
    const [resetting, setResetting] = useState(false);
    const [result, setResult] = useState(null);

    const resetButtonRef = useRef(null);
    const keepButtonRef = useRef(null);
    const resultRef = useRef(null);

    const load = useCallback(async (signal) => {
        try {
            const data = await api.getDevices({signal});
            setDevices(data ?? null);
            setLoadError(null);
        } catch (err) {
            if (err?.name === 'AbortError') return;
            setDevices(null);
            setLoadError(err?.message || 'We could not load your devices.');
        }
    }, []);

    useEffect(() => {
        const controller = new AbortController();
        load(controller.signal);
        return () => controller.abort();
    }, [load]);

    // The Reset button is swapped for the confirm step, so focus has to be
    // placed on purpose or it falls back to <body>. The safe choice gets it.
    useEffect(() => {
        if (confirming) keepButtonRef.current?.focus();
    }, [confirming]);

    useEffect(() => {
        if (result) resultRef.current?.focus();
    }, [result]);

    function cancelConfirm() {
        setConfirming(false);
        // Next frame: the button only exists again after this render commits.
        requestAnimationFrame(() => resetButtonRef.current?.focus());
    }

    async function handleReset() {
        setResetting(true);
        setResult(null);
        try {
            const data = await api.resetDevices();
            setDevices(data ?? null);
            setResult({
                tone: 'success',
                text: 'All device slots are free. Each patched app takes a slot again the next time it opens.',
            });
        } catch (err) {
            setResult({tone: 'error', text: err?.message || 'We could not reset your devices.'});
            // A 429 means our copy of the cooldown was stale; show the real one.
            if (err instanceof ApiError && err.status === 429) load();
        } finally {
            setResetting(false);
            setConfirming(false);
        }
    }

    const used = Number.isInteger(devices?.used) ? devices.used : 0;
    const limit = Number.isInteger(devices?.limit) && devices.limit > 0 ? devices.limit : null;
    const lastReset = formatDate(devices?.last_reset_at);
    const nextReset = formatDate(devices?.next_reset_at);
    const canReset = Boolean(devices?.can_reset) && used > 0;
    const percent = limit ? Math.min(100, Math.round((used / limit) * 100)) : 0;

    let resetNote = 'You can do this once every 30 days.';
    if (devices && !devices.can_reset) {
        resetNote = nextReset ? `You can reset again on ${nextReset}.` : 'You cannot reset your devices right now.';
    } else if (used === 0) {
        resetNote = 'Nothing to reset yet: no device has registered.';
    }

    return (
        <section className="subscription-panel subscription-devices" aria-labelledby="devices-title">
            <h2 id="devices-title" className="subscription-panel__title">Devices</h2>

            {devices === undefined && (
                <p className="subscription-loading subscription-loading--inline" role="status">
                    <span className="subscription-spinner" aria-hidden="true"/>
                    Loading your devices...
                </p>
            )}

            {loadError && (
                <div className="alert alert-error subscription-devices__error" role="alert">
                    <span>{loadError}</span>
                    <button
                        type="button"
                        className="subscription-btn"
                        onClick={() => {
                            setLoadError(null);
                            setDevices(undefined);
                            load();
                        }}
                    >
                        Try again
                    </button>
                </div>
            )}

            {devices && (
                <div className="subscription-panel__body">
                    <div className="subscription-devices__summary">
                        <p className="subscription-devices__count">
                            Devices: <strong>{limit ? `${used} of ${limit}` : used}</strong> used
                        </p>
                        {limit && (
                            // The text above says the same thing, so the bar is decoration.
                            // Its width is a style property set through the DOM, which a
                            // style-src 'self' CSP allows (only inline style attributes in
                            // markup are blocked).
                            <div className="subscription-meter" aria-hidden="true">
                                <span className="subscription-meter__fill" style={{width: `${percent}%`}}/>
                            </div>
                        )}
                        <p className="subscription-hint">
                            Each patched app on each phone takes one slot, so WhatsApp on two phones uses two. A
                            phone you no longer use keeps its slots until you reset them.
                        </p>
                        {lastReset && <p className="subscription-period">Last reset {lastReset}.</p>}
                    </div>

                    {confirming ? (
                        <div className="alert alert-info subscription-confirm" role="group" aria-labelledby="devices-confirm-text">
                            <p id="devices-confirm-text" className="subscription-confirm__text">
                                Free all {limit ?? ''} slots? The apps you still use take a slot again the next time
                                they open. You will not be able to reset again for 30 days.
                            </p>
                            <div className="subscription-confirm__actions">
                                <button
                                    type="button"
                                    className="subscription-btn subscription-btn--danger"
                                    onClick={handleReset}
                                    disabled={resetting}
                                    aria-busy={resetting}
                                >
                                    {resetting && <span className="subscription-spinner" aria-hidden="true"/>}
                                    {resetting ? 'Resetting...' : 'Yes, reset devices'}
                                </button>
                                <button
                                    ref={keepButtonRef}
                                    type="button"
                                    className="subscription-btn"
                                    onClick={cancelConfirm}
                                    disabled={resetting}
                                >
                                    Keep them
                                </button>
                            </div>
                        </div>
                    ) : (
                        <div className="subscription-devices__actions">
                            <button
                                ref={resetButtonRef}
                                type="button"
                                className="subscription-btn"
                                onClick={() => {
                                    setResult(null);
                                    setConfirming(true);
                                }}
                                disabled={!canReset}
                                aria-describedby="devices-reset-note"
                            >
                                Reset devices
                            </button>
                            <p id="devices-reset-note" className="subscription-hint">{resetNote}</p>
                        </div>
                    )}
                </div>
            )}

            {result && (
                <p
                    ref={resultRef}
                    tabIndex={-1}
                    className={`alert ${result.tone === 'error' ? 'alert-error' : 'alert-success'} subscription-devices__result`}
                    role={result.tone === 'error' ? 'alert' : 'status'}
                >
                    {result.text}
                </p>
            )}
        </section>
    );
}

export default DevicesCard;
