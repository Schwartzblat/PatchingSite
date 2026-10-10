/*
 * The last step of `npm run build`: writes one HTML file per route into dist/.
 *
 * Every URL used to answer with the same app shell: the home page's title,
 * description and canonical link, an empty <div id="root">, and status 200 even
 * for pages that do not exist. Googlebot runs the JavaScript that corrected
 * this, but Google advises against changing a canonical link that way, and
 * Bing, link previews (WhatsApp, Telegram) and AI crawlers run no JavaScript at
 * all, so to them every page was the home page. Each file now carries its own
 * <head> and, for public pages, the rendered page itself, which the browser
 * hydrates (main.jsx).
 *
 * Inputs: dist/index.html from `vite build` (the template, with the hashed
 * bundle already linked) and dist-ssr/entry-server.js from `vite build --ssr`.
 * Output: dist/index.html for "/", dist/<path>.html for every other route,
 * dist/404.html, which nginx serves with status 404 for any unknown path
 * (deploy/nginx/androidmod.site.conf), and dist/sitemap.xml listing every
 * indexable page. The sitemap used to be a hand-kept file in public/, a copy of
 * the catalogue that had to be edited for every new app and now every
 * translation; generated from the same pages, it cannot fall behind.
 *
 * Every check below throws, which fails the build and so the deploy: a page
 * with no title, two descriptions or an empty body is not worth shipping.
 */
import {mkdir, readFile, writeFile} from 'node:fs/promises';
import {dirname, join} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const clientDir = join(dirname(fileURLToPath(import.meta.url)), '..');
const distDir = join(clientDir, 'dist');
const {render, pages, NOT_FOUND_PATH, SITE_URL, DIRECTION, langOf, checkTranslations} = await import(
    pathToFileURL(join(clientDir, 'dist-ssr', 'entry-server.js')).href
);

/* Before anything is written: a Hebrew page must not ship half in English. */
checkTranslations();

const ROOT = '<div id="root"></div>';
const template = await readFile(join(distDir, 'index.html'), 'utf8');
if (template.split(ROOT).length !== 2) fail(`dist/index.html has no single ${ROOT}`);
if (count(template, /<title>/g) !== 1) fail('dist/index.html must have exactly one <title>');
/* PageTitle renders the description; a static one here would make two. */
if (/<meta\s+name="description"/.test(template)) fail('index.html must not carry a description');
const HTML_TAG = /<html lang="en">/;
if (count(template, /<html\b/g) !== 1 || !HTML_TAG.test(template)) fail('index.html must open with <html lang="en">');

/* React 19 emits the tags it hoists (PageTitle's <title> and <meta>, image
   preloads) as one run at the start of the string when it renders less than a
   whole document. Their text and attribute values arrive escaped. */
const HOISTED = /^(?:<title>[^<]*<\/title>|<meta\b[^>]*>|<link\b[^>]*>)*/;

function fail(message) {
    throw new Error(`prerender: ${message}`);
}

function count(text, pattern) {
    return (text.match(pattern) ?? []).length;
}

/* Every edit to the template must hit exactly one tag, or the template changed
   under this script and the page would ship half-updated. Replacements are
   functions so that a "$" in a title is never read as a replacement pattern. */
function replaceOnce(html, pattern, replace, what) {
    if (count(html, new RegExp(pattern.source, 'g')) !== 1) fail(`expected exactly one ${what} in the template`);
    return html.replace(pattern, replace);
}

function setMeta(html, attr, key, value) {
    const pattern = new RegExp(`(<meta\\s+${attr}="${key}"\\s+content=")[^"]*(")`);
    return replaceOnce(html, pattern, (_, open, close) => `${open}${value}${close}`, `${key} tag`);
}

/** {path, head, body} for one route, with its hoisted tags split off. */
function renderPage(path) {
    const html = render(path);
    const head = html.match(HOISTED)[0];
    const body = html.slice(head.length);

    if (count(head, /<title>/g) !== 1) fail(`${path} rendered ${count(head, /<title>/g)} titles`);
    if (count(head, /<meta name="description"/g) !== 1) fail(`${path} rendered no single description`);
    if (/<title>|<meta name="(?:description|robots)"|<link rel="alternate"/.test(body)) {
        fail(`${path} rendered head tags inside the body; React no longer hoists them as expected`);
    }

    return {
        path,
        head,
        body,
        title: head.match(/<title>([^<]*)<\/title>/)[1],
        description: head.match(/<meta name="description" content="([^"]*)"/)[1],
        noindex: /<meta name="robots" content="noindex"/.test(head),
        alternates: [...head.matchAll(/<link rel="alternate" hrefLang="([^"]+)" href="([^"]+)"/gi)]
            .map(([, lang, href]) => ({lang, href})),
    };
}

/**
 * The template with this page's head and, when `markup`, its rendered body.
 * `hydrate` stamps the root with the path, which is what tells main.jsx to
 * hydrate it; without it the browser renders from scratch. `canonical: false`
 * drops the canonical link and og:url, for the 404 page.
 */
function fill(page, {markup, hydrate = markup, canonical = true}) {
    let html = replaceOnce(template, /<title>[^<]*<\/title>/, () => page.head, '<title>');

    /* HeadSync keeps these right after the script runs; this is what a crawler
       and the first paint see, so a Hebrew page is right to left from the start. */
    const lang = langOf(page.path);
    html = html.replace(HTML_TAG, () => `<html lang="${lang}" dir="${DIRECTION[lang]}">`);

    /* Link previews read these, not the <title>; they run no JavaScript, so
       this is the only place the per-page values can come from. */
    html = setMeta(html, 'property', 'og:title', page.title);
    html = setMeta(html, 'property', 'og:description', page.description);
    html = setMeta(html, 'name', 'twitter:title', page.title);
    html = setMeta(html, 'name', 'twitter:description', page.description);

    const url = `${SITE_URL}${page.path}`;
    if (canonical) {
        html = replaceOnce(html, /(<link\s+rel="canonical"\s+href=")[^"]*(")/,
            (_, open, close) => `${open}${url}${close}`, 'canonical link');
        html = setMeta(html, 'property', 'og:url', url);
    } else {
        html = replaceOnce(html, /\s*<link\s+rel="canonical"[^>]*>/, () => '', 'canonical link');
        html = replaceOnce(html, /\s*<meta\s+property="og:url"[^>]*>/, () => '', 'og:url tag');
    }

    if (!markup) return html;
    if (!hydrate) return html.replace(ROOT, () => `<div id="root">${page.body}</div>`);
    if (!/^\/[a-z0-9/-]*$/.test(page.path)) fail(`${page.path} cannot go in a data-path attribute`);
    return html.replace(ROOT, () => `<div id="root" data-path="${page.path}">${page.body}</div>`);
}

async function write(file, html) {
    const target = join(distDir, file);
    await mkdir(dirname(target), {recursive: true});
    await writeFile(target, html);
}

const indexable = [];
for (const {path, hydrate} of pages()) {
    if (path === NOT_FOUND_PATH) fail(`${NOT_FOUND_PATH} is a real route; pick another NOT_FOUND_PATH`);
    const page = renderPage(path);
    if (hydrate && !page.body) fail(`${path} rendered nothing`);
    if (hydrate && !page.noindex) indexable.push(page);
    await write(path === '/' ? 'index.html' : `${path.slice(1)}.html`, fill(page, {markup: hydrate}));
}

/* Each hreflang must point at a page that exists, is indexable and points
   back; a pair that does not confirm itself both ways is ignored by Google. */
const byUrl = new Map(indexable.map((page) => [`${SITE_URL}${page.path}`, page]));
for (const page of indexable) {
    const self = `${SITE_URL}${page.path}`;
    for (const {lang, href} of page.alternates) {
        const target = byUrl.get(href);
        if (!target) fail(`${page.path} names ${href} (${lang}), which is not an indexable page`);
        if (!target.alternates.some((alt) => alt.href === self)) fail(`${href} does not name ${page.path} back`);
        if (lang !== 'x-default' && lang !== langOf(target.path)) fail(`${href} is not in ${lang}`);
    }
}

/* The last commit before this script made every description unique; a search
   result that repeats another page's snippet reads as duplicate content. */
for (const key of ['title', 'description']) {
    const seen = new Map();
    for (const page of indexable) {
        if (seen.has(page[key])) fail(`${page.path} and ${seen.get(page[key])} share a ${key}`);
        seen.set(page[key], page.path);
    }
}

const notFound = renderPage(NOT_FOUND_PATH);
if (!notFound.noindex) fail('the 404 page must be noindex');
/* Served for whatever path was asked for, so it is not stamped with a path and
   main.jsx renders it from scratch; the markup is there for the moment before
   the script runs, and for crawlers that never run it. */
await write('404.html', fill(notFound, {markup: true, hydrate: false, canonical: false}));

/* No <lastmod>: the owner chose to leave it out, and a date that changes on
   every build would tell crawlers nothing. */
const urls = indexable.map((page) => `  <url><loc>${SITE_URL}${page.path}</loc></url>`);
await write('sitemap.xml', [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<!-- Written by client/scripts/prerender.js: every page that is hydrated and not noindex. -->',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    ...urls,
    '</urlset>',
    '',
].join('\n'));

console.log(`prerender: ${pages().length} pages (${indexable.length} indexable, all in sitemap.xml) and 404.html`);
