export type User = { id: number; email: string; displayName: string | null; roles: string[] }

export type MediaFile = {
  id: string
  originalName: string | null
  contentType: string
  sizeBytes: number
  createdAt: string
  url: string
}

export type JobStatus = 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED'

export type Job = {
  id: string
  status: JobStatus
  progress: number
  enhance: boolean
  error: string | null
  videoId: string
  faceId: string
  videoUrl: string | null
  faceUrl: string | null
  resultUrl: string | null
  thumbnailUrl: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

type Session = { accessToken: string; refreshToken: string; user: User }

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

const STORAGE_KEY = 'faceswap.session'
const listeners = new Set<(s: Session | null) => void>()

export const session = {
  get(): Session | null {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      return raw ? (JSON.parse(raw) as Session) : null
    } catch {
      return null
    }
  },
  set(s: Session | null) {
    try {
      if (s) localStorage.setItem(STORAGE_KEY, JSON.stringify(s))
      else localStorage.removeItem(STORAGE_KEY)
    } catch {
      // Storage can be unavailable (private mode); the session then lives only in memory for this tab.
    }
    listeners.forEach((l) => l(s))
  },
  subscribe(listener: (s: Session | null) => void) {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },
}

async function errorFrom(res: Response): Promise<ApiError> {
  try {
    const body = await res.json()
    return new ApiError(res.status, body.message ?? res.statusText)
  } catch {
    return new ApiError(res.status, res.status === 413 ? 'Dosya çok büyük' : res.statusText || 'İstek başarısız')
  }
}

// Concurrent 401s share one refresh call; refresh tokens are single-use on the server.
let refreshing: Promise<boolean> | null = null

function refreshSession(): Promise<boolean> {
  const current = session.get()
  if (!current) return Promise.resolve(false)
  refreshing ??= fetch('/api/auth/refresh', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: current.refreshToken }),
  })
    .then(async (res) => {
      if (!res.ok) return false
      session.set(await res.json())
      return true
    })
    .catch(() => false)
    .finally(() => {
      refreshing = null
    })
  return refreshing
}

export async function api<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
  const token = session.get()?.accessToken
  const headers = new Headers(init.headers)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')

  const res = await fetch(path, { ...init, headers })
  if (res.status === 401 && retry && token) {
    if (await refreshSession()) return api<T>(path, init, false)
    session.set(null)
  }
  if (!res.ok) throw await errorFrom(res)
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

/** Multipart upload via XHR, because fetch has no upload progress events. */
export function upload(path: string, file: File, onProgress: (pct: number) => void, retry = true): Promise<MediaFile> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', path)
    const token = session.get()?.accessToken
    if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(Math.round((e.loaded / e.total) * 100))
    xhr.onerror = () => reject(new ApiError(0, 'Bağlantı hatası'))
    xhr.onload = async () => {
      if (xhr.status === 401 && retry && token && (await refreshSession())) {
        upload(path, file, onProgress, false).then(resolve, reject)
        return
      }
      let body: { message?: string } | MediaFile | null = null
      try {
        body = JSON.parse(xhr.responseText)
      } catch {
        // Non-JSON error bodies (e.g. nginx 413 page) fall through to the status text below.
      }
      if (xhr.status >= 200 && xhr.status < 300) resolve(body as MediaFile)
      else {
        const msg = (body as { message?: string } | null)?.message
        reject(new ApiError(xhr.status, msg ?? (xhr.status === 413 ? 'Dosya çok büyük' : 'Yükleme başarısız')))
      }
    }
    const form = new FormData()
    form.append('file', file)
    xhr.send(form)
  })
}
