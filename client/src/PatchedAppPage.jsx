// client/src/App.jsx
// import { useEffect } from 'react'
import {apps} from "./apps.js";


function PatchedAppPage({ app_name }) {
    const app = apps[app_name];
    return (
        <div>
            <h1>{app.package}</h1>
        </div>
    );
}

export default PatchedAppPage
