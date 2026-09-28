package com.faceswap.media;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoFaceRepository extends JpaRepository<VideoFace, UUID> {

    List<VideoFace> findByVideoIdOrderByFaceIndex(UUID videoId);

    List<VideoFace> findByVideoIdIn(Collection<UUID> videoIds);

    Optional<VideoFace> findByVideoIdAndFaceIndex(UUID videoId, int faceIndex);

    @Modifying
    @Query("delete from VideoFace f where f.videoId = :videoId")
    void deleteByVideoId(UUID videoId);
}
