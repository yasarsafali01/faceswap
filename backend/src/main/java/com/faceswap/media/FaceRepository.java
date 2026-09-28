package com.faceswap.media;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FaceRepository extends JpaRepository<Face, UUID> {

    List<Face> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<Face> findByIdAndUserId(UUID id, Long userId);
}
