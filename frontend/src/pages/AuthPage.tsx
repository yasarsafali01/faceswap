import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth'

export default function AuthPage({ mode }: { mode: 'login' | 'register' }) {
  const { login, register } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const isLogin = mode === 'login'

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      if (isLogin) await login(email, password)
      else await register(email, password, displayName)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Bir hata oluştu')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="auth">
      <form className="auth-card" onSubmit={submit}>
        <div className="brand">
          <span className="brand-mark" aria-hidden>◐</span> FaceSwap Studio
        </div>
        <h1>{isLogin ? 'Giriş yap' : 'Hesap oluştur'}</h1>

        {!isLogin && (
          <label>
            Ad (isteğe bağlı)
            <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} maxLength={100} />
          </label>
        )}
        <label>
          E-posta
          <input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email" />
        </label>
        <label>
          Şifre
          <input
            type="password"
            required
            minLength={isLogin ? undefined : 8}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete={isLogin ? 'current-password' : 'new-password'}
          />
        </label>

        {error && <p className="error" role="alert">{error}</p>}

        <button className="primary" disabled={busy}>
          {busy ? 'Lütfen bekleyin…' : isLogin ? 'Giriş yap' : 'Kayıt ol'}
        </button>
        <p className="muted small">
          {isLogin ? (
            <>Hesabın yok mu? <Link to="/register">Kayıt ol</Link></>
          ) : (
            <>Zaten hesabın var mı? <Link to="/login">Giriş yap</Link></>
          )}
        </p>
      </form>
    </main>
  )
}
