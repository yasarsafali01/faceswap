import os
from dataclasses import dataclass, field


def _env(name: str, default: str | None = None) -> str:
    value = os.getenv(name, default)
    if value is None:
        raise RuntimeError(f"Missing required environment variable {name}")
    return value


@dataclass(frozen=True)
class Settings:
    worker_id: str = field(default_factory=lambda: _env("WORKER_ID", "worker-1"))

    rabbitmq_host: str = field(default_factory=lambda: _env("RABBITMQ_HOST", "localhost"))
    rabbitmq_user: str = field(default_factory=lambda: _env("RABBITMQ_USER", "guest"))
    rabbitmq_password: str = field(default_factory=lambda: _env("RABBITMQ_PASSWORD", "guest"))

    minio_endpoint: str = field(default_factory=lambda: _env("MINIO_ENDPOINT", "localhost:9000"))
    minio_access_key: str = field(default_factory=lambda: _env("MINIO_ACCESS_KEY", "minioadmin"))
    minio_secret_key: str = field(default_factory=lambda: _env("MINIO_SECRET_KEY", "minioadmin"))
    minio_bucket: str = field(default_factory=lambda: _env("MINIO_BUCKET", "faceswap"))
    minio_secure: bool = field(default_factory=lambda: _env("MINIO_SECURE", "false").lower() == "true")

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
    watermark_text: str = field(default_factory=lambda: _env("WATERMARK_TEXT", "AI GENERATED"))


settings = Settings()
