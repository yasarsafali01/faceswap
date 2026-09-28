import os
from dataclasses import dataclass, field


def _env(name: str, default: str | None = None) -> str:
    value = os.getenv(name, default)
    if value is None:
        raise RuntimeError(f"Missing required environment variable {name}")
    return value


def _endpoint() -> tuple[str, bool]:
    """MINIO_ENDPOINT is shared with the backend and given as a URL (http://minio:9000);
    the MinIO SDK wants host:port plus a TLS flag."""
    value = _env("MINIO_ENDPOINT", "http://localhost:9000")
    secure = value.startswith("https://")
    return value.split("://", 1)[-1].rstrip("/"), secure


@dataclass(frozen=True)
class Settings:
    # Compose sets HOSTNAME to the container id, which keeps scaled workers distinguishable.
    worker_id: str = field(default_factory=lambda: _env("WORKER_ID", os.getenv("HOSTNAME", "worker")))

    rabbitmq_host: str = field(default_factory=lambda: _env("RABBITMQ_HOST", "localhost"))
    rabbitmq_port: int = field(default_factory=lambda: int(_env("RABBITMQ_PORT", "5672")))
    rabbitmq_user: str = field(default_factory=lambda: _env("RABBITMQ_USER", "guest"))
    rabbitmq_password: str = field(default_factory=lambda: _env("RABBITMQ_PASSWORD", "guest"))

    minio_endpoint: str = field(default_factory=lambda: _endpoint()[0])
    minio_secure: bool = field(default_factory=lambda: _endpoint()[1])
    minio_access_key: str = field(default_factory=lambda: _env("MINIO_ROOT_USER", "minioadmin"))
    minio_secret_key: str = field(default_factory=lambda: _env("MINIO_ROOT_PASSWORD", "minioadmin"))
    minio_bucket: str = field(default_factory=lambda: _env("MINIO_BUCKET", "faceswap"))

    models_dir: str = field(default_factory=lambda: _env("MODELS_DIR", "/models"))
    work_dir: str = field(default_factory=lambda: _env("WORK_DIR", "/tmp/faceswap"))

    max_duration_seconds: float = field(default_factory=lambda: float(_env("MAX_DURATION_SECONDS", "180")))
    max_height: int = field(default_factory=lambda: int(_env("MAX_HEIGHT", "1080")))
    max_fps: float = field(default_factory=lambda: float(_env("MAX_FPS", "60")))
    video_encoder: str = field(default_factory=lambda: _env("VIDEO_ENCODER", "libx264"))
    video_crf: int = field(default_factory=lambda: int(_env("VIDEO_CRF", "18")))

    # Frames processed concurrently; enough to keep the GPU fed while other frames are on the CPU.
    frame_workers: int = field(default_factory=lambda: int(_env("FRAME_WORKERS", "3")))

    enhancer_blend: float = field(default_factory=lambda: float(_env("ENHANCER_BLEND", "0.8")))
    # Faces whose estimated age is below this are refused. Age estimation is noisy, so this is a
    # safety net on top of the consent step, not a verification.
    min_face_age: int = field(default_factory=lambda: int(_env("MIN_FACE_AGE", "18")))
    # Visible watermark is off by default; outputs are always tagged in the file metadata instead.
    watermark_text: str = field(default_factory=lambda: _env("WATERMARK_TEXT", ""))
    ai_metadata_tag: str = field(default_factory=lambda: _env("AI_METADATA_TAG", "AI-generated content (FaceSwap)"))

    # Cosine similarity of ArcFace embeddings. Same person across poses is usually > 0.4,
    # different people rarely exceed 0.2; matching is looser than clustering to survive profile views.
    cluster_threshold: float = field(default_factory=lambda: float(_env("CLUSTER_THRESHOLD", "0.4")))
    match_threshold: float = field(default_factory=lambda: float(_env("MATCH_THRESHOLD", "0.3")))
    # Once a tracked face is recognized as the chosen person it stays chosen down to this similarity,
    # so profile or blurred frames don't flash the original face.
    keep_threshold: float = field(default_factory=lambda: float(_env("KEEP_THRESHOLD", "0.15")))
    temporal_smoothing: bool = field(default_factory=lambda: _env("TEMPORAL_SMOOTHING", "true").lower() == "true")
    # Landmark stabilization strength (0 = raw detections, closer to 1 = smoother).
    flow_weight: float = field(default_factory=lambda: float(_env("FLOW_WEIGHT", "0.7")))
    # Share of the current frame in the GFPGAN detail layer (1 = no temporal smoothing). GFPGAN
    # re-invents skin texture every frame, which was ~75% of the remaining flicker.
    enhancer_temporal: float = field(default_factory=lambda: float(_env("ENHANCER_TEMPORAL", "0.5")))
    analysis_samples_per_second: float = field(default_factory=lambda: float(_env("ANALYSIS_SAMPLES_PER_SECOND", "2")))
    analysis_max_samples: int = field(default_factory=lambda: int(_env("ANALYSIS_MAX_SAMPLES", "120")))


settings = Settings()
