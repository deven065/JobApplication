import { useEffect, useState } from 'react'
import { postJson } from './api.js'

const tokenFromUrl = () => new URLSearchParams(window.location.search).get('token') || ''

function Shell({ title, children }) {
  return <div className="loginScreen"><div className="loginCard">
    <a href="/" className="brandLink">ProofPath</a>
    <h1>{title}</h1>
    {children}
  </div></div>
}

/** /reset?token=…: opened from the password-reset email. */
export function ResetPasswordPage() {
  const token = tokenFromUrl()
  const [password, setPassword] = useState('')
  const [state, setState] = useState({ busy: false, error: '' })
  async function submit(event) {
    event.preventDefault()
    setState({ busy: true, error: '' })
    try { await postJson('/auth/reset', { token, password }); window.location.assign('/') }
    catch (error) { setState({ busy: false, error: error.message }) }
  }
  if (!token) return <Shell title="Reset link missing"><p className="loginLead">Open the link from your reset email, or request a new one from the sign-in screen.</p><a className="primary linkButton" href="/">Back to sign in</a></Shell>
  return <Shell title="Choose a new password">
    <form className="loginForm" onSubmit={submit}>
      <label>New password<input type="password" required minLength={8} maxLength={128} value={password} onChange={event => setPassword(event.target.value)} placeholder="At least 8 characters" autoComplete="new-password" /></label>
      {state.error && <p className="formError" role="alert">{state.error}</p>}
      <button className="primary" disabled={state.busy}>{state.busy ? 'Saving…' : 'Save and sign in'}</button>
    </form>
  </Shell>
}

/** /verify?token=…: opened from the confirmation email. */
export function VerifyEmailPage() {
  const token = tokenFromUrl()
  const [state, setState] = useState({ loading: !!token, error: token ? '' : 'This link is missing its token.' })
  useEffect(() => {
    if (!token) return
    postJson('/auth/verify', { token }).then(() => setState({ loading: false, error: '' })).catch(error => setState({ loading: false, error: error.message }))
  }, [token])
  return <Shell title={state.loading ? 'Confirming…' : state.error ? 'Link not valid' : 'Email confirmed'}>
    {!state.loading && <p className="loginLead">{state.error || 'Thanks, your email is confirmed.'}</p>}
    {!state.loading && <a className="primary linkButton" href="/">Open ProofPath</a>}
  </Shell>
}
