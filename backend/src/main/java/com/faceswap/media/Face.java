package com.faceswap.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "faces")
public class Face extends StoredFile {

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false)
    private AnalysisStatus analysisStatus = AnalysisStatus.PENDING;

    @Column(name = "analysis_error")
    private String analysisError;

    protected Face() {
    }

    public Face(UUID id, Long userId, String objectKey, String originalName, String contentType, long sizeBytes) {
        super(id, userId, objectKey, originalName, contentType, sizeBytes);
    }

    /** Object storage prefix for the per-face crops ({index}.jpg). */
    public String cropsPrefix() {
        return "face-crops/" + getUserId() + "/" + getId() + "/";
    }

    public void markAnalyzed() {
        analysisStatus = AnalysisStatus.READY;
        analysisError = null;
    }

    public void markAnalysisFailed(String error) {
        analysisStatus = AnalysisStatus.FAILED;
        analysisError = error;
    }

    public AnalysisStatus getAnalysisStatus() {
        return analysisStatus;
    }

    public String getAnalysisError() {
        return analysisError;
    }
}
