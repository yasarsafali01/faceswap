import type { Job } from '../api'

const STATUS_LABEL: Record<Job['status'], string> = {
  QUEUED: 'Sırada',
  PROCESSING: 'İşleniyor',
  COMPLETED: 'Hazır',
  FAILED: 'Başarısız',
}

export default function JobCard({ job, onOpen }: { job: Job; onOpen: (job: Job) => void }) {
  const first = job.swaps[0]
  const preview = job.thumbnailUrl ?? first?.targetFaceUrl ?? first?.faceUrl
  return (
    <article className={`job job-${job.status.toLowerCase()}`}>
      <div className="job-thumb">{preview && <img src={preview} alt="" loading="lazy" />}</div>
      <div className="job-body">
        <div className="job-head">
          <span className={`badge badge-${job.status.toLowerCase()}`}>{STATUS_LABEL[job.status]}</span>
          <time className="muted small">{new Date(job.createdAt).toLocaleString('tr-TR')}</time>
        </div>

        <div className="pairs">
          {job.swaps.map((s, i) => (
            <span key={i} className="pair" title={s.targetFaceIndex === null ? 'Herkes' : `Kişi ${s.targetFaceIndex + 1}`}>
              {s.targetFaceUrl ? <img src={s.targetFaceUrl} alt="" /> : <span className="pair-all">Herkes</span>}
              <span aria-hidden>→</span>
              {s.faceUrl && <img src={s.faceUrl} alt="" />}
            </span>
          ))}
        </div>

        {(job.status === 'QUEUED' || job.status === 'PROCESSING') && (
          <div className="bar" aria-label={`%${job.progress}`}>
            <span className={job.status === 'QUEUED' ? 'indeterminate' : ''} style={{ width: `${job.progress}%` }} />
          </div>
        )}
        {job.status === 'PROCESSING' && <span className="muted small">%{job.progress}</span>}
        {job.status === 'FAILED' && <p className="error small">{job.error}</p>}

        {job.status === 'COMPLETED' && job.resultUrl && (
          <div className="job-actions">
            <button className="primary small" onClick={() => onOpen(job)}>İzle</button>
            <a className="button small" href={job.resultUrl} download={`faceswap-${job.id}.mp4`}>İndir</a>
          </div>
        )}
      </div>
    </article>
  )
}
