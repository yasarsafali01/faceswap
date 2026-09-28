package com.faceswap.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Limits the web app shows to users, so they are configured in .env only and never duplicated in the UI. */
@RestController
public class PublicConfigController {

    public record PublicConfig(int maxVideoMb, int maxImageMb, int maxDurationSeconds, int maxActiveJobsPerUser) {
    }

    private final PublicConfig config;

    public PublicConfigController(AppProperties properties) {
        var l = properties.limits();
        this.config = new PublicConfig(l.maxVideoMb(), l.maxImageMb(), l.maxDurationSeconds(), l.maxActiveJobsPerUser());
    }

    @GetMapping("/api/config")
    public PublicConfig config() {
        return config;
    }
}
