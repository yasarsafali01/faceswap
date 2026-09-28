package com.faceswap.media;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoRepository extends JpaRepository<Video, UUID> {

    List<Video> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<Video> findByIdAndUserId(UUID id, Long userId);
}
