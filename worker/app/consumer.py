"""RabbitMQ consumer. The job runs on a separate thread so pika's I/O loop keeps servicing heartbeats
during multi-minute renders; all channel operations are marshalled back via add_callback_threadsafe."""
import functools
import json
import logging
import threading
import time

import pika

from . import messages
from .config import settings
from .messages import JobRequest
from .processor import JobError, Processor

log = logging.getLogger(__name__)


class Consumer:
    def __init__(self, processor: Processor) -> None:
        self.processor = processor
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
        channel = connection.channel()
        self._declare(channel)
        # One job at a time per worker: a single GPU is the bottleneck, and unacked jobs stay
        # available to other workers.
        channel.basic_qos(prefetch_count=1)
        channel.basic_consume(messages.JOBS_QUEUE, functools.partial(self._on_message, connection))
        log.info("%s waiting for jobs", settings.worker_id)
        channel.start_consuming()

    @staticmethod
    def _declare(channel) -> None:
        channel.exchange_declare(messages.EXCHANGE, "direct", durable=True)
        channel.exchange_declare(messages.DEAD_LETTER_EXCHANGE, "direct", durable=True)
        channel.queue_declare(messages.JOBS_QUEUE, durable=True, arguments={
            "x-dead-letter-exchange": messages.DEAD_LETTER_EXCHANGE,
            "x-dead-letter-routing-key": messages.JOBS_DEAD_QUEUE,
        })
        channel.queue_declare(messages.JOBS_DEAD_QUEUE, durable=True)
        channel.queue_declare(messages.EVENTS_QUEUE, durable=True)
        channel.queue_bind(messages.JOBS_QUEUE, messages.EXCHANGE, messages.JOB_ROUTING_KEY)
        channel.queue_bind(messages.JOBS_DEAD_QUEUE, messages.DEAD_LETTER_EXCHANGE, messages.JOBS_DEAD_QUEUE)
        channel.queue_bind(messages.EVENTS_QUEUE, messages.EXCHANGE, messages.EVENT_ROUTING_KEY)

    def _on_message(self, connection, channel, method, _properties, body) -> None:
        threading.Thread(
            target=self._work, args=(connection, channel, method.delivery_tag, body), daemon=True
        ).start()

    def _work(self, connection, channel, delivery_tag, body) -> None:
        def threadsafe(fn, *args, **kwargs):
            try:
                connection.add_callback_threadsafe(functools.partial(fn, *args, **kwargs))
            except Exception:
                # Connection is gone; the unacked message will be redelivered after reconnect.
                log.warning("Could not reach RabbitMQ from job thread")

        def publish(type_: str, progress: int | None = None, error: str | None = None) -> None:
            payload = messages.event(job.job_id, type_, settings.worker_id, progress, error)
            threadsafe(channel.basic_publish, messages.EXCHANGE, messages.EVENT_ROUTING_KEY, json.dumps(payload),
                       pika.BasicProperties(content_type="application/json", delivery_mode=2))

        try:
            job = JobRequest.from_json(json.loads(body))
        except (ValueError, KeyError, TypeError):
            log.error("Malformed job message, dead-lettering: %r", body[:500])
            threadsafe(channel.basic_reject, delivery_tag, requeue=False)
            return

        self.current_job = job.job_id
        log.info("Job %s started", job.job_id)
        publish("STARTED")
        try:
            self.processor.run(job, lambda p: publish("PROGRESS", progress=p))
            publish("COMPLETED")
            self.processed += 1
            log.info("Job %s completed", job.job_id)
        except JobError as e:
            publish("FAILED", error=str(e))
            self.failed += 1
            log.info("Job %s failed: %s", job.job_id, e)
        except Exception:
            log.exception("Job %s crashed", job.job_id)
            publish("FAILED", error="İşlem sırasında beklenmeyen bir hata oluştu")
            self.failed += 1
        finally:
            self.current_job = None
            threadsafe(channel.basic_ack, delivery_tag)
