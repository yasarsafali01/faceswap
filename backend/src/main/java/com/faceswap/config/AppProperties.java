package com.faceswap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Storage storage, Limits limits, RateLimit rateLimit) {

    public record Jwt(String secret, Duration accessTtl, Duration refreshTtl, Duration mediaTtl) {
    }

    public record Storage(String endpoint, String accessKey, String secretKey, String bucket) {
    }

    public record Limits(int maxVideoMb, int maxImageMb, int maxDurationSeconds, int maxActiveJobsPerUser) {

        public long maxVideoBytes() {
            return maxVideoMb * 1024L * 1024L;
        }

        public long maxImageBytes() {
            return maxImageMb * 1024L * 1024L;
        }
    }

    public record RateLimit(int authPerMinute, int uploadsPerHour, int jobsPerHour) {
    }
}
