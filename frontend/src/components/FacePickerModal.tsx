import type { FaceOption } from '../api'

type Props = {
  title: string
  options: FaceOption[]
  selectedKey: string | null
  // Option key -> label of the person it's already assigned to (disabled here).
  usedBy: Record<string, string>
  onPick: (option: FaceOption | null) => void
  onClose: () => void
}

export default function FacePickerModal({ title, options, selectedKey, usedBy, onPick, onClose }: Props) {
  return (
    <div className="modal" role="dialog" aria-modal onClick={onClose}>
      <div className="modal-body picker" onClick={(e) => e.stopPropagation()}>
        <h2>{title}</h2>
        {options.length === 0 ? (
          <p className="muted">Önce “Yeni yüzler” bölümünden fotoğraf yükle.</p>
        ) : (
          <div className="picker-grid">
            {options.map((o) => {
              const owner = usedBy[o.key]
              return (
                <button
                  key={o.key}
                  type="button"
                  className={`picker-option${o.key === selectedKey ? ' selected' : ''}`}
                  disabled={!!owner}
                  onClick={() => onPick(o)}
                  title={owner ? `${owner} için kullanılıyor` : undefined}
                >
                  <img src={o.url} alt="" />
                  {owner && <span className="picker-owner small">{owner}</span>}
                </button>
              )
            })}
          </div>
        )}
        <div className="modal-actions">
          {selectedKey && <button onClick={() => onPick(null)}>Yüzü kaldır</button>}
          <button onClick={onClose}>Kapat</button>
        </div>
      </div>
    </div>
  )
}
