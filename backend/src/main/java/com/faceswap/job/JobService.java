package com.faceswap.job;

import com.faceswap.common.ApiException;
import com.faceswap.config.AppProperties;
import com.faceswap.job.JobDtos.JobDto;
import com.faceswap.job.JobDtos.StartJobRequest;
import com.faceswap.job.JobMessages.JobRequest;
import com.faceswap.media.Face;
import com.faceswap.media.FaceRepository;
import com.faceswap.media.MediaService;
import com.faceswap.media.Video;
import com.faceswap.media.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final String PROGRESS_KEY = "job:progress:";

    private final JobRepository jobs;
    private final JobLogRepository jobLogs;
    private final VideoRepository videos;
    private final FaceRepository faces;
    private final MediaService mediaService;
    private final RabbitTemplate rabbit;
    private final StringRedisTemplate redis;
    private final AppProperties.Limits limits;

    public JobService(JobRepository jobs, JobLogRepository jobLogs, VideoRepository videos, FaceRepository faces,
                      MediaService mediaService, RabbitTemplate rabbit, StringRedisTemplate redis,
                      AppProperties properties) {
        this.jobs = jobs;
        this.jobLogs = jobLogs;
        this.videos = videos;
        this.faces = faces;
        this.mediaService = mediaService;
        this.rabbit = rabbit;
        this.redis = redis;
        this.limits = properties.limits();
    }

    @Transactional
    public JobDto start(long userId, StartJobRequest req) {
        if (!Boolean.TRUE.equals(req.consent())) {
            throw ApiException.badRequest("Yüz sahibinin izni olduğunu onaylamanız gerekiyor");
        }
        Video video = videos.findByIdAndUserId(req.videoId(), userId).orElseThrow(() -> ApiException.notFound("Video"));
        Face face = faces.findByIdAndUserId(req.faceId(), userId).orElseThrow(() -> ApiException.notFound("Yüz"));

        long active = jobs.countByUserIdAndStatusIn(userId, List.of(JobStatus.QUEUED, JobStatus.PROCESSING));
        if (active >= limits.maxActiveJobsPerUser()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Aynı anda en fazla " + limits.maxActiveJobsPerUser() + " işlem çalıştırabilirsiniz");
        }

        Job job = jobs.save(new Job(userId, video.getId(), face.getId(), req.enhance() == null || req.enhance()));
        jobLogs.save(new JobLog(job.getId(), "INFO", "Job queued"));

        var message = new JobRequest(job.getId(), userId, video.getObjectKey(), face.getObjectKey(),
                resultKey(job), thumbnailKey(job), job.isEnhance());
        // Publish only after commit, otherwise a fast worker could report on a job row that doesn't exist yet.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish(job.getId(), message);
            }
        });
        return toDto(job, video, face);
    }

    @Transactional(readOnly = true)
    public List<JobDto> list(long userId) {
        List<Job> page = jobs.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 50));
        Map<UUID, Video> videoById = videos.findAllById(page.stream().map(Job::getVideoId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Video::getId, Function.identity()));
        Map<UUID, Face> faceById = faces.findAllById(page.stream().map(Job::getFaceId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Face::getId, Function.identity()));
        return page.stream()
                .map(j -> toDto(j, videoById.get(j.getVideoId()), faceById.get(j.getFaceId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public JobDto get(long userId, UUID id) {
        return toDto(find(userId, id));
    }

    @Transactional(readOnly = true)
    public Job find(long userId, UUID id) {
        return jobs.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("İşlem"));
    }

    public void cacheProgress(UUID jobId, int progress) {
        redis.opsForValue().set(PROGRESS_KEY + jobId, String.valueOf(progress), Duration.ofHours(6));
    }

    public JobDto toDto(Job job) {
        return toDto(job, videos.findById(job.getVideoId()).orElse(null), faces.findById(job.getFaceId()).orElse(null));
    }

    static String resultKey(Job job) {
        return "results/" + job.getUserId() + "/" + job.getId() + ".mp4";
    }

    static String thumbnailKey(Job job) {
        return "thumbnails/" + job.getUserId() + "/" + job.getId() + ".jpg";
    }

    private void publish(UUID jobId, JobRequest message) {
        try {
            rabbit.convertAndSend(JobMessages.EXCHANGE, JobMessages.JOB_ROUTING_KEY, message);
        } catch (Exception e) {
            log.error("Failed to publish job {}", jobId, e);
            jobs.findById(jobId).ifPresent(j -> {
                j.markFailed("İşlem kuyruğa alınamadı, lütfen tekrar deneyin");
                jobs.save(j);
            });
        }
    }

    private JobDto toDto(Job job, Video video, Face face) {
        int progress = job.getProgress();
        if (job.getStatus() == JobStatus.PROCESSING) {
            String cached = redis.opsForValue().get(PROGRESS_KEY + job.getId());
            if (cached != null) {
                progress = Integer.parseInt(cached);
            }
        }
        long uid = job.getUserId();
        return new JobDto(job.getId(), job.getStatus(), progress, job.isEnhance(), job.getErrorMessage(),
                job.getVideoId(), job.getFaceId(),
                video == null ? null : mediaService.mediaUrl(uid, video.getObjectKey()),
                face == null ? null : mediaService.mediaUrl(uid, face.getObjectKey()),
                mediaService.mediaUrl(uid, job.getResultKey()),
                mediaService.mediaUrl(uid, job.getThumbnailKey()),
                job.getCreatedAt(), job.getStartedAt(), job.getFinishedAt());
    }
}
