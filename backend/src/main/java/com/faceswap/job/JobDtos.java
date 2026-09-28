package com.faceswap.job;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public final class JobDtos {

    private JobDtos() {
    }

    public record StartJobRequest(@NotNull UUID videoId, @NotNull UUID faceId, Boolean consent, Boolean enhance) {
    }

    public record JobDto(UUID id, JobStatus status, int progress, boolean enhance, String error,
                         UUID videoId, UUID faceId, String videoUrl, String faceUrl,
                         String resultUrl, String thumbnailUrl,
                         Instant createdAt, Instant startedAt, Instant finishedAt) {
    }
}
