import {appsHe} from './apps.he.js';

/**
 * The object key is the route segment (/apps/<key>), so it is not duplicated in
 * a `path` field. `package`, `name` and each feature's `name` must stay in sync
 * with server/config.py APPS — the backend is the source of truth for them.
 *
 * `about` is web-only copy for an app whose name means nothing to a visitor
 * from abroad: "12+" is what Keshet 12 calls its Mako app on the Play Store.
 *
 * `description` is the page's <meta name="description"> and its SoftwareApplication
 * blurb. It names the actual patches rather than the app, because the app's own
 * name is a query this site cannot win against its publisher. Keep it under ~160
 * characters, which is roughly where Google truncates a search snippet, and keep
 * each one different: four near-identical descriptions read as thin content.
 *
 * `category` is the schema.org applicationCategory for AppSchema.jsx. It must be
 * one of the app types Google accepts (CommunicationApplication,
 * TravelApplication, EntertainmentApplication, SocialNetworkingApplication and
 * so on); anything else is reported as an invalid value.
 *
 * `intro`, each feature's `detail` and `faq` are the app page's text. Every
 * claim in them comes from the patcher's own code and docs (the WhatsApp,
 * Moovit, Mako, Reshet and OkCupid patcher projects), not from the feature's name:
 * where the code does less than the name suggests, the detail says what it
 * does. Change them when a patch changes.
 *
 * apps.he.js holds the Hebrew text for every app here, with the features in
 * the same order; the build refuses a catalogue where the two disagree.
 */
export const apps = {
    whatsapp: {
        name: "WhatsApp",
        package: "com.whatsapp",
        category: "CommunicationApplication",
        description:
            "Patched WhatsApp for Android: save view-once media, keep revoked messages, "
            + "disable read receipts per chat and back up chats to your own Google Drive.",
        icon: "/whatsapp.webp",
        intro:
            "The official WhatsApp, with the changes below built in and a Patcher screen in WhatsApp's own "
            + "settings to switch each of them on or off. You sign in with your own number, and your chats "
            + "move over from the Play Store version with the backup WhatsApp keeps on your phone.",
        features: [
            {
                name: "Keep Revoked Messages",
                detail: "When someone deletes a message for everyone, your copy stays in the chat. It covers "
                    + "deletions that arrive while the option is on.",
            },
            {
                name: "Trash Icon on Revoked Messages",
                detail: "A trash-can icon on the time of every message its sender deleted, so you can tell "
                    + "which ones they tried to take back.",
            },
            {
                name: "Unlimited Time to Delete for Everyone",
                detail: "Delete for everyone stays available on your messages after WhatsApp's usual limit of "
                    + "about two and a half days. Whether an old message then disappears on the other phone is "
                    + "up to WhatsApp's servers and the other person's app.",
            },
            {
                name: "Disable Read Receipts Per Chat",
                detail: "Read messages and play voice notes without sending blue ticks: in every chat, every chat "
                    + "except groups, or only the chats you pick. You still see other people's ticks.",
            },
            {
                name: "Meta AI Removed From the Home Screen",
                detail: "Hides the Meta AI tab and the Meta AI buttons on the Chats and Calls screens.",
            },
            {
                name: "Save View Once Media",
                detail: "View-once photos and videos you receive arrive as ordinary ones, so you can open them "
                    + "again and keep them.",
            },
            {
                name: "Profile Picture Screenshot",
                detail: "Screenshots and screen recording work everywhere in WhatsApp, profile pictures included. "
                    + "On Android 14 and newer, WhatsApp is not told when you take one.",
            },
            {
                name: "Unlock Premium Features",
                detail: "Turns on the WhatsApp Premium extras that WhatsApp offers only to some users: app themes "
                    + "and icons, ringtones, chat lists, more pinned chats and custom reactions.",
            },
            {
                name: "Tag a Group With @everyone",
                detail: "Type @everyone or @all in a group to mention every member at once. Members whose WhatsApp "
                    + "has not switched this feature on yet are not notified.",
            },
            {
                name: "Settings Screen to Toggle Features",
                detail: "A Patcher entry in WhatsApp's Settings turns each change on or off. Changes apply the next "
                    + "time WhatsApp starts, and if WhatsApp ever fails to start twice in a row, it starts with "
                    + "only the essentials so you can switch the culprit off.",
            },
            {
                name: "Group and Member Statistics",
                detail: "A Statistics entry in group info: who writes the most, activity by hour and weekday, "
                    + "favourite emoji and reactions, and a card for each member. It counts the history stored "
                    + "on your phone.",
            },
            {
                name: "Send More Group History to New Members",
                detail: "When you add someone to a group, send them up to 2,500 recent messages or up to a year "
                    + "of history instead of WhatsApp's small default. Their app may still keep less. It is off "
                    + "until you turn it on.",
            },
            {
                name: "Shared Members Between Groups",
                detail: "Pick two or more groups and see the people who are in all of them. Tap a name to open "
                    + "their profile.",
            },
            {
                name: "Back Up Chats to Your Own Google Drive",
                detail: "WhatsApp's own Drive backup cannot work in a re-signed app, so this one copies WhatsApp's "
                    + "nightly chat backup to a folder in your Google Drive about once a day, and restores it "
                    + "without reinstalling. Chats only, not media. It is off until you turn it on.",
            },
            {
                name: "Unlimited Pinned Chats",
                detail: "Pin as many chats as you like, instead of WhatsApp's three, or twenty with WhatsApp "
                    + "Premium. Pinning or unpinning a chat on a linked device such as WhatsApp Web, or linking a "
                    + "new one, keeps only the twenty chats you pinned most recently.",
            },
        ],
        faq: [
            {
                q: "Can I keep my chats?",
                a: "Yes, with the backup WhatsApp keeps on your phone. A Google Drive backup made by the Play "
                    + "Store WhatsApp cannot be restored into a patched build. The Patched Store app moves the "
                    + "phone backup for you, and the install guide has the manual steps.",
            },
            {
                q: "Can WhatsApp ban my number?",
                a: "It can. WhatsApp may limit or ban an account that uses a modified app, and we cannot "
                    + "prevent or undo that. Patched apps are used at your own risk, as our Terms say.",
            },
            {
                q: "Do the people I chat with need it too?",
                a: "No. Everything here happens on your phone, and the people you talk to keep using the normal "
                    + "WhatsApp.",
            },
            {
                q: "What happens when WhatsApp updates?",
                a: "A patched WhatsApp does not update from the Play Store. When a newer patched build is listed "
                    + "here and in the store app, install it over yours, and your chats stay.",
            },
        ],
    },
    moovit: {
        name: "Moovit",
        package: "com.tranzmate",
        category: "TravelApplication",
        description:
            "Patched Moovit for Android: public-transport routes and live arrival times with no ads, "
            + "plus Moovit+ Live Location to follow your line on the map.",
        icon: "/moovit.webp",
        intro:
            "The official Moovit with its ads taken out and Moovit+'s Live Location switched on. Routes, "
            + "live arrival times and everything else come from the same Moovit service as the Play Store "
            + "version.",
        features: [
            {
                name: "No Ads",
                detail: "Removes the banners, full-screen ads, ads in lists, sponsored places on the map and the "
                    + "\"watch a video to remove ads\" offer.",
            },
            {
                name: "Live Location Enabled",
                detail: "Moovit+'s Live Location, which follows your bus or train on the map, without a Moovit+ "
                    + "subscription.",
            },
        ],
        faq: [
            {
                q: "Are the routes and times the same?",
                a: "Yes. It is the same app talking to the same Moovit service; only the ads and the Moovit+ "
                    + "lock are changed.",
            },
            {
                q: "What happens when Moovit updates?",
                a: "The patched Moovit does not update from the Play Store. When a newer patched build is listed "
                    + "here and in the store app, install it over yours, and your saved places stay.",
            },
        ],
    },
    mako: {
        name: "12+",
        about: "Mako, the Keshet 12 TV app",
        package: "com.keshet.mako.VOD",
        category: "EntertainmentApplication",
        description:
            "Patched 12+ for Android, the Keshet 12 (Mako) TV app, with the ad breaks removed "
            + "from live channels and on-demand episodes.",
        icon: "/mako.webp",
        intro:
            "12+ is Keshet 12's app for Mako: the live channel and full episodes on demand. This build plays "
            + "them without the video ad breaks.",
        features: [
            {
                name: "No Ads",
                detail: "Removes the video ads before a show starts and the ad breaks in the middle of it, on the "
                    + "live channel and on demand.",
            },
        ],
        faq: [
            {
                q: "Does everything else work as usual?",
                a: "Yes. It is the same app and the same Mako service; the patch only changes when ads play.",
            },
            {
                q: "What happens when 12+ updates?",
                a: "The patched 12+ does not update from the Play Store. When a newer patched build is listed "
                    + "here and in the store app, install it over yours.",
            },
        ],
    },
    reshet: {
        name: "Reshet 13+",
        about: "The Channel 13 TV app",
        package: "com.applicaster.iReshet",
        category: "EntertainmentApplication",
        description:
            "Patched Reshet 13+ for Android, the Channel 13 TV app, without its video ads on demand "
            + "and live, or its full-screen, banner and web ads.",
        icon: "/reshet.webp",
        intro:
            "Reshet 13+ is Reshet's app for Channel 13: the live channel and full episodes on demand. This "
            + "build plays them without the app's video ads, and leaves out its full-screen, banner and web ads.",
        features: [
            {
                name: "No Video Ads",
                detail: "Removes the video ads before a show and in the middle of an episode on demand, and the "
                    + "ads the app adds to the live channel. The live channel's commercial breaks stay: they show "
                    + "the channel's own TV commercials, exactly as on TV.",
            },
            {
                name: "No Full-Screen or Banner Ads",
                detail: "No full-screen ads when the app opens or between screens, no banners, and no ads on the "
                    + "web pages the app shows inside it.",
            },
        ],
        faq: [
            {
                q: "Why are there still commercials on the live channel?",
                a: "The live channel is the broadcast itself, and its commercial breaks are part of it, so they "
                    + "play as they air on TV. What the patch removes are the ads the app adds on its own: before "
                    + "a show, during episodes on demand, and in place of the broadcast's commercials. If the "
                    + "broadcast's own stream cannot be reached, the app's stream plays instead, with each of its "
                    + "ads replaced by a black, silent screen of the same length.",
            },
            {
                q: "Does everything else work as usual?",
                a: "Yes. It is the same app and the same Reshet 13 service; the patch only changes the ads.",
            },
            {
                q: "What happens when Reshet 13+ updates?",
                a: "The patched Reshet 13+ does not update from the Play Store. When a newer patched build is "
                    + "listed here and in the store app, install it over yours.",
            },
        ],
    },
    okcupid: {
        name: "OkCupid",
        package: "com.okcupid.okcupid",
        category: "SocialNetworkingApplication",
        description:
            "Patched OkCupid for Android: unlimited rewinds to undo a pass, and see the full "
            + "profiles of everyone who viewed or liked you.",
        icon: "/okcupid.webp",
        intro:
            "The official OkCupid with two paid features free: rewinding as often as you like, and the full "
            + "list of people who liked or viewed your profile. It also adds a plain Like to OkCupid Picks.",
        features: [
            {
                name: "Unlimited Rewinds",
                detail: "Undo a pass as many times as you like, instead of the few rewinds a free account gets.",
            },
            {
                name: "See who viewed/liked you (full profile)",
                detail: "The \"Interested in you\" list shows everyone who liked or viewed your profile with their "
                    + "name and an unblurred photo, and tapping a card opens their full profile. The first time, "
                    + "names fill in over a few minutes as the app looks each one up.",
            },
            {
                name: "Regular Like on OkCupid Picks",
                detail: "A Picks card normally offers only its profile and a SuperLike, which costs tokens. A Like "
                    + "button next to the SuperLike sends an ordinary like instead.",
            },
        ],
        faq: [
            {
                q: "Do I keep my OkCupid account?",
                a: "Yes. You sign in with the account you have. Uninstalling the Play Store version signs you out, "
                    + "so you sign in again once the patched one is installed.",
            },
            {
                q: "Will these features keep working?",
                a: "They rely on how OkCupid's service works today. If OkCupid changes it, a feature can stop "
                    + "working until a new build is out.",
            },
        ],
    },
};

/**
 * One app's text in `lang`: the catalogue entry with the Hebrew text laid over
 * it, so the Hebrew file holds only what is said differently (the name, the
 * copy, a page title written for Hebrew searches) and shares the rest.
 */
export function appText(key, lang) {
    const app = apps[key];
    return lang === 'he' ? {...app, ...appsHe[key]} : app;
}

/**
 * Throws when apps.he.js and this file disagree: an app with no Hebrew text, a
 * feature list of a different length, or a Hebrew field left empty. Run by the
 * prerender step, so a Hebrew page can never ship half in English.
 */
export function checkTranslations() {
    const REQUIRED = ['name', 'title', 'description', 'intro', 'features', 'faq'];
    for (const [key, app] of Object.entries(apps)) {
        const he = appsHe[key];
        if (!he) throw new Error(`apps.he.js has no entry for ${key}`);
        for (const field of REQUIRED) {
            if (!he[field] || he[field].length === 0) throw new Error(`apps.he.js: ${key}.${field} is missing`);
        }
        if (he.features.length !== app.features.length) {
            throw new Error(`apps.he.js: ${key} has ${he.features.length} features, apps.js has ${app.features.length}`);
        }
        if (app.about && !he.about) throw new Error(`apps.he.js: ${key}.about is missing`);
    }
    for (const key of Object.keys(appsHe)) {
        if (!apps[key]) throw new Error(`apps.he.js has an entry for ${key}, which is not in apps.js`);
    }
}
