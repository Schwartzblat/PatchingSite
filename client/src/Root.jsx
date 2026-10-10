import {Routes} from 'react-router';
import {AuthProvider} from './lib/auth.jsx';
import {HeadSync} from './PageTitle.jsx';
import Analytics from './Analytics.jsx';
import {routes} from './routes.jsx';

/** Everything inside the router. The caller supplies the router itself. */
export default function Root() {
    return (
        <AuthProvider>
            <HeadSync/>
            <Analytics/>
            <Routes>{routes}</Routes>
        </AuthProvider>
    );
}
