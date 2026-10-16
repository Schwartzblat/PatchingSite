// client/src/PatchedAppPage.jsx
import {useEffect, useRef, useState} from 'react';
import {Link, useLocation, useParams} from 'react-router';
import {api, ApiError, formatBytes, isSubscriptionActive} from './lib/api.js';
import {useAuth} from './lib/auth.jsx';
import {loginPath} from './lib/redirect.js';
import {apps, appText} from './apps.js';
import {localPath, useLang} from './lib/i18n.js';
import PageTitle from './PageTitle.jsx';
import AppSchema from './AppSchema.jsx';
import Faq from './Faq.jsx';
import StoreDownload from './StoreDownload.jsx';
import './PatchedAppPage.css';

/*
 * Everything this page says, in both languages. Messages the server writes
 * (a build's progress text, most error details) arrive in English and are
 * shown as they come; only the ones this page writes itself are here.
 */
const COPY = {
    en: {
        notFoundTitle: 'App not found',
        missingHeading: 'We don\u2019t patch that app',
        missingText: (key) => (
            <>There is no app called <code className="pap-code">{key}</code> in the catalogue. It may have
                been renamed or retired.</>
        ),
        backToApps: 'Back to all apps',
        title: (text) => `${text.name} patches`,
        heading: (name) => `Patched ${name}`,
        installHint: (to) => (
            <>Installing for the first time? Uninstall the Play Store version first, then follow
                {' '}<Link to={to}>how to install</Link>.</>
        ),
        guest: (to) => <>You are browsing as a guest. <Link to={to}>Sign in</Link> to download patched builds.</>,
        needsSubscription: <>Downloads need an active subscription. <Link to="/subscription">See the plans</Link>.</>,
        subscriptionCheckFailed: (detail) => `We could not check your subscription (${detail}). You can still try a download.`,
        subscriptionCheckDefault: 'We could not check your subscription.',
        ready: (name, version) => `${name} ${version} is ready \u2014 your download should have started`,
        readyFallback: 'Nothing happened? Download it here (the link works for 10 minutes).',
        dismiss: 'Dismiss',
        goSignIn: 'Go to sign in',
        seePlans: 'See the plans',
        listFailed: 'Failed to load builds.',
        listErrorPill: 'Something went wrong',
        noBuilds: 'No patched builds yet. Check back soon.',
        onDeviceHeading: 'Get it through the store app',
        onDeviceText: (name) => `${name} is now patched on your phone, so there is nothing to download here. Install the store app, sign in, and it does the rest.`,
        fetching: 'Fetching builds\u2026',
        count: (n) => `${n} build${n === 1 ? '' : 's'} ready`,
        xapk: (to) => (
            <>Split bundle: a browser cannot install it.{' '}<Link to={to}>Install with the Patched Store app</Link>.</>
        ),
        size: 'Download size',
        updated: 'Updated',
        immediate: 'Already built \u2014 starting your download\u2026',
        requesting: 'Requesting a build\u2026',
        building: (name, version) => `Building ${name} ${version}`,
        retrying: 'Connection interrupted, still trying\u2026',
        queued: 'Queued\u2026',
        resigning: 'Re-signing the APK\u2026',
        preparing: 'Preparing\u2026',
        download: 'Download',
        lostContact: 'We lost contact with the server while your build was being prepared. '
            + 'Check your connection and start the download again.',
        jobGone: 'The server no longer has this build request (it may have restarted). Start the download again.',
        noLink: 'The build finished but the server did not return a download link. Please try again.',
        badLink: 'The build finished but the download link was not valid. Please try again.',
        buildFailed: 'The build failed. Please try again.',
        signInFirst: 'You need to sign in before you can download patched builds.',
        noJob: 'The server did not return a download job.',
        sessionExpired: 'Your session has expired. Sign in again to download this build.',
        subscriptionRequired: 'An active subscription is required to download patched builds.',
        generic: 'Something went wrong. Please try again.',
        patchesHeading: (name) => `What the patched ${name} changes`,
        faqHeading: 'Questions',
        guideLink: 'Read the full install guide',
    },
    he: {
        notFoundTitle: 'האפליקציה לא נמצאה',
        missingHeading: 'לאפליקציה הזאת אין לנו גרסה',
        missingText: (key) => (
            <>אין בקטלוג אפליקציה בשם <code className="pap-code" dir="ltr">{key}</code>. ייתכן שהשם שלה
                השתנה או שהיא הוסרה.</>
        ),
        backToApps: 'חזרה לכל האפליקציות',
        title: (text) => text.title,
        heading: (name) => `גרסה משופרת של ${name}`,
        installHint: (to) => (
            <>מתקינים בפעם הראשונה? קודם מסירים את הגרסה מחנות Play, ואז ממשיכים
                לפי <Link to={to}>מדריך ההתקנה</Link>.</>
        ),
        guest: (to) => <>אתם גולשים כאורחים. <Link to={to}>התחברו</Link> כדי להוריד גרסאות משופרות.</>,
        needsSubscription: <>להורדה צריך מנוי פעיל. <Link to="/subscription" hrefLang="en">לתוכניות המנוי</Link>.</>,
        subscriptionCheckFailed: (detail) => `לא הצלחנו לבדוק את המנוי שלכם (${detail}). אפשר בכל זאת לנסות להוריד.`,
        subscriptionCheckDefault: 'לא הצלחנו לבדוק את המנוי שלכם.',
        ready: (name, version) => `הגרסה ${version} של ${name} מוכנה, וההורדה אמורה להתחיל`,
        readyFallback: 'לא קרה כלום? אפשר להוריד מכאן (הקישור תקף ל-10 דקות).',
        dismiss: 'סגירה',
        goSignIn: 'להתחברות',
        seePlans: 'לתוכניות המנוי',
        listFailed: 'לא הצלחנו לטעון את הגרסאות.',
        listErrorPill: 'משהו השתבש',
        noBuilds: 'עדיין אין גרסאות משופרות. כדאי לבדוק שוב בקרוב.',
        onDeviceHeading: 'מקבלים אותה דרך אפליקציית החנות',
        onDeviceText: (name) => `${name} עוברת עכשיו שיפור ישירות בטלפון, ולכן אין כאן מה להוריד. מתקינים את אפליקציית החנות, מתחברים, והיא עושה את השאר.`,
        fetching: 'טוענים גרסאות\u2026',
        count: (n) => (n === 1 ? 'גרסה אחת מוכנה' : `${n} גרסאות מוכנות`),
        xapk: (to) => (
            <>חבילה מפוצלת: אי אפשר להתקין אותה מהדפדפן.{' '}<Link to={to}>מתקינים עם אפליקציית החנות</Link>.</>
        ),
        size: 'גודל ההורדה',
        updated: 'עודכן',
        immediate: 'הגרסה כבר מוכנה, ההורדה מתחילה\u2026',
        requesting: 'מבקשים גרסה\u2026',
        building: (name, version) => `מכינים את ${name} ${version}`,
        retrying: 'החיבור נקטע, ממשיכים לנסות\u2026',
        queued: 'בתור\u2026',
        resigning: 'חותמים את ה-APK מחדש\u2026',
        preparing: 'מכינים\u2026',
        download: 'הורדה',
        lostContact: 'הקשר עם השרת נקטע בזמן שהגרסה שלכם הוכנה. בדקו את החיבור והתחילו את ההורדה מחדש.',
        jobGone: 'בקשת ההכנה כבר לא קיימת בשרת (ייתכן שהוא הופעל מחדש). התחילו את ההורדה מחדש.',
        noLink: 'הגרסה מוכנה, אבל השרת לא החזיר קישור להורדה. נסו שוב.',
        badLink: 'הגרסה מוכנה, אבל קישור ההורדה לא היה תקין. נסו שוב.',
        buildFailed: 'הכנת הגרסה נכשלה. נסו שוב.',
        signInFirst: 'צריך להתחבר לפני שמורידים גרסאות משופרות.',
        noJob: 'השרת לא החזיר בקשת הורדה.',
        sessionExpired: 'פג תוקף החיבור שלכם. התחברו שוב כדי להוריד את הגרסה הזאת.',
        subscriptionRequired: 'להורדת גרסאות משופרות צריך מנוי פעיל.',
        /* The server's 402 is always the bare "Subscription required", so the
           Hebrew page says it in its own words; the English page keeps
           showing the server's text, as it always has. */
        ownSubscriptionMessage: true,
        generic: 'משהו השתבש. נסו שוב.',
        patchesHeading: () => 'מה משתנה בגרסה המשופרת',
        faqHeading: 'שאלות נפוצות',
        guideLink: 'למדריך ההתקנה המלא',
    },
};

const POLL_BASE_MS = 1000;
const POLL_MAX_MS = 5000;
const POLL_TIMEOUT_MS = 15_000;

/*
 * A signing job takes seconds to a couple of minutes, and one dropped poll
 * (mobile data flapping, nginx returning a 502 while the service restarts)
 * used to throw the whole job away. Transient failures now retry with backoff
 * until they have been failing for RETRY_WINDOW_MS in a row; only a job the
 * server reports as failed, or a 4xx (job gone, session gone), ends it early.
 */
const RETRY_BASE_MS = 2000;
const RETRY_MAX_MS = 15_000;
const RETRY_WINDOW_MS = 2 * 60 * 1000;

function pollDelay(attempt) {
    return Math.min(POLL_BASE_MS * 1.5 ** attempt, POLL_MAX_MS);
}

function retryDelay(failures) {
    return Math.min(RETRY_BASE_MS * 2 ** (failures - 1), RETRY_MAX_MS);
}

/** No response at all, a 5xx, or the two 4xx statuses that mean "try later". */
function isTransient(err) {
    return err instanceof ApiError
        && (err.status === 0 || err.status >= 500 || err.status === 408 || err.status === 429);
}

/** The list endpoint sends a stable `id`; fall back to the version so a card
 *  can never share an identity with another one. */
function versionKey(version) {
    return version.id ?? version.version;
}

/*
 * Newest first, comparing dotted components as numbers. The server sorts the
 * version directory names as strings, which puts 2.25.9 above 2.25.10.
 * A component that is not all digits ("beta") falls back to text order.
 */
function compareVersions(a, b) {
    const left = String(a).split(/[.\-_+]/);
    const right = String(b).split(/[.\-_+]/);
    for (let i = 0; i < Math.max(left.length, right.length); i += 1) {
        const x = left[i];
        const y = right[i];
        if (x === undefined) return -1;
        if (y === undefined) return 1;
        const diff = /^\d+$/.test(x) && /^\d+$/.test(y) ? Number(x) - Number(y) : x.localeCompare(y);
        if (diff !== 0) return Math.sign(diff);
    }
    return 0;
}

function newestFirst(versions) {
    return [...versions].sort((a, b) => compareVersions(b.version, a.version));
}

function isPolling(state) {
    return state === 'queued' || state === 'running';
}

/** The download URL is server-supplied, so it is validated as http(s) before it
 *  ever reaches an href or a programmatic click. React already blocks
 *  `javascript:` hrefs, so this is defence-in-depth against a compromised or
 *  misconfigured server handing us a `javascript:` or `data:` URL. Relative URLs
 *  resolve against the site origin (https) and stay safe. */
function isSafeDownloadUrl(url) {
    if (typeof url !== 'string') return false;
    try {
        const {protocol} = new URL(url, window.location.origin);
        return protocol === 'http:' || protocol === 'https:';
    } catch {
        return false;
    }
}

/** Started automatically once. The signed URL stays valid for 10 minutes and
 *  can be fetched again in that window, which is what the "Nothing happened?"
 *  fallback link relies on. */
function triggerBrowserDownload(url, filename) {
    const link = document.createElement('a');
    link.href = url;
    link.rel = 'noopener';
    if (filename) link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
}

function describeError(err, t) {
    if (err instanceof ApiError) {
        if (err.status === 401) {
            return {kind: 'auth', message: t.sessionExpired};
        }
        if (err.status === 402) {
            return {
                kind: 'subscription',
                message: (!t.ownSubscriptionMessage && err.message) || t.subscriptionRequired,
            };
        }
        return {kind: 'error', message: err.message};
    }
    return {kind: 'error', message: t.generic};
}

function PatchedAppPage() {
    const {appKey} = useParams();
    const lang = useLang();
    const t = COPY[lang];

    if (!Object.hasOwn(apps, appKey)) {
        return (
            <div className="page-panel pap-panel">
                <PageTitle title={t.notFoundTitle} noindex/>
                <div className="pap-missing">
                    <h1 className="pap-missing-title">{t.missingHeading}</h1>
                    <p className="pap-missing-text">{t.missingText(appKey)}</p>
                    <Link className="main-button" to={localPath('/', lang)}>{t.backToApps}</Link>
                </div>
            </div>
        );
    }

    /* Remounting on appKey resets the version list and any in-flight download
       job; so does switching language, which is a different route. */
    return <AppVersions key={appKey} app={appText(appKey, lang)} lang={lang} t={t}/>;
}

function AppVersions({app, lang, t}) {
    const {user, loading: authLoading} = useAuth();
    const signedIn = Boolean(user);
    const location = useLocation();
    // Guests who sign in from here should come straight back to this app.
    const signInTo = loginPath(`${location.pathname}${location.search}`);

    const [versions, setVersions] = useState([]);
    const [listLoading, setListLoading] = useState(true);
    const [listError, setListError] = useState(null);
    const [subscription, setSubscription] = useState(undefined);
    const [subscriptionError, setSubscriptionError] = useState(null);
    const [job, setJob] = useState(null);
    // From the catalogue; a failed lookup just leaves the usual build list.
    const [onDevice, setOnDevice] = useState(false);

    const mountedRef = useRef(true);
    useEffect(() => {
        mountedRef.current = true;
        return () => {
            mountedRef.current = false;
        };
    }, []);

    useEffect(() => {
        const controller = new AbortController();
        api.listVersions(app.package, {signal: controller.signal})
            .then((payload) => {
                setVersions(Array.isArray(payload) ? newestFirst(payload) : []);
                setListError(null);
                setListLoading(false);
            })
            .catch((err) => {
                if (err?.name === 'AbortError') return;
                setVersions([]);
                setListError(err?.message ?? t.listFailed);
                setListLoading(false);
            });
        return () => controller.abort();
    }, [app.package, t]);

    useEffect(() => {
        const controller = new AbortController();
        api.listApps({signal: controller.signal})
            .then((items) => {
                const mine = Array.isArray(items) ? items.find((item) => item.package === app.package) : null;
                setOnDevice(mine?.on_device === true);
            })
            .catch(() => {});
        return () => controller.abort();
    }, [app.package]);

    // Entitlement is only a hint for the UI; the server is the authority, so a
    // failure here surfaces a notice instead of silently blocking downloads.
    useEffect(() => {
        if (!signedIn) return undefined;
        const controller = new AbortController();
        api.getSubscription({signal: controller.signal})
            .then((data) => {
                setSubscription(data?.subscription ?? null);
                setSubscriptionError(null);
            })
            .catch((err) => {
                if (err?.name === 'AbortError') return;
                setSubscriptionError(err?.message ?? t.subscriptionCheckDefault);
            });
        return () => controller.abort();
    }, [signedIn, t]);

    const activeJob = job && (job.state === 'starting' || isPolling(job.state)) ? job : null;
    const busy = activeJob !== null;
    const pollingId = job && isPolling(job.state) ? job.jobId : null;

    useEffect(() => {
        if (!pollingId) return undefined;

        const controller = new AbortController();
        let timer = null;
        let attempt = 0;
        let stopped = false;
        let failures = 0;
        let failingSince = null;

        const settle = (update) => {
            stopped = true;
            setJob((prev) => (prev && prev.jobId === pollingId ? {...prev, ...update} : prev));
        };

        const markRetrying = (retrying) => {
            setJob((prev) => (prev && prev.jobId === pollingId && prev.retrying !== retrying
                ? {...prev, retrying}
                : prev));
        };

        const poll = async () => {
            timer = null;
            let data;
            try {
                // Shorter than the default deadline: a poll is a dictionary
                // lookup, so 15 s of silence means the connection is gone.
                data = await api.getDownloadJob(pollingId, {signal: controller.signal, timeout: POLL_TIMEOUT_MS});
            } catch (err) {
                if (stopped || err?.name === 'AbortError') return;
                if (isTransient(err)) {
                    failures += 1;
                    failingSince ??= Date.now();
                    if (Date.now() - failingSince < RETRY_WINDOW_MS) {
                        markRetrying(true);
                        timer = setTimeout(poll, retryDelay(failures));
                        return;
                    }
                    settle({
                        state: 'failed',
                        kind: 'error',
                        message: t.lostContact,
                    });
                    return;
                }
                if (err instanceof ApiError && err.status === 404) {
                    // Jobs live in server memory, so a restart forgets them.
                    settle({
                        state: 'failed',
                        kind: 'error',
                        message: t.jobGone,
                    });
                    return;
                }
                const failure = describeError(err, t);
                settle({state: 'failed', kind: failure.kind, message: failure.message});
                return;
            }
            if (stopped) return;
            if (failures > 0) {
                failures = 0;
                failingSince = null;
                markRetrying(false);
            }

            if (data?.state === 'ready') {
                if (!data.download_url) {
                    settle({
                        state: 'failed',
                        kind: 'error',
                        message: t.noLink,
                    });
                    return;
                }
                // Refuse a non-http(s) link rather than feeding it to the DOM.
                if (!isSafeDownloadUrl(data.download_url)) {
                    settle({
                        state: 'failed',
                        kind: 'error',
                        message: t.badLink,
                    });
                    return;
                }
                settle({
                    state: 'ready',
                    progress: 1,
                    message: null,
                    downloadUrl: data.download_url,
                    filename: data.filename ?? null,
                    size: typeof data.size === 'number' ? data.size : null,
                });
                triggerBrowserDownload(data.download_url, data.filename);
                return;
            }

            if (data?.state === 'failed') {
                settle({
                    state: 'failed',
                    kind: 'error',
                    message: data.message || t.buildFailed,
                });
                return;
            }

            setJob((prev) => (prev && prev.jobId === pollingId
                ? {
                    ...prev,
                    state: data?.state === 'running' ? 'running' : 'queued',
                    progress: typeof data?.progress === 'number' ? data.progress : prev.progress,
                    message: data?.message ?? null,
                }
                : prev));

            attempt += 1;
            timer = setTimeout(poll, pollDelay(attempt));
        };

        // Checked immediately so a cached build never sits behind a poll interval.
        timer = setTimeout(poll, 0);

        return () => {
            stopped = true;
            if (timer !== null) clearTimeout(timer);
            controller.abort();
        };
    }, [pollingId, t]);

    async function startDownload(version) {
        if (busy) return;

        const base = {
            versionId: versionKey(version),
            versionLabel: version.version,
            jobId: null,
            state: 'starting',
            kind: null,
            message: null,
            progress: 0,
            immediate: false,
            retrying: false,
            downloadUrl: null,
            filename: null,
            size: null,
        };

        if (!authLoading && !signedIn) {
            setJob({
                ...base,
                state: 'failed',
                kind: 'auth',
                message: t.signInFirst,
            });
            return;
        }

        setJob(base);

        try {
            const created = await api.createDownload(app.package, version.version);
            if (!mountedRef.current) return;
            setJob((prev) => (prev && prev.versionId === versionKey(version) && prev.state === 'starting'
                ? {
                    ...prev,
                    jobId: created?.job_id ?? null,
                    state: created?.job_id ? 'queued' : 'failed',
                    kind: created?.job_id ? null : 'error',
                    message: created?.job_id ? null : t.noJob,
                    immediate: Boolean(created?.cached) || created?.state === 'ready',
                }
                : prev));
        } catch (err) {
            if (!mountedRef.current) return;
            const failure = describeError(err, t);
            setJob((prev) => (prev && prev.versionId === versionKey(version) && prev.state === 'starting'
                ? {...prev, state: 'failed', kind: failure.kind, message: failure.message}
                : prev));
        }
    }

    const statusText = listLoading ? t.fetching : t.count(versions.length);

    const needsSubscription = signedIn && subscription !== undefined && !isSubscriptionActive(subscription);

    const skeletons = Array.from({length: 4}, (_, index) => (
        <div className="pap-card pap-skeleton" key={`skeleton-${index}`} aria-hidden="true">
            <span className="pap-skeleton-line pap-skeleton-line--title"/>
            <span className="pap-skeleton-line"/>
            <span className="pap-skeleton-line pap-skeleton-line--meta"/>
        </div>
    ));

    const installPath = localPath('/install', lang);
    const heading = t.heading(app.name);

    return (
        <div className="page-panel pap-panel">
            <PageTitle title={t.title(app)} description={app.description}/>
            <AppSchema app={app} name={heading} path={location.pathname} lang={lang}/>
            <header className="pap-header">
                <img className="pap-icon" src={app.icon} alt="" width="72" height="72"/>
                <div className="pap-meta">
                    <p className="pap-package">{app.package}</p>
                    <h1 className="pap-name">{heading}</h1>
                    {app.about && <p className="pap-about">{app.about}</p>}
                </div>
                <div className="pap-status-bar">
                    {!onDevice && <span className="pap-pill">{statusText}</span>}
                    {listError && <span className="pap-pill pap-pill--error">{t.listErrorPill}</span>}
                </div>
                <p className="pap-install-hint">{t.installHint(installPath)}</p>
            </header>

            {/* What the build is, in a sentence or two, for a visitor who arrived
                from a search result rather than from the home page's card. */}
            <p className="pap-intro">{app.intro}</p>

            {onDevice && (
                <section className="pap-section" aria-labelledby="pap-on-device-title">
                    <h2 id="pap-on-device-title" className="pap-section-title">{t.onDeviceHeading}</h2>
                    <p>{t.onDeviceText(app.name)}</p>
                    <StoreDownload/>
                </section>
            )}

            {!onDevice && <div className="pap-notices">
                {!authLoading && !signedIn && (
                    <p className="alert alert-info">{t.guest(signInTo)}</p>
                )}
                {needsSubscription && (
                    <p className="alert alert-info">{t.needsSubscription}</p>
                )}
                {subscriptionError && (
                    <p className="alert alert-info">{t.subscriptionCheckFailed(subscriptionError)}</p>
                )}

                <div className="pap-job-region" role="status" aria-live="polite">
                    {job?.state === 'ready' && (
                        <p className="alert alert-success pap-job-alert">
                            <span>
                                {t.ready(app.name, job.versionLabel)}
                                {job.filename ? ` (${job.filename}` : ''}
                                {job.filename && typeof job.size === 'number' ? `, ${formatBytes(job.size)}` : ''}
                                {job.filename ? ')' : ''}.{' '}
                                {job.downloadUrl && isSafeDownloadUrl(job.downloadUrl) && (
                                    <a href={job.downloadUrl} download={job.filename ?? undefined}>
                                        {t.readyFallback}
                                    </a>
                                )}
                            </span>
                            <button type="button" className="pap-dismiss" onClick={() => setJob(null)}>{t.dismiss}</button>
                        </p>
                    )}
                    {job?.state === 'failed' && (
                        <p className="alert alert-error pap-job-alert">
                            <span>
                                {job.message}{' '}
                                {job.kind === 'auth' && <Link to={signInTo}>{t.goSignIn}</Link>}
                                {job.kind === 'subscription' && <Link to="/subscription">{t.seePlans}</Link>}
                            </span>
                            <button type="button" className="pap-dismiss" onClick={() => setJob(null)}>{t.dismiss}</button>
                        </p>
                    )}
                </div>
            </div>}

            {!onDevice && <section className="pap-grid" aria-live="polite" aria-busy={listLoading}>
                <p className="visually-hidden">{statusText}</p>
                {listLoading && skeletons}
                {!listLoading && listError && (
                    <div className="pap-message pap-message--error">
                        <p>{listError}</p>
                    </div>
                )}
                {!listLoading && !listError && versions.length === 0 && (
                    <div className="pap-message">
                        <p>{t.noBuilds}</p>
                    </div>
                )}
                {!listLoading && !listError && versions.map((version) => {
                    const mine = activeJob?.versionId === versionKey(version);
                    const percent = mine && typeof activeJob.progress === 'number' && activeJob.progress > 0
                        ? Math.min(100, Math.round(activeJob.progress * 100))
                        : null;

                    return (
                        <article key={versionKey(version)} className="pap-card">
                            <div className="pap-card-head">
                                <p className="pap-version">{version.version}</p>
                                <span className="pap-type">{version.type}</span>
                            </div>
                            {/* A split bundle cannot be installed from a browser at all. */}
                            {String(version.type).toLowerCase() === 'xapk' && (
                                <p className="pap-note">{t.xapk(`${installPath}#xapk`)}</p>
                            )}
                            <dl className="pap-details">
                                <div className="pap-detail">
                                    <dt>{t.size}</dt>
                                    <dd>{formatBytes(version.size)}</dd>
                                </div>
                                <div className="pap-detail">
                                    <dt>{t.updated}</dt>
                                    <dd>{version.updated_at}</dd>
                                </div>
                            </dl>

                            {mine && (
                                <div className="pap-progress-wrap">
                                    {activeJob.state === 'starting' || activeJob.immediate ? (
                                        <p className="pap-progress-label">
                                            {activeJob.immediate ? t.immediate : t.requesting}
                                        </p>
                                    ) : (
                                        <>
                                            <div
                                                className="pap-progress"
                                                role="progressbar"
                                                aria-label={t.building(app.name, version.version)}
                                                aria-valuemin={0}
                                                aria-valuemax={100}
                                                aria-valuenow={percent ?? undefined}
                                            >
                                                <span
                                                    className={percent === null
                                                        ? 'pap-progress-fill pap-progress-fill--pending'
                                                        : 'pap-progress-fill'}
                                                    style={percent === null ? undefined : {width: `${percent}%`}}
                                                />
                                            </div>
                                            <p className="pap-progress-label">
                                                {activeJob.retrying
                                                    ? t.retrying
                                                    : activeJob.message
                                                        || (activeJob.state === 'queued' ? t.queued : t.resigning)}
                                                {percent !== null && <span className="pap-progress-percent"> {percent}%</span>}
                                            </p>
                                        </>
                                    )}
                                </div>
                            )}

                            <button
                                type="button"
                                className="main-button pap-download"
                                onClick={() => startDownload(version)}
                                disabled={busy}
                                aria-busy={mine}
                            >
                                {mine ? t.preparing : t.download}
                                <span className="visually-hidden"> {app.name} {version.version}</span>
                            </button>
                        </article>
                    );
                })}
            </section>}

            {/* The page used to be little more than the build list, which loads
                from the API and so is not in the prerendered file: a search engine
                saw a heading and an empty grid, while the description promised
                features the page never named. These two sections are the page's
                substance for anyone who has not subscribed yet. */}
            <section className="pap-section" aria-labelledby="pap-patches-title">
                <h2 id="pap-patches-title" className="pap-section-title">{t.patchesHeading(app.name)}</h2>
                <dl className="pap-patches">
                    {app.features.map((feature) => (
                        <div key={feature.name} className="pap-patch">
                            <dt className="pap-patch-name">{feature.name}</dt>
                            <dd className="pap-patch-detail">{feature.detail}</dd>
                        </div>
                    ))}
                </dl>
            </section>

            <Faq id="pap-faq" heading={t.faqHeading} items={app.faq}>
                <p className="faq-a"><Link to={installPath}>{t.guideLink}</Link></p>
            </Faq>
        </div>
    );
}

export default PatchedAppPage;
