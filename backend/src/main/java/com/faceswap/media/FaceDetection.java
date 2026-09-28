package com.faceswap.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** One face found in an uploaded source photo, stored as its own crop. */
@Entity
@Table(name = "face_detections")
public class FaceDetection {

    @Id
    private UUID id;

    @Column(name = "face_id", nullable = false)
    private UUID faceId;

    @Column(name = "det_index", nullable = false)
    private int detIndex;

    @Column(name = "crop_key", nullable = false)
    private String cropKey;

    protected FaceDetection() {
    }

    public FaceDetection(UUID faceId, int detIndex, String cropKey) {
        this.id = UUID.randomUUID();
        this.faceId = faceId;
        this.detIndex = detIndex;
        this.cropKey = cropKey;
    }

    public UUID getFaceId() {
        return faceId;
    }

    public int getDetIndex() {
        return detIndex;
    }

    public String getCropKey() {
        return cropKey;
    }
}
