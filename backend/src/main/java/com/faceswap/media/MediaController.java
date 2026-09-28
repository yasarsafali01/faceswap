package com.faceswap.media;

import com.faceswap.auth.AuthUser;
import com.faceswap.auth.JwtService;
import com.faceswap.media.MediaService.FaceDto;
import com.faceswap.media.MediaService.VideoDto;
import com.faceswap.storage.MediaStreamer;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
public class MediaController {

    private final MediaService mediaService;
    private final MediaStreamer streamer;
    private final JwtService jwtService;

    public MediaController(MediaService mediaService, MediaStreamer streamer, JwtService jwtService) {
        this.mediaService = mediaService;
        this.streamer = streamer;
        this.jwtService = jwtService;
    }

    @PostMapping(path = "/api/videos/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public VideoDto uploadVideo(@AuthenticationPrincipal AuthUser user, @RequestParam("file") MultipartFile file) {
        return mediaService.uploadVideo(user.id(), file);
    }

    @GetMapping("/api/videos")
    public List<VideoDto> videos(@AuthenticationPrincipal AuthUser user) {
        return mediaService.listVideos(user.id());
    }

    /** Polled by the client after upload until analysisStatus leaves PENDING and the people in it are known. */
    @GetMapping("/api/videos/{id}")
    public VideoDto video(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return mediaService.getVideo(user.id(), id);
    }

    @PostMapping(path = "/api/faces/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FaceDto uploadFace(@AuthenticationPrincipal AuthUser user, @RequestParam("file") MultipartFile file) {
        return mediaService.uploadFace(user.id(), file);
    }

    @GetMapping("/api/faces")
    public List<FaceDto> faces(@AuthenticationPrincipal AuthUser user) {
        return mediaService.listFaces(user.id());
    }

    /** Polled after upload until analysisStatus leaves PENDING and the faces in the photo are known. */
    @GetMapping("/api/faces/{id}")
    public FaceDto face(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return mediaService.getFace(user.id(), id);
    }

    @GetMapping("/api/media/{token}")
    public void media(@PathVariable String token, HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        Claims claims = jwtService.parse(token, JwtService.TokenType.MEDIA);
        streamer.stream(claims.get("key", String.class), null, request, response);
    }
}
