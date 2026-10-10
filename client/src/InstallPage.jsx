import {Link} from 'react-router';
import DocPage, {DocSection, SupportLink} from './DocPage.jsx';
import StoreDownload from './StoreDownload.jsx';
import {MAX_DEVICES, MIN_ANDROID} from './lib/site.js';
import {useLang} from './lib/i18n.js';
import InstallPageHe from './InstallPageHe.jsx';

/*
 * The install guide. Each claim here is taken from how the store app
 * actually behaves (android/README.md, "How installing works"): minSdk 30,
 * split bundles streamed into one PackageInstaller session, SignatureVerifier
 * refusing anything not signed with the shared key,
 * INSTALL_FAILED_UPDATE_INCOMPATIBLE over a Play Store copy with an
 * "Uninstall and retry" offer, and Play Protect's "App scan recommended"
 * prompt on every install. Update this page when any of that changes.
 *
 * The WhatsApp chats section follows the patcher's own findings
 * (whatsappPatcher docs/superpowers/specs/2026-09-30-drive-backup-design.md):
 * WhatsApp's Google Drive backup goes through a Google service bound to
 * WhatsApp's signing key, so a re-signed build can never restore from it, while
 * the daily local backup in Android/media/com.whatsapp restores normally. And
 * Android deletes Android/media/<package> on uninstall, so that local backup
 * has to be copied out before the Play Store copy is removed.
 *
 * /he/install renders InstallPageHe.jsx, a translation of this page section by
 * section; a change here belongs there too.
 */
function InstallPage() {
    return useLang() === 'he' ? <InstallPageHe/> : <InstallPageEn/>;
}

function InstallPageEn() {
    return (
        <DocPage
            title="How to install"
            description="How to install a patched Android app: uninstall the Play Store copy, allow the install, then let the Patched Store app handle updates from then on."
            lead="Patched apps install like any app from outside the Play Store. It takes a few minutes the first time, and the store app makes it one tap after that."
        >
            <nav aria-label="On this page">
                <ul className="doc-toc">
                    <li><a href="#requirements">Before you start</a></li>
                    <li><a href="#store-app">The store app</a></li>
                    <li><a href="#replace">Replacing the Play Store version</a></li>
                    <li><a href="#whatsapp-chats">Keeping your WhatsApp chats</a></li>
                    <li><a href="#play-protect">Play Protect</a></li>
                    <li><a href="#browser">From the browser</a></li>
                    <li><a href="#xapk">XAPK bundles</a></li>
                    <li><a href="#updates">Updates</a></li>
                    <li><a href="#troubleshooting">Troubleshooting</a></li>
                </ul>
            </nav>

            <DocSection id="requirements" title="Before you start">
                <ul>
                    <li>An Android phone or tablet running <strong>{MIN_ANDROID} or newer</strong>.</li>
                    <li>
                        A <Link to="/subscription">Pro subscription</Link> to download patched builds. The store app
                        itself is free.
                    </li>
                    <li>Some free space: a build is 40 to 150 MB, and WhatsApp is the largest.</li>
                </ul>
            </DocSection>

            <DocSection id="store-app" title="Install the Patched Store app (recommended)">
                <p>The store app is the easiest way to install patched builds:</p>
                <ul>
                    <li>It installs split <strong>.xapk</strong> bundles, which a browser cannot install.</li>
                    <li>It checks the signature of every build before installing it, and refuses anything we did
                        not sign.</li>
                    <li>It keeps itself up to date.</li>
                </ul>
                <ol className="doc-steps">
                    <li>On your phone, tap <strong>Download APK</strong> below (it is also on
                        the <Link to="/">home page</Link>).</li>
                    <li>Open the downloaded file from the notification or your Downloads folder.</li>
                    <li>
                        If Android says your browser is not allowed to install unknown apps, tap
                        {' '}<strong>Settings</strong>, turn on <strong>Allow from this source</strong>, go back and
                        tap <strong>Install</strong>.
                    </li>
                    <li>If Google Play Protect warns you, see <a href="#play-protect">Play Protect</a> below.</li>
                    <li>
                        Open Patched Store and sign in with your site account. Pick an app and a version, then tap
                        {' '}<strong>Download</strong> and <strong>Install</strong>.
                    </li>
                </ol>
                <StoreDownload steps={false}/>
            </DocSection>

            <DocSection id="replace" title="Replacing the Play Store version">
                <p>
                    A patched app cannot be installed over the copy from the Play Store. The two are signed with
                    different keys, so Android refuses the install (&quot;App not installed&quot;, or
                    {' '}<code>INSTALL_FAILED_UPDATE_INCOMPATIBLE</code> in the store app).
                    <strong> Uninstall the original first.</strong> The store app recognises this case and offers
                    {' '}<strong>Uninstall and retry</strong>.
                </p>
                <p className="doc-callout">
                    <strong>WhatsApp: save your chats before you uninstall.</strong> A Google Drive backup is not
                    enough, and uninstalling deletes the backup on the phone. Follow
                    {' '}<a href="#whatsapp-chats">Keeping your WhatsApp chats</a> first.
                </p>
                <p>
                    You only do this once. Every patched build is signed with the same key, so a newer patched
                    build installs over an older one and keeps your data.
                </p>
            </DocSection>

            <DocSection id="whatsapp-chats" title="Keeping your WhatsApp chats">
                <p className="doc-callout">
                    <strong>The store app does this for you.</strong> Open WhatsApp in the Patched Store app and
                    tap <strong>Start</strong> on the <strong>Keep your chats</strong> card. It walks you
                    through each step and moves the backup itself. The steps below are the manual way, for
                    when you can&apos;t use the store app.
                </p>
                <p>
                    <strong>WhatsApp&apos;s Google Drive backup cannot be restored into a patched build.</strong>
                    {' '}Google only lets the original WhatsApp app use it. Move your chats with the backup
                    WhatsApp keeps on the phone instead. Uninstalling WhatsApp deletes that backup as well, so
                    copy it somewhere safe first.
                </p>
                <ol className="doc-steps">
                    <li>
                        In the Play Store WhatsApp, open <strong>Settings &gt; Chats &gt; Chat backup</strong> and
                        tap <strong>Back up</strong>. Besides Google Drive, this writes a fresh copy on the phone.
                    </li>
                    <li>
                        If <strong>End-to-end encrypted backup</strong> is turned on in that screen, make sure you
                        know its password or 64-digit key. You will need it to restore.
                    </li>
                    <li>
                        Open a file manager, for example <strong>Files by Google</strong>, and go to
                        {' '}<strong>Android &gt; media &gt; com.whatsapp</strong>. Copy the
                        {' '}<strong>WhatsApp</strong> folder in it to <strong>Download</strong>, or to a computer.
                        Its <strong>Databases</strong> folder holds your chats and <strong>Media</strong> your
                        photos and videos.
                    </li>
                    <li>
                        Uninstall the Play Store WhatsApp, then install the patched build with the store app.
                        Do not open it yet.
                    </li>
                    <li>
                        Copy the saved <strong>WhatsApp</strong> folder back into
                        {' '}<strong>Android &gt; media &gt; com.whatsapp</strong>, creating the
                        {' '}<strong>com.whatsapp</strong> folder if it is not there.
                    </li>
                    <li>
                        Open the patched WhatsApp, verify the <strong>same phone number</strong>, and tap
                        {' '}<strong>Restore</strong> when it finds the backup.
                    </li>
                </ol>
                <p>
                    Keep your saved copy until you have checked that your chats are back. If your phone&apos;s file
                    manager cannot open <strong>Android &gt; media</strong>, connect the phone to a computer over
                    USB and copy the same folder there. Stuck? Email <SupportLink/>.
                </p>
            </DocSection>

            <DocSection id="play-protect" title="Google Play Protect">
                <p>
                    Our builds are signed with our own key, which Play Protect has not seen before, so it shows
                    {' '}<strong>App scan recommended</strong> (or a similar warning) when you install one. Tap
                    {' '}<strong>Install anyway</strong>, or <strong>Install without scanning</strong> on some
                    phones. This is the normal prompt for any app from outside the Play Store and appears on every
                    install, including updates.
                </p>
            </DocSection>

            <DocSection id="browser" title="Installing from the browser">
                <p>Builds marked <strong>APK</strong> on an app&apos;s page can also be installed without the store
                    app:</p>
                <ol className="doc-steps">
                    <li>Open the app&apos;s page on this site on your phone and tap <strong>Download</strong> on the
                        version you want.</li>
                    <li>When the download finishes, open the <strong>.apk</strong> file.</li>
                    <li>Allow your browser to install unknown apps if asked, then tap <strong>Install</strong>.</li>
                </ol>
            </DocSection>

            <DocSection id="xapk" title="XAPK bundles need the store app">
                <p>
                    Builds marked <strong>XAPK</strong> are split bundles: several APK files packed into one
                    archive. Android cannot install that file from a browser or a file manager. Download and
                    install these builds with the <a href="#store-app">Patched Store app</a>, which installs every
                    part in one go.
                </p>
            </DocSection>

            <DocSection id="updates" title="Updates">
                <ul>
                    <li>The Patched Store app updates itself: when a new version is published, it offers the
                        update inside the app.</li>
                    <li>
                        Patched apps do not update themselves. When a new build is listed, download it and install
                        it over the one you have. Your data stays, because every build is signed with the same key.
                    </li>
                </ul>
            </DocSection>

            <DocSection id="troubleshooting" title="Troubleshooting">
                <ul>
                    <li>
                        <strong>&quot;App not installed&quot; or &quot;package conflicts with an existing
                        package&quot;:</strong> the original app is still installed. Uninstall it first (for
                        WhatsApp, <a href="#whatsapp-chats">save your chats</a> before you do).
                    </li>
                    <li>
                        <strong>&quot;There was a problem parsing the package&quot;:</strong> the phone runs an
                        Android version older than {MIN_ANDROID}, or the download did not finish. Download it
                        again.
                    </li>
                    <li>
                        <strong>The app says your device limit is reached:</strong> each patched app on each phone
                        takes one of your {MAX_DEVICES} slots. Free them from
                        the <Link to="/subscription">Subscription</Link> page, once every 30 days.
                    </li>
                    <li>
                        <strong>Still stuck?</strong> Use <strong>Report a bug</strong> at the bottom of any page,
                        or email <SupportLink/>.
                    </li>
                </ul>
            </DocSection>
        </DocPage>
    );
}

export default InstallPage;
