package com.faceswap.job;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public final class JobDtos {

    private JobDtos() {
    }

    /** targetFaceIndex: which person from the video's analysis to replace; null replaces everyone. */
    public record StartJobRequest(@NotNull UUID videoId, @NotNull UUID faceId, Boolean consent, Boolean enhance,
                                  @Min(0) Integer targetFaceIndex) {
    }

    public record JobDto(UUID id, JobStatus status, int progress, boolean enhance, String error,
                         UUID videoId, UUID faceId, String videoUrl, String faceUrl,
                         Integer targetFaceIndex, String targetFaceUrl,
                         String resultUrl, String thumbnailUrl,
                         Instant createdAt, Instant startedAt, Instant finishedAt) {
    }
}
