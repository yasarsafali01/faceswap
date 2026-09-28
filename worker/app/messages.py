"""Wire contract with the backend. Keep in sync with backend JobMessages.java."""
from dataclasses import dataclass

EXCHANGE = "faceswap"
DEAD_LETTER_EXCHANGE = "faceswap.dlx"
JOBS_QUEUE = "faceswap.jobs"
JOBS_DEAD_QUEUE = "faceswap.jobs.dead"
EVENTS_QUEUE = "faceswap.job-events"
JOB_ROUTING_KEY = "job.requested"
EVENT_ROUTING_KEY = "job.event"

ANALYZE_QUEUE = "faceswap.analyze"
ANALYZE_DEAD_QUEUE = "faceswap.analyze.dead"
ANALYSIS_RESULTS_QUEUE = "faceswap.analysis-results"
ANALYZE_ROUTING_KEY = "video.analyze"
ANALYZED_ROUTING_KEY = "video.analyzed"


@dataclass(frozen=True)
class SwapSpec:
    face_key: str
    # Person index from the video analysis; None means every face in the video gets this face.
    target_face_index: int | None


@dataclass(frozen=True)
class JobRequest:
    job_id: str
    user_id: int
    video_key: str
    result_key: str
    thumbnail_key: str
    enhance: bool
    # Analysis embeddings (faces.json); None when the single swap applies to everyone.
    faces_key: str | None
    swaps: list[SwapSpec]

    @staticmethod
    def from_json(data: dict) -> "JobRequest":
        swaps = [SwapSpec(face_key=s["faceKey"], target_face_index=s.get("targetFaceIndex")) for s in data["swaps"]]
        if not swaps:
            raise ValueError("job without swaps")
        return JobRequest(
            job_id=str(data["jobId"]),
            user_id=int(data["userId"]),
            video_key=data["videoKey"],
            result_key=data["resultKey"],
            thumbnail_key=data["thumbnailKey"],
            enhance=bool(data.get("enhance", True)),
            faces_key=data.get("facesKey"),
            swaps=swaps,
        )


@dataclass(frozen=True)
class AnalyzeRequest:
    video_id: str
    user_id: int
    video_key: str
    faces_prefix: str

    @staticmethod
    def from_json(data: dict) -> "AnalyzeRequest":
        return AnalyzeRequest(
            video_id=str(data["videoId"]),
            user_id=int(data["userId"]),
            video_key=data["videoKey"],
            faces_prefix=data["facesPrefix"],
        )


def event(job_id: str, type_: str, worker_id: str, progress: int | None = None, error: str | None = None) -> dict:
    return {"jobId": job_id, "type": type_, "progress": progress, "error": error, "workerId": worker_id}


def analysis_result(video_id: str, faces: list[dict] | None = None, error: str | None = None) -> dict:
    return {"videoId": video_id, "status": "FAILED" if error else "READY", "error": error, "faces": faces or []}
