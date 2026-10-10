import {Navigate, Outlet, useLocation} from 'react-router';
import {useAuth} from './lib/auth.jsx';
import {loginPath} from './lib/redirect.js';
import PageTitle from './PageTitle.jsx';

/**
 * Gate for pages that are useless without a session.
 *
 *   <Route path="/x" element={<RequireAuth><X/></RequireAuth>}/>   // single page
 *   <Route element={<RequireAuth/>}>...</Route>                    // layout route
 *
 * `loading` is checked first: on a cold load `user` is null until
 * /api/users/me answers, and redirecting then would bounce signed-in users out
 * of their own deep links.
 */
function RequireAuth({children}) {
    const {user, loading} = useAuth();
    const location = useLocation();

    /* The title is what the prerendered file carries (the session is unknown at
       build time), and a page behind a session has nothing to show a search
       engine, hence noindex. The gated page's own PageTitle replaces it. */
    if (loading) {
        return (
            <p className="layout-pending" role="status">
                <PageTitle title="Checking your session" noindex/>
                Checking your session…
            </p>
        );
    }

    if (!user) {
        const from = `${location.pathname}${location.search}${location.hash}`;
        return <Navigate to={loginPath(from)} replace/>;
    }

    return children ?? <Outlet/>;
}

export default RequireAuth;
