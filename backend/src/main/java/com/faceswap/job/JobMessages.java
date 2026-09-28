package com.faceswap.job;

import java.util.UUID;

/** Wire contract with the Python GPU workers. Keep in sync with worker/app/messages.py. */
public final class JobMessages {

    private JobMessages() {
    }

    public static final String EXCHANGE = "faceswap";
    public static final String DEAD_LETTER_EXCHANGE = "faceswap.dlx";
    public static final String JOBS_QUEUE = "faceswap.jobs";
    public static final String JOBS_DEAD_QUEUE = "faceswap.jobs.dead";
    public static final String EVENTS_QUEUE = "faceswap.job-events";
    public static final String JOB_ROUTING_KEY = "job.requested";
    public static final String EVENT_ROUTING_KEY = "job.event";

    /** Backend -> worker. Object keys are decided here so workers never invent storage paths. */
    public record JobRequest(UUID jobId, long userId, String videoKey, String faceKey,
                             String resultKey, String thumbnailKey, boolean enhance) {
    }

    public enum EventType {STARTED, PROGRESS, COMPLETED, FAILED}

    /** Worker -> backend. */
    public record JobEvent(UUID jobId, EventType type, Integer progress, String error, String workerId) {
    }
}
