# Web client

The androidmod.site front end: React 19 + Vite, plain JavaScript, dark theme only.
The repository root `README.md` covers the whole project; this file is what you need
to work on `client/` alone.

## Commands

```bash
npm ci            # install exactly what package-lock.json pins
npm run dev       # http://localhost:5173, proxies /api to http://localhost:8000
npm run lint      # ESLint
npm run build     # production bundle in dist/, one prerendered HTML file per route
npm run preview   # serve dist/ locally (same /api proxy)
```

`deploy/deploy.sh` builds on the host from the deployed git checkout and copies
`dist/` into the nginx web root, so nothing here is built on your machine for
production.

## Prerendering

`npm run build` runs three steps: the browser bundle (`vite build`), a Node build of
`src/entry-server.jsx` into `dist-ssr/` (`vite build --ssr`), and
`scripts/prerender.js`, which renders every route and writes `dist/index.html` for
`/`, `dist/<path>.html` for the rest, and `dist/404.html`. Each file has that page's
title, description, canonical link and share tags in `<head>`, and public pages also
carry their rendered markup, which `main.jsx` hydrates. Before this, every URL served
the home page's head over an empty `<div id="root">`, which is all that Bing, link
previews and AI crawlers ever saw, since they run no JavaScript.

- The list of files comes from the route tree in `src/routes.jsx`, with
  `/apps/:appKey` expanded from `src/apps.js`. nginx serves files only and answers 404
  for anything else, so a route the build did not write is a 404 in production.
- Routes whose first render depends on the query string or the session carry
  `handle={{prerender: false}}`. They still get a file with their title and robots
  tags, but an empty root that the browser renders from scratch.
- The script fails the build if a page has no title or description, or two of
  either, if two indexable pages share one, or if an hreflang pair does not point
  both ways.
- It also writes `dist/sitemap.xml` from the same pages (every hydrated page that is
  not noindex), so the sitemap no longer has to be edited by hand.
- `npm run preview` serves `dist/` with Vite's own fallback, not nginx's rules. To
  check 404s and caching, run the real config (`deploy/nginx/`) in a container.

## Environment

The client reads no environment variables. It talks to `/api` on its own origin,
which the dev server proxies to the backend and nginx proxies in production.

- Run the backend with `ENVIRONMENT=development` for local work. Otherwise its session
  cookie is Secure-only, a browser drops it over `http://localhost`, and login appears
  to do nothing.
- `vite.config.js` stamps `__APP_VERSION__` (`git describe --always --dirty` plus the
  build date) into the bundle. Bug reports send it as `context.app_version`.

## The API

`src/lib/api.js` is the only module that calls the backend. It adds a 30 s timeout to
every request, turns FastAPI's `{"detail": ...}` errors into `ApiError`, and sends
any unexpected 401 to `/login` through the handler that `src/lib/auth.jsx` registers.
The contract lives in the server routers (`server/api/*.py`, mounted under `/api` by
`server/main.py`). The Android client's `data/ApiClient.kt` mirrors `api.js`, so a
change to an endpoint usually touches both.

## Things that must stay true

- **CSP.** Production's policy lives in `deploy/nginx/androidmod-headers.conf`. No
  inline `<script>` or `<style>` in `index.html`, no fonts from a CDN. React
  `style={{...}}` props are fine, because they are set through the DOM rather than
  as inline markup. One third party is allowed to run code: Google Analytics, whose
  origins are allowlisted for `script-src`, `connect-src` and `img-src`.
  `'unsafe-inline'` is still absent, which is why `src/lib/analytics.js` builds the
  tag with `createElement` instead of using Google's published snippet - a created
  script is matched by URL, an inline block is not. Adding or removing a third party
  means changing the CSP **and** the Privacy Policy's "Cookies and tracking" section,
  which states exactly what loads and when.
- **Analytics.** `src/lib/analytics.js` does not load Google's script until the
  visitor accepts (Consent Mode alone would still send cookieless pings, and the
  policy promises nothing is sent). `page_location` and `page_referrer` are set
  globally with the query string removed, because `/reset-password` and
  `/verify-email` keep a token in `?token=` and GA's own events would otherwise
  report the full address. One setting lives in GA, not here: in the web
  stream's Enhanced measurement, turn off page views on "browser history events",
  because the app sends its own `page_view` per route and would otherwise count
  each navigation twice.
- **Theme.** Colour tokens live in `src/index.css`, and `android/.../ui/theme/Color.kt`
  ports them. Text on a filled control uses `--pap-fill`, not `--pap-gradient`: white
  on the gradient's bright end is 3.0:1.
- **Catalogue.** `src/apps.js` duplicates the name, package and feature names from
  `server/config.py` `APPS`, and adds the app page's text: an intro, a sentence per
  feature and a short FAQ, each taken from what the patcher's code does rather than
  from the feature's name. `src/apps.he.js` holds the same text in Hebrew, in the
  same order; the build refuses a catalogue where the two disagree. The build writes
  `/apps/<key>`, `/he/apps/<key>` and their sitemap entries by itself.
- **Hydration.** The first render in the browser must match the prerendered markup,
  so it cannot read `window`, `localStorage`, the query string or the session. Read
  those in an effect, or gate on the render after hydration as `Analytics.jsx` does
  (`useSyncExternalStore`). A mismatch is not just a console error: React throws the
  markup away and renders again, and the page ends up with two titles and two
  descriptions. PageTitle renders the title, description and robots tags; nothing
  else should, and `index.html` must not.
- **Support address and legal dates** live in `src/lib/site.js`. The legal pages
  (`TermsPage`, `PrivacyPage`, `RefundsPage`) describe what the server really stores
  and how billing really works, so update them when either changes.

## Hebrew

The home page, every app page and the install guide also exist in Hebrew under
`/he`. Everything else (sign-in, subscription, legal pages) is English only, and
links to it from a Hebrew page stay English.

- `src/lib/i18n.js` reads the language from the path (`useLang()`), maps a page to
  its other version (`localPath`, `translations`), and is the only place that
  knows which pages are translated.
- Copy sits beside the component that renders it, as `{en, he}` objects; there is
  no translation library. The install guide, which is prose, has a twin file
  (`InstallPageHe.jsx`) with the same section ids.
- PageTitle adds `hreflang` links (en, he, x-default) on every page that has both
  versions. HeadSync and the prerender set `<html lang dir>`, so Hebrew pages are
  right to left; CSS uses logical properties (`padding-inline-start`, not
  `padding-left`) wherever direction matters.
- Inside Hebrew text, a left-to-right token that starts or ends with a neutral
  character needs a left-to-right mark (`\u200E`) beside it, or it is drawn the
  wrong way round: `\u200E@everyone`, `\u200E12+\u200E`.
- Messages the server writes (build progress, most errors) are English on both.
- The Patched Store app is English only, so the Hebrew guide quotes its buttons in
  English.

## Layout

```
src/
  main.jsx            browser entry: hydrates a prerendered page or renders from scratch
  apps.js, apps.he.js the catalogue and each app page's text, in English and Hebrew
  lib/i18n.js         which pages exist in Hebrew, and the language of a path
  Faq.jsx             the question/answer block on the home and app pages
  routes.jsx          the route tree; auth pages use BareLayout (footer, no header)
  Root.jsx            auth context, canonical sync, analytics and the routes
  entry-server.jsx    build-time entry for scripts/prerender.js
  Layout.jsx          header + page + footer, and BareLayout
  lib/                api client, auth context, price, redirect guard, validation
  PageTitle.jsx       per-route <title>, description and robots, plus the canonical URL sync
  Admin*.jsx          the admin panel at /admin: English only, not prerendered, ?user= selects
  *.jsx               a component per route (App.jsx is home) and the pieces they share
public/               favicon, share image, robots.txt, app icons
scripts/prerender.js  writes one HTML file per route after the build
```
