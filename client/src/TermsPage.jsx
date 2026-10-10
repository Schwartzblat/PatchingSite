import {Link} from 'react-router';
import DocPage, {DocSection, SupportLink} from './DocPage.jsx';
import {MAX_DEVICES, OPERATOR_NAME, SITE_HOST, TERMS_UPDATED} from './lib/site.js';

/*
 * Plain-English draft written for launch. The operator comes from
 * lib/site.js (OPERATOR_NAME); the owner chose not to name a country or a
 * governing law. The facts here (device limit, per-account identifier,
 * billing through Stripe, cancel from Manage billing) mirror how the server
 * actually behaves, so change them together.
 */
function TermsPage() {
    return (
        <DocPage
            title="Terms of Service"
            description={`The terms covering accounts, subscriptions and patched downloads on ${SITE_HOST} and in the Patched Store app.`}
            updated={TERMS_UPDATED}
            lead={`These terms cover your use of ${SITE_HOST}, the Patched Store app and the builds you download. By creating an account or subscribing, you agree to them.`}
        >
            <DocSection id="who" title="Who we are">
                <p>
                    {SITE_HOST} is run by {OPERATOR_NAME} (&quot;we&quot;, &quot;us&quot;). We run this website
                    and the Patched Store Android app. You can reach us at <SupportLink/>.
                </p>
            </DocSection>

            <DocSection id="service" title="What the service is">
                <p>
                    We publish modified (&quot;patched&quot;) builds of third-party Android apps: WhatsApp,
                    Moovit, 12+ (Mako) and OkCupid at the moment. Each build is changed to add or remove features and is
                    re-signed with our own key.
                </p>
                <ul>
                    <li>
                        <strong>We are not affiliated with, endorsed by or sponsored by</strong> the developers of
                        those apps. Their names and icons belong to their owners and are used only to say which app a
                        build is based on.
                    </li>
                    <li>
                        Builds are provided <strong>as is</strong>. A patched app can break, lose features, behave
                        differently from the official one, or stop working when the original service changes.
                    </li>
                    <li>
                        <strong>You use patched apps at your own risk.</strong> You are responsible for complying
                        with the terms of the original apps and services. Using a modified app may break those terms,
                        and the original service may limit or ban an account that uses one. We cannot help with, or
                        be responsible for, action a third party takes against your account.
                    </li>
                </ul>
            </DocSection>

            <DocSection id="account" title="Your account">
                <ul>
                    <li>An account is for <strong>one person</strong>. Keep your password to yourself; you are
                        responsible for what happens under your account.</li>
                    <li>Use an email address you can read. You verify it before you can subscribe, and it is how
                        you reset your password and how we reach you.</li>
                    <li>
                        A subscription covers <strong>up to {MAX_DEVICES} devices</strong>. Each patched app on
                        each phone takes one slot. You can free all slots from
                        the <Link to="/subscription">Subscription</Link> page once every 30 days.
                    </li>
                    <li>
                        <strong>Every build you download carries an identifier tied to your account.</strong> It
                        lets the app check your subscription and device limit, and it means a shared build can be
                        traced to the account that downloaded it.
                    </li>
                    <li>
                        Sharing builds, sharing your account, or reselling access can get the account closed,
                        without a refund.
                    </li>
                    <li>
                        Do not try to get around the device limit or payment, overload the service, or access
                        accounts or data that are not yours.
                    </li>
                </ul>
            </DocSection>

            <DocSection id="subscription" title="Subscription and billing">
                <ul>
                    <li>
                        Downloading patched builds needs a Pro subscription. Browsing the catalogue and installing
                        the Patched Store app are free.
                    </li>
                    <li>
                        Pro is billed <strong>monthly, in advance</strong>, by our payment processor, Stripe. It
                        <strong> renews automatically</strong> every month until you cancel. The price is shown on
                        the Subscription page and at checkout before you pay.
                    </li>
                    <li>
                        <strong>You can cancel at any time</strong> from <strong>Manage billing</strong> (or
                        Cancel subscription) on the <Link to="/subscription">Subscription</Link> page. You keep
                        access until the end of the month you have paid for, and you are not charged again.
                    </li>
                    <li>
                        If we change the price, we will tell you before it applies. A new price takes effect from
                        your next billing month, never in the middle of one you have paid for.
                    </li>
                    <li>
                        Refunds are covered by our <Link to="/refunds">Refund Policy</Link>.
                    </li>
                </ul>
            </DocSection>

            <DocSection id="changes-to-service" title="Changes to the service">
                <p>
                    We may add, update or remove apps, builds and features, and the service may be unavailable
                    at times, for example while we deploy an update. We try to keep builds working, but we
                    cannot promise that every build will work on every device or keep working forever.
                </p>
            </DocSection>

            <DocSection id="ending" title="Ending your account">
                <p>
                    You can stop using the service at any time and ask us to delete your account by
                    emailing <SupportLink/>. Cancel your subscription first so you are not charged again. We may
                    suspend or close accounts that break these terms.
                </p>
            </DocSection>

            <DocSection id="liability" title="Disclaimer and limitation of liability">
                <p>
                    To the fullest extent the law allows, the service, the Patched Store app and every build are
                    provided &quot;as is&quot; and &quot;as available&quot;, without warranties of any kind,
                    including fitness for a particular purpose.
                </p>
                <p>
                    We are not liable for indirect or consequential losses, for lost data (including chat history),
                    or for action a third-party service takes against your account because you used a patched app.
                    Our total liability for any claim is limited to the amount you paid us in the three months
                    before the claim. Nothing in these terms limits liability that the law does not allow to be
                    limited.
                </p>
            </DocSection>

            <DocSection id="changes" title="Changes to these terms">
                <p>
                    We may update these terms. When we do, we change the &quot;Last updated&quot; date above, and
                    for significant changes we will also tell you by email or on the site before they apply. If you
                    keep using the service after a change takes effect, the new terms apply to you.
                </p>
            </DocSection>

            <DocSection id="contact" title="Contact">
                <p>
                    Questions about these terms: <SupportLink/>. See also our <Link to="/privacy">Privacy
                    Policy</Link>.
                </p>
            </DocSection>
        </DocPage>
    );
}

export default TermsPage;
