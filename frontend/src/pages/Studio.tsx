import { useCallback, useEffect, useState } from 'react'
import { api, type Job, type MediaFile, type Video } from '../api'
import { useAuth } from '../auth'
import Dropzone from '../components/Dropzone'
import JobCard from '../components/JobCard'
import PeopleAssigner, { type Assignments } from '../components/PeopleAssigner'
import { useJobUpdates } from '../useJobUpdates'

export default function Studio() {
  const { user, logout } = useAuth()
  const [video, setVideo] = useState<Video | null>(null)
  const [assignments, setAssignments] = useState<Assignments>({})
  // One face for every person in the video (also the only option when analysis failed).
  const [allFace, setAllFace] = useState<MediaFile | null>(null)
  const [consent, setConsent] = useState(false)
  const [enhance, setEnhance] = useState(true)
  const [jobs, setJobs] = useState<Job[]>([])
  const [error, setError] = useState<string | null>(null)
  const [starting, setStarting] = useState(false)
  const [playing, setPlaying] = useState<Job | null>(null)

  const loadJobs = useCallback(() => api<Job[]>('/api/jobs').then(setJobs).catch(() => {}), [])

  const connected = useJobUpdates(
    useCallback((updated: Job) => {
      setJobs((list) => {
        const i = list.findIndex((j) => j.id === updated.id)
        if (i === -1) return [updated, ...list]
        const next = list.slice()
        next[i] = updated
        return next
      })
    }, []),
  )

  useEffect(() => {
    void loadJobs()
  }, [loadJobs])

  // The worker analyzes each uploaded video to find the people in it; poll until it's done.
  const videoId = video?.id
  const analyzing = video?.analysisStatus === 'PENDING'
  useEffect(() => {
    if (!videoId || !analyzing) return
    const t = setInterval(async () => {
      try {
        const updated = await api<Video>(`/api/videos/${videoId}`)
        if (updated.analysisStatus === 'PENDING') return
        setVideo(updated)
      } catch {
        // Transient errors: keep polling.
      }
    }, 1500)
    return () => clearInterval(t)
  }, [videoId, analyzing])

  function changeVideo(v: Video | null) {
    setVideo(v)
    setAssignments({})
    setAllFace(null)
  }

  function assign(index: number, face: MediaFile | null) {
    setAssignments((current) => {
      const next = { ...current }
      if (face) next[index] = face
      else delete next[index]
      return next
    })
  }

  const swaps = allFace
    ? [{ faceId: allFace.id, targetFaceIndex: null }]
    : Object.entries(assignments).map(([index, face]) => ({ faceId: face.id, targetFaceIndex: Number(index) }))
  const analyzed = video?.analysisStatus === 'FAILED' || (video?.analysisStatus === 'READY' && video.faces.length > 0)
  const canStart = !!video && analyzed && swaps.length > 0 && consent && !starting

  // Fall back to polling only while the socket is down and something is still running.
  const hasActive = jobs.some((j) => j.status === 'QUEUED' || j.status === 'PROCESSING')
  useEffect(() => {
    if (connected || !hasActive) return
    const t = setInterval(loadJobs, 4000)
    return () => clearInterval(t)
  }, [connected, hasActive, loadJobs])

  async function start() {
    if (!video || swaps.length === 0) return
    setError(null)
    setStarting(true)
    try {
      const job = await api<Job>('/api/jobs/start', {
        method: 'POST',
        body: JSON.stringify({ videoId: video.id, swaps, consent, enhance }),
      })
      setJobs((list) => [job, ...list.filter((j) => j.id !== job.id)])
      setConsent(false)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'İşlem başlatılamadı')
    } finally {
      setStarting(false)
    }
  }

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" aria-hidden>◐</span> FaceSwap Studio
        </div>
        <div className="topbar-right">
          <span className={`dot ${connected ? 'on' : ''}`} title={connected ? 'Canlı' : 'Bağlantı yok'} />
          <span className="muted small">{user?.displayName || user?.email}</span>
          <button className="link small" onClick={() => void logout()}>Çıkış</button>
        </div>
      </header>

      <main className="studio">
        <section className="panel">
          <Dropzone
            kind="video"
            title="1. Video"
            hint="MP4, MOV, WEBM · en fazla 200 MB · 3 dakika"
            accept="video/mp4,video/quicktime,video/webm,video/x-matroska"
            value={video}
            onChange={changeVideo}
          />

          {video && (
            <PeopleAssigner
              video={video}
              assignments={assignments}
              onAssign={assign}
              allFace={allFace}
              onAllFace={setAllFace}
            />
          )}

          <div className="options">
            <label className="check">
              <input type="checkbox" checked={enhance} onChange={(e) => setEnhance(e.target.checked)} />
              <span>
                Yüz iyileştirme (GFPGAN)
                <small className="muted">Daha net göz ve cilt detayı, işlem süresi uzar</small>
              </span>
            </label>
            <label className="check">
              <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
              <span>
                Videodaki ve fotoğraftaki kişilerin iznine sahibim
                <small className="muted">Rızası olmadan birinin yüzünü kullanmak yasaktır.</small>
              </span>
            </label>
          </div>

          {error && <p className="error" role="alert">{error}</p>}
          <button className="primary wide" disabled={!canStart} onClick={() => void start()}>
            {starting ? 'Başlatılıyor…' : 'Yüz değiştirmeyi başlat'}
          </button>
        </section>

        <section className="panel">
          <h2>İşlemler</h2>
          {jobs.length === 0 ? (
            <p className="muted">Henüz bir işlem yok. Video yükleyip kişilere yeni yüz ekleyin.</p>
          ) : (
            <div className="jobs">
              {jobs.map((job) => <JobCard key={job.id} job={job} onOpen={setPlaying} />)}
            </div>
          )}
        </section>
      </main>

      {playing?.resultUrl && (
        <div className="modal" role="dialog" aria-modal onClick={() => setPlaying(null)}>
          <div className="modal-body" onClick={(e) => e.stopPropagation()}>
            <video src={playing.resultUrl} controls autoPlay playsInline />
            <div className="modal-actions">
              <a className="button" href={playing.resultUrl} download={`faceswap-${playing.id}.mp4`}>İndir</a>
              <button onClick={() => setPlaying(null)}>Kapat</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
