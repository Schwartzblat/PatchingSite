import {renderToString} from 'react-dom/server';
import {createRoutesFromElements, StaticRouter} from 'react-router';
import ErrorBoundary from './ErrorBoundary.jsx';
import Root from './Root.jsx';
import {routes} from './routes.jsx';
import {apps} from './apps.js';
export {checkTranslations} from './apps.js';

/*
 * The server half of the build-time prerender. `vite build --ssr` bundles this
 * file and scripts/prerender.js runs it in Node, once per route, so every URL
 * gets its own HTML. Nothing serves it at request time.
 */

export {SITE_URL} from './lib/site.js';
export {DIRECTION, langOf} from './lib/i18n.js';

/* Matches no route but the "*" one, so it renders NotFound. */
export const NOT_FOUND_PATH = '/404';

/**
 * The page at `path`, wrapped exactly as main.jsx wraps it. React 19 puts the
 * <title>, <meta> and <link> elements it hoists at the very start of the string
 * when it renders less than a whole document; prerender.js moves that run into
 * <head>.
 */
export function render(path) {
    return renderToString(
        <ErrorBoundary>
            <StaticRouter location={path}>
                <Root/>
            </StaticRouter>
        </ErrorBoundary>,
    );
}

/**
 * Every URL the site serves, read from the route tree so that the two cannot
 * drift: [{path, hydrate}]. `/apps/:appKey` (and `/he/apps/:appKey`) becomes one
 * path per app in apps.js.
 * Layout routes have no path of their own and pass their `handle` down.
 */
export function pages() {
    const out = [];

    function walk(list, hydrate) {
        for (const route of list) {
            const own = hydrate && route.handle?.prerender !== false;
            if (route.children) walk(route.children, own);
            if (!route.path || route.path === '*') continue;

            if (route.path.endsWith('/apps/:appKey')) {
                for (const key of Object.keys(apps)) {
                    out.push({path: route.path.replace(':appKey', key), hydrate: own});
                }
            } else if (route.path.includes(':') || route.path.includes('*')) {
                throw new Error(`prerender: no rule for the dynamic route ${route.path}`);
            } else {
                out.push({path: route.path, hydrate: own});
            }
        }
    }

    walk(createRoutesFromElements(routes), true);
    return out;
}
