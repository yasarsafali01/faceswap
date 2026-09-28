package com.faceswap.media;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "videos")
public class Video extends StoredFile {

    protected Video() {
    }

    public Video(UUID id, Long userId, String objectKey, String originalName, String contentType, long sizeBytes) {
        super(id, userId, objectKey, originalName, contentType, sizeBytes);
    }
}
