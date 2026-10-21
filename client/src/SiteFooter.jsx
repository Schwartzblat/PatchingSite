import {useRef, useState} from 'react';
import {Link} from 'react-router';
import BugReportDialog from './BugReportDialog.jsx';
import {SUPPORT_MAILTO} from './lib/site.js';
import {ANALYTICS_ENABLED, clearConsent} from './lib/analytics.js';
import {localPath, useLang} from './lib/i18n.js';
import './SiteFooter.css';

/* The legal pages exist in English only; their links keep hrefLang="en" so the
   switch of language is announced rather than a surprise. */
const COPY = {
    en: {
        nav: 'Site', install: 'How to install', terms: 'Terms', privacy: 'Privacy', refunds: 'Refunds',
        contact: 'Contact', analytics: 'Analytics choice', bug: 'Report a bug',
        note: 'Not affiliated with or endorsed by WhatsApp. App names and icons belong to their owners.',
    },
    he: {
        nav: 'האתר', install: 'איך מתקינים', terms: 'תנאי שימוש', privacy: 'פרטיות', refunds: 'החזרים',
        contact: 'צור קשר', analytics: 'הסכמה לאנליטיקס', bug: 'דיווח על תקלה',
        note: 'האתר אינו קשור ל-WhatsApp ואינו מאושר על ידה. שמות האפליקציות והסמלים שלהן שייכים לבעליהם.',
    },
};

/**
 * On every page, including /login and /register, which sit outside <Layout>
 * and render it through <BareLayout>. The legal pages have to be reachable
 * from the pages where someone agrees to them.
 */
function SiteFooter() {
    const [reporting, setReporting] = useState(false);
    const reportButtonRef = useRef(null);
    const lang = useLang();
    const t = COPY[lang];
    const legalLang = lang === 'en' ? undefined : 'en';

    return (
        <footer className="site-footer">
            <nav aria-label={t.nav}>
                <ul className="site-footer-links">
                    <li><Link className="site-footer-link" to={localPath('/install', lang)}>{t.install}</Link></li>
                    <li><Link className="site-footer-link" to="/terms" hrefLang={legalLang}>{t.terms}</Link></li>
                    <li><Link className="site-footer-link" to="/privacy" hrefLang={legalLang}>{t.privacy}</Link></li>
                    <li><Link className="site-footer-link" to="/refunds" hrefLang={legalLang}>{t.refunds}</Link></li>
                    <li><a className="site-footer-link" href={SUPPORT_MAILTO}>{t.contact}</a></li>
                    {/* Only where there is a tag to consent to, so a dev build does
                        not offer a choice that measures nothing either way. */}
                    {ANALYTICS_ENABLED && (
                        <li>
                            <button
                                type="button"
                                className="site-footer-link site-footer-button"
                                onClick={clearConsent}
                            >
                                {t.analytics}
                            </button>
                        </li>
                    )}
                    <li>
                        <button
                            ref={reportButtonRef}
                            type="button"
                            className="site-footer-link site-footer-button"
                            onClick={() => setReporting(true)}
                            aria-haspopup="dialog"
                        >
                            {t.bug}
                        </button>
                    </li>
                </ul>
            </nav>
            <p className="site-footer-note">{t.note}</p>
            {reporting && (
                <BugReportDialog onClose={() => setReporting(false)} returnFocusRef={reportButtonRef}/>
            )}
        </footer>
    );
}

export default SiteFooter;
