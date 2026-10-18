import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { PublicProfilePage } from './Growth.jsx'
import { ResetPasswordPage, VerifyEmailPage } from './AccountPages.jsx'
import './styles.css'

const path = window.location.pathname.replace(/\/+$/, '')
const profile = window.location.pathname.match(/^\/u\/([^/]+)\/?$/)
createRoot(document.getElementById('root')).render(<StrictMode>{profile ? <PublicProfilePage login={decodeURIComponent(profile[1])} /> : path === '/reset' ? <ResetPasswordPage /> : path === '/verify' ? <VerifyEmailPage /> : <App />}</StrictMode>)
