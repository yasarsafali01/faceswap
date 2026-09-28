import { useCallback, useEffect, useState } from 'react'
import { api, type Job, type MediaFile, type Video } from '../api'
import { useAuth } from '../auth'
import Dropzone from '../components/Dropzone'
import FacePicker, { type Target } from '../components/FacePicker'
import JobCard from '../components/JobCard'
import { useJobUpdates } from '../useJobUpdates'

export default function Studio() {
  const { user, logout } = useAuth()
  const [video, setVideo] = useState<Video | null>(null)
  const [target, setTarget] = useState<Target>(null)
  const [face, setFace] = useState<MediaFile | null>(null)
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
        setTarget(updated.faces.length === 1 ? updated.faces[0].index : null)
      } catch {
        // Transient errors: keep polling.
      }
    }, 1500)
    return () => clearInterval(t)
  }, [videoId, analyzing])

  function changeVideo(v: Video | null) {
    setVideo(v)
    setTarget(null)
  }

  const targetReady =
    video?.analysisStatus === 'FAILED' ||
    (video?.analysisStatus === 'READY' && video.faces.length > 0 && target !== null)
  const canStart = !!video && !!face && consent && targetReady && !starting

  // Fall back to polling only while the socket is down and something is still running.
  const hasActive = jobs.some((j) => j.status === 'QUEUED' || j.status === 'PROCESSING')
  useEffect(() => {
    if (connected || !hasActive) return
    const t = setInterval(loadJobs, 4000)
    return () => clearInterval(t)
  }, [connected, hasActive, loadJobs])

  async function start() {
    if (!video || !face) return
    setError(null)
    setStarting(true)
    try {
      const job = await api<Job>('/api/jobs/start', {
        method: 'POST',
        body: JSON.stringify({
          videoId: video.id,
          faceId: face.id,
          consent,
          enhance,
          targetFaceIndex: typeof target === 'number' ? target : null,
        }),
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
          <div className="steps">
            <Dropzone
              kind="video"
              title="1. Video"
              hint="MP4, MOV, WEBM · en fazla 200 MB · 3 dakika"
              accept="video/mp4,video/quicktime,video/webm,video/x-matroska"
              value={video}
              onChange={changeVideo}
            />
            <Dropzone
              kind="face"
              title="2. Kaynak yüz"
              hint="Yüzün net ve önden göründüğü JPG, PNG, WEBP"
              accept="image/jpeg,image/png,image/webp"
              value={face}
              onChange={setFace}
            />
          </div>

          {video && <FacePicker video={video} value={target} onChange={setTarget} />}

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
            <p className="muted">Henüz bir işlem yok. Video ve yüz yükleyip başlatın.</p>
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
