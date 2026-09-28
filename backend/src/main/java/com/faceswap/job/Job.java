package com.faceswap.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
public class Job {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "video_id", nullable = false)
    private UUID videoId;

    @Column(name = "face_id", nullable = false)
    private UUID faceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status = JobStatus.QUEUED;

    private short progress;

    private boolean enhance = true;

    @Column(name = "target_face_index")
    private Integer targetFaceIndex;

    @Column(name = "result_key")
    private String resultKey;

    @Column(name = "thumbnail_key")
    private String thumbnailKey;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "consent_at", nullable = false)
    private Instant consentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected Job() {
    }

    public Job(Long userId, UUID videoId, UUID faceId, boolean enhance, Integer targetFaceIndex) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.videoId = videoId;
        this.faceId = faceId;
        this.enhance = enhance;
        this.targetFaceIndex = targetFaceIndex;
        this.consentAt = Instant.now();
    }

    public Integer getTargetFaceIndex() {
        return targetFaceIndex;
    }

    public void markProcessing() {
        status = JobStatus.PROCESSING;
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }

    public void markCompleted(String resultKey, String thumbnailKey) {
        status = JobStatus.COMPLETED;
        progress = 100;
        this.resultKey = resultKey;
        this.thumbnailKey = thumbnailKey;
        finishedAt = Instant.now();
    }

    public void markFailed(String error) {
        status = JobStatus.FAILED;
        errorMessage = error;
        finishedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public UUID getVideoId() {
        return videoId;
    }

    public UUID getFaceId() {
        return faceId;
    }

    public JobStatus getStatus() {
        return status;
    }

    public short getProgress() {
        return progress;
    }

    public boolean isEnhance() {
        return enhance;
    }

    public String getResultKey() {
        return resultKey;
    }

    public String getThumbnailKey() {
        return thumbnailKey;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
