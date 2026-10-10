import {Route} from 'react-router';
import Layout, {BareLayout} from './Layout.jsx';
import RequireAuth from './RequireAuth.jsx';
import App from './App.jsx';
import PatchedAppPage from './PatchedAppPage.jsx';
import Subscription from './Subscription.jsx';
import SubscriptionSuccess from './SubscriptionSuccess.jsx';
import LoginPage from './LoginPage.jsx';
import RegisterPage from './RegisterPage.jsx';
import ForgotPasswordPage from './ForgotPasswordPage.jsx';
import ResetPasswordPage from './ResetPasswordPage.jsx';
import VerifyEmailPage from './VerifyEmailPage.jsx';
import NotFound from './NotFound.jsx';
import TermsPage from './TermsPage.jsx';
import PrivacyPage from './PrivacyPage.jsx';
import RefundsPage from './RefundsPage.jsx';
import InstallPage from './InstallPage.jsx';
import AdminPage from './AdminPage.jsx';

/*
 * The route tree, shared by the browser (main.jsx, under BrowserRouter) and the
 * build-time prerender (entry-server.jsx, under StaticRouter). The prerender also
 * reads it to decide which files to write, so a route added here is served by
 * nginx without being listed anywhere else; a route missing from the build would
 * be a 404 in production, because nginx no longer falls back to one app shell.
 *
 * `handle: {prerender: false}` marks routes whose first render depends on
 * something only the browser has: the query string (?redirect=, ?token=) or the
 * session. They still get their own file, with the right title and noindex, but
 * an empty root that the browser renders from scratch instead of hydrating.
 */
export const routes = (
    <>
        <Route element={<Layout/>}>
            <Route path="/" element={<App/>}/>
            {/* one route for every app; PatchedAppPage reads useParams().appKey */}
            <Route path="/apps/:appKey" element={<PatchedAppPage/>}/>
            <Route path="/subscription" element={<Subscription/>}/>
            <Route path="/install" element={<InstallPage/>}/>
            {/* The Hebrew versions (lib/i18n.js). The same components read the
                language from the path, so a page cannot exist in one language
                and drift in the other. */}
            <Route path="/he" element={<App/>}/>
            <Route path="/he/apps/:appKey" element={<PatchedAppPage/>}/>
            <Route path="/he/install" element={<InstallPage/>}/>
            <Route path="/terms" element={<TermsPage/>}/>
            <Route path="/privacy" element={<PrivacyPage/>}/>
            <Route path="/refunds" element={<RefundsPage/>}/>
            <Route
                path="/subscription/success"
                handle={{prerender: false}}
                element={
                    <RequireAuth>
                        <SubscriptionSuccess/>
                    </RequireAuth>
                }
            />
            {/* English only, behind the session and ADMIN_USERNAMES (server/api/admin.py).
                Not prerendered: the page depends on who is signed in. Its file
                is noindex, and the sitemap leaves it out. */}
            <Route
                path="/admin"
                handle={{prerender: false}}
                element={
                    <RequireAuth>
                        <AdminPage/>
                    </RequireAuth>
                }
            />
            <Route path="*" element={<NotFound/>}/>
        </Route>
        {/* auth pages bring their own full-page card, so no header; they keep the footer */}
        <Route element={<BareLayout/>} handle={{prerender: false}}>
            <Route path="/login" element={<LoginPage/>}/>
            <Route path="/register" element={<RegisterPage/>}/>
            <Route path="/forgot-password" element={<ForgotPasswordPage/>}/>
            <Route path="/reset-password" element={<ResetPasswordPage/>}/>
            {/* the link in the verification email; not in sitemap.xml, it is transactional */}
            <Route path="/verify-email" element={<VerifyEmailPage/>}/>
        </Route>
    </>
);
