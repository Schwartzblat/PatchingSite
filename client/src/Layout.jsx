import {Outlet, useLocation} from 'react-router';
import Header from './Header.jsx';
import SiteFooter from './SiteFooter.jsx';
import ErrorBoundary from './ErrorBoundary.jsx';
import VerifyEmailBanner from './VerifyEmailBanner.jsx';
import {useLang} from './lib/i18n.js';
import './Layout.css';

/**
 * Layout route for every page that wears the site chrome. The header lives
 * here instead of inside each page, so /api/users/me is not refetched on
 * every navigation.
 */
function Layout() {
    const {pathname} = useLocation();
    const lang = useLang();

    return (
        <div className="layout-shell">
            <a className="layout-skip" href="#layout-main">{lang === 'he' ? 'דילוג לתוכן' : 'Skip to content'}</a>
            <Header/>
            {/*
              Keyed on the path, like the boundary below: a resend's answer belongs to
              the page it was asked on. /subscription is left out because it shows the
              same prompt, and its resend button, beside the Subscribe button that
              verification unlocks; two resend buttons on one screen would each report
              only its own clicks.
            */}
            {pathname !== '/subscription' && <VerifyEmailBanner key={pathname}/>}
            {/* keyed on the path so a crashed page clears itself once the user navigates away */}
            <main id="layout-main" className="layout-main" tabIndex={-1}>
                <ErrorBoundary key={pathname}>
                    <Outlet/>
                </ErrorBoundary>
            </main>
            <SiteFooter/>
        </div>
    );
}

/**
 * The full-page auth screens (login, register, password reset, email
 * verification) have no header and render their own <main> (AuthCard), but
 * still need the footer: the Terms and Privacy Policy that registering agrees
 * to must be one tap away.
 *
 * No verification banner here either. These screens are about signing in or
 * proving an address, and the two where it could matter say it themselves:
 * /register right after sign-up and /verify-email.
 */
export function BareLayout() {
    const {pathname} = useLocation();

    return (
        <div className="layout-shell">
            <ErrorBoundary key={pathname}>
                <Outlet/>
            </ErrorBoundary>
            <SiteFooter/>
        </div>
    );
}

export default Layout;
