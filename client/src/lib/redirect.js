/**
 * Only same-origin relative paths are accepted as a post-login destination.
 * Anything protocol-relative ("//evil.example"), scheme-bearing or containing a
 * backslash (browsers normalise "\" to "/") would be an open redirect.
 *
 * LoginPage and RegisterPage each used to carry a copy of this; the session
 * expiry redirect in auth.jsx needs it too, so it lives in one place now.
 */
export function safeRedirect(value) {
    if (typeof value !== 'string' || value === '') return '/';
    if (value.includes('\\') || /^[a-z][a-z0-9+.-]*:/i.test(value)) return '/';
    if (!value.startsWith('/') || value.startsWith('//')) return '/';
    return value;
}

/** `/login`, carrying the page to come back to unless that is the home page. */
export function loginPath(returnTo) {
    const target = safeRedirect(returnTo);
    return target === '/' ? '/login' : `/login?redirect=${encodeURIComponent(target)}`;
}

/** Same for `/register`. */
export function registerPath(returnTo) {
    const target = safeRedirect(returnTo);
    return target === '/' ? '/register' : `/register?redirect=${encodeURIComponent(target)}`;
}
