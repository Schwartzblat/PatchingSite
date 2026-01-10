// client/src/PatchedAppPage.jsx
import { useEffect, useState } from 'react';
import { apps } from './apps.js';
import './PatchedAppPage.css';
import {NavLink, Outlet} from "react-router";

function PatchedAppPage({ app_name }) {
    const app = apps[app_name];
    const [versions, setVersions] = useState([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);

    useEffect(() => {
        if (!app) {
            // eslint-disable-next-line react-hooks/set-state-in-effect
            setVersions([]);
            setError('App not found.');
            return;
        }

        let isActive = true;
        setLoading(true);
        setError(null);

        fetch(`http://localhost:8000/list/${app.package}`)
            .then((response) => {
                if (!response.ok) {
                    throw new Error(`Request failed (${response.status})`);
                }
                return response.json();
            })
            .then((payload) => {
                if (!isActive) return;

                const rawList = Array.isArray(payload) ? payload : payload?.versions ?? [];
                const normalized = rawList.map((entry, index) => {
                    if (typeof entry === 'string') {
                        return {
                            id: `${entry}-${index}`,
                            label: entry,
                            meta: 'Ready to download',
                        };
                    }

                    const label = entry?.version ?? entry?.name ?? entry?.tag ?? `Build ${index + 1}`;
                    const meta = entry?.updated_at
                        ? new Date(entry.updated_at).toLocaleString()
                        : entry?.build ?? entry?.hash ?? 'Ready to download';

                    return {
                        id: entry?.id ?? `${label}-${index}`,
                        label,
                        meta,
                        href: entry?.url ?? entry?.download ?? entry?.href ?? entry?.link ?? null,
                    };
                });

                setVersions(normalized);
            })
            .catch((fetchError) => {
                if (!isActive) return;
                setError(fetchError.message ?? 'Failed to load versions.');
                setVersions([]);
            })
            .finally(() => {
                if (isActive) {
                    setLoading(false);
                }
            });

        return () => {
            isActive = false;
        };
    }, [app]);

    if (!app) {
        return (
            <div className="patched-app-page">
                <div className="page-panel">
                    <p className="grid-message grid-message--error">Unknown app.</p>
                </div>
            </div>
        );
    }

    const statusText = loading
        ? 'Fetching builds…'
        : `${versions.length} build${versions.length === 1 ? '' : 's'} ready`;

    const skeletons = Array.from({ length: 4 }).map((_, index) => (
        <div className="version-card skeleton-card" key={`skeleton-${index}`} aria-hidden="true">
            <span className="skeleton-line skeleton-line--title" />
            <span className="skeleton-line skeleton-line--meta" />
        </div>
    ));

    return (
        <div className="patched-app-page">
            <div className="page-panel" aria-busy={loading}>
                <div className="app_shell">
                    <header className="app_header">
                        <NavLink to="/" className="back_home" aria-label="Back to Home">
                            ← Back to Home
                        </NavLink>
                    </header>

                    <main className="app_main">
                        <Outlet />
                    </main>
                </div>
                <header className="app-header">
                    <div className="app-icon-ring">
                        <img src={app.icon} alt={`${app.name} icon`} />
                    </div>
                    <div className="app-meta">
                        <p className="app-label">{app.name}</p>
                        <h1 className="app-package">{app.package}</h1>
                    </div>
                </header>

                <div className="status-bar">
                    <span className="status-pill">{statusText}</span>
                    {error && <span className="status-pill status-pill--error">Something went wrong</span>}
                </div>

                <section className="versions-grid" aria-live="polite">
                    {loading && skeletons}
                    {!loading && error && (
                        <div className="grid-message grid-message--error">
                            <p>{error}</p>
                        </div>
                    )}
                    {!loading && !error && versions.length === 0 && (
                        <div className="grid-message">
                            <p>No patched builds yet. Check back soon.</p>
                        </div>
                    )}
                    {!loading &&
                        !error &&
                        versions.map((version) => (
                            <article key={version.id} className="version-card">
                                <div>
                                    <p className="version-name">{version.label}</p>
                                    <p className="version-meta">{version.meta}</p>
                                </div>
                                {version.href && (
                                    <a
                                        className="download-btn"
                                        href={version.href}
                                        target="_blank"
                                        rel="noopener noreferrer"
                                    >
                                        Download
                                    </a>
                                )}
                            </article>
                        ))}
                </section>
            </div>
        </div>
    );
}

export default PatchedAppPage;