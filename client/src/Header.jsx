import {Link, NavLink, useLocation, useNavigate} from 'react-router';
import {useAuth} from './lib/auth.jsx';
import {loginPath} from './lib/redirect.js';
import {SITE_NAME} from './lib/site.js';
import {localPath, translations, useLang} from './lib/i18n.js';
import './Header.css';

const COPY = {
    en: {
        nav: 'Main', home: 'Home', subscription: 'Subscription', checking: 'Checking your session',
        logout: 'Log out', login: 'Log in', register: 'Register',
    },
    he: {
        nav: 'ראשי', home: 'דף הבית', subscription: 'מנוי', checking: 'בודקים את החיבור שלך',
        logout: 'התנתקות', login: 'התחברות', register: 'הרשמה',
    },
};

/* NavLink sets aria-current="page" on the active link for us. */
function navLinkClass({isActive}) {
    return isActive ? 'topbar-link topbar-link--active' : 'topbar-link';
}

function Header() {
    const {user, loading, logout} = useAuth();
    const navigate = useNavigate();
    const location = useLocation();
    const lang = useLang();
    const t = COPY[lang];

    async function onLogout() {
        try {
            await logout();
        } finally {
            // The old handler assigned window.location.href, which reloaded the
            // whole bundle just to get back to "/".
            navigate(localPath('/', lang));
        }
    }

    const username = user?.username ?? user?.email ?? null;
    const here = `${location.pathname}${location.search}${location.hash}`;
    const loginTo = loginPath(here);
    /* Offered only where the other version exists. Elsewhere (sign-in,
       subscription, legal) the page is English only, and a switch that led
       to a different page would be a surprise. */
    const pair = translations(location.pathname);

    return (
        <header className="topbar">
            <div className="topbar-inner">
                <Link to={localPath('/', lang)} className="topbar-brand">{SITE_NAME}</Link>

                <nav className="topbar-nav" aria-label={t.nav}>
                    <NavLink to={localPath('/', lang)} end className={navLinkClass}>{t.home}</NavLink>
                    <NavLink to="/subscription" className={navLinkClass}>{t.subscription}</NavLink>
                    {/* Admins only, in English like the page. user is null on the
                        first render in the build and in the browser alike, so this
                        cannot break hydration. The server checks for itself. */}
                    {user?.is_admin && <NavLink to="/admin" className={navLinkClass}>Admin</NavLink>}
                </nav>

                {pair && (lang === 'he' ? (
                    <Link className="topbar-link topbar-lang" to={pair.en} lang="en" hrefLang="en">English</Link>
                ) : (
                    <Link className="topbar-link topbar-lang" to={pair.he} lang="he" hrefLang="he">עברית</Link>
                ))}

                <div className="topbar-account">
                    {loading ? (
                        <span className="topbar-pending" role="status">
                            <span className="visually-hidden">{t.checking}</span>
                        </span>
                    ) : username ? (
                        <>
                            <span className="topbar-user" title={username}>{username}</span>
                            <button type="button" className="topbar-btn" onClick={onLogout}>
                                {t.logout}
                            </button>
                        </>
                    ) : (
                        <>
                            <Link className="topbar-btn" to={loginTo}>{t.login}</Link>
                            <Link className="topbar-btn topbar-btn--primary" to="/register">{t.register}</Link>
                        </>
                    )}
                </div>
            </div>
        </header>
    );
}

export default Header;
