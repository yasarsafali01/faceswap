import { useRef, useState } from 'react'
import { upload, type MediaFile } from '../api'

type Props = { value: MediaFile | null; onChange: (f: MediaFile | null) => void; label: string }

/** Compact upload box for one source face photo. */
export default function FaceSlot({ value, onChange, label }: Props) {
  const input = useRef<HTMLInputElement>(null)
  const [progress, setProgress] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function handle(file: File | undefined) {
    if (!file) return
    setError(null)
    setProgress(0)
    try {
      onChange(await upload('/api/faces/upload', file, setProgress))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Yükleme başarısız')
    } finally {
      setProgress(null)
    }
  }

  return (
    <div className="slot-wrap">
      <button
        type="button"
        className={`slot${value ? ' filled' : ''}`}
        onClick={() => progress === null && input.current?.click()}
        onDragOver={(e) => e.preventDefault()}
        onDrop={(e) => {
          e.preventDefault()
          void handle(e.dataTransfer.files[0])
        }}
        aria-label={label}
      >
        {progress !== null ? (
          <span className="small muted">%{progress}</span>
        ) : value ? (
          <img src={value.url} alt="" />
        ) : (
          <span className="slot-plus" aria-hidden>+</span>
        )}
      </button>
      {value && progress === null && (
        <button type="button" className="link small" onClick={() => onChange(null)}>Kaldır</button>
      )}
      {error && <span className="error small">{error}</span>}
      <input
        ref={input}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        hidden
        onChange={(e) => {
          void handle(e.target.files?.[0])
          e.target.value = ''
        }}
      />
    </div>
  )
}
