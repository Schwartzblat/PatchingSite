// client/src/lib/friendApps.js
import {useEffect, useState} from 'react';
import {api} from './api.js';
import {useAuth} from './auth.jsx';

/*
 * Friends-only apps never come from this bundle: they are not in apps.js, not
 * in the prerendered pages and not in the sitemap (see the friends catalogue
 * spec's anti-goal). Their names, packages and icons arrive from /api/apps
 * for a signed-in friend and nobody else, so no client code may name them,
 * not even in a comment.
 */

/**
 * The friends-only entries of /api/apps. Fetches only for a friend; `loading`
 * is true while the session is still being resolved or that fetch is running,
 * and false with an empty list for guests and everyone who is not a friend.
 */
export function useFriendApps() {
    const {user, loading: authLoading} = useAuth();
    const isFriend = Boolean(user?.is_friend);
    const [state, setState] = useState({forFriend: false, apps: []});

    useEffect(() => {
        if (!isFriend) return undefined;
        const controller = new AbortController();
        api.listApps({signal: controller.signal})
            .then((items) => {
                const apps = Array.isArray(items) ? items.filter((item) => item.friends_only === true) : [];
                setState({forFriend: true, apps});
            })
            .catch((err) => {
                if (err?.name === 'AbortError') return;
                setState({forFriend: true, apps: []});
            });
        return () => controller.abort();
    }, [isFriend]);

    if (authLoading) return {loading: true, apps: []};
    if (!isFriend) return {loading: false, apps: []};
    if (!state.forFriend) return {loading: true, apps: []};
    return {loading: false, apps: state.apps};
}

/** One entry in the shape the home card and the app page read, in `lang`. */
export function friendAppText(entry, lang) {
    const he = lang === 'he';
    const names = he && Array.isArray(entry.features_he) ? entry.features_he : entry.features;
    const name = he && entry.name_he ? entry.name_he : entry.name;
    return {
        key: entry.key,
        name,
        // The Hebrew page title reads text.title (the public apps carry a written one).
        title: name,
        package: entry.package,
        icon: entry.icon,
        features: (names ?? []).map((name) => ({name})),
        friendsOnly: true,
    };
}
