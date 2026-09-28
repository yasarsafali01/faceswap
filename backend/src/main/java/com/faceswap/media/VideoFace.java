package com.faceswap.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** A distinct person found in a video by the worker's analysis. Embeddings stay in object storage. */
@Entity
@Table(name = "video_faces")
public class VideoFace {

    @Id
    private UUID id;

    @Column(name = "video_id", nullable = false)
    private UUID videoId;

    @Column(name = "face_index", nullable = false)
    private int faceIndex;

    @Column(name = "thumbnail_key", nullable = false)
    private String thumbnailKey;

    @Column(nullable = false)
    private int occurrences;

    protected VideoFace() {
    }

    public VideoFace(UUID videoId, int faceIndex, String thumbnailKey, int occurrences) {
        this.id = UUID.randomUUID();
        this.videoId = videoId;
        this.faceIndex = faceIndex;
        this.thumbnailKey = thumbnailKey;
        this.occurrences = occurrences;
    }

    public UUID getVideoId() {
        return videoId;
    }

    public int getFaceIndex() {
        return faceIndex;
    }

    public String getThumbnailKey() {
        return thumbnailKey;
    }

    public int getOccurrences() {
        return occurrences;
    }
}
