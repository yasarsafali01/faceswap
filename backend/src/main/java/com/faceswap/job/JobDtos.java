package com.faceswap.job;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class JobDtos {

    private JobDtos() {
    }

    /**
     * targetFaceIndex: which person from the video's analysis gets the new face; null means everyone.
     * sourceFaceIndex: which face of the photo faceId to use; required when the photo shows several people.
     */
    public record SwapRequest(@NotNull UUID faceId, @Min(0) Integer targetFaceIndex, @Min(0) Integer sourceFaceIndex) {
    }

    /**
     * Either {@code swaps} (one entry per person to replace) or the single-face shorthand
     * {@code faceId} + optional {@code targetFaceIndex}.
     */
    public record StartJobRequest(@NotNull UUID videoId, Boolean consent, Boolean enhance,
                                  @Valid @Size(max = 12) List<SwapRequest> swaps,
                                  UUID faceId, @Min(0) Integer targetFaceIndex) {

        List<SwapRequest> effectiveSwaps() {
            if (swaps != null && !swaps.isEmpty()) {
                return swaps;
            }
            return faceId == null ? List.of() : List.of(new SwapRequest(faceId, targetFaceIndex, null));
        }
    }

    public record SwapDto(Integer targetFaceIndex, UUID faceId, Integer sourceFaceIndex, String faceUrl,
                          String targetFaceUrl) {
    }

    public record JobDto(UUID id, JobStatus status, int progress, boolean enhance, String error,
                         UUID videoId, String videoUrl, List<SwapDto> swaps,
                         String resultUrl, String thumbnailUrl,
                         Instant createdAt, Instant startedAt, Instant finishedAt) {
    }
}
