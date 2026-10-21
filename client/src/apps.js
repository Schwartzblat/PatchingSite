import {appsHe} from './apps.he.js';

/**
 * The object key is the route segment (/apps/<key>), so it is not duplicated in
 * a `path` field. `package`, `name` and each feature's `name` must stay in sync
 * with server/config.py APPS — the backend is the source of truth for them.
 *
 * `about` is web-only copy for an app whose name means nothing to a visitor
 * from abroad.
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
 * claim in them comes from the patcher's own code and docs (the WhatsApp patcher
 * project), not from the feature's name:
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
