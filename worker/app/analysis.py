"""Finds the distinct people in a video so the user can choose whom to replace.

Frames are sampled sparsely, every detected face is embedded with ArcFace, and embeddings are
greedily clustered by cosine similarity. Each cluster's centroid is later used by the processor
to decide, per frame, which detected faces belong to the chosen person."""
import dataclasses
import json
import logging
import os
import shutil
from fractions import Fraction

import cv2
import numpy as np

from .config import settings
from .faces import FaceEngine
from .messages import AnalyzeImageRequest, AnalyzeRequest
from .storage import Storage
from .video import FrameReader, probe

log = logging.getLogger(__name__)

MIN_DET_SCORE = 0.6
MIN_FACE_PX = 32
MAX_PEOPLE = 12
MAX_PHOTO_FACES = 10


class PhotoAnalysisError(Exception):
    """Failure whose message is safe to show to the end user."""


class _Cluster:
    def __init__(self, embedding: np.ndarray) -> None:
        self.total = embedding.copy()
        self.count = 1
        self.best_quality = -1.0
        self.best_crop: np.ndarray | None = None

    @property
    def centroid(self) -> np.ndarray:
        return self.total / np.linalg.norm(self.total)


def _portrait(frame: np.ndarray, bbox: np.ndarray, size: int = 256) -> np.ndarray:
    """Square crop around the face with some margin, for the picker thumbnail."""
    x0, y0, x1, y1 = bbox
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    half = max(x1 - x0, y1 - y0) * 0.8
    h, w = frame.shape[:2]
    left, top = int(max(0, cx - half)), int(max(0, cy - half))
    right, bottom = int(min(w, cx + half)), int(min(h, cy + half))
    return cv2.resize(frame[top:bottom, left:right], (size, size), interpolation=cv2.INTER_AREA)


def _source_crop(image: np.ndarray, bbox: np.ndarray) -> np.ndarray:
    """Generous square crop around one face of a group photo. It must keep enough context for the
    detector to find the face again at render time, and the chosen face stays the largest in it."""
    x0, y0, x1, y1 = bbox
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    half = max(x1 - x0, y1 - y0) * 1.1
    h, w = image.shape[:2]
    left, top = int(max(0, cx - half)), int(max(0, cy - half))
    right, bottom = int(min(w, cx + half)), int(min(h, cy + half))
    crop = image[top:bottom, left:right]
    side = max(crop.shape[:2])
    # Upscale small faces so the crop stays usable as a source image.
    return cv2.resize(crop, None, fx=512 / side, fy=512 / side, interpolation=cv2.INTER_CUBIC) if side < 512 else crop


class PhotoAnalyzer:
    def __init__(self, engine: FaceEngine, storage: Storage) -> None:
        self.engine = engine
        self.storage = storage

    def run(self, req: AnalyzeImageRequest) -> list[int]:
        workdir = os.path.join(settings.work_dir, "photo-" + req.face_id)
        os.makedirs(workdir, exist_ok=True)
        try:
            path = os.path.join(workdir, "input" + os.path.splitext(req.image_key)[1])
            self.storage.download(req.image_key, path)
            image = cv2.imread(path)
            if image is None:
                raise PhotoAnalysisError("Fotoğraf okunamadı")
            faces, detected_on = self.engine.photo_faces(image)
            faces = [f for f in faces if f.det_score >= MIN_DET_SCORE][:MAX_PHOTO_FACES]
            if not faces:
                raise PhotoAnalysisError("Fotoğrafta yüz bulunamadı")
            indexes = []
            for index, face in enumerate(faces):
                crop_path = os.path.join(workdir, f"{index}.jpg")
                cv2.imwrite(crop_path, _source_crop(detected_on, face.bbox), [cv2.IMWRITE_JPEG_QUALITY, 92])
                self.storage.upload(f"{req.crops_prefix}{index}.jpg", crop_path, "image/jpeg")
                indexes.append(index)
            log.info("Photo %s: %d face(s)", req.face_id, len(indexes))
            return indexes
        finally:
            shutil.rmtree(workdir, ignore_errors=True)


class VideoAnalyzer:
    def __init__(self, engine: FaceEngine, storage: Storage) -> None:
        self.engine = engine
        self.storage = storage

    def run(self, req: AnalyzeRequest) -> list[dict]:
        workdir = os.path.join(settings.work_dir, "analyze-" + req.video_id)
        os.makedirs(workdir, exist_ok=True)
        try:
            return self._run(req, workdir)
        finally:
            shutil.rmtree(workdir, ignore_errors=True)

    def _run(self, req: AnalyzeRequest, workdir: str) -> list[dict]:
        video_path = os.path.join(workdir, "input" + os.path.splitext(req.video_key)[1])
        self.storage.download(req.video_key, video_path)
        info = probe(video_path)

        sample_fps = min(float(info.fps), settings.analysis_samples_per_second,
                         settings.analysis_max_samples / info.duration)
        sampled = dataclasses.replace(info, fps=Fraction(sample_fps).limit_denominator(1000))

        clusters: list[_Cluster] = []
        frames = 0
        reader = FrameReader(video_path, sampled)
        try:
            for frame in reader:
                frames += 1
                for face in self.engine.analyzer.get(frame):
                    x0, y0, x1, y1 = face.bbox
                    if face.det_score < MIN_DET_SCORE or min(x1 - x0, y1 - y0) < MIN_FACE_PX:
                        continue
                    emb = face.normed_embedding
                    sims = [float(c.centroid @ emb) for c in clusters]
                    if sims and max(sims) >= settings.cluster_threshold:
                        cluster = clusters[int(np.argmax(sims))]
                        cluster.total += emb
                        cluster.count += 1
                    else:
                        cluster = _Cluster(emb)
                        clusters.append(cluster)
                    # Prefer large, confidently detected faces for the thumbnail.
                    quality = float(face.det_score) * (x1 - x0) * (y1 - y0)
                    if quality > cluster.best_quality:
                        cluster.best_quality = quality
                        cluster.best_crop = _portrait(frame, face.bbox)
        finally:
            reader.close()

        # One-off detections in longer videos are usually background faces or false positives.
        if frames >= 10:
            clusters = [c for c in clusters if c.count >= 2]
        clusters = sorted(clusters, key=lambda c: c.count, reverse=True)[:MAX_PEOPLE]

        records = []
        for index, cluster in enumerate(clusters):
            thumb = os.path.join(workdir, f"{index}.jpg")
            cv2.imwrite(thumb, cluster.best_crop, [cv2.IMWRITE_JPEG_QUALITY, 90])
            self.storage.upload(f"{req.faces_prefix}{index}.jpg", thumb, "image/jpeg")
            records.append({"index": index, "occurrences": cluster.count,
                            "embedding": cluster.centroid.round(6).tolist()})

        faces_json = os.path.join(workdir, "faces.json")
        with open(faces_json, "w") as f:
            json.dump({"faces": records}, f)
        self.storage.upload(req.faces_prefix + "faces.json", faces_json, "application/json")

        log.info("Video %s: %d people in %d sampled frames", req.video_id, len(records), frames)
        return [{"index": r["index"], "occurrences": r["occurrences"]} for r in records]
