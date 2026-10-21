/*
 * Facts about the site that more than one page prints. They live here so the
 * footer, the legal pages and every "email us" message cannot drift apart.
 */

export const SITE_NAME = 'AndroidMod';
export const SITE_HOST = 'androidmod.site';
export const SITE_URL = `https://${SITE_HOST}`;

/* The server's SUPPORT_EMAIL defaults to the same address and embeds it in some
   error details (a 503 from forgot-password), so change both together. */
export const SUPPORT_EMAIL = 'sudorm.ext@gmail.com';
export const SUPPORT_MAILTO = `mailto:${SUPPORT_EMAIL}`;

/* Mirrors server/config.py MAX_DEVICES_ALLOWED. Only marketing and legal copy use
   it; the Subscription page's devices card prints the limit the server returns. */
export const MAX_DEVICES = 3;

/* The store app's minSdk is 30. Patched builds target the same floor. */
export const MIN_ANDROID = 'Android 11';

export const LEGAL_UPDATED = 'September 30, 2026';
/* The Privacy Policy carries its own date because analytics was added after
   launch and the Terms and Refund pages did not change with it. Re-dating all
   three from one constant would claim revisions that never happened. */
export const PRIVACY_UPDATED = 'October 6, 2026';
/* The Terms likewise: they changed when OkCupid and then Reshet 13+ joined the
   list of apps they name, and again when every app but WhatsApp left it; the
   Terms promise a new date whenever they change. LEGAL_UPDATED above is now the
   Refund Policy's alone. */
export const TERMS_UPDATED = 'October 22, 2026';
// Who runs the site, as the legal pages name it. The owner chose not to name a
// country or a governing law on the site.
export const OPERATOR_NAME = 'sudorm';

/* "<commit> <build date>", stamped by vite.config.js so a bug report says which
   build the reporter was running. The typeof guard keeps anything that imports
   this without Vite's `define` from throwing a ReferenceError. */
export const APP_VERSION = typeof __APP_VERSION__ === 'string' ? __APP_VERSION__ : 'dev';
