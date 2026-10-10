/*
 * Google Analytics 4 under Consent Mode v2, with every storage signal denied
 * until the visitor chooses.
 *
 * None of this lives in index.html, where Google's copy-paste snippet is meant
 * to go, because production serves `script-src 'self'` with no 'unsafe-inline':
 * the snippet's second <script> block would be blocked and silently measure
 * nothing. A <script> element created here is matched against script-src by its
 * URL rather than treated as inline code, so allowlisting googletagmanager.com
 * is enough and the inline exception stays switched off. See "CSP" in README.md
 * and deploy/nginx/androidmod-headers.conf.
 */

export const GA_MEASUREMENT_ID = 'G-FVWDH9BQLE';

/* Dev builds would otherwise file every reload against the live property, and a
   consent banner with no tag behind it is just a confusing box. */
export const ANALYTICS_ENABLED = import.meta.env.PROD;

const STORAGE_KEY = 'androidmod.consent.analytics';

/* Withdrawing has to be as easy as agreeing, and the footer button that
   withdraws is not in Analytics.jsx's tree. A window event is the whole
   coupling between them, which beats threading a context through the app for
   one boolean. */
export const CONSENT_EVENT = 'androidmod:consent';

/* Consent Mode v2 requires all four signals to be named even when they will
   never be granted. Only analytics_storage is ever turned on: the site shows no
   ads, so there is nothing the three ad signals would be granted for. */
const DEFAULT_CONSENT = {
    ad_storage: 'denied',
    ad_user_data: 'denied',
    ad_personalization: 'denied',
    analytics_storage: 'denied',
};

function gtag() {
    window.dataLayer = window.dataLayer || [];
    /* Pushed as the arguments object, exactly as Google's snippet does it. The
       queue reader unpacks an arguments-shaped value, and a plain
       dataLayer.push([...]) is accepted and then quietly ignored. */
    window.dataLayer.push(arguments);
}

/** 'granted', 'denied', or null when the visitor has not chosen yet. */
export function readConsent() {
    try {
        return localStorage.getItem(STORAGE_KEY);
    } catch {
        /* A private window or blocked site data throws on access rather than
           returning null. Treating that as "not chosen" shows the banner and
           sends nothing, which is the safe direction to fail in. */
        return null;
    }
}

export function setConsent(state) {
    try {
        localStorage.setItem(STORAGE_KEY, state);
    } catch {
        /* The choice will not survive a reload, so the banner asks again. Better
           than sending data the visitor did not agree to. */
    }
    gtag('consent', 'update', {analytics_storage: state});
    if (state === 'granted') {
        loadTag();
        trackPageView(window.location.pathname);
    }
}

/**
 * Forget the stored choice and stop measuring until it is made again. The
 * consent update is sent as well as the key removed, so a visitor who withdraws
 * mid-session is not tracked for the rest of it.
 */
export function clearConsent() {
    try {
        localStorage.removeItem(STORAGE_KEY);
    } catch { /* nothing was stored to begin with */ }
    gtag('consent', 'update', {analytics_storage: 'denied'});
    window.dispatchEvent(new Event(CONSENT_EVENT));
}

let started = false;
let tagLoaded = false;

/*
 * Consent Mode alone is not enough to promise that nothing is sent. A tag loaded
 * with analytics_storage denied still sends Google cookieless pings, by design,
 * so that it can model the traffic it is not allowed to measure. The Privacy
 * Policy says nothing reaches Google before you accept, so the script itself is
 * withheld until then: the consent defaults are still declared, because they are
 * what makes the granted path behave correctly, but googletagmanager.com is not
 * contacted at all until loadTag() runs.
 */
function loadTag() {
    if (tagLoaded) return;
    tagLoaded = true;
    const tag = document.createElement('script');
    tag.async = true;
    tag.src = `https://www.googletagmanager.com/gtag/js?id=${GA_MEASUREMENT_ID}`;
    document.head.appendChild(tag);
}

export function initAnalytics() {
    if (started) return;
    started = true;

    /* Queued before the tag can execute, which is the ordering Consent Mode
       requires. These pushes only sit in dataLayer until the script arrives. */
    gtag('consent', 'default', DEFAULT_CONSENT);
    gtag('js', new Date());
    gtag('config', GA_MEASUREMENT_ID, {
        /* Off because it fires once per document load, which in an SPA means the
           landing route and nothing after it. trackPageView reports the rest. */
        send_page_view: false,
        /* The Privacy Policy says the advertising features are off. Denying the
           three ad consent signals already keeps them from working, but these two
           make it hold in code too, whatever the GA property's admin settings say. */
        allow_google_signals: false,
        allow_ad_personalization_signals: false,
        page_location: strippedLocation(window.location.pathname),
        ...referrerParam(),
    });

    if (readConsent() === 'granted') {
        gtag('consent', 'update', {analytics_storage: 'granted'});
        loadTag();
    }
}

/* The address GA is allowed to see: origin and path, never the query string.
   /reset-password and /verify-email carry their token in ?token= for as long as
   the page is open, and the reset token's payload names the user. */
function strippedLocation(path) {
    return `${window.location.origin}${path}`;
}

/* The referrer leaks the same way. Referrer-Policy is
   strict-origin-when-cross-origin, which sends the FULL URL on a same-origin
   navigation, so a full page load away from /reset-password?token=... leaves the
   token in document.referrer, and GA reports that as page_referrer on every hit.
   Found by the token test, where it was the one parameter still carrying it.
   Query strings are dropped from outside referrers too; nothing here needs them. */
function referrerParam() {
    if (!document.referrer) return {};
    try {
        const url = new URL(document.referrer);
        return {page_referrer: `${url.origin}${url.pathname}`};
    } catch {
        return {};
    }
}

export function trackPageView(path) {
    if (!started) return;
    /* Set globally, not only on the page_view below. Every hit GA sends carries
       page_location - including the engagement and enhanced-measurement events
       it fires by itself, such as form_submit on the reset form - and it
       defaults to the full address bar. An event-level parameter would cover
       this one event and leave the token in all of those. Pushed whether or not
       consent is granted: it is a dataLayer entry, not a request, and it has to
       be in place before the tag runs if consent arrives later. */
    gtag('set', {page_location: strippedLocation(path), ...referrerParam()});
    /* Checked on every call rather than cached, so withdrawing consent stops the
       events immediately instead of at the next reload. */
    if (readConsent() !== 'granted') return;
    gtag('event', 'page_view');
}
