package com.faceswap.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface JobSwapRepository extends JpaRepository<JobSwap, Long> {

    List<JobSwap> findByJobIdIn(Collection<UUID> jobIds);
}
