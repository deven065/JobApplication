import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { PublicProfilePage } from './Growth.jsx'
import './styles.css'

const profile = window.location.pathname.match(/^\/u\/([^/]+)\/?$/)
createRoot(document.getElementById('root')).render(<StrictMode>{profile ? <PublicProfilePage login={decodeURIComponent(profile[1])} /> : <App />}</StrictMode>)
