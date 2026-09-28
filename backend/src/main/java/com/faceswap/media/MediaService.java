package com.faceswap.media;

import com.faceswap.auth.JwtService;
import com.faceswap.common.ApiException;
import com.faceswap.config.AppProperties;
import com.faceswap.storage.StorageService;
import org.apache.tika.Tika;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MediaService {

    public record MediaFileDto(UUID id, String originalName, String contentType, long sizeBytes,
                               Instant createdAt, String url) {
    }

    // Detected type -> stored extension. Types are sniffed from magic bytes, never trusted from the client.
    private static final Map<String, String> VIDEO_TYPES = Map.of(
            "video/mp4", "mp4",
            "video/quicktime", "mov",
            "video/webm", "webm",
            "video/x-matroska", "mkv",
            "video/x-m4v", "m4v");
    private static final Map<String, String> IMAGE_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private final Tika tika = new Tika();
    private final StorageService storage;
    private final JwtService jwtService;
    private final VideoRepository videos;
    private final FaceRepository faces;
    private final AppProperties.Limits limits;

    public MediaService(StorageService storage, JwtService jwtService, VideoRepository videos,
                        FaceRepository faces, AppProperties properties) {
        this.storage = storage;
        this.jwtService = jwtService;
        this.videos = videos;
        this.faces = faces;
        this.limits = properties.limits();
    }

    @Transactional
    public MediaFileDto uploadVideo(long userId, MultipartFile file) {
        String type = validate(file, VIDEO_TYPES, limits.maxVideoBytes(), "Desteklenmeyen video formatı (mp4, mov, webm, mkv)");
        UUID id = UUID.randomUUID();
        String key = "videos/" + userId + "/" + id + "." + VIDEO_TYPES.get(type);
        store(key, file, type);
        Video video = videos.save(new Video(id, userId, key, safeName(file), type, file.getSize()));
        return toDto(video);
    }

    @Transactional
    public MediaFileDto uploadFace(long userId, MultipartFile file) {
        String type = validate(file, IMAGE_TYPES, limits.maxImageBytes(), "Desteklenmeyen görsel formatı (jpg, png, webp)");
        UUID id = UUID.randomUUID();
        String key = "faces/" + userId + "/" + id + "." + IMAGE_TYPES.get(type);
        store(key, file, type);
        Face face = faces.save(new Face(id, userId, key, safeName(file), type, file.getSize()));
        return toDto(face);
    }

    @Transactional(readOnly = true)
    public List<MediaFileDto> listVideos(long userId) {
        return videos.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 50)).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<MediaFileDto> listFaces(long userId) {
        return faces.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 50)).stream().map(this::toDto).toList();
    }

    public String mediaUrl(long userId, String objectKey) {
        return objectKey == null ? null : "/api/media/" + jwtService.mediaToken(userId, objectKey).token();
    }

    private MediaFileDto toDto(StoredFile f) {
        return new MediaFileDto(f.getId(), f.getOriginalName(), f.getContentType(), f.getSizeBytes(),
                f.getCreatedAt(), mediaUrl(f.getUserId(), f.getObjectKey()));
    }

    private String validate(MultipartFile file, Map<String, String> allowed, long maxBytes, String typeError) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Dosya boş");
        }
        if (file.getSize() > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Dosya çok büyük (en fazla " + (maxBytes / 1024 / 1024) + " MB)");
        }
        String detected;
        try (InputStream in = new BufferedInputStream(file.getInputStream())) {
            in.mark(16);
            byte[] head = in.readNBytes(12);
            in.reset();
            detected = refineIsoBmff(tika.detect(in), head);
        } catch (IOException e) {
            throw ApiException.badRequest("Dosya okunamadı");
        }
        if (!allowed.containsKey(detected)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, typeError);
        }
        return detected;
    }

    /**
     * tika-core reports most ISO-BMFF files (brand isom, mp42, ...) as video/quicktime, and browsers
     * may refuse to play that type. The ftyp major brand tells MP4 and QuickTime apart.
     */
    static String refineIsoBmff(String detected, byte[] head) {
        boolean isoFamily = detected.equals("video/quicktime") || detected.equals("video/mp4") || detected.equals("video/x-m4v");
        if (!isoFamily || head.length < 12 || !"ftyp".equals(new String(head, 4, 4, StandardCharsets.US_ASCII))) {
            return detected;
        }
        String brand = new String(head, 8, 4, StandardCharsets.US_ASCII);
        if (brand.equals("qt  ")) {
            return "video/quicktime";
        }
        return brand.startsWith("M4V") ? "video/x-m4v" : "video/mp4";
    }

    private void store(String key, MultipartFile file, String type) {
        try (InputStream in = file.getInputStream()) {
            storage.put(key, in, file.getSize(), type);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Dosya depolanamadı, lütfen tekrar deneyin");
        }
    }

    private static String safeName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) {
            return null;
        }
        name = name.replaceAll("[\\\\/\\r\\n\"]", "_");
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
}
