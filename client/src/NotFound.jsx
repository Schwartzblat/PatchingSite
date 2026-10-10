import {Link} from 'react-router';
import PageTitle from './PageTitle.jsx';
import './NotFound.css';

function NotFound() {
    return (
        <section className="notfound">
            <PageTitle title="Page not found" noindex/>
            <p className="notfound-code" aria-hidden="true">404</p>
            <h1 className="notfound-title">We could not find that page</h1>
            <p className="notfound-text">
                The link may be out of date, or the app you were looking for is not one we patch.
            </p>
            <Link className="main-button notfound-home" to="/">Back to the app list</Link>
        </section>
    );
}

export default NotFound;
