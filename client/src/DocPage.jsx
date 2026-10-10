import {useEffect} from 'react';
import {useLocation} from 'react-router';
import PageTitle from './PageTitle.jsx';
import {SUPPORT_EMAIL, SUPPORT_MAILTO} from './lib/site.js';
import './DocPage.css';

/**
 * Shell for the long-form pages: Terms, Privacy, Refunds and the install
 * guide. Prose is capped at a readable measure inside the usual panel.
 *
 * `description` is the page's search snippet. It is a separate prop rather
 * than a slice of `lead`, because a lead is written to be read underneath a
 * heading and a snippet has to stand on its own in a result list.
 *
 * React Router does not scroll to a #fragment, and on a cold load the target
 * does not exist yet when the browser tries. Links such as /install#xapk
 * from the app pages rely on this effect to land on their section.
 */
export default function DocPage({title, description, updated, lead, children}) {
    const {hash} = useLocation();

    useEffect(() => {
        if (!hash) return;
        const target = document.getElementById(decodeURIComponent(hash.slice(1)));
        target?.scrollIntoView();
    }, [hash]);

    return (
        <article className="page-panel doc-panel">
            <PageTitle title={title} description={description}/>
            <header className="doc-header">
                <h1 className="doc-title">{title}</h1>
                {updated && <p className="doc-updated">Last updated: {updated}</p>}
                {lead && <p className="doc-lead">{lead}</p>}
            </header>
            <div className="doc-body">{children}</div>
        </article>
    );
}

/** A section with a linkable heading. `id` doubles as the #fragment. */
export function DocSection({id, title, children}) {
    return (
        <section id={id} className="doc-section" aria-labelledby={`${id}-title`}>
            <h2 id={`${id}-title`} className="doc-heading">{title}</h2>
            {children}
        </section>
    );
}

export function SupportLink() {
    return <a href={SUPPORT_MAILTO}>{SUPPORT_EMAIL}</a>;
}
