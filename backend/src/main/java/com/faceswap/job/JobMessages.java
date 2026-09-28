package com.faceswap.job;

import java.util.List;
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

    public static final String ANALYZE_QUEUE = "faceswap.analyze";
    public static final String ANALYZE_DEAD_QUEUE = "faceswap.analyze.dead";
    public static final String ANALYSIS_RESULTS_QUEUE = "faceswap.analysis-results";
    public static final String ANALYZE_ROUTING_KEY = "video.analyze";
    public static final String ANALYZED_ROUTING_KEY = "video.analyzed";

    /**
     * Backend -> worker. Object keys are decided here so workers never invent storage paths.
     * facesKey/targetFaceIndex are null when every face in the video should be swapped.
     */
    public record JobRequest(UUID jobId, long userId, String videoKey, String faceKey,
                             String resultKey, String thumbnailKey, boolean enhance,
                             String facesKey, Integer targetFaceIndex) {
    }

    /** Backend -> worker: find the distinct people in a freshly uploaded video. */
    public record AnalyzeRequest(UUID videoId, long userId, String videoKey, String facesPrefix) {
    }

    /** Worker -> backend. Thumbnails live at facesPrefix + index + ".jpg", embeddings in facesPrefix + "faces.json". */
    public record AnalysisResult(UUID videoId, String status, String error, List<DetectedFace> faces) {
    }

    public record DetectedFace(int index, int occurrences) {
    }

    public enum EventType {STARTED, PROGRESS, COMPLETED, FAILED}

    /** Worker -> backend. */
    public record JobEvent(UUID jobId, EventType type, Integer progress, String error, String workerId) {
    }
}
