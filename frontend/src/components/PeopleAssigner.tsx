import type { MediaFile, Video } from '../api'
import FaceSlot from './FaceSlot'

export type Assignments = Record<number, MediaFile>

type Props = {
  video: Video
  assignments: Assignments
  onAssign: (index: number, face: MediaFile | null) => void
  allFace: MediaFile | null
  onAllFace: (face: MediaFile | null) => void
}

/** Lists the people found in the video; each can get its own new face, or one face goes on everyone. */
export default function PeopleAssigner({ video, assignments, onAssign, allFace, onAllFace }: Props) {
  if (video.analysisStatus === 'PENDING') {
    return (
      <section className="step">
        <h2>2. Kişiler ve yeni yüzler</h2>
        <p className="muted analyzing"><span className="spinner" aria-hidden /> Videodaki kişiler bulunuyor…</p>
      </section>
    )
  }

  if (video.analysisStatus === 'FAILED') {
    return (
      <section className="step">
        <h2>2. Yeni yüz</h2>
        <p className="muted small">Kişi analizi yapılamadı; eklediğin yüz videodaki herkese uygulanacak.</p>
        <FaceSlot value={allFace} onChange={onAllFace} label="Herkes için yeni yüz" />
      </section>
    )
  }

  if (video.faces.length === 0) {
    return (
      <section className="step">
        <h2>2. Kişiler ve yeni yüzler</h2>
        <p className="error small">Videoda yüz bulunamadı. Yüzün net göründüğü başka bir video deneyin.</p>
      </section>
    )
  }

  const perPersonDisabled = allFace !== null
  return (
    <section className="step">
      <h2>2. Kişiler ve yeni yüzler</h2>
      <p className="muted small">
        Değiştirmek istediğin her kişinin yanına yeni yüzünü ekle. Yüz eklemediğin kişiler olduğu gibi kalır.
      </p>
      <div className={`assignments${perPersonDisabled ? ' disabled' : ''}`}>
        {video.faces.map((f) => (
          <div key={f.index} className="assignment">
            <img className="person-thumb" src={f.url} alt={`Kişi ${f.index + 1}`} title={`${f.occurrences} karede görüldü`} />
            <span className="arrow" aria-hidden>→</span>
            <FaceSlot
              value={perPersonDisabled ? null : assignments[f.index] ?? null}
              onChange={(face) => onAssign(f.index, face)}
              label={`Kişi ${f.index + 1} için yeni yüz`}
            />
          </div>
        ))}
      </div>
      {video.faces.length > 1 && (
        <div className="assignment all">
          <span className="small">Hepsine aynı yüz</span>
          <span className="arrow" aria-hidden>→</span>
          <FaceSlot value={allFace} onChange={onAllFace} label="Herkes için yeni yüz" />
        </div>
      )}
    </section>
  )
}
