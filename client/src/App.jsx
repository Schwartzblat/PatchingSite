// client/src/App.jsx
import {useEffect} from 'react'
import './App.css'
import {apps} from "./apps.js";
import { NavLink } from "react-router";
function Icon({path, alt}) {
    return (
        <img src={path} className="app_icon" alt={alt} width={120} height={120}/>
    );
}

const app_buttons = Object.entries(apps).map(([app_name, app]) => (
    <NavLink key={"button_" + app_name} to={"/apps" + app.path}
            className="w-48 h-48 flex items-center justify-center rounded-2xl bg-white shadow-xl hover:shadow-2xl transition">
        <Icon path={app.icon} alt={app_name}/>
    </NavLink>
));

function App() {
    useEffect(() => {
        document.title = 'Patching Site'
    }, []);

    return (
        <div className="min-h-screen flex items-center w-full">
            <div className="flex flex-col items-center" style={{textAlign: 'center'}}>
                <h1 className="text-3xl font-bold text-gray-800 title">Patching Site</h1>
                <div className="flex items-center justify-center gap-10">
                    {app_buttons}
                </div>
            </div>
        </div>
    );
}

export default App
