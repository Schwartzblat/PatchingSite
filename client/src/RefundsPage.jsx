import {Link} from 'react-router';
import DocPage, {DocSection, SupportLink} from './DocPage.jsx';
import {LEGAL_UPDATED} from './lib/site.js';

/* The owner's policy, stated as plainly as it was given: cancel any time,
   keep the paid month, no partial refunds, mistakes refunded in full. */
function RefundsPage() {
    return (
        <DocPage
            title="Refund Policy"
            description="Cancel whenever you like and keep what you paid for. How cancellations, refunds and billing mistakes are handled."
            updated={LEGAL_UPDATED}
            lead="Cancel whenever you like. You keep what you paid for, and we fix billing mistakes."
        >
            <DocSection id="cancel" title="Cancelling">
                <p>
                    You can cancel at any time from <strong>Manage billing</strong> (or Cancel subscription) on
                    the <Link to="/subscription">Subscription</Link> page. There is no notice period and no
                    cancellation fee.
                </p>
            </DocSection>

            <DocSection id="after-cancelling" title="After you cancel">
                <p>
                    Your subscription keeps working <strong>until the end of the month you have already paid
                    for</strong>, and you are not charged again. You can reactivate before that date and nothing
                    changes.
                </p>
            </DocSection>

            <DocSection id="partial" title="Partial months">
                <p>
                    We do not refund or credit partial months. Cancelling halfway through a month stops the next
                    charge; it does not return part of the current one.
                </p>
            </DocSection>

            <DocSection id="mistakes" title="Charged twice or by mistake?">
                <p>
                    Email <SupportLink/> with your username and the date of the charge, and <strong>we will
                    refund it</strong>. Refunds go back to the card you paid with, through Stripe; your bank
                    usually shows them within 5 to 10 business days.
                </p>
            </DocSection>

            <DocSection id="your-rights" title="Your legal rights">
                <p>
                    This policy does not limit any right you have under consumer protection law. Where the law
                    gives you more, for example a right to cancel and be refunded, the law applies.
                </p>
            </DocSection>
        </DocPage>
    );
}

export default RefundsPage;
