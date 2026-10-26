import { useState } from 'react'
import { api } from '../api'
import Modal from './Modal'
import Icon from './Icon'

export default function AuthModal({ initialMode = 'register', onClose, onSuccess }) {
  const [mode, setMode] = useState(initialMode)
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function submit(event) {
    event.preventDefault()
    if (busy) return
    setError('')
    setBusy(true)
    try {
      const user = await api(`/api/auth/${mode}`, { method: 'POST', body: mode === 'register' ? { name: name.trim(), email: email.trim(), password } : { email: email.trim(), password } })
      await onSuccess(user)
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }
  return <Modal titleId="auth-title" onClose={() => { if (!busy) onClose() }} className="auth-modal">
    <span className="modal-stamp" aria-hidden="true"><Icon name="book" size={29} /></span>
    <span className="eyebrow">YOUR LITTLE CORNER OF THE BOOKVERSE</span>
    <h2 id="auth-title">{mode === 'register' ? 'A shelf with your name on it.' : 'Welcome back, book person.'}</h2>
    <p className="modal-intro">{mode === 'register' ? 'Save the maybes, celebrate the finished, and find your next favorite.' : 'Your stories are right where you left them.'}</p>
    <div className="auth-tabs" aria-label="Account form"><button type="button" aria-pressed={mode === 'register'} disabled={busy} onClick={() => { setMode('register'); setError('') }}>Create account</button><button type="button" aria-pressed={mode === 'login'} disabled={busy} onClick={() => { setMode('login'); setError('') }}>Sign in</button></div>
    <form onSubmit={submit} className="auth-form">
      {mode === 'register' && <label>Your name<input name="name" autoComplete="given-name" required minLength={2} maxLength={60} value={name} onChange={(e) => setName(e.target.value)} placeholder="What should Pip call you?" disabled={busy} /></label>}
      <label>Email<input name="email" type="email" autoComplete="email" required maxLength={200} value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" disabled={busy} /></label>
      <label>Password<input name="password" type="password" autoComplete={mode === 'register' ? 'new-password' : 'current-password'} required minLength={mode === 'register' ? 8 : 1} maxLength={64} value={password} onChange={(e) => setPassword(e.target.value)} placeholder={mode === 'register' ? 'At least 8 characters' : 'Your password'} disabled={busy} /></label>
      {error && <p className="inline-error" role="alert">{error}</p>}
      <button className="button button-purple" type="submit" disabled={busy}>{busy ? <><span className="spinner" /> Opening your shelf…</> : <>{mode === 'register' ? 'Make it mine' : 'Back to my books'}<Icon name="arrow" size={18} /></>}</button>
    </form>
    <p className="privacy-note"><Icon name="shield" size={14} /> Your shelf is private to your account. No subscription.</p>
  </Modal>
}
