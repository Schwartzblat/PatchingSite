import {createRoot, hydrateRoot} from 'react-dom/client'
import {BrowserRouter} from 'react-router'
import './index.css'
import ErrorBoundary from './ErrorBoundary.jsx'
import Root from './Root.jsx'

const app = (
    <ErrorBoundary>
        <BrowserRouter>
            <Root/>
        </BrowserRouter>
    </ErrorBoundary>
)

/*
 * The build writes one HTML file per route (scripts/prerender.js), and stamps
 * the root with the path it was rendered for. Hydrating is only correct when
 * that is the page on screen. Anything else renders from scratch: the dev
 * server's empty shell, a route marked prerender: false, the 404 page (served
 * for any unknown path), or an older nginx that falls back to the home page's
 * file for every route.
 *
 * Rendering from scratch leaves the file's <title>, description, robots and
 * hreflang tags behind, and they describe some other page (a home page's
 * description on /install, a noindex left over after leaving /login). PageTitle
 * renders all of them, so the stale copies are removed first and PageTitle's
 * become the only ones.
 */
const container = document.getElementById('root')

if (container.dataset.path === window.location.pathname) {
    hydrateRoot(container, app)
} else {
    for (const tag of document.head.querySelectorAll(
        'title, meta[name="description"], meta[name="robots"], link[rel="alternate"]',
    )) {
        tag.remove()
    }
    createRoot(container).render(app)
}
