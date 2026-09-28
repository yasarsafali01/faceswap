package com.faceswap.job;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<Job> findByIdAndUserId(UUID id, Long userId);

    long countByUserIdAndStatusIn(Long userId, Collection<JobStatus> statuses);
}
