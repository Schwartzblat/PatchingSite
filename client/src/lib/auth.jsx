import {createContext, useCallback, useContext, useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate} from 'react-router';
import {api, setUnauthorizedHandler} from './api.js';
import {loginPath} from './redirect.js';

/**
 * Replaces the old module-level `me` singleton in consts.js. That value was
 * read during render but was not React state, so mutating it never triggered a
 * re-render and pages showed stale auth.
 */
const AuthContext = createContext(null);

/* Pages that handle a signed-out visitor themselves. A 401 raised while one of
   them is showing must not bounce the user to /login again. /verify-email is
   one for a second reason too: the redirect carries the current URL in
   ?redirect=, and there that URL holds the verification token. */
const SIGNED_OUT_PAGES = new Set(['/login', '/register', '/forgot-password', '/reset-password', '/verify-email']);

const SESSION_EXPIRED_NOTICE = 'Your session expired. Sign in again to continue.';

export function AuthProvider({children}) {
    // Whatever /users/me returned: {username, email, created_at, email_verified}.
    const [user, setUser] = useState(null);
    const [loading, setLoading] = useState(true);
    /*
     * What this tab knows about the verification email: true once one was
     * sent (by sign-up or a resend), false when sign-up could not send it, null
     * when this tab has not seen either. /users/me cannot say whether a link
     * ever went out, so without this the banner would tell someone whose
     * sign-up email failed that "we sent a link". It is memory only; after a
     * reload the banner assumes the link was sent, as it was for nearly
     * everyone.
     */
    const [verificationSent, setVerificationSent] = useState(null);
    const navigate = useNavigate();

    // Read by the 401 handler, which runs outside render.
    const userRef = useRef(null);
    useEffect(() => {
        userRef.current = user;
    }, [user]);

    /*
     * Any authenticated call that comes back 401 lands here (see request() in
     * api.js). Each page used to handle it alone, or not at all: an expired
     * cookie on /subscription showed "Authentication required!" in a red
     * banner with no way forward. The location is read from `window` rather
     * than the router because the handler outlives any one render. Reading it
     * also dedupes: navigate() pushes synchronously, so when a page fires two
     * requests that both 401, the second sees /login and stops.
     *
     * Only a session we believed in can expire. With no user (a guest, or a
     * request still in flight when the user logged out) a 401 is left to the
     * page, which already shows its own sign-in prompt; redirecting then would
     * bounce someone who just logged out straight to /login.
     */
    useEffect(() => setUnauthorizedHandler(() => {
        const {pathname, search} = window.location;
        if (userRef.current === null || SIGNED_OUT_PAGES.has(pathname)) return;
        userRef.current = null;
        setUser(null);
        setVerificationSent(null);
        navigate(loginPath(`${pathname}${search}`), {
            state: {notice: SESSION_EXPIRED_NOTICE, noticeType: 'info'},
        });
    }), [navigate]);

    const refresh = useCallback(async (signal) => {
        try {
            const data = await api.me({signal});
            setUser(data ?? null);
            return data ?? null;
        } catch (err) {
            if (err?.name === 'AbortError') throw err;
            setUser(null);
            return null;
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        const controller = new AbortController();
        refresh(controller.signal).catch(() => {});
        return () => controller.abort();
    }, [refresh]);

    const login = useCallback(
        async (username, password) => {
            await api.login(username, password);
            return refresh();
        },
        [refresh],
    );

    const register = useCallback(async (username, email, password) => {
        const result = await api.register(username, email, password);
        // A server without verification omits the field; null keeps the default wording.
        const sent = result?.verification_sent;
        setVerificationSent(typeof sent === 'boolean' ? sent : null);
        return result;
    }, []);

    const markVerificationSent = useCallback(() => setVerificationSent(true), []);

    const logout = useCallback(async () => {
        // Before the request, not after the re-render: a 401 from anything
        // still in flight must not read as an expired session.
        userRef.current = null;
        try {
            await api.logout();
        } finally {
            setUser(null);
            setVerificationSent(null);
        }
    }, []);

    const value = useMemo(
        () => ({user, loading, refresh, login, register, logout, verificationSent, markVerificationSent}),
        [user, loading, refresh, login, register, logout, verificationSent, markVerificationSent],
    );

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// Fast Refresh prefers a module to export only components. Keeping the hook
// beside its provider is the conventional shape and this file changes rarely,
// so the tradeoff is worth more than the extra indirection of splitting it.
// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
    const context = useContext(AuthContext);
    if (context === null) {
        throw new Error('useAuth must be used inside an <AuthProvider>');
    }
    return context;
}
