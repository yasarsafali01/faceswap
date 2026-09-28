package com.faceswap.job;

import com.faceswap.job.JobMessages.JobEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies worker status events to the job row and pushes the new state to the owner over WebSocket.
 * Progress events only touch Redis to keep write load off Postgres.
 */
@Component
public class JobEventListener {

    private static final Logger log = LoggerFactory.getLogger(JobEventListener.class);

    private final JobRepository jobs;
    private final JobLogRepository jobLogs;
    private final JobService jobService;
    private final SimpMessagingTemplate messaging;

    public JobEventListener(JobRepository jobs, JobLogRepository jobLogs, JobService jobService,
                            SimpMessagingTemplate messaging) {
        this.jobs = jobs;
        this.jobLogs = jobLogs;
        this.jobService = jobService;
        this.messaging = messaging;
    }

    @RabbitListener(queues = JobMessages.EVENTS_QUEUE)
    @Transactional
    public void onEvent(JobEvent event) {
        if (event == null || event.jobId() == null || event.type() == null) {
            log.warn("Ignoring malformed job event: {}", event);
            return;
        }
        Job job = jobs.findById(event.jobId()).orElse(null);
        if (job == null) {
            log.warn("Event for unknown job {}", event.jobId());
            return;
        }
        // A redelivered job can emit events again after it already finished; the first terminal state wins.
        if (job.getStatus().isTerminal()) {
            return;
        }

        String worker = event.workerId() == null ? "worker" : event.workerId();
        switch (event.type()) {
            case STARTED -> {
                job.markProcessing();
                jobService.cacheProgress(job.getId(), 0);
                jobLogs.save(new JobLog(job.getId(), "INFO", "Started on " + worker));
            }
            case PROGRESS -> {
                if (job.getStatus() == JobStatus.QUEUED) {
                    job.markProcessing();
                }
                int p = event.progress() == null ? 0 : Math.max(0, Math.min(99, event.progress()));
                jobService.cacheProgress(job.getId(), p);
            }
            case COMPLETED -> {
                job.markCompleted(JobService.resultKey(job), JobService.thumbnailKey(job));
                jobLogs.save(new JobLog(job.getId(), "INFO", "Completed on " + worker));
            }
            case FAILED -> {
                String error = event.error() == null || event.error().isBlank() ? "İşlem başarısız oldu" : event.error();
                job.markFailed(error.length() > 1000 ? error.substring(0, 1000) : error);
                jobLogs.save(new JobLog(job.getId(), "ERROR", worker + ": " + error));
            }
        }
        messaging.convertAndSendToUser(String.valueOf(job.getUserId()), "/queue/jobs", jobService.toDto(job));
    }
}
