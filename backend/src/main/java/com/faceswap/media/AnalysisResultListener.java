package com.faceswap.media;

import com.faceswap.job.JobMessages;
import com.faceswap.job.JobMessages.AnalysisResult;
import com.faceswap.job.JobMessages.DetectedFace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AnalysisResultListener {

    private static final Logger log = LoggerFactory.getLogger(AnalysisResultListener.class);

    private final VideoRepository videos;
    private final VideoFaceRepository videoFaces;

    public AnalysisResultListener(VideoRepository videos, VideoFaceRepository videoFaces) {
        this.videos = videos;
        this.videoFaces = videoFaces;
    }

    @RabbitListener(queues = JobMessages.ANALYSIS_RESULTS_QUEUE)
    @Transactional
    public void onResult(AnalysisResult result) {
        if (result == null || result.videoId() == null) {
            log.warn("Ignoring malformed analysis result: {}", result);
            return;
        }
        Video video = videos.findById(result.videoId()).orElse(null);
        if (video == null) {
            log.warn("Analysis result for unknown video {}", result.videoId());
            return;
        }

        // Redelivered results replace the previous ones instead of duplicating rows.
        videoFaces.deleteByVideoId(video.getId());
        if ("READY".equals(result.status())) {
            if (result.faces() != null) {
                for (DetectedFace f : result.faces()) {
                    videoFaces.save(new VideoFace(video.getId(), f.index(),
                            video.facesPrefix() + f.index() + ".jpg", f.occurrences()));
                }
            }
            video.markAnalyzed();
        } else {
            video.markAnalysisFailed(result.error() == null ? "Video analiz edilemedi" : result.error());
        }
    }
}
