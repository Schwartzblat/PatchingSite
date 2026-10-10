import {useLocation} from 'react-router';
import {apps} from '../apps.js';

/*
 * The site is English, with Hebrew versions of the pages a search result lands
 * on: the home page, each app page and the install guide. Everything a visitor
 * reaches after that (sign-in, subscription, the legal pages) stays English.
 *
 * The language is read from the path, never from a cookie or the browser's
 * language: every URL is in exactly one language, which is what lets a search
 * engine index both versions and pair them with hreflang. A Hebrew page is the
 * English path under /he (/he, /he/apps/whatsapp, /he/install).
 *
 * Copy lives next to the component that renders it, as {en, he} objects; there
 * is no translation library. The install guide, which is prose, has a Hebrew
 * twin file instead.
 */

export const LANGS = ['en', 'he'];
export const DIRECTION = {en: 'ltr', he: 'rtl'};

/** 'he' for /he and anything under it, else 'en'. */
export function langOf(pathname) {
    return pathname === '/he' || pathname.startsWith('/he/') ? 'he' : 'en';
}

export function useLang() {
    return langOf(useLocation().pathname);
}

/* The English pages that have a Hebrew version. An unknown app key has none,
   so a 404 under /he/apps/ declares no alternates. */
function translated(englishPath) {
    if (englishPath === '/' || englishPath === '/install') return true;
    const app = /^\/apps\/([^/]+)$/.exec(englishPath);
    return Boolean(app && Object.hasOwn(apps, app[1]));
}

/**
 * Where an English link should point on a `lang` page: the Hebrew version when
 * there is one, else the English page itself (the legal pages, sign-in). A
 * #fragment is kept, since both versions of the install guide share section ids.
 */
export function localPath(path, lang) {
    if (lang !== 'he') return path;
    const [pathname, hash] = path.split('#');
    if (!translated(pathname)) return path;
    const he = pathname === '/' ? '/he' : `/he${pathname}`;
    return hash === undefined ? he : `${he}#${hash}`;
}

/** {en, he} paths when the page at `pathname` exists in both languages, else null. */
export function translations(pathname) {
    let en = pathname;
    if (langOf(pathname) === 'he') en = pathname === '/he' ? '/' : pathname.slice('/he'.length);
    return translated(en) ? {en, he: localPath(en, 'he')} : null;
}
