package com.faceswap.media;

import com.faceswap.job.JobMessages;
import com.faceswap.job.JobMessages.ImageAnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ImageAnalysisResultListener {

    private static final Logger log = LoggerFactory.getLogger(ImageAnalysisResultListener.class);

    private final FaceRepository faces;
    private final FaceDetectionRepository detections;

    public ImageAnalysisResultListener(FaceRepository faces, FaceDetectionRepository detections) {
        this.faces = faces;
        this.detections = detections;
    }

    @RabbitListener(queues = JobMessages.IMAGE_ANALYSIS_RESULTS_QUEUE)
    @Transactional
    public void onResult(ImageAnalysisResult result) {
        if (result == null || result.faceId() == null) {
            log.warn("Ignoring malformed image analysis result: {}", result);
            return;
        }
        Face face = faces.findById(result.faceId()).orElse(null);
        if (face == null) {
            log.warn("Image analysis result for unknown photo {}", result.faceId());
            return;
        }
        // Redelivered results replace the previous ones instead of duplicating rows.
        detections.deleteByFaceId(face.getId());
        if ("READY".equals(result.status()) && result.faces() != null && !result.faces().isEmpty()) {
            for (Integer index : result.faces()) {
                detections.save(new FaceDetection(face.getId(), index, face.cropsPrefix() + index + ".jpg"));
            }
            face.markAnalyzed();
        } else {
            face.markAnalysisFailed(result.error() == null ? "Fotoğrafta yüz bulunamadı" : result.error());
        }
    }
}
