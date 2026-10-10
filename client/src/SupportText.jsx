import {SUPPORT_EMAIL, SUPPORT_MAILTO} from './lib/site.js';

/**
 * Renders a message, turning every occurrence of the support address into a
 * mailto link. Server errors embed the address as plain text (the
 * forgot-password 503 does), and an address you cannot tap is a dead end on
 * a phone.
 */
export default function SupportText({text}) {
    const parts = String(text ?? '').split(SUPPORT_EMAIL);
    return parts.flatMap((part, index) => (index === 0
        ? [part]
        : [<a key={index} href={SUPPORT_MAILTO}>{SUPPORT_EMAIL}</a>, part]));
}
