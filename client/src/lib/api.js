const BASE = '/api';

/*
 * Without a deadline a request to a server that accepted the connection but
 * never answered (a stalled Stripe call behind /create-checkout, a proxy that
 * lost its upstream) left its button spinning forever. 30 s is well past any
 * healthy response, including the Stripe-backed ones.
 */
export const DEFAULT_TIMEOUT_MS = 30_000;
const TIMEOUT_MESSAGE = 'The server took too long to answer. Check your connection and try again.';

export class ApiError extends Error {
    /** status 0 means no HTTP response at all: offline, refused, or timed out. */
    constructor(message, status, payload, {timedOut = false} = {}) {
        super(message);
        this.name = 'ApiError';
        this.status = status;
        this.payload = payload;
        this.timedOut = timedOut;
    }
}

/*
 * A 401 from an authenticated call means the session cookie expired or was
 * revoked mid-visit. AuthProvider registers the handler (it owns the user
 * state and the router), and request() calls it before throwing, so every
 * page gets the same "sign in again" redirect without each catch block
 * special-casing 401.
 */
let unauthorizedHandler = null;

/** Returns an unregister function, for use as an effect cleanup. */
export function setUnauthorizedHandler(handler) {
    unauthorizedHandler = handler;
    return () => {
        if (unauthorizedHandler === handler) unauthorizedHandler = null;
    };
}

/*
 * The caller's signal cancels (a component unmounting); the timeout signal
 * expires. Both must abort the fetch, but they are reported differently, so
 * the timeout signal is returned alongside the combined one for the catch
 * block to tell them apart. Reading err.name is not enough: browsers disagree
 * on whether fetch rejects with the signal's reason or a generic AbortError.
 */
function requestSignals(callerSignal, timeoutMs) {
    // AbortSignal.timeout: Chrome 103, Safari 16, Firefox 100. Older engines just get no deadline.
    const timeout = typeof AbortSignal.timeout === 'function' ? AbortSignal.timeout(timeoutMs) : null;
    if (!timeout) return {signal: callerSignal, timeout: null};
    if (!callerSignal) return {signal: timeout, timeout};
    // AbortSignal.any: Chrome 116, Safari 17.4, Firefox 124.
    if (typeof AbortSignal.any === 'function') return {signal: AbortSignal.any([callerSignal, timeout]), timeout};
    const controller = new AbortController();
    for (const source of [callerSignal, timeout]) {
        if (source.aborted) {
            controller.abort(source.reason);
            break;
        }
        source.addEventListener('abort', () => controller.abort(source.reason), {once: true});
    }
    return {signal: controller.signal, timeout};
}

function transportError(err, callerSignal, timeout) {
    // A cancelled request is not a failure; callers ignore AbortError.
    if (callerSignal?.aborted || (err?.name === 'AbortError' && !timeout?.aborted)) return err;
    if (timeout?.aborted) return new ApiError(TIMEOUT_MESSAGE, 0, null, {timedOut: true});
    return new ApiError(messageFrom(null, 0), 0, null);
}

/**
 * FastAPI reports failures as {detail: string} or, for 422s,
 * {detail: [{loc, msg, type}, ...]}. The old code read `data.error`,
 * which never exists, so every real message was swallowed.
 */
function messageFrom(payload, status) {
    const detail = payload?.detail;
    if (typeof detail === 'string' && detail) return detail;
    if (Array.isArray(detail) && detail.length) {
        return detail
            .map((item) => {
                const field = Array.isArray(item?.loc) ? item.loc[item.loc.length - 1] : null;
                return field ? `${field}: ${item.msg}` : item?.msg;
            })
            .filter(Boolean)
            .join(', ');
    }
    if (typeof payload?.message === 'string' && payload.message) return payload.message;
    if (status === 0) return 'Network error. Check your connection and try again.';
    return `Request failed (${status})`;
}

/**
 * `sessionCheck: false` opts a call out of the expired-session redirect: the
 * initial /users/me probe (a 401 there just means "signed out"), and the
 * login/register/password-reset calls, where a 401 is a wrong password and the
 * user is already on the page that handles it.
 */
async function request(path, {method = 'GET', body, signal, timeout = DEFAULT_TIMEOUT_MS, sessionCheck = true} = {}) {
    const signals = requestSignals(signal, timeout);
    let res;
    let text;
    try {
        res = await fetch(`${BASE}${path}`, {
            method,
            credentials: 'include',
            headers: body === undefined ? undefined : {'Content-Type': 'application/json'},
            body: body === undefined ? undefined : JSON.stringify(body),
            signal: signals.signal,
        });
        // Inside the try: the deadline covers the body too, and a stalled body
        // rejects exactly like a stalled response.
        text = res.status === 204 ? '' : await res.text();
    } catch (err) {
        throw transportError(err, signal, signals.timeout);
    }

    if (res.status === 204) return null;

    let payload = null;
    if (text) {
        try {
            payload = JSON.parse(text);
        } catch {
            payload = null;
        }
    }

    if (!res.ok) {
        if (res.status === 401 && sessionCheck) unauthorizedHandler?.();
        throw new ApiError(messageFrom(payload, res.status), res.status, payload);
    }
    return payload;
}

const NO_SESSION_CHECK = {sessionCheck: false};

/* Usernames go in the path; a row older than the username rules can hold "@". */
const adminUser = (username) => `/admin/users/${encodeURIComponent(username)}`;

export const api = {
    // auth
    me: (opts) => request('/users/me', {...opts, ...NO_SESSION_CHECK}),
    login: (username, password) =>
        request('/login', {method: 'POST', body: {username, password}, ...NO_SESSION_CHECK}),
    // {message, verification_sent}: false when the server could not mail the
    // verification link (no SMTP, or the day's mail budget is spent).
    register: (username, email, password) =>
        request('/register', {method: 'POST', body: {username, email, password}, ...NO_SESSION_CHECK}),
    // Logging out of an already-dead session is still a successful log out.
    logout: () => request('/users/logout', {method: 'POST', ...NO_SESSION_CHECK}),
    // Both answer the same way whether or not an account matches, and both run
    // signed out, so neither may trigger the expired-session redirect.
    forgotPassword: (email) =>
        request('/auth/forgot-password', {method: 'POST', body: {email}, ...NO_SESSION_CHECK}),
    resetPassword: (token, password) =>
        request('/auth/reset-password', {method: 'POST', body: {token, password}, ...NO_SESSION_CHECK}),
    // Signed in, so a 401 here really is an expired session. A wrong current
    // password is a 400 for that reason; 409 means it changed elsewhere meanwhile.
    changePassword: (currentPassword, newPassword) =>
        request('/users/me/password', {
            method: 'POST',
            body: {current_password: currentPassword, new_password: newPassword},
        }),

    // email verification. The link from the email works signed out (it is often
    // opened on another device), so verifyEmail never triggers the expired-session
    // redirect. Resending needs a session: 409 already verified, 429 rate
    // limited, 503 no email on the server (its detail names the support address).
    verifyEmail: (token) =>
        request('/auth/verify-email', {method: 'POST', body: {token}, ...NO_SESSION_CHECK}),
    resendVerification: () => request('/auth/resend-verification', {method: 'POST'}),

    // catalogue
    listVersions: (packageName, opts) =>
        request(`/list/${encodeURIComponent(packageName)}`, opts),

    // the public catalogue; each item has on_device (patched on the phone, not built here)
    listApps: (opts) => request('/apps', opts),

    // the Android store app itself; the file is a plain GET on storeDownloadUrl
    getStore: (opts) => request('/store', opts),

    // downloads (async job: enqueue -> poll -> signed url, valid for 10 minutes and reusable)
    createDownload: (packageName, version) =>
        request('/downloads', {method: 'POST', body: {package: packageName, version}}),
    getDownloadJob: (jobId, opts) => request(`/downloads/${encodeURIComponent(jobId)}`, opts),

    // billing
    // Public; {amount (minor units), currency, interval, interval_count}.
    getPrice: (opts) => request('/subscription/price', opts),
    getSubscription: (opts) => request('/subscription', opts),
    createCheckout: (priceId) =>
        request('/subscription/create-checkout', {method: 'POST', body: {priceId}}),
    billingPortal: () => request('/subscription/billing-portal', {method: 'POST'}),
    cancelSubscription: () => request('/subscription/cancel', {method: 'POST'}),
    reactivateSubscription: () => request('/subscription/reactivate', {method: 'POST'}),
    confirmSuccess: () => request('/subscription/confirm_success', {method: 'POST'}),

    // device slots: {used, limit, last_reset_at, next_reset_at, can_reset}
    getDevices: (opts) => request('/users/me/devices', opts),
    resetDevices: () => request('/users/me/devices/reset', {method: 'POST'}),

    // support. Signed-in or anonymous alike, so a 401 must not bounce the
    // reporter to /login and throw away what they typed.
    sendBugReport: (report) => request('/bug-reports', {method: 'POST', body: report, ...NO_SESSION_CHECK}),

    // the admin panel (server/api/admin.py): the session cookie of an account in
    // ADMIN_USERNAMES. Anyone else gets 403, which the page shows as it is.
    admin: {
        // {users: [{username, email, email_verified, created_at, customer_id, devices_used, is_admin}],
        //  total, limit, devices_limit}
        listUsers: (q, opts) => request(`/admin/users${q ? `?q=${encodeURIComponent(q)}` : ''}`, opts),
        // {user: {...a list row, devices_limit, devices_reset_at}, subscription: {...} | null,
        //  stripe: {customer_id, dashboard_url, customer_missing, error, test_mode}}
        getUser: (username, opts) => request(adminUser(username), opts),
        // These three answer {user}.
        changeEmail: (username, email, verified) =>
            request(`${adminUser(username)}/email`, {method: 'POST', body: {email, verified}}),
        verifyEmail: (username) => request(`${adminUser(username)}/verify-email`, {method: 'POST'}),
        clearDevices: (username) => request(`${adminUser(username)}/devices/reset`, {method: 'POST'}),
        // `end` is {days} or {until: 'YYYY-MM-DD'}. The preview writes nothing.
        previewTrial: (username, end) => request(`${adminUser(username)}/trial/preview`, {method: 'POST', body: end}),
        grantTrial: (username, end) => request(`${adminUser(username)}/trial`, {method: 'POST', body: end}),
        // {deleted, customer_id, dashboard_url}
        deleteUser: (username) => request(adminUser(username), {method: 'DELETE'}),
    },
};

export const storeDownloadUrl = `${BASE}/store/download`;

export const ACTIVE_SUBSCRIPTION_STATUSES = ['active', 'trialing'];

export function isSubscriptionActive(subscription) {
    return Boolean(subscription) && ACTIVE_SUBSCRIPTION_STATUSES.includes(subscription.status);
}

/*
 * /users/me reports `email_verified`. Only an explicit false counts: a server
 * from before verification existed omits the field, and nagging every user
 * then would be wrong. The server enforces verification on checkout itself,
 * so this only decides what the pages show.
 */
export function isEmailUnverified(user) {
    return user?.email_verified === false;
}

export function formatBytes(bytes) {
    if (typeof bytes !== 'number' || !Number.isFinite(bytes) || bytes < 0) return '—';
    if (bytes < 1024) return `${bytes} B`;
    const units = ['KB', 'MB', 'GB', 'TB'];
    let value = bytes / 1024;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
        value /= 1024;
        unit += 1;
    }
    return `${value.toFixed(value >= 10 || unit === 0 ? 0 : 1)} ${units[unit]}`;
}
