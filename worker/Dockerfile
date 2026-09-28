# Must match the CUDA major that the pinned onnxruntime-gpu wheel links against (1.30 -> CUDA 13).
# CUDA 13 also covers Blackwell (RTX 50xx); the host driver must support CUDA 13 (R580+).
FROM nvidia/cuda:13.0.2-cudnn-runtime-ubuntu24.04

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PATH=/opt/venv/bin:$PATH

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-venv python3-dev build-essential ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && python3 -m venv /opt/venv

WORKDIR /app
COPY requirements.txt constraints.txt ./
RUN pip install -r requirements.txt -c constraints.txt \
    # insightface's setup.py imports numpy/cython at build time, so build against the venv.
    && pip install --no-build-isolation insightface==0.7.3 -c constraints.txt

COPY app ./app

# Port comes from WORKER_HEALTH_PORT in .env; shell form so the variable is expanded at start.
CMD uvicorn app.main:app --host 0.0.0.0 --port ${WORKER_HEALTH_PORT:-8000}
