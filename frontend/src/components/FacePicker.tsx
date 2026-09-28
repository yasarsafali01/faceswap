import type { Video } from '../api'

export type Target = number | 'all' | null

type Props = { video: Video; value: Target; onChange: (t: Target) => void }

export default function FacePicker({ video, value, onChange }: Props) {
  if (video.analysisStatus === 'PENDING') {
    return (
      <section className="step">
        <h2>3. Değiştirilecek kişi</h2>
        <p className="muted analyzing"><span className="spinner" aria-hidden /> Videodaki kişiler bulunuyor…</p>
      </section>
    )
  }

  if (video.analysisStatus === 'FAILED') {
    return (
      <section className="step">
        <h2>3. Değiştirilecek kişi</h2>
        <p className="muted small">Kişi analizi yapılamadı; videodaki tüm yüzler değiştirilecek.</p>
      </section>
    )
  }

  if (video.faces.length === 0) {
    return (
      <section className="step">
        <h2>3. Değiştirilecek kişi</h2>
        <p className="error small">Videoda yüz bulunamadı. Yüzün net göründüğü başka bir video deneyin.</p>
      </section>
    )
  }

  return (
    <section className="step">
      <h2>3. Değiştirilecek kişi</h2>
      <p className="muted small">
        {video.faces.length === 1 ? 'Videoda bir kişi bulundu.' : `Videoda ${video.faces.length} kişi bulundu. Hangisinin yüzü değişsin?`}
      </p>
      <div className="people" role="radiogroup">
        {video.faces.map((f) => (
          <button
            key={f.index}
            type="button"
            role="radio"
            aria-checked={value === f.index}
            className={`person${value === f.index ? ' selected' : ''}`}
            onClick={() => onChange(f.index)}
            title={`${f.occurrences} karede görüldü`}
          >
            <img src={f.url} alt={`Kişi ${f.index + 1}`} />
            <span className="small">Kişi {f.index + 1}</span>
          </button>
        ))}
        {video.faces.length > 1 && (
          <button
            type="button"
            role="radio"
            aria-checked={value === 'all'}
            className={`person all${value === 'all' ? ' selected' : ''}`}
            onClick={() => onChange('all')}
          >
            <span className="all-icon" aria-hidden>✦</span>
            <span className="small">Hepsi</span>
          </button>
        )}
      </div>
    </section>
  )
}
