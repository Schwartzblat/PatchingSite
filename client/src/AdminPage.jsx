import {useCallback, useEffect, useState} from 'react';
import {Link, useNavigate, useSearchParams} from 'react-router';
import {api} from './lib/api.js';
import {useAuth} from './lib/auth.jsx';
import {formatDate} from './lib/format.js';
import PageTitle from './PageTitle.jsx';
import AdminUserDetail from './AdminUserDetail.jsx';
import './AuthForm.css';
import './Subscription.css';
import './Admin.css';

const SEARCH_DELAY_MS = 300;

/**
 * The admin panel (server/api/admin.py): every account on one side, the one
 * named by ?user= on the other. The selection lives in the query string, not a
 * route like /admin/users/:name, because nginx serves only the files the build
 * wrote and that path would be a 404 in production. It also makes reload, back
 * and forward, and "open in new tab" work.
 *
 * English only. The server refuses non-admins on every endpoint; the check here
 * only spares them a page of errors.
 */
function AdminPage() {
    const {user} = useAuth();
    const navigate = useNavigate();
    const [searchParams] = useSearchParams();
    const selected = searchParams.get('user');
    const [query, setQuery] = useState('');
    // undefined while the first answer is pending.
    const [list, setList] = useState(undefined);
    const [listError, setListError] = useState(null);
    const [reloadKey, setReloadKey] = useState(0);
    // The last delete's answer, kept here because the detail it came from is gone.
    const [deleted, setDeleted] = useState(null);

    const isAdmin = Boolean(user?.is_admin);

    useEffect(() => {
        if (!isAdmin) return undefined;
        const controller = new AbortController();
        // Debounced: one request per pause in typing, not one per keystroke.
        const timer = setTimeout(async () => {
            try {
                const data = await api.admin.listUsers(query.trim(), {signal: controller.signal});
                setList(data ?? null);
                setListError(null);
            } catch (err) {
                if (err?.name === 'AbortError') return;
                setListError(err?.message || 'We could not load the accounts.');
            }
        }, query ? SEARCH_DELAY_MS : 0);
        return () => {
            clearTimeout(timer);
            controller.abort();
        };
    }, [isAdmin, query, reloadKey]);

    const reloadList = useCallback(() => setReloadKey((key) => key + 1), []);

    const onDeleted = useCallback((answer) => {
        setDeleted(answer);
        reloadList();
        navigate('/admin');
    }, [navigate, reloadList]);

    if (!isAdmin) {
        return (
            <div className="subscription-page subscription-page--narrow">
                <PageTitle title="Admin" noindex/>
                <h1 className="subscription-title">Admin</h1>
                <p className="subscription-empty">This page is for administrators.</p>
            </div>
        );
    }

    const users = list?.users ?? [];
    const count = list && (list.total > users.length
        ? `Showing ${users.length} of ${list.total}. Search to narrow it down.`
        : `${list.total} ${list.total === 1 ? 'account' : 'accounts'}`);

    return (
        <div className="subscription-page admin-page">
            <PageTitle title="Admin" noindex/>
            <h1 className="subscription-title">Admin</h1>

            <div className="admin-layout">
                <section className="subscription-panel admin-list" aria-labelledby="admin-accounts-title">
                    <h2 id="admin-accounts-title" className="subscription-panel__title">Accounts</h2>
                    <div className="authField">
                        <label className="authLabel" htmlFor="admin-search">Search by username or email</label>
                        <input
                            id="admin-search"
                            className="authInput"
                            type="search"
                            value={query}
                            onChange={(e) => setQuery(e.target.value)}
                            autoComplete="off"
                            spellCheck={false}
                        />
                    </div>

                    {listError && <p className="alert alert-error" role="alert">{listError}</p>}
                    {list === undefined && !listError && (
                        <p className="subscription-loading subscription-loading--inline" role="status">
                            <span className="subscription-spinner" aria-hidden="true"/>
                            Loading the accounts…
                        </p>
                    )}

                    {list && (
                        <>
                            <p className="subscription-period" role="status">{count}</p>
                            <div className="admin-table-wrap">
                                <table className="admin-table">
                                    <thead>
                                        <tr>
                                            <th scope="col">Username</th>
                                            <th scope="col">Email</th>
                                            <th scope="col">Verified</th>
                                            <th scope="col">Created</th>
                                            <th scope="col">Devices</th>
                                            <th scope="col">Stripe</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {users.map((row) => (
                                            <tr key={row.username} aria-current={row.username === selected ? 'true' : undefined}>
                                                <td>
                                                    <Link to={{pathname: '/admin', search: `?user=${encodeURIComponent(row.username)}`}}>
                                                        {row.username}
                                                    </Link>
                                                    {row.is_admin && <span className="admin-badge">admin</span>}
                                                </td>
                                                <td className="admin-table__email" title={row.email}>{row.email}</td>
                                                <td>{row.email_verified ? 'yes' : <span className="admin-flag--warn">no</span>}</td>
                                                <td>{formatDate(row.created_at) ?? '—'}</td>
                                                <td>{row.devices_used}/{list.devices_limit}</td>
                                                <td>{row.customer_id ? 'linked' : '—'}</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        </>
                    )}
                </section>

                <div className="admin-detail">
                    {selected ? (
                        <AdminUserDetail
                            key={selected}
                            username={selected}
                            self={user.username}
                            onChanged={reloadList}
                            onDeleted={onDeleted}
                        />
                    ) : deleted ? (
                        <div className="alert alert-success" role="status">
                            <p className="admin-result">Deleted {deleted.deleted}.</p>
                            {deleted.dashboard_url && (
                                <p className="admin-result">
                                    Cancel any subscription and delete the customer in Stripe:{' '}
                                    <a href={deleted.dashboard_url} target="_blank" rel="noreferrer">{deleted.customer_id}</a>
                                </p>
                            )}
                        </div>
                    ) : (
                        <p className="subscription-empty">Choose an account to see its details.</p>
                    )}
                </div>
            </div>
        </div>
    );
}

export default AdminPage;
