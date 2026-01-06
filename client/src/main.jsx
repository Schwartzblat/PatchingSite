import {StrictMode} from 'react'
import {createRoot} from 'react-dom/client'
import {BrowserRouter, Routes, Route} from "react-router";
import './index.css'
import App from './App.jsx'
import PatchedAppPage from "./PatchedAppPage.jsx";
import {apps} from "./apps.js";
import NotFound from "./NotFound.jsx";

/**
 *
 * @type {Route[]}
 */
const appRoutes = Object.keys(apps).map((key) => (
    <Route
        key={key}
        path={key}
        element={<PatchedAppPage app_name={key}/>}
    />
));


createRoot(document.getElementById('root')).render(
    <BrowserRouter>

        <Routes>
            <Route path="*" element={<NotFound/>}/>
            <Route path="/" element={<App/>}/>
            <Route path="/apps">
                {appRoutes}
            </Route>
        </Routes>
    </BrowserRouter>,
)
