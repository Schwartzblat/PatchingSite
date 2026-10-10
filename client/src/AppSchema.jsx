import {SITE_NAME, SITE_URL} from './lib/site.js';

/**
 * schema.org MobileApplication data for one app page, so a crawler can read
 * what the page is about without inferring it from the feature list.
 * MobileApplication is the type, and applicationCategory the kind of app.
 * Putting "MobileApplication" in applicationCategory looks equivalent but is
 * reported as an invalid value: it is a type, not one of the app categories.
 *
 * This is a data block, not code: the browser never executes
 * application/ld+json, so production's CSP (script-src 'self', no
 * 'unsafe-inline') does not apply to it. Rendering it in the body rather than
 * <head> is fine too, because Google reads JSON-LD from anywhere in the DOM.
 *
 * No `offers`: the price comes from Stripe at runtime and lib/price.js omits it
 * entirely when Stripe cannot be reached, so an `offers` block here would
 * sooner or later state a price the site does not charge. Structured data that
 * contradicts the page is worse than structured data that says less.
 */
export default function AppSchema({app, name, path, lang}) {
    const data = {
        '@context': 'https://schema.org',
        '@type': 'MobileApplication',
        // `name` is the page's h1. The <title> words the same claim differently;
        // what matters is that no piece of metadata promises something the page
        // itself does not show. `app` is already in the page's language.
        name,
        description: app.description,
        inLanguage: lang,
        applicationCategory: app.category,
        operatingSystem: 'Android',
        url: `${SITE_URL}${path}`,
        publisher: {'@type': 'Organization', name: SITE_NAME, url: SITE_URL},
    };

    /* JSON.stringify produces nothing an HTML parser reacts to except "<", and a
       literal "</script>" inside any future blurb would end the element early.
       Escaping it keeps the block valid JSON either way. */
    const json = JSON.stringify(data).replace(/</g, '\\u003c');

    return <script type="application/ld+json" dangerouslySetInnerHTML={{__html: json}}/>;
}
