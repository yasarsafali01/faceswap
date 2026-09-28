import { useRef, useState, type DragEvent } from 'react'
import type { FacePhoto } from '../api'

export type PendingUpload = { name: string; progress: number; error: string | null }

type Props = {
  photos: FacePhoto[]
  uploads: PendingUpload[]
  onFiles: (files: File[]) => void
  onRemove: (id: string) => void
}

/** Multi-photo upload area; every face found in the photos becomes something to assign. */
export default function FaceLibrary({ photos, uploads, onFiles, onRemove }: Props) {
  const input = useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = useState(false)

  function onDrop(e: DragEvent) {
    e.preventDefault()
    setDragging(false)
    onFiles(Array.from(e.dataTransfer.files))
  }

  const faceCount = photos.reduce((n, p) => n + (p.analysisStatus === 'READY' ? p.detections.length : 0), 0)

  return (
    <section className="step">
      <h2>2. Yeni yüzler</h2>
      <div
        className={`dropzone compact${dragging ? ' dragging' : ''}`}
        onClick={() => input.current?.click()}
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
        <div className="placeholder">
          <strong>Fotoğrafları sürükle bırak ya da tıkla</strong>
          <span className="muted small">Birden fazla fotoğraf seçebilirsin; grup fotoğraflarındaki her yüz ayrı kullanılabilir</span>
        </div>
        <input
          ref={input}
          type="file"
          accept="image/jpeg,image/png,image/webp"
          multiple
          hidden
          onChange={(e) => {
            onFiles(Array.from(e.target.files ?? []))
            e.target.value = ''
          }}
        />
      </div>

      {uploads.length > 0 && (
        <ul className="upload-list">
          {uploads.map((u, i) => (
            <li key={i} className="small">
              <span className="upload-name">{u.name}</span>
              {u.error ? <span className="error">{u.error}</span> : <span className="muted">%{u.progress}</span>}
            </li>
          ))}
        </ul>
      )}

      {photos.length > 0 && (
        <>
          <p className="muted small">{faceCount} yüz hazır</p>
          <div className="photos">
            {photos.map((p) => (
              <div key={p.id} className="photo">
                <button type="button" className="photo-remove" onClick={() => onRemove(p.id)} aria-label="Kaldır">×</button>
                {p.analysisStatus === 'PENDING' && (
                  <div className="photo-state"><span className="spinner" aria-hidden /> <span className="small muted">Yüzler bulunuyor…</span></div>
                )}
                {p.analysisStatus === 'FAILED' && (
                  <div className="photo-state"><img src={p.url} alt="" /><span className="error small">{p.analysisError}</span></div>
                )}
                {p.analysisStatus === 'READY' && (
                  <>
                    <div className="photo-faces">
                      {p.detections.map((d) => <img key={d.index} src={d.url} alt="" />)}
                    </div>
                    {p.detections.length > 1 && <span className="small muted">{p.detections.length} kişi</span>}
                  </>
                )}
              </div>
            ))}
          </div>
        </>
      )}
    </section>
  )
}
