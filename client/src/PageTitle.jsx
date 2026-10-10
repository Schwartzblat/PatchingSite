import {useEffect} from 'react';
import {useLocation} from 'react-router';
import {SITE_NAME, SITE_URL} from './lib/site.js';
import {DIRECTION, langOf, translations} from './lib/i18n.js';

const SEPARATOR = ' — ';
/* The home tab names the apps rather than the category, because it is what a
   search result shows. Its en dash is the requested wording, not SEPARATOR;
   every other page keeps SEPARATOR. */
const HOME_TITLE = `${SITE_NAME} – Patched WhatsApp, Moovit & 12+`;

/* Any page that passes no description of its own gets this one. It used to be
   read out of index.html, so that a crawler running no JavaScript saw it. The
   build now writes every page's own tags into that page's file
   (scripts/prerender.js), so the copy lives here and index.html has none. */
const HOME_DESCRIPTION =
    'AndroidMod rebuilds popular Android apps with their limits removed - ad-free Moovit '
    + 'and Mako, WhatsApp without view-once or anti-revoke restrictions - signed and ready to install.';

/**
 * Every route renders exactly one of these. React 19 hoists the <title> and
 * <meta> tags into <head> and removes them again on unmount, so they always
 * belong to the page on screen. Pages used to mix this with `document.title =
 * ...` in an effect, and the two fight: the assignment rewrites whichever
 * <title> comes first, so /login and /register (which set nothing) kept the
 * last page's title. Nothing assigns document.title any more.
 *
 * The description used to be written into index.html's static tag by an
 * effect, because rendering one beside that tag left two. index.html no longer
 * has one, and rendering it is what gets it into the prerendered file: effects
 * never run at build time. Hydration adopts the file's copy, and main.jsx
 * removes it before a from-scratch render, so there is still exactly one.
 *
 * `noindex` is for pages a search result should never land on: the 404 page and
 * the password-reset screens.
 *
 * A page that exists in English and Hebrew also names both versions (hreflang),
 * so a search engine shows each searcher the one in their language instead of
 * treating the pair as duplicates. x-default is the English page, for everyone
 * else. Each version lists both, itself included, as Google requires.
 */
export default function PageTitle({title, description, noindex = false}) {
    const {pathname} = useLocation();
    const text = title ? `${title}${SEPARATOR}${SITE_NAME}` : HOME_TITLE;
    const pair = noindex ? null : translations(pathname);

    return (
        <>
            <title>{text}</title>
            <meta name="description" content={description || HOME_DESCRIPTION}/>
            {noindex && <meta name="robots" content="noindex"/>}
            {pair && (
                <>
                    <link rel="alternate" hrefLang="en" href={`${SITE_URL}${pair.en}`}/>
                    <link rel="alternate" hrefLang="he" href={`${SITE_URL}${pair.he}`}/>
                    <link rel="alternate" hrefLang="x-default" href={`${SITE_URL}${pair.en}`}/>
                </>
            )}
        </>
    );
}

/**
 * Every prerendered file already carries its own canonical link and og:url
 * (scripts/prerender.js). This keeps them right while the app navigates
 * without a reload, and when the browser renders a different path than the
 * one the file was built for. The query string is left out on purpose:
 * ?redirect= and ?token= are never a different page. The 404 page ships
 * neither tag, because it answers for any unknown URL, so there is nothing to
 * point here.
 *
 * <html lang dir> is kept in step the same way: the build writes it per file,
 * and moving between /he and English pages without a reload has to flip it, or
 * Hebrew would be laid out left to right (and read by a screen reader as
 * English).
 *
 * Mounted once, beside the routes, so a page that forgets its PageTitle still
 * gets a correct canonical.
 */
export function HeadSync() {
    const {pathname} = useLocation();

    useEffect(() => {
        const url = `${SITE_URL}${pathname}`;
        document.querySelector('link[rel="canonical"]')?.setAttribute('href', url);
        document.querySelector('meta[property="og:url"]')?.setAttribute('content', url);

        const lang = langOf(pathname);
        document.documentElement.lang = lang;
        document.documentElement.dir = DIRECTION[lang];
    }, [pathname]);

    return null;
}
