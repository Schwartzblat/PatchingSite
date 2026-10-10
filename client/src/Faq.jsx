import './Faq.css';

/**
 * Questions and answers under a heading, on the home page and every app page.
 * Plain headings and paragraphs rather than <details>: the answers are the
 * text a search engine should read, and an open page is easier to scan on a
 * phone than a column of closed toggles. `children` goes under the last answer.
 */
export default function Faq({id, heading, items, children}) {
    return (
        <section className="faq" aria-labelledby={id}>
            <h2 id={id} className="faq-title">{heading}</h2>
            {items.map(({q, a}) => (
                <div key={q} className="faq-item">
                    <h3 className="faq-q">{q}</h3>
                    <p className="faq-a">{a}</p>
                </div>
            ))}
            {children}
        </section>
    );
}
