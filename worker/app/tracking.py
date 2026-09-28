"""Temporal stabilization: follows faces across frames so the swap doesn't flicker.

Per-frame processing flickers in three ways: detector landmarks jitter by a few pixels (the swapped
face "swims"), the chosen person is briefly not recognized on profile or blurred frames (the original
face flashes through), and the detector misses a face for a frame or two. Tracks fix all three:
landmarks are stabilized with optical flow, identity decisions have hysteresis, and short detection
gaps are bridged by carrying the face along the measured motion."""
from dataclasses import dataclass
from typing import Callable

import cv2
import numpy as np
from insightface.app.common import Face

MIN_IOU = 0.3
# A face missing for longer than this is treated as gone (left the frame or a scene cut).
MAX_GAP_FRAMES = 3
# A tracked face keeps its identity between frames; re-running ArcFace on every face every frame
# was the main cost of tracking in crowded shots.
REIDENTIFY_EVERY = 5


def _iou(a: np.ndarray, b: np.ndarray) -> float:
    x0, y0 = max(a[0], b[0]), max(a[1], b[1])
    x1, y1 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


def _face_motion(prev_gray: np.ndarray, gray: np.ndarray, bbox: np.ndarray) -> np.ndarray | None:
    """Similarity transform of the face region between two frames, from sparse optical flow."""
    h, w = gray.shape
    x0, y0 = max(int(bbox[0]), 0), max(int(bbox[1]), 0)
    x1, y1 = min(int(bbox[2]), w), min(int(bbox[3]), h)
    if x1 - x0 < 16 or y1 - y0 < 16:
        return None
    mask = np.zeros_like(prev_gray)
    mask[y0:y1, x0:x1] = 255
    pts = cv2.goodFeaturesToTrack(prev_gray, maxCorners=60, qualityLevel=0.01, minDistance=4, mask=mask)
    if pts is None or len(pts) < 8:
        return None
    nxt, status, _ = cv2.calcOpticalFlowPyrLK(prev_gray, gray, pts, None, winSize=(21, 21), maxLevel=3)
    good = status.ravel() == 1
    if good.sum() < 8:
        return None
    m, _ = cv2.estimateAffinePartial2D(pts[good], nxt[good], method=cv2.RANSAC, ransacReprojThreshold=2.0)
    return m


def _apply(m: np.ndarray, points: np.ndarray) -> np.ndarray:
    return (points @ m[:, :2].T + m[:, 2]).astype(np.float32)


class FlowStabilizer:
    """Motion-compensated landmark smoothing.

    A plain low-pass filter can't tell detector noise from real fast motion (camera shake), so it lags
    and the swapped face slides off the head. Instead the previous estimate is carried along the
    measured motion of the face region and only then blended with the new detection: real motion is
    followed exactly, detector noise is averaged out. On a synthetic shaky clip with exact ground truth
    this cut landmark jitter by ~40% without adding error; a One Euro filter made both worse."""

    def __init__(self, weight: float = 0.7) -> None:
        # Share of the motion-carried previous estimate; the rest comes from the fresh detection, which
        # keeps flow drift bounded.
        self.weight = weight
        self.kps: np.ndarray | None = None

    def update(self, motion: np.ndarray | None, det: np.ndarray) -> np.ndarray:
        if self.kps is None or motion is None:
            self.kps = det.astype(np.float32)
        else:
            self.kps = self.weight * _apply(motion, self.kps) + (1 - self.weight) * det
        return self.kps

    def coast(self, motion: np.ndarray | None) -> np.ndarray:
        """No detection this frame: follow the motion alone (or hold still if it can't be measured)."""
        if motion is not None and self.kps is not None:
            self.kps = _apply(motion, self.kps)
        return self.kps


@dataclass
class Track:
    id: int
    bbox: np.ndarray
    stabilizer: FlowStabilizer
    last_seen: int
    # Smoothed similarity to each chosen person (row of FaceTracker.targets).
    similarities: np.ndarray | None = None
    # Index of the chosen person this face is, or None.
    assigned: int | None = None
    last_embedded: int = -REIDENTIFY_EVERY


class FaceTracker:
    """Decides, frame by frame and in order, which landmarks to swap and with which source face.

    targets=None swaps every face (face.target = 0). Otherwise targets is (K, 512): a track is assigned
    to its most similar chosen person once the similarity reaches match_threshold, and keeps that
    assignment until it drops below keep_threshold, so a turned or blurred head doesn't flip back to the
    original face or jump to another person's source face."""

    def __init__(self, targets: np.ndarray | None, match_threshold: float, keep_threshold: float,
                 flow_weight: float = 0.7) -> None:
        self.targets = targets
        self.match_threshold = match_threshold
        self.keep_threshold = keep_threshold
        self.flow_weight = flow_weight
        self.tracks: list[Track] = []
        self.prev_gray: np.ndarray | None = None
        self._next_id = 0

    def update(self, index: int, frame: np.ndarray, faces: list[Face],
               embed: Callable[[Face], np.ndarray]) -> list[Face]:
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        # Motion is only needed for faces that get swapped; others are just associated by overlap.
        motions = [_face_motion(self.prev_gray, gray, t.bbox)
                   if self.prev_gray is not None and (self.targets is None or t.assigned is not None) else None
                   for t in self.tracks]
        claimed: set[int] = set()

        for face in faces:
            best, best_iou = None, MIN_IOU
            for i, track in enumerate(self.tracks):
                if i in claimed:
                    continue
                iou = _iou(track.bbox, face.bbox)
                if iou > best_iou:
                    best, best_iou = i, iou
            if best is None:
                # New face, or the same face after a scene cut: start fresh, no carry-over.
                track = Track(id=self._next_id, bbox=face.bbox, stabilizer=FlowStabilizer(self.flow_weight),
                              last_seen=index)
                self._next_id += 1
                track.stabilizer.update(None, face.kps)
                self.tracks.append(track)
                motions.append(None)
                claimed.add(len(self.tracks) - 1)
            else:
                track = self.tracks[best]
                track.stabilizer.update(motions[best], face.kps)
                track.bbox, track.last_seen = face.bbox, index
                claimed.add(best)
            if self.targets is not None and index - track.last_embedded >= REIDENTIFY_EVERY:
                track.last_embedded = index
                sims = self.targets @ embed(face)
                # Smoothed similarity: one bad frame shouldn't decide identity.
                track.similarities = sims if track.similarities is None else 0.6 * track.similarities + 0.4 * sims
                k = int(np.argmax(track.similarities))
                threshold = self.keep_threshold if k == track.assigned else self.match_threshold
                track.assigned = k if track.similarities[k] >= threshold else None

        for i, track in enumerate(self.tracks):
            if i not in claimed:
                track.stabilizer.coast(motions[i])
                if motions[i] is not None:
                    corners = track.bbox.reshape(2, 2).astype(np.float32)
                    track.bbox = _apply(motions[i], corners).reshape(4)

        self.prev_gray = gray
        self.tracks = [t for t in self.tracks if index - t.last_seen <= MAX_GAP_FRAMES]

        if self.targets is None:
            active = [(t, 0) for t in self.tracks]
        else:
            # Each chosen person appears once per frame; if two tracks claim the same person, keep the
            # more similar one.
            best: dict[int, Track] = {}
            for t in self.tracks:
                k = t.assigned
                if k is not None and (k not in best or t.similarities[k] > best[k].similarities[k]):
                    best[k] = t
            active = [(t, k) for k, t in best.items()]
        # track_id lets later stages keep per-face temporal state (e.g. enhancement detail).
        return [Face(bbox=t.bbox, kps=t.stabilizer.kps.copy(), det_score=1.0, track_id=t.id, target=k)
                for t, k in active]
