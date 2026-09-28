"""FFmpeg-based decode/encode. Frames stream through pipes as raw BGR, so nothing is written to disk
between extraction and reassembly."""
import json
import subprocess
import tempfile
import threading
from dataclasses import dataclass
from fractions import Fraction
from queue import Empty, Queue

import numpy as np

from .config import settings


class VideoError(Exception):
    pass


@dataclass(frozen=True)
class VideoInfo:
    width: int
    height: int
    fps: Fraction
    duration: float
    has_audio: bool

    @property
    def frame_count(self) -> int:
        return max(1, round(self.duration * float(self.fps)))


def _even(v: float) -> int:
    return max(2, int(round(v / 2)) * 2)


def probe(path: str) -> VideoInfo:
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", path],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        raise VideoError("Video dosyası okunamadı")
    data = json.loads(result.stdout)
    streams = data.get("streams", [])
    video = next((s for s in streams if s.get("codec_type") == "video"), None)
    if video is None:
        raise VideoError("Dosyada video akışı yok")

    width, height = int(video["width"]), int(video["height"])
    rotation = 0
    for side in video.get("side_data_list", []):
        if "rotation" in side:
            rotation = int(side["rotation"])
    rotation = rotation or int(video.get("tags", {}).get("rotate", 0))
    # FFmpeg auto-rotates on decode, so the frames we receive have the display orientation.
    if abs(rotation) % 180 == 90:
        width, height = height, width

    if height > settings.max_height:
        width, height = width * settings.max_height / height, settings.max_height
    width, height = _even(width), _even(height)

    fps = Fraction(0)
    for field in ("avg_frame_rate", "r_frame_rate"):
        try:
            fps = Fraction(video.get(field, "0/0"))
        except (ValueError, ZeroDivisionError):
            continue
        if fps > 0:
            break
    if fps <= 0:
        raise VideoError("Video kare hızı belirlenemedi")
    fps = min(fps, Fraction(settings.max_fps)).limit_denominator(1001)

    duration = float(data.get("format", {}).get("duration") or video.get("duration") or 0)
    if duration <= 0:
        raise VideoError("Video süresi belirlenemedi")

    return VideoInfo(width, height, fps, duration, any(s.get("codec_type") == "audio" for s in streams))


class FrameReader:
    """Decodes frames on a background thread so decoding overlaps with GPU work."""

    def __init__(self, path: str, info: VideoInfo, prefetch: int = 16) -> None:
        self.frame_size = info.width * info.height * 3
        self.shape = (info.height, info.width, 3)
        self.stderr = tempfile.TemporaryFile()
        self.proc = subprocess.Popen(
            ["ffmpeg", "-nostdin", "-v", "error", "-i", path, "-an", "-sn", "-dn",
             "-vf", f"fps={info.fps},scale={info.width}:{info.height}:flags=bicubic",
             "-pix_fmt", "bgr24", "-f", "rawvideo", "pipe:1"],
            stdout=subprocess.PIPE, stderr=self.stderr,
        )
        self.queue: Queue = Queue(maxsize=prefetch)
        self.thread = threading.Thread(target=self._pump, daemon=True)
        self.thread.start()

    def _pump(self) -> None:
        try:
            while True:
                buf = self.proc.stdout.read(self.frame_size)
                if len(buf) < self.frame_size:
                    break
                self.queue.put(np.frombuffer(buf, np.uint8).reshape(self.shape).copy())
        finally:
            self.queue.put(None)

    def __iter__(self):
        while (frame := self.queue.get()) is not None:
            yield frame

    def close(self) -> None:
        if self.proc.poll() is None:
            self.proc.kill()
        self.proc.wait()
        # Drain so the pump thread can't stay blocked on a full queue after an early exit.
        while self.thread.is_alive():
            try:
                self.queue.get(timeout=0.1)
            except Empty:
                pass
        self.stderr.close()


class FrameWriter:
    """Encodes raw frames to H.264 and muxes the original audio track back in, in one ffmpeg pass."""

    def __init__(self, out_path: str, source_path: str, info: VideoInfo) -> None:
        if settings.video_encoder == "h264_nvenc":
            codec = ["-c:v", "h264_nvenc", "-preset", "p5", "-rc", "vbr", "-cq", str(settings.video_crf), "-b:v", "0"]
        else:
            codec = ["-c:v", "libx264", "-preset", "medium", "-crf", str(settings.video_crf)]
        audio = ["-map", "1:a:0?", "-c:a", "aac", "-b:a", "192k"] if info.has_audio else []
        self.stderr = tempfile.TemporaryFile()
        self.proc = subprocess.Popen(
            ["ffmpeg", "-nostdin", "-y", "-v", "error",
             "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{info.width}x{info.height}", "-r", str(info.fps),
             "-i", "pipe:0", "-i", source_path,
             "-map", "0:v:0", *audio, *codec, "-pix_fmt", "yuv420p",
             "-shortest", "-movflags", "+faststart", out_path],
            stdin=subprocess.PIPE, stderr=self.stderr,
        )

    def write(self, frame: np.ndarray) -> None:
        try:
            self.proc.stdin.write(frame.tobytes())
        except BrokenPipeError:
            raise VideoError("Video kodlayıcı beklenmedik şekilde kapandı: " + self._error())

    def finish(self) -> None:
        self.proc.stdin.close()
        if self.proc.wait() != 0:
            raise VideoError("Video oluşturulamadı: " + self._error())
        self.stderr.close()

    def abort(self) -> None:
        if self.proc.poll() is None:
            self.proc.kill()
            self.proc.wait()

    def _error(self) -> str:
        self.stderr.seek(0)
        return self.stderr.read().decode(errors="replace")[-500:]
