/*
 * Form rules that mirror the server's, purely for fast feedback: the backend
 * is the authority and its 422 messages are surfaced verbatim when they come
 * back. Registration and password reset share the password rules, so the
 * hints on both pages cannot drift apart (server/utils/passwords.py).
 */

export const USERNAME_PATTERN = /^[A-Za-z0-9_.-]+$/;
export const USERNAME_MIN = 3;
export const USERNAME_MAX = 32;

export const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export const PASSWORD_MIN = 8;
export const PASSWORD_MAX = 128;
export const PASSWORD_HINT = `At least ${PASSWORD_MIN} characters.`;

/** An error message for a new password, or undefined when it is acceptable. */
export function passwordError(password) {
    if (!password) return 'Create a password.';
    if (password.length < PASSWORD_MIN || password.length > PASSWORD_MAX) {
        return `Password must be ${PASSWORD_MIN}-${PASSWORD_MAX} characters.`;
    }
    return undefined;
}

export function confirmPasswordError(password, confirmPassword) {
    if (!confirmPassword) return 'Repeat your password.';
    if (password !== confirmPassword) return 'Passwords do not match.';
    return undefined;
}

export function emailError(email) {
    if (!email) return 'Enter your email address.';
    if (!EMAIL_PATTERN.test(email)) return 'Enter a valid email address.';
    return undefined;
}
