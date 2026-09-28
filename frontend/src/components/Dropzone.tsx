import { useRef, useState, type DragEvent } from 'react'
import { upload, type MediaFile } from '../api'

type Props<T extends MediaFile> = {
  kind: 'video' | 'face'
  title: string
  hint: string
  accept: string
  value: T | null
  onChange: (file: T | null) => void
}

export default function Dropzone<T extends MediaFile>({ kind, title, hint, accept, value, onChange }: Props<T>) {
  const input = useRef<HTMLInputElement>(null)
  const [progress, setProgress] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [dragging, setDragging] = useState(false)

  async function handle(file: File | undefined) {
    if (!file) return
    setError(null)
    setProgress(0)
    try {
      const path = kind === 'video' ? '/api/videos/upload' : '/api/faces/upload'
      onChange(await upload<T>(path, file, setProgress))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Yükleme başarısız')
    } finally {
      setProgress(null)
    }
  }

  function onDrop(e: DragEvent) {
    e.preventDefault()
    setDragging(false)
    void handle(e.dataTransfer.files[0])
  }

  return (
    <section className="step">
      <h2>{title}</h2>
      <div
        className={`dropzone${dragging ? ' dragging' : ''}${value ? ' filled' : ''}`}
        onClick={() => progress === null && input.current?.click()}
        onDragOver={(e) => {
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && input.current?.click()}
      >
        {value && progress === null ? (
          kind === 'video' ? (
            <video src={value.url} muted loop autoPlay playsInline />
          ) : (
            <img src={value.url} alt="Kaynak yüz" />
          )
        ) : progress !== null ? (
          <div className="upload-progress">
            <div className="bar"><span style={{ width: `${progress}%` }} /></div>
            <span>%{progress} yükleniyor</span>
          </div>
        ) : (
          <div className="placeholder">
            <strong>Sürükle bırak ya da tıkla</strong>
            <span className="muted small">{hint}</span>
          </div>
        )}
        <input
          ref={input}
          type="file"
          accept={accept}
          hidden
          onChange={(e) => {
            void handle(e.target.files?.[0])
            e.target.value = ''
          }}
        />
      </div>
      {value && progress === null && (
        <button className="link small" onClick={() => onChange(null)}>Değiştir</button>
      )}
      {error && <p className="error small" role="alert">{error}</p>}
    </section>
  )
}
