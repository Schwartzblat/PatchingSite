import {Link} from 'react-router';
import DocPage, {DocSection, SupportLink} from './DocPage.jsx';
import {MAX_DEVICES, OPERATOR_NAME, PRIVACY_UPDATED, SITE_HOST} from './lib/site.js';

/*
 * Plain-English draft written for launch. Every item listed as stored
 * matches something the server really keeps (server/database.py, the
 * bug_reports table, the rotating log in utils/logging_config.py), and the
 * cookie section has to match what the client actually loads. It loads one
 * third-party script, Google Analytics, and only after the visitor accepts it
 * (client/src/lib/analytics.js, Consent Mode v2 with everything denied by
 * default). The CSP in deploy/nginx/androidmod-headers.conf allowlists exactly
 * those origins and nothing else, so it is the second place to change if a
 * third party is ever added or removed here.
 */
function PrivacyPage() {
    return (
        <DocPage
            title="Privacy Policy"
            description={`What ${SITE_HOST} collects, why, who else sees it, and how to have it deleted: only what running your account needs, with no ads and no tracking unless you accept it.`}
            updated={PRIVACY_UPDATED}
            lead="What we collect, why, who else sees it, and how to have it deleted. The short version: only what running your account needs, no ads, and no analytics unless you accept them."
        >
            <DocSection id="who" title="Who we are">
                <p>
                    {SITE_HOST} is run by {OPERATOR_NAME}. We run this website and the Patched Store app, and
                    are responsible for the data described here. Contact: <SupportLink/>.
                </p>
            </DocSection>

            <DocSection id="collect" title="What we store">
                <ul>
                    <li>
                        <strong>Your account:</strong> username and email address, when you verified that address,
                        and your password stored only as a salted one-way hash (Argon2; accounts created before we
                        adopted it are upgraded the next time they sign in). We cannot read your password.
                    </li>
                    <li>
                        <strong>Billing:</strong> your Stripe customer ID, which links your account to your
                        subscription. Card details are entered on Stripe&apos;s checkout page and handled by Stripe;
                        they never reach our servers and we never see them.
                    </li>
                    <li>
                        <strong>Devices:</strong> when a patched app starts, it reports an identifier for the device
                        together with the app it came from. We keep these to enforce the {MAX_DEVICES}-device limit
                        until you reset your devices or delete your account.
                    </li>
                    <li>
                        <strong>Server logs:</strong> IP address, browser or app user agent, and the requests made,
                        used for security, abuse prevention and fixing problems. Logs rotate automatically, so they
                        are kept for a limited time.
                    </li>
                    <li>
                        <strong>Bug reports:</strong> what you write, the email you give (or your account email if
                        you leave it blank), your username if you are signed in, your IP address, and the details
                        sent with the report: the page address, your browser&apos;s user agent and the site version.
                        Reports from the Android app also include the app version, Android version and device
                        model.
                    </li>
                </ul>
                <p>We do not sell your data or use it for advertising.</p>
            </DocSection>

            <DocSection id="build-identifier" title="The identifier inside each download">
                <p>
                    Every build you download is prepared for you: it carries a signed token that identifies your
                    account. The patched app uses it to check that your subscription is active and that the
                    device limit is respected. It also means a build that is shared publicly can be traced to the
                    account that downloaded it. The token contains no password or payment details.
                </p>
            </DocSection>

            <DocSection id="cookies" title="Cookies and tracking">
                <p>
                    We set <strong>one cookie of our own</strong>: a session cookie that keeps you signed
                    in. It is HttpOnly, so scripts on the page cannot read it.
                </p>
                <p>
                    We also use <strong>Google Analytics</strong> to count page visits, and only if you
                    let us. We ask the first time you arrive. Until you answer, and for as long as you
                    decline, <strong>Google&apos;s script is not loaded at all</strong>: nothing is stored
                    on your device and your browser never contacts Google about this site. If you accept,
                    it sets its own <code>_ga</code> cookies to recognise a returning browser and tells
                    Google which pages you viewed, roughly where you are (from your IP address) and what
                    device and browser you used. It is never sent your username, your email address or
                    anything from a download link. We show <strong>no ads</strong>, and the advertising
                    features of Google Analytics are switched off.
                </p>
                <p>
                    You can change your mind at any time with the{' '}
                    <strong>Analytics choice</strong> link at the bottom of any page. Declining again
                    stops the measuring immediately for the rest of your visit.
                </p>
                <p>
                    Stripe&apos;s checkout and billing pages are hosted by Stripe and use Stripe&apos;s
                    own cookies under its policy.
                </p>
            </DocSection>

            <DocSection id="processors" title="Who else processes your data">
                <ul>
                    <li>
                        <strong>Stripe</strong> processes payments and stores your card and billing details. See{' '}
                        <a href="https://stripe.com/privacy" rel="noopener noreferrer">Stripe&apos;s privacy
                        policy</a>.
                    </li>
                    <li>
                        <strong>Google</strong> delivers our email (email verification and password reset links,
                        and bug reports sent to our support inbox) and, if you accepted it, provides the
                        analytics described under Cookies and tracking above. See{' '}
                        <a href="https://policies.google.com/privacy" rel="noopener noreferrer">Google&apos;s
                        privacy policy</a>.
                    </li>
                </ul>
                <p>We share data with anyone else only if the law requires it.</p>
            </DocSection>

            <DocSection id="retention" title="How long we keep it">
                <ul>
                    <li>Account, billing link and device records: for as long as your account exists.</li>
                    <li>Server logs: a limited time, as they rotate.</li>
                    <li>Bug reports: as long as they are useful for fixing the problem they describe.</li>
                    <li>
                        Backups: the account database is backed up every night, and backups are kept for up to
                        60 days. Anything deleted from the database is gone from the backups within that time.
                    </li>
                    <li>Stripe keeps payment records for as long as financial regulations require.</li>
                </ul>
            </DocSection>

            <DocSection id="rights" title="Access and deletion">
                <p>
                    To get a copy of your data, correct it or have your account deleted, email <SupportLink/>
                    {' '}from the address on your account. Deleting your account removes your username, email,
                    password hash and device records at once, and from our backups within 60 days. Cancel your
                    subscription first so you are not charged again; billing records held by Stripe are kept as
                    the law requires.
                </p>
            </DocSection>

            <DocSection id="changes" title="Changes to this policy">
                <p>
                    If we change what we collect or how we use it, we will update this page and its &quot;Last
                    updated&quot; date, and tell you by email or on the site for significant changes. See also
                    our <Link to="/terms">Terms of Service</Link>.
                </p>
            </DocSection>
        </DocPage>
    );
}

export default PrivacyPage;
