import {Link} from 'react-router';

/**
 * "By <action> you agree to the Terms and Privacy Policy", beside the two
 * buttons that bind someone to them: Register and Subscribe.
 *
 * The links open a new tab so reading the terms does not throw away a
 * half-filled registration form; the visually hidden suffix says so, since a
 * new tab is otherwise a surprise for screen reader users.
 */
export default function LegalConsent({action, className = ''}) {
    return (
        <p className={`legal-consent ${className}`.trim()}>
            By {action} you agree to the{' '}
            <Link to="/terms" target="_blank" rel="noopener">
                Terms of Service<span className="visually-hidden"> (opens in a new tab)</span>
            </Link>
            {' '}and{' '}
            <Link to="/privacy" target="_blank" rel="noopener">
                Privacy Policy<span className="visually-hidden"> (opens in a new tab)</span>
            </Link>.
        </p>
    );
}
