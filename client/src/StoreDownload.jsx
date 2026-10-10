import {useEffect, useState} from 'react';
import {Link} from 'react-router';
import {api, formatBytes, storeDownloadUrl} from './lib/api.js';
import {MIN_ANDROID} from './lib/site.js';
import {localPath, useLang} from './lib/i18n.js';

/* Android's own prompts are described by what they do, not quoted, in Hebrew:
   their wording on a Hebrew-language phone varies by maker and version. */
const COPY = {
    en: {
        title: 'Get the Android app',
        body: 'Browse, install and update every patched build straight from your phone. Sign in with your site account.',
        steps: [
            <>Download the APK on your phone ({MIN_ANDROID} or newer).</>,
            <>Open it, and allow your browser to install unknown apps if Android asks.</>,
            <>If Play Protect warns about an app it has not scanned, tap <strong>Install anyway</strong>.</>,
        ],
        updated: 'updated',
        guide: 'Full install guide',
        action: 'Download APK',
    },
    he: {
        title: 'אפליקציית החנות לאנדרואיד',
        body: 'מחפשים, מתקינים ומעדכנים כל גרסה משופרת ישירות מהטלפון. מתחברים עם חשבון האתר.',
        steps: [
            <>מורידים את קובץ ה-APK בטלפון ({MIN_ANDROID} ומעלה).</>,
            <>פותחים אותו, ואם אנדרואיד מבקש, מאשרים לדפדפן להתקין אפליקציות ממקורות לא ידועים.</>,
            <>אם Play Protect מזהיר על אפליקציה שלא נסרקה, בוחרים <strong>להתקין בכל זאת</strong>.</>,
        ],
        updated: 'עודכן',
        guide: 'מדריך ההתקנה המלא',
        action: 'הורדת APK',
    },
};

/*
 * The Android store app (android/). Rendered only once the server confirms a
 * build is deployed, so a missing store.apk hides the offer rather than showing
 * a button that 404s.
 *
 * `steps` adds the three things that trip up a first sideload, plus a link to
 * the full guide. The guide itself embeds this box with steps={false}, since
 * the page around it already explains them.
 */
function StoreDownload({steps = true}) {
    const [store, setStore] = useState(null);
    const lang = useLang();
    const t = COPY[lang];

    useEffect(() => {
        const controller = new AbortController();
        api.getStore({signal: controller.signal})
            .then(setStore)
            // Not worth an error banner on the home page: the section just stays hidden.
            .catch(() => {});
        return () => controller.abort();
    }, []);

    if (!store?.available) return null;

    return (
        <section className="store-download" aria-labelledby="store-download-title">
            <div className="store-download-text">
                <h2 id="store-download-title" className="store-download-title">{t.title}</h2>
                <p className="store-download-body">{t.body}</p>
                {steps && (
                    <ol className="store-download-steps">
                        {t.steps.map((step, index) => <li key={index}>{step}</li>)}
                    </ol>
                )}
                <p className="store-download-meta">
                    APK, {formatBytes(store.size)} · {t.updated} {store.updated_at}
                    {steps && (
                        <>
                            {' · '}
                            <Link className="store-download-guide" to={localPath('/install', lang)}>{t.guide}</Link>
                        </>
                    )}
                </p>
            </div>
            <a className="main-button store-download-action" href={storeDownloadUrl} download={store.filename}>
                {t.action}
            </a>
        </section>
    );
}

export default StoreDownload;
