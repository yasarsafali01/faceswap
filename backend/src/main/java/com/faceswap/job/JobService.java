package com.faceswap.job;

import com.faceswap.common.ApiException;
import com.faceswap.config.AppProperties;
import com.faceswap.job.JobDtos.JobDto;
import com.faceswap.job.JobDtos.StartJobRequest;
import com.faceswap.job.JobDtos.SwapDto;
import com.faceswap.job.JobDtos.SwapRequest;
import com.faceswap.job.JobMessages.JobRequest;
import com.faceswap.job.JobMessages.SwapSpec;
import com.faceswap.media.Face;
import com.faceswap.media.FaceRepository;
import com.faceswap.media.MediaService;
import com.faceswap.media.Video;
import com.faceswap.media.VideoFace;
import com.faceswap.media.VideoFaceRepository;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final String PROGRESS_KEY = "job:progress:";

    private final JobRepository jobs;
    private final JobSwapRepository jobSwaps;
    private final JobLogRepository jobLogs;
    private final VideoRepository videos;
    private final FaceRepository faces;
    private final VideoFaceRepository videoFaces;
    private final MediaService mediaService;
    private final RabbitTemplate rabbit;
    private final StringRedisTemplate redis;
    private final AppProperties.Limits limits;

    public JobService(JobRepository jobs, JobSwapRepository jobSwaps, JobLogRepository jobLogs, VideoRepository videos,
                      FaceRepository faces, VideoFaceRepository videoFaces, MediaService mediaService,
                      RabbitTemplate rabbit, StringRedisTemplate redis, AppProperties properties) {
        this.jobs = jobs;
        this.jobSwaps = jobSwaps;
        this.jobLogs = jobLogs;
        this.videos = videos;
        this.faces = faces;
        this.videoFaces = videoFaces;
        this.mediaService = mediaService;
        this.rabbit = rabbit;
        this.redis = redis;
        this.limits = properties.limits();
    }

    @Transactional
    public JobDto start(long userId, StartJobRequest req) {
        if (!Boolean.TRUE.equals(req.consent())) {
            throw ApiException.badRequest("Yüz sahiplerinin izni olduğunu onaylamanız gerekiyor");
        }
        List<SwapRequest> requested = req.effectiveSwaps();
        if (requested.isEmpty()) {
            throw ApiException.badRequest("En az bir kişiye yeni yüz atayın");
        }
        Video video = videos.findByIdAndUserId(req.videoId(), userId).orElseThrow(() -> ApiException.notFound("Video"));
        Map<UUID, Face> sourceFaces = new HashMap<>();
        for (SwapRequest s : requested) {
            sourceFaces.computeIfAbsent(s.faceId(), id ->
                    faces.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Yüz")));
        }

        long active = jobs.countByUserIdAndStatusIn(userId, List.of(JobStatus.QUEUED, JobStatus.PROCESSING));
        if (active >= limits.maxActiveJobsPerUser()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Aynı anda en fazla " + limits.maxActiveJobsPerUser() + " işlem çalıştırabilirsiniz");
        }

        List<Integer> targets = resolveTargets(video, requested);
        // jobs.face_id / target_face_index keep the first assignment for older readers of the table.
        Job job = jobs.save(new Job(userId, video.getId(), requested.get(0).faceId(),
                req.enhance() == null || req.enhance(), targets.get(0)));
        List<JobSwap> swaps = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            swaps.add(jobSwaps.save(new JobSwap(job.getId(), targets.get(i), requested.get(i).faceId())));
        }
        jobLogs.save(new JobLog(job.getId(), "INFO", "Job queued with " + swaps.size() + " face assignment(s)"));

        boolean byPerson = targets.stream().anyMatch(Objects::nonNull);
        var message = new JobRequest(job.getId(), userId, video.getObjectKey(), resultKey(job), thumbnailKey(job),
                job.isEnhance(), byPerson ? video.facesPrefix() + "faces.json" : null,
                swaps.stream().map(s -> new SwapSpec(sourceFaces.get(s.getFaceId()).getObjectKey(), s.getTargetFaceIndex())).toList());
        // Publish only after commit, otherwise a fast worker could report on a job row that doesn't exist yet.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish(job.getId(), message);
            }
        });
        return toDto(job, video, swaps, sourceFaces);
    }

    @Transactional(readOnly = true)
    public List<JobDto> list(long userId) {
        List<Job> page = jobs.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 50));
        return toDtos(page);
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
        return toDtos(List.of(job)).get(0);
    }

    /**
     * Resolves each requested assignment to a person index from the video analysis (null = everyone),
     * in the same order as the request.
     */
    private List<Integer> resolveTargets(Video video, List<SwapRequest> requested) {
        switch (video.getAnalysisStatus()) {
            case PENDING -> throw new ApiException(HttpStatus.CONFLICT,
                    "Videodaki yüzler hâlâ analiz ediliyor, birkaç saniye sonra tekrar deneyin");
            case FAILED -> {
                // Without analysis there is nothing to pick from; one face for everyone is all we can do.
                if (requested.size() != 1 || requested.get(0).targetFaceIndex() != null) {
                    throw ApiException.badRequest("Bu video için kişi seçimi yapılamıyor");
                }
                return Collections.singletonList(null);
            }
            default -> {
                List<VideoFace> found = videoFaces.findByVideoIdOrderByFaceIndex(video.getId());
                if (found.isEmpty()) {
                    throw ApiException.badRequest("Videoda yüz bulunamadı");
                }
                if (requested.size() == 1 && requested.get(0).targetFaceIndex() == null) {
                    // A single person is always the target; matching by identity also skips stray detections.
                    return Collections.singletonList(found.size() == 1 ? found.get(0).getFaceIndex() : null);
                }
                Set<Integer> known = found.stream().map(VideoFace::getFaceIndex).collect(Collectors.toSet());
                Set<Integer> used = new HashSet<>();
                List<Integer> targets = new ArrayList<>();
                for (SwapRequest s : requested) {
                    Integer t = s.targetFaceIndex();
                    if (t == null) {
                        throw ApiException.badRequest("Birden fazla yüz atarken her yüz için bir kişi seçin");
                    }
                    if (!known.contains(t)) {
                        throw ApiException.badRequest("Seçilen kişi bu videoda yok");
                    }
                    if (!used.add(t)) {
                        throw ApiException.badRequest("Aynı kişiye birden fazla yüz atanamaz");
                    }
                    targets.add(t);
                }
                return targets;
            }
        }
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

    private List<JobDto> toDtos(List<Job> page) {
        Map<UUID, Video> videoById = videos.findAllById(page.stream().map(Job::getVideoId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Video::getId, Function.identity()));
        Map<UUID, List<JobSwap>> swapsByJob = jobSwaps.findByJobIdIn(page.stream().map(Job::getId).toList())
                .stream().collect(Collectors.groupingBy(JobSwap::getJobId));
        Set<UUID> faceIds = swapsByJob.values().stream().flatMap(List::stream).map(JobSwap::getFaceId)
                .collect(Collectors.toSet());
        Map<UUID, Face> faceById = faces.findAllById(faceIds).stream()
                .collect(Collectors.toMap(Face::getId, Function.identity()));
        return page.stream()
                .map(j -> toDto(j, videoById.get(j.getVideoId()), swapsByJob.getOrDefault(j.getId(), List.of()), faceById))
                .toList();
    }

    private JobDto toDto(Job job, Video video, List<JobSwap> swaps, Map<UUID, Face> faceById) {
        int progress = job.getProgress();
        if (job.getStatus() == JobStatus.PROCESSING) {
            String cached = redis.opsForValue().get(PROGRESS_KEY + job.getId());
            if (cached != null) {
                progress = Integer.parseInt(cached);
            }
        }
        long uid = job.getUserId();
        List<SwapDto> swapDtos = swaps.stream().map(s -> {
            Face face = faceById.get(s.getFaceId());
            String targetUrl = video == null || s.getTargetFaceIndex() == null ? null
                    : mediaService.mediaUrl(uid, video.facesPrefix() + s.getTargetFaceIndex() + ".jpg");
            return new SwapDto(s.getTargetFaceIndex(), s.getFaceId(),
                    face == null ? null : mediaService.mediaUrl(uid, face.getObjectKey()), targetUrl);
        }).toList();
        return new JobDto(job.getId(), job.getStatus(), progress, job.isEnhance(), job.getErrorMessage(),
                job.getVideoId(), video == null ? null : mediaService.mediaUrl(uid, video.getObjectKey()), swapDtos,
                mediaService.mediaUrl(uid, job.getResultKey()), mediaService.mediaUrl(uid, job.getThumbnailKey()),
                job.getCreatedAt(), job.getStartedAt(), job.getFinishedAt());
    }
}
