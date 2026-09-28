"""Runs one face swap job end to end: download -> probe -> per-frame swap/enhance -> encode -> upload."""
import json
import logging
import os
import shutil
import time
from collections import deque
from concurrent.futures import Future, ThreadPoolExecutor
from typing import Callable

import cv2
import numpy as np
from insightface.app.common import Face

from .config import settings
from .faces import FaceEngine
from .messages import JobRequest
from .storage import Storage
from .tracking import FaceTracker
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

    def _target_embeddings(self, job: JobRequest, workdir: str) -> np.ndarray | None:
        """(K, 512) centroid embeddings of the chosen people, row k for job.swaps[k]; None = swap everyone."""
        if job.faces_key is None or any(s.target_face_index is None for s in job.swaps):
            return None
        path = os.path.join(workdir, "faces.json")
        self.storage.download(job.faces_key, path)
        with open(path) as f:
            by_index = {f["index"]: f["embedding"] for f in json.load(f)["faces"]}
        rows = []
        for swap in job.swaps:
            if swap.target_face_index not in by_index:
                raise JobError("Seçilen kişi bulunamadı, videoyu yeniden yükleyin")
            emb = np.asarray(by_index[swap.target_face_index], dtype=np.float32)
            rows.append(emb / np.linalg.norm(emb))
        return np.stack(rows)

    def _identities(self, job: JobRequest, workdir: str) -> list[np.ndarray]:
        """Swap latent of each source face, in job.swaps order."""
        identities = []
        for k, swap in enumerate(job.swaps):
            path = os.path.join(workdir, f"face{k}" + os.path.splitext(swap.face_key)[1])
            self.storage.download(swap.face_key, path)
            image = cv2.imread(path)
            if image is None:
                raise JobError("Yüz fotoğrafı okunamadı")
            source = self.engine.source_face(image)
            if source is None:
                raise JobError("Kaynak fotoğraflardan birinde yüz bulunamadı")
            if int(source.age) < settings.min_face_age:
                raise JobError("Kaynak fotoğraftaki kişi reşit olmayan biri gibi görünüyor, işlem yapılamaz")
            identities.append(self.engine.identity(source))
        return identities

    def _match_untracked(self, frame: np.ndarray, faces: list[Face], targets: np.ndarray) -> list[Face]:
        """Per-frame identity matching when tracking is disabled: best detected face per chosen person."""
        best: dict[int, tuple[float, Face]] = {}
        for face in faces:
            sims = targets @ self.engine.embed(frame, face)
            k = int(np.argmax(sims))
            if sims[k] >= settings.match_threshold and sims[k] > best.get(k, (-1.0, None))[0]:
                best[k] = (float(sims[k]), face)
        for k, (_, face) in best.items():
            face.target = k
        return [face for _, face in best.values()]

    def run(self, job: JobRequest, on_progress: Callable[[int], None]) -> None:
        workdir = os.path.join(settings.work_dir, job.job_id)
        os.makedirs(workdir, exist_ok=True)
        try:
            self._run(job, workdir, on_progress)
        finally:
            shutil.rmtree(workdir, ignore_errors=True)

    def _run(self, job: JobRequest, workdir: str, on_progress: Callable[[int], None]) -> None:
        video_path = os.path.join(workdir, "input" + os.path.splitext(job.video_key)[1])
        out_path = os.path.join(workdir, "result.mp4")
        thumb_path = os.path.join(workdir, "thumb.jpg")

        self.storage.download(job.video_key, video_path)
        try:
            info = probe(video_path)
        except VideoError as e:
            raise JobError(str(e))
        if info.duration > settings.max_duration_seconds:
            raise JobError(f"Video en fazla {int(settings.max_duration_seconds)} saniye olabilir")

        identities = self._identities(job, workdir)
        targets = self._target_embeddings(job, workdir)
        watermark = Watermark(settings.watermark_text, info.width, info.height) if settings.watermark_text else None
        age_check_interval = max(1, round(float(info.fps) * AGE_CHECK_EVERY_SECONDS))
        age_checked = False
        tracker = (FaceTracker(targets, settings.match_threshold, settings.keep_threshold, settings.flow_weight)
                   if settings.temporal_smoothing else None)

        def select(index: int, frame: np.ndarray) -> list[Face]:
            """Which faces to swap in this frame, each tagged with face.target = index into job.swaps.
            Runs strictly in frame order because tracking is stateful."""
            nonlocal age_checked
            faces = self.engine.detect(frame)
            if tracker is not None:
                faces = tracker.update(index, frame, faces, lambda f: self.engine.embed(frame, f))
            elif targets is not None:
                faces = self._match_untracked(frame, faces, targets)
            if faces and (not age_checked or index % age_check_interval == 0):
                if any(self.engine.estimate_age(frame, f) < settings.min_face_age for f in faces):
                    raise JobError("Videodaki kişi reşit olmayan biri gibi görünüyor, işlem yapılamaz")
                age_checked = True
            return faces

        def render(frame: np.ndarray, faces: list[Face]) -> tuple[np.ndarray, int, list]:
            """Stateless per-frame work, safe to run out of order: swap, and GFPGAN inference if enabled.
            The enhancement is pasted later, in frame order, so its detail can be stabilized over time."""
            restorations = []
            for face in faces:
                self.engine.swap(frame, face.kps, identities[face.target if face.target is not None else 0])
                if job.enhance:
                    result = self.engine.restore(frame, face.kps)
                    if result is not None:
                        restorations.append((face.track_id, *result))
            return frame, len(faces), restorations

        # Per-track GFPGAN detail from the previous frame, in aligned face space.
        detail_state: dict[int, tuple[np.ndarray, int]] = {}
        blend_now = settings.enhancer_temporal if settings.temporal_smoothing else 1.0

        def finish(frame: np.ndarray, restorations: list, frame_index: int) -> None:
            for track_id, detail, crop, matrix in restorations:
                prev = detail_state.get(track_id) if track_id is not None else None
                if prev is not None and frame_index - prev[1] == 1:
                    detail = blend_now * detail + (1 - blend_now) * prev[0]
                if track_id is not None:
                    detail_state[track_id] = (detail, frame_index)
                self.engine.paste_restored(frame, detail, crop, matrix)
            for stale in [k for k, (_, seen) in detail_state.items() if frame_index - seen > 1]:
                del detail_state[stale]
            if watermark:
                watermark.apply(frame)

        total = info.frame_count
        mid = total // 2
        frames_with_faces = 0
        last_progress, last_emit = -1, 0.0
        started = time.monotonic()

        reader = FrameReader(video_path, info)
        writer = FrameWriter(out_path, video_path, info)
        # Detection and tracking run in order on this thread; the heavy swap/enhance work of several frames
        # runs in parallel so CPU blending overlaps GPU inference. Results are consumed in submission order.
        pool = ThreadPoolExecutor(max_workers=settings.frame_workers)
        pending: deque[Future] = deque()
        index = 0

        def drain_one() -> None:
            nonlocal frames_with_faces, last_progress, last_emit, index
            frame, face_count, restorations = pending.popleft().result()
            frames_with_faces += face_count > 0
            finish(frame, restorations, index)
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
                pending.append(pool.submit(render, frame, select(submitted, frame)))
                if len(pending) >= settings.frame_workers * 2:
                    drain_one()
            while pending:
                drain_one()

            if frames_with_faces == 0:
                raise JobError("Seçilen kişiler videoda bulunamadı" if targets is not None else "Videoda yüz bulunamadı")
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
        log.info("Job %s: faces swapped in %d of %d frames", job.job_id, frames_with_faces, index)
        log.info("Job %s rendered %d frames in %.1fs (%.1f fps)", job.job_id, total, elapsed, total / max(elapsed, 1e-6))
        self.storage.upload(job.result_key, out_path, "video/mp4")
        self.storage.upload(job.thumbnail_key, thumb_path, "image/jpeg")
