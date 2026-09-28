package com.faceswap.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** One "person in the video -> new face" assignment of a job. */
@Entity
@Table(name = "job_swaps")
public class JobSwap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "target_face_index")
    private Integer targetFaceIndex;

    @Column(name = "face_id", nullable = false)
    private UUID faceId;

    protected JobSwap() {
    }

    public JobSwap(UUID jobId, Integer targetFaceIndex, UUID faceId) {
        this.jobId = jobId;
        this.targetFaceIndex = targetFaceIndex;
        this.faceId = faceId;
    }

    public UUID getJobId() {
        return jobId;
    }

    public Integer getTargetFaceIndex() {
        return targetFaceIndex;
    }

    public UUID getFaceId() {
        return faceId;
    }
}
