// client/src/App.jsx
import {Link, NavLink} from 'react-router';
import {apps, appText} from './apps.js';
import {formatPrice, usePrice} from './lib/price.js';
import {localPath, useLang} from './lib/i18n.js';
import {MAX_DEVICES} from './lib/site.js';
import StoreDownload from './StoreDownload.jsx';
import PageTitle from './PageTitle.jsx';
import Faq from './Faq.jsx';
import './App.css';

/*
 * The FAQ answers the questions a visitor from a search result has before they
 * care about any one app. Every claim matches a page that states it in full: the
 * ban risk is the Terms' "at your own risk" clause, the signing and updates are
 * the install guide's, the slots are DevicesCard's. Change those together.
 */
const COPY = {
    en: {
        // The English home keeps PageTitle's defaults, written for search results.
        title: undefined,
        description: undefined,
        heading: 'Patched Android apps, signed and ready to install',
        tagline: 'Patched builds of the apps you already use. Pick one to see every build we ship.',
        pricing: (price, install) => (
            <>
                Browsing is free. Downloading needs a <Link to="/subscription">subscription</Link>
                {price && <>: <strong>{price.amount}{price.period}</strong></>}, cancel anytime.
                {' '}New to this? <Link to={install}>How to install</Link>.
            </>
        ),
        view: 'View Patches',
        viewFor: (name) => ` for ${name}`,
        faqHeading: 'Questions',
        faq: [
            {
                q: 'What is a patched app?',
                a: 'It is the official app, taken from the Play Store, with changes built into it: ads removed, '
                    + 'or features the original locks away or leaves out switched on. Each build is then signed '
                    + 'with our own key so that Android will install it.',
            },
            {
                q: 'Do I keep my account?',
                a: 'Yes. You sign in to a patched app with the account you already have. The original service '
                    + 'may still limit or ban an account that uses a modified app, so patched apps are used at '
                    + 'your own risk, as our Terms say.',
            },
            {
                q: 'How do I know a build is ours?',
                a: 'Every build is signed with the same key, and the Patched Store app refuses to install '
                    + 'anything that is not signed with it. Google Play Protect still shows a warning, because it '
                    + 'has not seen our key before.',
            },
            {
                q: 'What does a subscription cover?',
                a: `Every app in the catalogue, and every new build of it. You get ${MAX_DEVICES} slots: each `
                    + 'patched app on each phone uses one, and you can free them from the Subscription page. '
                    + 'Browsing the site is free, and you can cancel at any time.',
            },
            {
                q: 'How do updates work?',
                a: 'When a new build is listed, install it over the one you have. Your data stays, because every '
                    + 'build is signed with the same key. The Patched Store app lists new builds and updates itself.',
            },
        ],
    },
    he: {
        title: 'וואטסאפ, מוביט ו-\u200E12+\u200E בגרסה משופרת לאנדרואיד',
        description:
            'גרסאות משופרות של אפליקציות אנדרואיד: מוביט ו-\u200E12+\u200E (מאקו) בלי פרסומות, וואטסאפ ששומר הודעות '
            + 'שנמחקו ומדיה לצפייה חד פעמית. חתומות ומוכנות להתקנה.',
        heading: 'אפליקציות אנדרואיד משופרות, חתומות ומוכנות להתקנה',
        tagline: 'גרסאות משופרות של האפליקציות שאתם כבר משתמשים בהן. בחרו אפליקציה כדי לראות את כל הגרסאות שלנו.',
        pricing: (price, install) => (
            <>
                הגלישה באתר חינמית. להורדה צריך <Link to="/subscription" hrefLang="en">מנוי</Link>
                {price && <>: <strong>{price.amount}{price.period}</strong></>}, שאפשר לבטל בכל עת.
                {' '}חדשים בזה? <Link to={install}>איך מתקינים</Link>.
            </>
        ),
        view: 'לכל הפרטים',
        viewFor: (name) => ` על ${name}`,
        faqHeading: 'שאלות נפוצות',
        faq: [
            {
                q: 'מה זו אפליקציה משופרת?',
                a: 'זו האפליקציה הרשמית, כמו שהיא בחנות Play, עם שינויים שנבנו לתוכה: בלי פרסומות, או עם אפשרויות '
                    + 'שהמקור נועל או לא כולל. אחר כך אנחנו חותמים כל גרסה במפתח שלנו, כדי שאנדרואיד יסכים להתקין אותה.',
            },
            {
                q: 'אפשר להמשיך עם החשבון הקיים?',
                a: 'כן. מתחברים לאפליקציה המשופרת עם החשבון הקיים. השירות המקורי עלול להגביל או לחסום חשבון שמשתמש '
                    + 'באפליקציה ששונתה, ולכן השימוש בגרסאות משופרות הוא על אחריותכם, כמו שכתוב בתנאי השימוש.',
            },
            {
                q: 'איך יודעים שהגרסה באמת שלנו?',
                a: 'כל הגרסאות חתומות באותו מפתח, ואפליקציית החנות מסרבת להתקין כל דבר שלא חתום בו. Google Play '
                    + 'Protect עדיין מציג אזהרה, כי הוא לא מכיר את המפתח שלנו.',
            },
            {
                q: 'מה כולל המנוי?',
                a: `את כל האפליקציות בקטלוג, וכל גרסה חדשה שלהן. יש לכם ${MAX_DEVICES} מקומות: כל אפליקציה משופרת `
                    + 'בכל טלפון תופסת אחד, ואפשר לפנות אותם בדף המנוי. הגלישה באתר חינמית, ואפשר לבטל בכל עת.',
            },
            {
                q: 'איך מתעדכנים?',
                a: 'כשמופיעה גרסה חדשה, מתקינים אותה מעל הגרסה הקיימת. הנתונים נשמרים, כי כל הגרסאות חתומות באותו '
                    + 'מפתח. אפליקציית החנות מציגה גרסאות חדשות ומעדכנת את עצמה.',
            },
        ],
    },
};

function App() {
    const lang = useLang();
    const t = COPY[lang];
    // Omitted, not guessed, when Stripe cannot be reached (see lib/price.js).
    const price = formatPrice(usePrice(), lang);

    return (
        <div className="page-panel home-panel">
            <PageTitle title={t.title} description={t.description}/>
            <header className="home-header">
                {/* The brand used to be the h1, which is the page's strongest heading
                    spent on a word nobody searches for. SITE_NAME still names the site
                    in the top bar (Header.jsx), so it has not left the page. */}
                <h1 className="home-title">{t.heading}</h1>
                <p className="home-tagline">{t.tagline}</p>
                <p className="home-pricing">{t.pricing(price, localPath('/install', lang))}</p>
            </header>

            <section className="home-grid">
                {Object.keys(apps).map((key) => {
                    const app = appText(key, lang);
                    return (
                        <article key={key} className="home-card">
                            <div className="home-card-head">
                                <img className="home-card-icon" src={app.icon} alt="" width="72" height="72"/>
                                <div>
                                    <h2 className="home-card-name">{app.name}</h2>
                                    {app.about && <p className="home-card-about">{app.about}</p>}
                                </div>
                            </div>
                            <ul className="home-features">
                                {app.features.map((feature) => (
                                    <li key={feature.name} className="home-feature">{feature.name}</li>
                                ))}
                            </ul>
                            <NavLink className="main-button home-card-action" to={localPath(`/apps/${key}`, lang)}>
                                {t.view}
                                <span className="visually-hidden">{t.viewFor(app.name)}</span>
                            </NavLink>
                        </article>
                    );
                })}
            </section>

            <StoreDownload/>

            <Faq id="home-faq" heading={t.faqHeading} items={t.faq}/>
        </div>
    );
}

export default App;
