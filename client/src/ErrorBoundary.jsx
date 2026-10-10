import {Component} from 'react';
import './Layout.css';

/**
 * React only stops a render error from blanking the whole tree if a class
 * component catches it, so this one stays a class on purpose.
 */
class ErrorBoundary extends Component {
    constructor(props) {
        super(props);
        this.state = {error: null};
        this.handleReload = this.handleReload.bind(this);
    }

    static getDerivedStateFromError(error) {
        return {error};
    }

    componentDidCatch(error, info) {
        // Only log the stack/component trace in development; production stays quiet.
        if (import.meta.env.DEV) {
            console.error('Render error:', error, info?.componentStack);
        }
    }

    handleReload() {
        window.location.reload();
    }

    render() {
        if (!this.state.error) return this.props.children;

        return (
            <div className="layout-crash" role="alert">
                <h1 className="layout-crash-title">Something went wrong</h1>
                <p className="layout-crash-text">
                    This part of the page failed to render. Reloading usually clears it.
                </p>
                <button type="button" className="main-button" onClick={this.handleReload}>
                    Reload the page
                </button>
            </div>
        );
    }
}

export default ErrorBoundary;
