import type { Job } from '../api'

const STATUS_LABEL: Record<Job['status'], string> = {
  QUEUED: 'Sırada',
  PROCESSING: 'İşleniyor',
  COMPLETED: 'Hazır',
  FAILED: 'Başarısız',
}

export default function JobCard({ job, onOpen }: { job: Job; onOpen: (job: Job) => void }) {
  const preview = job.thumbnailUrl ?? job.targetFaceUrl ?? job.faceUrl
  return (
    <article className={`job job-${job.status.toLowerCase()}`}>
      <div className="job-thumb">{preview && <img src={preview} alt="" loading="lazy" />}</div>
      <div className="job-body">
        <div className="job-head">
          <span className={`badge badge-${job.status.toLowerCase()}`}>{STATUS_LABEL[job.status]}</span>
          <time className="muted small">{new Date(job.createdAt).toLocaleString('tr-TR')}</time>
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
