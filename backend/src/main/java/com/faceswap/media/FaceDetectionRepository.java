package com.faceswap.media;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FaceDetectionRepository extends JpaRepository<FaceDetection, UUID> {

    List<FaceDetection> findByFaceIdOrderByDetIndex(UUID faceId);

    List<FaceDetection> findByFaceIdIn(Collection<UUID> faceIds);

    @Modifying
    @Query("delete from FaceDetection d where d.faceId = :faceId")
    void deleteByFaceId(UUID faceId);
}
