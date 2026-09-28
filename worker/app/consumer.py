"""RabbitMQ consumer. Work runs on separate threads so pika's I/O loop keeps servicing heartbeats
during multi-minute renders; all channel operations are marshalled back via add_callback_threadsafe.

Renders and video analyses use separate channels, each with prefetch=1, so a quick analysis of a
freshly uploaded video never waits behind a long render on the same worker."""
import functools
import json
import logging
import threading
import time
from typing import Callable

import pika

from . import messages
from .analysis import VideoAnalyzer
from .config import settings
from .messages import AnalyzeRequest, JobRequest
from .processor import JobError, Processor
from .video import VideoError

log = logging.getLogger(__name__)

Publish = Callable[[str, dict], None]


class Consumer:
    def __init__(self, processor: Processor, analyzer: VideoAnalyzer) -> None:
        self.processor = processor
        self.analyzer = analyzer
        self.current_job: str | None = None
        self.processed = 0
        self.failed = 0

    def run_forever(self) -> None:
        while True:
            try:
                self._consume()
            except pika.exceptions.AMQPError as e:
                log.warning("RabbitMQ connection lost (%s), reconnecting in 5s", e)
                time.sleep(5)

    def _consume(self) -> None:
        params = pika.ConnectionParameters(
            host=settings.rabbitmq_host,
            credentials=pika.PlainCredentials(settings.rabbitmq_user, settings.rabbitmq_password),
            heartbeat=60,
            blocked_connection_timeout=300,
        )
        connection = pika.BlockingConnection(params)
        jobs_channel = connection.channel()
        self._declare(jobs_channel)
        jobs_channel.basic_qos(prefetch_count=1)
        jobs_channel.basic_consume(messages.JOBS_QUEUE, functools.partial(self._dispatch, connection, self._render))

        analyze_channel = connection.channel()
        analyze_channel.basic_qos(prefetch_count=1)
        analyze_channel.basic_consume(messages.ANALYZE_QUEUE,
                                      functools.partial(self._dispatch, connection, self._analyze))

        log.info("%s waiting for jobs", settings.worker_id)
        # Blocks on the shared connection; deliveries for both channels are dispatched from here.
        jobs_channel.start_consuming()

    @staticmethod
    def _declare(channel) -> None:
        channel.exchange_declare(messages.EXCHANGE, "direct", durable=True)
        channel.exchange_declare(messages.DEAD_LETTER_EXCHANGE, "direct", durable=True)
        for queue, dead in ((messages.JOBS_QUEUE, messages.JOBS_DEAD_QUEUE),
                            (messages.ANALYZE_QUEUE, messages.ANALYZE_DEAD_QUEUE)):
            channel.queue_declare(queue, durable=True, arguments={
                "x-dead-letter-exchange": messages.DEAD_LETTER_EXCHANGE,
                "x-dead-letter-routing-key": dead,
            })
            channel.queue_declare(dead, durable=True)
            channel.queue_bind(dead, messages.DEAD_LETTER_EXCHANGE, dead)
        channel.queue_declare(messages.EVENTS_QUEUE, durable=True)
        channel.queue_declare(messages.ANALYSIS_RESULTS_QUEUE, durable=True)
        channel.queue_bind(messages.JOBS_QUEUE, messages.EXCHANGE, messages.JOB_ROUTING_KEY)
        channel.queue_bind(messages.ANALYZE_QUEUE, messages.EXCHANGE, messages.ANALYZE_ROUTING_KEY)
        channel.queue_bind(messages.EVENTS_QUEUE, messages.EXCHANGE, messages.EVENT_ROUTING_KEY)
        channel.queue_bind(messages.ANALYSIS_RESULTS_QUEUE, messages.EXCHANGE, messages.ANALYZED_ROUTING_KEY)

    def _dispatch(self, connection, handler, channel, method, _properties, body) -> None:
        threading.Thread(
            target=self._run, args=(connection, channel, method.delivery_tag, body, handler), daemon=True
        ).start()

    @staticmethod
    def _run(connection, channel, delivery_tag, body, handler) -> None:
        def threadsafe(fn, *args, **kwargs):
            try:
                connection.add_callback_threadsafe(functools.partial(fn, *args, **kwargs))
            except Exception:
                # Connection is gone; the unacked message will be redelivered after reconnect.
                log.warning("Could not reach RabbitMQ from worker thread")

        def publish(routing_key: str, payload: dict) -> None:
            threadsafe(channel.basic_publish, messages.EXCHANGE, routing_key, json.dumps(payload),
                       pika.BasicProperties(content_type="application/json", delivery_mode=2))

        try:
            data = json.loads(body)
        except ValueError:
            log.error("Malformed message, dead-lettering: %r", body[:500])
            threadsafe(channel.basic_reject, delivery_tag, requeue=False)
            return
        try:
            handler(data, publish)
        except (KeyError, TypeError, ValueError):
            log.exception("Invalid message, dead-lettering: %r", body[:500])
            threadsafe(channel.basic_reject, delivery_tag, requeue=False)
            return
        threadsafe(channel.basic_ack, delivery_tag)

    def _render(self, data: dict, publish: Publish) -> None:
        job = JobRequest.from_json(data)

        def event(type_: str, progress: int | None = None, error: str | None = None) -> None:
            publish(messages.EVENT_ROUTING_KEY, messages.event(job.job_id, type_, settings.worker_id, progress, error))

        self.current_job = job.job_id
        log.info("Job %s started", job.job_id)
        event("STARTED")
        try:
            self.processor.run(job, lambda p: event("PROGRESS", progress=p))
            event("COMPLETED")
            self.processed += 1
            log.info("Job %s completed", job.job_id)
        except JobError as e:
            event("FAILED", error=str(e))
            self.failed += 1
            log.info("Job %s failed: %s", job.job_id, e)
        except Exception:
            log.exception("Job %s crashed", job.job_id)
            event("FAILED", error="İşlem sırasında beklenmeyen bir hata oluştu")
            self.failed += 1
        finally:
            self.current_job = None

    def _analyze(self, data: dict, publish: Publish) -> None:
        req = AnalyzeRequest.from_json(data)
        try:
            faces = self.analyzer.run(req)
            result = messages.analysis_result(req.video_id, faces=faces)
        except VideoError as e:
            result = messages.analysis_result(req.video_id, error=str(e))
        except Exception:
            log.exception("Analysis of video %s crashed", req.video_id)
            result = messages.analysis_result(req.video_id, error="Video analiz edilemedi")
        publish(messages.ANALYZED_ROUTING_KEY, result)
