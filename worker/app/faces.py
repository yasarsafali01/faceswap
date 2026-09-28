"""Face detection, identity swap (inswapper) and restoration (GFPGAN) on single frames.

All methods are safe to call from several threads at once: ONNX Runtime sessions are thread-safe
and per-call state lives on the stack. The processor relies on this to overlap CPU and GPU work."""
import logging
import os
import urllib.request

import cv2
import numpy as np
import onnx
import onnxruntime as ort
from insightface.app import FaceAnalysis
from insightface.app.common import Face
from onnx import numpy_helper

from .config import settings

log = logging.getLogger(__name__)

ASSETS = "https://github.com/facefusion/facefusion-assets/releases/download/models-3.0.0/"
MODEL_URLS = {
    "inswapper_128.onnx": ASSETS + "inswapper_128.onnx",
    "inswapper_128_fp16.onnx": ASSETS + "inswapper_128_fp16.onnx",
    "gfpgan_1.4.onnx": ASSETS + "gfpgan_1.4.onnx",
}

# ArcFace 5-point template (eyes, nose, mouth corners) at 128px, the alignment inswapper was trained on.
ARCFACE_128 = np.array([
    [46.2946, 51.6963],
    [81.5318, 51.5014],
    [64.0252, 71.7366],
    [49.5493, 92.3655],
    [78.7299, 92.2041],
], dtype=np.float32)

# FFHQ template GFPGAN was trained on, at 512px.
FFHQ_512 = np.array([
    [0.37691676, 0.46864664],
    [0.62285697, 0.46912813],
    [0.50123859, 0.61331904],
    [0.39308822, 0.72541100],
    [0.61150205, 0.72490465],
], dtype=np.float32) * 512


def ensure_model(name: str) -> str:
    path = os.path.join(settings.models_dir, name)
    if not os.path.exists(path):
        os.makedirs(settings.models_dir, exist_ok=True)
        log.info("Downloading %s", name)
        tmp = path + ".part"
        urllib.request.urlretrieve(MODEL_URLS[name], tmp)
        os.replace(tmp, path)
    return path


def _box_mask(size: int, blur: float = 0.3) -> np.ndarray:
    blur_amount = int(size * 0.5 * blur)
    blur_area = max(blur_amount // 2, 1)
    mask = np.ones((size, size), np.float32)
    mask[:blur_area, :] = 0
    mask[-blur_area:, :] = 0
    mask[:, :blur_area] = 0
    mask[:, -blur_area:] = 0
    return cv2.GaussianBlur(mask, (0, 0), blur_amount * 0.25)


def _align(kps: np.ndarray, template: np.ndarray) -> np.ndarray | None:
    # A huge RANSAC threshold keeps all 5 points as inliers, i.e. a plain least-squares similarity fit.
    return cv2.estimateAffinePartial2D(kps, template, method=cv2.RANSAC, ransacReprojThreshold=100)[0]


def _paste_back(frame: np.ndarray, crop: np.ndarray, matrix: np.ndarray, mask: np.ndarray, strength: float = 1.0) -> None:
    """Blends an aligned crop back into the frame in place, touching only the face's bounding region."""
    size = crop.shape[0]
    inverse = cv2.invertAffineTransform(matrix)
    corners = np.array([[0, 0], [size, 0], [0, size], [size, size]], dtype=np.float32)
    pts = corners @ inverse[:, :2].T + inverse[:, 2]
    h, w = frame.shape[:2]
    x0, y0 = np.floor(pts.min(axis=0)).astype(int)
    x1, y1 = np.ceil(pts.max(axis=0)).astype(int)
    x0, y0, x1, y1 = max(x0, 0), max(y0, 0), min(x1, w), min(y1, h)
    if x1 <= x0 or y1 <= y0:
        return

    local = inverse.copy()
    local[:, 2] -= (x0, y0)
    region_size = (x1 - x0, y1 - y0)
    alpha = cv2.warpAffine(mask, local, region_size).clip(0, 1)[..., None] * strength
    warped = cv2.warpAffine(crop, local, region_size, borderMode=cv2.BORDER_REPLICATE)
    region = frame[y0:y1, x0:x1].astype(np.float32)
    frame[y0:y1, x0:x1] = (alpha * warped + (1 - alpha) * region).astype(np.uint8)


class FaceEngine:
    def __init__(self) -> None:
        on_gpu = "CUDAExecutionProvider" in ort.get_available_providers()
        providers = ["CUDAExecutionProvider", "CPUExecutionProvider"] if on_gpu else ["CPUExecutionProvider"]
        options = ort.SessionOptions()
        # With CUDA doing the math, idle ORT threads spinning after each run only steal CPU from OpenCV.
        options.add_session_config_entry("session.intra_op.allow_spinning", "0")

        self.analyzer = FaceAnalysis(
            name="buffalo_l",
            root=os.path.join(settings.models_dir, "insightface"),
            providers=providers,
            sess_options=options,
            allowed_modules=["detection", "recognition", "genderage"],
        )
        self.analyzer.prepare(ctx_id=0, det_size=(640, 640))

        # fp16 is ~40% faster on tensor cores with no visible difference (<1.5px); on CPU it's slower.
        swapper_path = ensure_model("inswapper_128_fp16.onnx" if on_gpu else "inswapper_128.onnx")
        self.swapper = ort.InferenceSession(swapper_path, sess_options=options, providers=providers)
        # The identity projection matrix ships as the model's last initializer.
        self.emap = numpy_helper.to_array(onnx.load(swapper_path).graph.initializer[-1]).astype(np.float32)
        self.swap_mask = _box_mask(128)

        self.gfpgan = ort.InferenceSession(ensure_model("gfpgan_1.4.onnx"), sess_options=options, providers=providers)
        self.gfpgan_input = self.gfpgan.get_inputs()[0].name
        self.enhance_mask = _box_mask(512)

        # What the sessions actually ended up on; CUDA silently falls back to CPU if the GPU is unusable.
        self.providers = self.gfpgan.get_providers()
        log.info("Face engine ready, providers=%s, swapper=%s", self.providers, os.path.basename(swapper_path))

    def source_face(self, image: np.ndarray) -> Face | None:
        faces = self.analyzer.get(image)
        if not faces:
            # Tightly cropped portraits (face filling the whole image) are missed by the detector;
            # padding gives it the surrounding context it expects. Only the embedding is used later,
            # so detecting on the padded copy is fine.
            pad = max(image.shape[:2]) // 2
            padded = cv2.copyMakeBorder(image, pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=(0, 0, 0))
            faces = self.analyzer.get(padded)
        if not faces:
            return None
        return max(faces, key=lambda f: (f.bbox[2] - f.bbox[0]) * (f.bbox[3] - f.bbox[1]))

    def identity(self, source: Face) -> np.ndarray:
        """Projects the source face embedding into inswapper's latent space; computed once per job."""
        latent = source.normed_embedding.reshape(1, -1) @ self.emap
        return (latent / np.linalg.norm(latent)).astype(np.float32)

    def detect(self, frame: np.ndarray) -> list[Face]:
        # Only the detector runs per frame; swapping and enhancing just need the 5 landmarks.
        bboxes, kpss = self.analyzer.det_model.detect(frame, max_num=0, metric="default")
        if bboxes is None or len(bboxes) == 0:
            return []
        return [Face(bbox=b[:4], kps=k, det_score=b[4]) for b, k in zip(bboxes, kpss)]

    def embed(self, frame: np.ndarray, face: Face) -> np.ndarray:
        """Unit-length ArcFace identity embedding of a detected face."""
        emb = self.analyzer.models["recognition"].get(frame, face)
        return emb / np.linalg.norm(emb)

    def estimate_age(self, frame: np.ndarray, face: Face) -> int:
        self.analyzer.models["genderage"].get(frame, face)
        return int(face.age)

    def swap(self, frame: np.ndarray, kps: np.ndarray, identity: np.ndarray) -> None:
        matrix = _align(kps, ARCFACE_128)
        if matrix is None:
            return
        crop = cv2.warpAffine(frame, matrix, (128, 128), borderMode=cv2.BORDER_REPLICATE)
        blob = (crop[:, :, ::-1].astype(np.float32) / 255.0).transpose(2, 0, 1)[None]
        out = self.swapper.run(None, {"target": np.ascontiguousarray(blob), "source": identity})[0][0]
        swapped = (out.transpose(1, 2, 0).clip(0, 1) * 255).astype(np.uint8)[:, :, ::-1]
        _paste_back(frame, swapped, matrix, self.swap_mask)

    def enhance(self, frame: np.ndarray, kps: np.ndarray) -> None:
        matrix = _align(kps, FFHQ_512)
        if matrix is None:
            return
        crop = cv2.warpAffine(frame, matrix, (512, 512), borderMode=cv2.BORDER_REPLICATE)
        x = (crop[:, :, ::-1].astype(np.float32) / 127.5 - 1.0).transpose(2, 0, 1)[None]
        y = self.gfpgan.run(None, {self.gfpgan_input: np.ascontiguousarray(x)})[0][0]
        restored = ((y.transpose(1, 2, 0).clip(-1, 1) + 1) * 127.5).astype(np.uint8)[:, :, ::-1]
        _paste_back(frame, restored, matrix, self.enhance_mask, settings.enhancer_blend)
