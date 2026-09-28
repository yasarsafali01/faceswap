import logging
import threading
from contextlib import asynccontextmanager

from fastapi import FastAPI

from .config import settings

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("worker")

state: dict = {"status": "loading", "consumer": None, "providers": [], "error": None}


def _boot() -> None:
    # Imported lazily so the health endpoint is up while models download on first start.
    try:
        from .analysis import PhotoAnalyzer, VideoAnalyzer
        from .consumer import Consumer
        from .faces import FaceEngine
        from .processor import Processor
        from .storage import Storage

        engine = FaceEngine()
        state["providers"] = engine.providers
        storage = Storage()
        consumer = Consumer(Processor(engine, storage), VideoAnalyzer(engine, storage), PhotoAnalyzer(engine, storage))
        state["consumer"] = consumer
        state["status"] = "ready"
        consumer.run_forever()
    except Exception as e:
        log.exception("Worker failed to start")
        state["status"] = "error"
        state["error"] = str(e)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    threading.Thread(target=_boot, name="consumer", daemon=True).start()
    yield


app = FastAPI(title="faceswap-worker", lifespan=lifespan)


@app.get("/health")
def health() -> dict:
    consumer = state["consumer"]
    return {
        "status": state["status"],
        "workerId": settings.worker_id,
        "providers": state["providers"],
        "currentJob": consumer.current_job if consumer else None,
        "processed": consumer.processed if consumer else 0,
        "failed": consumer.failed if consumer else 0,
        "error": state["error"],
    }
