"""Runs one face swap job end to end: download -> probe -> per-frame swap/enhance -> encode -> upload."""
import logging
import os
import shutil
import time
from collections import deque
from concurrent.futures import Future, ThreadPoolExecutor
from typing import Callable

import cv2
import numpy as np

from .config import settings
from .faces import FaceEngine
from .messages import JobRequest
from .storage import Storage
from .video import FrameReader, FrameWriter, VideoError, probe

log = logging.getLogger(__name__)

AGE_CHECK_EVERY_SECONDS = 5


class JobError(Exception):
    """Failure whose message is safe to show to the end user."""


class Watermark:
    def __init__(self, text: str, width: int, height: int) -> None:
        scale = max(0.5, height / 900)
        thickness = max(1, round(scale * 2))
        (tw, th), baseline = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, scale, thickness)
        margin = round(height * 0.03)
        self.x0 = max(0, width - tw - margin)
        self.y0 = max(0, height - th - baseline - margin)
        self.x1 = min(width, self.x0 + tw)
        self.y1 = min(height, self.y0 + th + baseline)
        mask = np.zeros((self.y1 - self.y0, self.x1 - self.x0), np.uint8)
        cv2.putText(mask, text, (0, th), cv2.FONT_HERSHEY_SIMPLEX, scale, 255, thickness, cv2.LINE_AA)
        self.alpha = (mask.astype(np.float32) / 255 * 0.7)[..., None]

    def apply(self, frame: np.ndarray) -> None:
        region = frame[self.y0:self.y1, self.x0:self.x1].astype(np.float32)
        frame[self.y0:self.y1, self.x0:self.x1] = (region * (1 - self.alpha) + 255 * self.alpha).astype(np.uint8)


class Processor:
    def __init__(self, engine: FaceEngine, storage: Storage) -> None:
        self.engine = engine
        self.storage = storage

    def run(self, job: JobRequest, on_progress: Callable[[int], None]) -> None:
        workdir = os.path.join(settings.work_dir, job.job_id)
        os.makedirs(workdir, exist_ok=True)
        try:
            self._run(job, workdir, on_progress)
        finally:
            shutil.rmtree(workdir, ignore_errors=True)

    def _run(self, job: JobRequest, workdir: str, on_progress: Callable[[int], None]) -> None:
        video_path = os.path.join(workdir, "input" + os.path.splitext(job.video_key)[1])
        face_path = os.path.join(workdir, "face" + os.path.splitext(job.face_key)[1])
        out_path = os.path.join(workdir, "result.mp4")
        thumb_path = os.path.join(workdir, "thumb.jpg")

        self.storage.download(job.video_key, video_path)
        self.storage.download(job.face_key, face_path)

        try:
            info = probe(video_path)
        except VideoError as e:
            raise JobError(str(e))
        if info.duration > settings.max_duration_seconds:
            raise JobError(f"Video en fazla {int(settings.max_duration_seconds)} saniye olabilir")

        face_image = cv2.imread(face_path)
        if face_image is None:
            raise JobError("Yüz fotoğrafı okunamadı")
        source = self.engine.source_face(face_image)
        if source is None:
            raise JobError("Kaynak fotoğrafta yüz bulunamadı")
        if int(source.age) < settings.min_face_age:
            raise JobError("Kaynak fotoğraftaki kişi reşit olmayan biri gibi görünüyor, işlem yapılamaz")

        identity = self.engine.identity(source)
        watermark = Watermark(settings.watermark_text, info.width, info.height) if settings.watermark_text else None
        age_check_interval = max(1, round(float(info.fps) * AGE_CHECK_EVERY_SECONDS))
        # Set once any frame with faces passed the age check; a race here only means an extra check.
        age_checked = False

        def process(index: int, frame: np.ndarray) -> tuple[np.ndarray, int]:
            nonlocal age_checked
            faces = self.engine.detect(frame)
            if faces and (not age_checked or index % age_check_interval == 0):
                if any(self.engine.estimate_age(frame, f) < settings.min_face_age for f in faces):
                    raise JobError("Videodaki kişi reşit olmayan biri gibi görünüyor, işlem yapılamaz")
                age_checked = True
            for face in faces:
                self.engine.swap(frame, face.kps, identity)
                if job.enhance:
                    self.engine.enhance(frame, face.kps)
            if watermark:
                watermark.apply(frame)
            return frame, len(faces)

        total = info.frame_count
        mid = total // 2
        frames_with_faces = 0
        last_progress, last_emit = -1, 0.0
        started = time.monotonic()

        reader = FrameReader(video_path, info)
        writer = FrameWriter(out_path, video_path, info)
        # Several frames in flight so one frame's CPU work (alignment, blending) overlaps another's GPU
        # inference; results are consumed strictly in submission order to keep frames in sequence.
        pool = ThreadPoolExecutor(max_workers=settings.frame_workers)
        pending: deque[Future] = deque()
        index = 0

        def drain_one() -> None:
            nonlocal frames_with_faces, last_progress, last_emit, index
            frame, face_count = pending.popleft().result()
            frames_with_faces += face_count > 0
            writer.write(frame)
            if index == 0 or index == mid:
                cv2.imwrite(thumb_path, frame, [cv2.IMWRITE_JPEG_QUALITY, 85])
            progress = min(99, index * 100 // total)
            now = time.monotonic()
            if progress != last_progress and now - last_emit >= 1.0:
                on_progress(progress)
                last_progress, last_emit = progress, now
            index += 1

        try:
            for submitted, frame in enumerate(reader):
                pending.append(pool.submit(process, submitted, frame))
                if len(pending) >= settings.frame_workers * 2:
                    drain_one()
            while pending:
                drain_one()

            if frames_with_faces == 0:
                raise JobError("Videoda yüz bulunamadı")
            writer.finish()
        except VideoError as e:
            raise JobError(str(e))
        finally:
            for f in pending:
                f.cancel()
            pool.shutdown(wait=True)
            writer.abort()
            reader.close()

        elapsed = time.monotonic() - started
        log.info("Job %s rendered %d frames in %.1fs (%.1f fps)", job.job_id, total, elapsed, total / max(elapsed, 1e-6))
        self.storage.upload(job.result_key, out_path, "video/mp4")
        self.storage.upload(job.thumbnail_key, thumb_path, "image/jpeg")
