// client/src/App.jsx
import { useEffect } from 'react'
import './App.css'

function Icon({ path }) {
    return (
        <img src={path} className="app_icon" alt="Logo" width={120} height={120}/>
    );
}

function App() {
    useEffect(() => {
        document.title = 'My App Title'
    }, []);

    return (
        <div className="min-h-screen flex items-center w-full">
            <div className="flex flex-col items-center" style={{ textAlign: 'center' }}>
                <h1 className="text-3xl font-bold text-gray-800">My App Title</h1>
                <div className="flex items-center justify-center gap-10">
                    <button className="w-48 h-48 flex items-center justify-center rounded-2xl bg-white shadow-xl hover:shadow-2xl transition">
                        <Icon path="/whatsapp.webp"/>
                    </button>
                    <button className="w-48 h-48 flex items-center justify-center rounded-2xl bg-white shadow-xl hover:shadow-2xl transition">
                        <Icon path="/moovit.webp"/>
                    </button>
                    <button className="w-48 h-48 flex items-center justify-center rounded-2xl bg-white shadow-xl hover:shadow-2xl transition">
                        <Icon path="/mako.webp"/>
                    </button>
                </div>
            </div>
        </div>
    );
}

export default App
