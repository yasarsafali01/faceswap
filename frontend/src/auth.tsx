import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { api, session, type User } from './api'

type AuthContextValue = {
  user: User | null
  login: (email: string, password: string) => Promise<void>
  register: (email: string, password: string, displayName: string) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(() => session.get()?.user ?? null)

  useEffect(() => {
    const unsubscribe = session.subscribe((s) => setUser(s?.user ?? null))
    return () => {
      unsubscribe()
    }
  }, [])

  const value: AuthContextValue = {
    user,
    async login(email, password) {
      session.set(await api('/api/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) }))
    },
    async register(email, password, displayName) {
      session.set(
        await api('/api/auth/register', {
          method: 'POST',
          body: JSON.stringify({ email, password, displayName: displayName || null }),
        }),
      )
    },
    async logout() {
      const refreshToken = session.get()?.refreshToken
      try {
        await api('/api/auth/logout', { method: 'POST', body: JSON.stringify({ refreshToken }) }, false)
      } finally {
        session.set(null)
      }
    },
  }

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
