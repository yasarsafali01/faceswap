import { useState } from 'react'
import type { FaceOption, Video } from '../api'
import FacePickerModal from './FacePickerModal'

export type Assignments = Record<number, FaceOption>
const ALL = -1

type Props = {
  video: Video
  options: FaceOption[]
  assignments: Assignments
  onAssign: (index: number, option: FaceOption | null) => void
  allFace: FaceOption | null
  onAllFace: (option: FaceOption | null) => void
}

/** Lists the people found in the video; each gets a face picked from the uploaded photos. */
export default function PeopleAssigner({ video, options, assignments, onAssign, allFace, onAllFace }: Props) {
  const [picking, setPicking] = useState<number | null>(null)

  if (video.analysisStatus === 'PENDING') {
    return (
      <section className="step">
        <h2>3. Kişilere yüz ata</h2>
        <p className="muted analyzing"><span className="spinner" aria-hidden /> Videodaki kişiler bulunuyor…</p>
      </section>
    )
  }
  if (video.analysisStatus === 'READY' && video.faces.length === 0) {
    return (
      <section className="step">
        <h2>3. Kişilere yüz ata</h2>
        <p className="error small">Videoda yüz bulunamadı. Yüzün net göründüğü başka bir video deneyin.</p>
      </section>
    )
  }

  const people = video.analysisStatus === 'READY' ? video.faces : []
  const label = (index: number) => (index === ALL ? 'Herkes' : `Kişi ${index + 1}`)
  // Each photo face goes to one person only, so a group photo can fill as many people as it has faces.
  const usedBy: Record<string, string> = {}
  for (const [index, option] of Object.entries(assignments)) usedBy[option.key] = label(Number(index))
  const assignedCount = Object.keys(assignments).length

  const current = picking === ALL ? allFace : picking !== null ? assignments[picking] ?? null : null
  const pick = (option: FaceOption | null) => {
    if (picking === ALL) onAllFace(option)
    else if (picking !== null) onAssign(picking, option)
    setPicking(null)
  }

  const slot = (index: number, option: FaceOption | null, disabled = false) => (
    <button
      type="button"
      className={`slot${option ? ' filled' : ''}`}
      onClick={() => setPicking(index)}
      disabled={disabled}
      aria-label={`${label(index)} için yüz seç`}
    >
      {option ? <img src={option.url} alt="" /> : <span className="slot-plus" aria-hidden>+</span>}
    </button>
  )

  return (
    <section className="step">
      <h2>3. Kişilere yüz ata</h2>
      {people.length > 0 ? (
        <>
          <p className="muted small">
            Videoda {people.length} kişi var. Değiştirmek istediğin kişinin kutusuna tıklayıp yüklediğin yüzlerden birini seç;
            yüz atamadığın kişiler olduğu gibi kalır. {options.length > 0 && `(${assignedCount}/${Math.min(options.length, people.length)} atandı)`}
          </p>
          <div className={`assignments${allFace ? ' disabled' : ''}`}>
            {people.map((p) => (
              <div key={p.index} className="assignment">
                <img className="person-thumb" src={p.url} alt={label(p.index)} title={`${p.occurrences} karede görüldü`} />
                <span className="arrow" aria-hidden>→</span>
                {slot(p.index, allFace ? null : assignments[p.index] ?? null, !!allFace)}
              </div>
            ))}
          </div>
          {people.length > 1 && (
            <div className="assignment all">
              <span className="small">Hepsine aynı yüz</span>
              <span className="arrow" aria-hidden>→</span>
              {slot(ALL, allFace)}
            </div>
          )}
        </>
      ) : (
        <>
          <p className="muted small">Kişi analizi yapılamadı; seçtiğin yüz videodaki herkese uygulanacak.</p>
          <div className="assignment all">
            <span className="small">Herkes</span>
            <span className="arrow" aria-hidden>→</span>
            {slot(ALL, allFace)}
          </div>
        </>
      )}

      {picking !== null && (
        <FacePickerModal
          title={`${label(picking)} için yüz seç`}
          options={options}
          selectedKey={current?.key ?? null}
          usedBy={picking === ALL ? {} : Object.fromEntries(Object.entries(usedBy).filter(([, who]) => who !== label(picking)))}
          onPick={pick}
          onClose={() => setPicking(null)}
        />
      )}
    </section>
  )
}
