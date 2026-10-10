import {useEffect, useState, useSyncExternalStore} from 'react';
import {Link, useLocation} from 'react-router';
import {
    ANALYTICS_ENABLED,
    CONSENT_EVENT,
    initAnalytics,
    readConsent,
    setConsent,
    trackPageView,
} from './lib/analytics.js';
import {useLang} from './lib/i18n.js';
import './Analytics.css';

/* useSyncExternalStore reads the server snapshot during the build and during
   hydration, and the client snapshot from the next render on: React's own way
   to say "this render is the one that matches the prerendered markup". */
const subscribeNever = () => () => {};
const isBrowserRender = () => true;
const isServerRender = () => false;

/**
 * Owns everything Google Analytics touches: starting the tag, reporting a view
 * per route, and the prompt that gates both.
 *
 * Mounted once beside the routes in main.jsx rather than inside Layout, because
 * the auth pages use BareLayout and a visitor who lands on /login first must
 * still get the choice.
 *
 * The banner does not trap focus and is not a modal: nothing on the site is
 * withheld until it is answered, so blocking the page would be a worse
 * experience than the measurement is worth.
 */
export default function Analytics() {
    const {pathname} = useLocation();
    const lang = useLang();
    /* null until chosen, which is what renders the banner. In a dev build there
       is no tag, so nothing should ever ask. */
    const [choice, setChoice] = useState(() => (ANALYTICS_ENABLED ? readConsent() : 'denied'));
    /* Pages are prerendered at build time, where there is no localStorage, so
       the built markup never has the banner. Hydration needs the browser's first
       render to match it, or it fails for everyone who has already answered;
       the banner therefore waits for the render after hydration. */
    const hydrated = useSyncExternalStore(subscribeNever, isBrowserRender, isServerRender);

    useEffect(() => {
        if (ANALYTICS_ENABLED) initAnalytics();
    }, []);

    /* The footer's "Analytics choice" button clears the stored answer from
       outside this tree; listening brings the banner straight back instead of
       waiting for a reload. */
    useEffect(() => {
        const onChange = () => setChoice(readConsent());
        window.addEventListener(CONSENT_EVENT, onChange);
        return () => window.removeEventListener(CONSENT_EVENT, onChange);
    }, []);

    /* A route change is this app's page load. The query string is dropped for
       the reason HeadSync drops it, and because ?token= is a credential. */
    useEffect(() => {
        if (ANALYTICS_ENABLED) trackPageView(pathname);
    }, [pathname]);

    if (!hydrated || !ANALYTICS_ENABLED || choice) return null;

    function decide(state) {
        setConsent(state);
        setChoice(state);
    }

    return (
        <aside className="consent" aria-label={lang === 'he' ? 'הסכמה לאנליטיקס' : 'Analytics choice'}>
            {lang === 'he' ? (
                <p className="consent-text">
                    נשמח לספור ביקורים בדפים בעזרת Google Analytics, כדי לדעת אילו דפים שווה לשמור.
                    {' '}<strong>שום דבר לא נשלח בלי שתאשרו.</strong> האתר עובד אותו דבר בכל מקרה. הפרטים
                    {' '}<Link to="/privacy" hrefLang="en">במדיניות הפרטיות</Link> (באנגלית).
                </p>
            ) : (
                <p className="consent-text">
                    We would like to count page visits with Google Analytics, to see which pages are
                    worth keeping. <strong>Nothing is sent unless you accept.</strong> Either way the
                    site works the same. Details are in our{' '}
                    <Link to="/privacy">Privacy Policy</Link>.
                </p>
            )}
            <div className="consent-actions">
                <button type="button" className="consent-decline" onClick={() => decide('denied')}>
                    {lang === 'he' ? 'לא, תודה' : 'No thanks'}
                </button>
                <button type="button" className="main-button consent-accept" onClick={() => decide('granted')}>
                    {lang === 'he' ? 'אישור' : 'Accept'}
                </button>
            </div>
        </aside>
    );
}
