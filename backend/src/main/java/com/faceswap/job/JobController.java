package com.faceswap.job;

import com.faceswap.auth.AuthUser;
import com.faceswap.common.ApiException;
import com.faceswap.job.JobDtos.JobDto;
import com.faceswap.job.JobDtos.StartJobRequest;
import com.faceswap.storage.MediaStreamer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;
    private final MediaStreamer streamer;

    public JobController(JobService jobService, MediaStreamer streamer) {
        this.jobService = jobService;
        this.streamer = streamer;
    }

    @PostMapping("/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobDto start(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody StartJobRequest req) {
        return jobService.start(user.id(), req);
    }

    @GetMapping
    public List<JobDto> list(@AuthenticationPrincipal AuthUser user) {
        return jobService.list(user.id());
    }

    @GetMapping("/{id}")
    public JobDto get(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return jobService.get(user.id(), id);
    }

    @GetMapping("/{id}/result")
    public void result(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                       HttpServletRequest request, HttpServletResponse response) throws Exception {
        Job job = jobService.find(user.id(), id);
        if (job.getStatus() != JobStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "İşlem henüz tamamlanmadı");
        }
        streamer.stream(job.getResultKey(), "faceswap-" + id + ".mp4", request, response);
    }
}
