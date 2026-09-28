"""Wire contract with the backend. Keep in sync with backend JobMessages.java."""
from dataclasses import dataclass

EXCHANGE = "faceswap"
DEAD_LETTER_EXCHANGE = "faceswap.dlx"
JOBS_QUEUE = "faceswap.jobs"
JOBS_DEAD_QUEUE = "faceswap.jobs.dead"
EVENTS_QUEUE = "faceswap.job-events"
JOB_ROUTING_KEY = "job.requested"
EVENT_ROUTING_KEY = "job.event"


@dataclass(frozen=True)
class JobRequest:
    job_id: str
    user_id: int
    video_key: str
    face_key: str
    result_key: str
    thumbnail_key: str
    enhance: bool

    @staticmethod
    def from_json(data: dict) -> "JobRequest":
        return JobRequest(
            job_id=str(data["jobId"]),
            user_id=int(data["userId"]),
            video_key=data["videoKey"],
            face_key=data["faceKey"],
            result_key=data["resultKey"],
            thumbnail_key=data["thumbnailKey"],
            enhance=bool(data.get("enhance", True)),
        )


def event(job_id: str, type_: str, worker_id: str, progress: int | None = None, error: str | None = None) -> dict:
    return {"jobId": job_id, "type": type_, "progress": progress, "error": error, "workerId": worker_id}
